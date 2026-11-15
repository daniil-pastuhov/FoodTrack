/**
 * Envelope encryption for the few long-lived third-party credentials the backend keeps (today:
 * Sign in with Apple refresh tokens). Each value gets its own random AES-256-GCM data key; the
 * data key is wrapped by a key-encryption key (KEK) that never leaves Cloud KMS, and the caller's
 * context string is bound to both layers as additional authenticated data, so a sealed value
 * copied onto another user's record does not open.
 *
 * Reading Firestore (an export, a backup, a console session, a leaked admin credential without
 * KMS rights) yields only ciphertext: opening a value needs `cloudkms.cryptoKeyVersions.useToDecrypt`
 * on the one key, which only the functions' service account holds (docs/recipe-setup.md).
 */
import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import { GoogleAuth } from "google-auth-library";
import type { SealedSecret, SecretSealer } from "../ports";

/** Wraps and unwraps data keys. Cloud KMS in production, a local key in the emulator and tests. */
export interface KeyWrapper {
  /** The KEK's name, stored with each value (informational; KMS picks the version from the ciphertext). */
  readonly name: string;
  wrap(dataKey: Buffer, aad: Buffer): Promise<string>;
  unwrap(wrapped: string, aad: Buffer): Promise<Buffer>;
}

const ALG = "aes-256-gcm";

function aesSeal(key: Buffer, plaintext: Buffer, aad: Buffer): { iv: Buffer; ciphertext: Buffer; tag: Buffer } {
  const iv = randomBytes(12);
  const cipher = createCipheriv(ALG, key, iv);
  cipher.setAAD(aad);
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  return { iv, ciphertext, tag: cipher.getAuthTag() };
}

function aesOpen(key: Buffer, iv: Buffer, ciphertext: Buffer, tag: Buffer, aad: Buffer): Buffer {
  if (iv.length !== 12 || tag.length !== 16) throw new Error("sealed value is malformed");
  const decipher = createDecipheriv(ALG, key, iv);
  decipher.setAAD(aad);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(ciphertext), decipher.final()]);
}

export class EnvelopeSealer implements SecretSealer {
  constructor(private readonly kek: KeyWrapper) {}

  async seal(plaintext: string, context: string): Promise<SealedSecret> {
    const aad = Buffer.from(context, "utf8");
    const dataKey = randomBytes(32);
    try {
      const { iv, ciphertext, tag } = aesSeal(dataKey, Buffer.from(plaintext, "utf8"), aad);
      const wrappedKey = await this.kek.wrap(dataKey, aad);
      return {
        v: 1,
        kek: this.kek.name,
        wrappedKey,
        iv: iv.toString("base64"),
        ciphertext: ciphertext.toString("base64"),
        tag: tag.toString("base64"),
      };
    } finally {
      dataKey.fill(0);
    }
  }

  async open(sealed: SealedSecret, context: string): Promise<string> {
    if (sealed?.v !== 1) throw new Error("sealed value has an unknown version");
    const aad = Buffer.from(context, "utf8");
    const dataKey = await this.kek.unwrap(sealed.wrappedKey, aad);
    try {
      return aesOpen(
        dataKey,
        Buffer.from(sealed.iv, "base64"),
        Buffer.from(sealed.ciphertext, "base64"),
        Buffer.from(sealed.tag, "base64"),
        aad,
      ).toString("utf8");
    } finally {
      dataKey.fill(0);
    }
  }
}

/** A KEK held in memory: the emulator (a fresh random key per process) and tests. Never production. */
export class LocalKeyWrapper implements KeyWrapper {
  readonly name = "local";
  constructor(private readonly key: Buffer = randomBytes(32)) {}

  async wrap(dataKey: Buffer, aad: Buffer): Promise<string> {
    const { iv, ciphertext, tag } = aesSeal(this.key, dataKey, aad);
    return Buffer.concat([iv, tag, ciphertext]).toString("base64");
  }

  async unwrap(wrapped: string, aad: Buffer): Promise<Buffer> {
    const raw = Buffer.from(wrapped, "base64");
    return aesOpen(this.key, raw.subarray(0, 12), raw.subarray(28), raw.subarray(12, 28), aad);
  }
}

/**
 * Cloud KMS `encrypt` / `decrypt` on one symmetric key (REST, through google-auth-library with the
 * function's service account). The data key, not the secret, is what reaches KMS.
 */
export class KmsKeyWrapper implements KeyWrapper {
  private readonly auth = new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/cloudkms"] });

  /** `name`: projects/P/locations/L/keyRings/R/cryptoKeys/K. */
  constructor(readonly name: string) {}

  private async call(verb: "encrypt" | "decrypt", body: Record<string, string>): Promise<Record<string, unknown>> {
    const client = await this.auth.getClient();
    try {
      const res = await client.request<Record<string, unknown>>({
        url: `https://cloudkms.googleapis.com/v1/${this.name}:${verb}`,
        method: "POST",
        data: body,
        timeout: 10_000,
      });
      return res.data;
    } catch (e) {
      // Only the status, and no `cause`: the caught error carries the request, whose body holds
      // key material, and a logged cause would print it.
      const status = (e as { response?: { status?: number } }).response?.status ?? "network";
      // eslint-disable-next-line preserve-caught-error
      throw new Error(`Cloud KMS ${verb} failed (${status})`);
    }
  }

  async wrap(dataKey: Buffer, aad: Buffer): Promise<string> {
    const out = await this.call("encrypt", {
      plaintext: dataKey.toString("base64"),
      additionalAuthenticatedData: aad.toString("base64"),
    });
    if (typeof out.ciphertext !== "string") throw new Error("Cloud KMS encrypt returned no ciphertext");
    return out.ciphertext;
  }

  async unwrap(wrapped: string, aad: Buffer): Promise<Buffer> {
    const out = await this.call("decrypt", { ciphertext: wrapped, additionalAuthenticatedData: aad.toString("base64") });
    if (typeof out.plaintext !== "string") throw new Error("Cloud KMS decrypt returned no plaintext");
    return Buffer.from(out.plaintext, "base64");
  }
}
