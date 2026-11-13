/**
 * AppStoreVerifier over Apple's App Store Server Library: JWS verification against Apple's root
 * CA (bundled in certs/, public), and Get Transaction Info from the App Store Server API.
 *
 * Only Production and Sandbox are accepted. Xcode and LocalTesting transactions are signed by a
 * local key the library does not verify, so they are refused before any verifier sees them.
 */
import {
  APIException,
  AppStoreServerAPIClient,
  Environment,
  SignedDataVerifier,
  VerificationException,
  VerificationStatus,
  type JWSTransactionDecodedPayload,
} from "@apple/app-store-server-library";
import { IOS_BUNDLE_ID } from "../constants";
import {
  InvalidSignatureError,
  RetryableStoreError,
  type AppleEnvironment,
  type AppleNotification,
  type AppleTransaction,
  type AppStoreVerifier,
} from "../ports";

export interface AppStoreOptions {
  rootCertificates: Buffer[];
  /** Numeric App Store app id; the library requires it for Production. */
  appAppleId: number;
  /** OCSP revocation checks of the signing chain; true in production. */
  enableOnlineChecks: boolean;
  /** App Store Server API key (`.p8` contents), key id and issuer id. Absent in tests. */
  apiKey?: { privateKey: string; keyId: string; issuerId: string };
}

function decodeUnverified(jws: string): Record<string, unknown> {
  const part = jws.split(".")[1];
  if (!part) throw new InvalidSignatureError("malformed JWS");
  try {
    return JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as Record<string, unknown>;
  } catch {
    throw new InvalidSignatureError("malformed JWS payload");
  }
}

function envOf(value: unknown): AppleEnvironment {
  if (value === Environment.PRODUCTION) return "production";
  if (value === Environment.SANDBOX) return "sandbox";
  throw new InvalidSignatureError(`refused environment ${String(value)}`);
}

export function toAppleTransaction(p: JWSTransactionDecodedPayload): AppleTransaction {
  if (!p.transactionId || !p.productId || !p.bundleId) throw new InvalidSignatureError("transaction is missing fields");
  return {
    transactionId: p.transactionId,
    originalTransactionId: p.originalTransactionId ?? p.transactionId,
    bundleId: p.bundleId,
    productId: p.productId,
    quantity: p.quantity ?? 1,
    type: String(p.type ?? ""),
    environment: envOf(p.environment),
    revocationDate: p.revocationDate ?? null,
    revocationPercentage: p.revocationPercentage ?? null,
    signedDate: p.signedDate ?? 0,
  };
}

function mapVerificationError(e: unknown): never {
  if (e instanceof VerificationException) {
    if (e.status === VerificationStatus.RETRYABLE_VERIFICATION_FAILURE) {
      throw new RetryableStoreError("Apple signature check could not complete");
    }
    throw new InvalidSignatureError(`signature verification failed (${VerificationStatus[e.status]})`);
  }
  throw e;
}

export class AppleAppStoreVerifier implements AppStoreVerifier {
  private readonly verifiers: Record<AppleEnvironment, SignedDataVerifier>;
  private readonly clients: Partial<Record<AppleEnvironment, AppStoreServerAPIClient>> = {};

  constructor(private readonly options: AppStoreOptions) {
    this.verifiers = {
      production: new SignedDataVerifier(
        options.rootCertificates, options.enableOnlineChecks, Environment.PRODUCTION, IOS_BUNDLE_ID, options.appAppleId,
      ),
      sandbox: new SignedDataVerifier(
        options.rootCertificates, options.enableOnlineChecks, Environment.SANDBOX, IOS_BUNDLE_ID, options.appAppleId,
      ),
    };
  }

  private client(env: AppleEnvironment): AppStoreServerAPIClient {
    const key = this.options.apiKey;
    if (!key) throw new RetryableStoreError("App Store Server API key is not configured");
    this.clients[env] ??= new AppStoreServerAPIClient(
      key.privateKey, key.keyId, key.issuerId, IOS_BUNDLE_ID,
      env === "production" ? Environment.PRODUCTION : Environment.SANDBOX,
    );
    return this.clients[env]!;
  }

  async verifyTransaction(jws: string): Promise<AppleTransaction> {
    const env = envOf(decodeUnverified(jws).environment);
    try {
      return toAppleTransaction(await this.verifiers[env].verifyAndDecodeTransaction(jws));
    } catch (e) {
      return mapVerificationError(e);
    }
  }

  async fetchTransaction(transactionId: string, environment: AppleEnvironment): Promise<AppleTransaction | null> {
    let signed: string | undefined;
    try {
      signed = (await this.client(environment).getTransactionInfo(transactionId)).signedTransactionInfo;
    } catch (e) {
      if (e instanceof APIException && (e.httpStatusCode === 404 || e.httpStatusCode === 400)) return null;
      throw new RetryableStoreError(`App Store Server API failed: ${(e as APIException).httpStatusCode ?? "network"}`);
    }
    if (!signed) return null;
    return this.verifyTransaction(signed);
  }

  async verifyNotification(signedPayload: string): Promise<AppleNotification> {
    const data = decodeUnverified(signedPayload).data as Record<string, unknown> | undefined;
    const env = envOf(data?.environment);
    const verifier = this.verifiers[env];
    let decoded;
    try {
      decoded = await verifier.verifyAndDecodeNotification(signedPayload);
    } catch (e) {
      return mapVerificationError(e);
    }
    let transaction: AppleTransaction | null = null;
    const signedTx = decoded.data?.signedTransactionInfo;
    if (signedTx) {
      try {
        transaction = toAppleTransaction(await verifier.verifyAndDecodeTransaction(signedTx));
      } catch (e) {
        return mapVerificationError(e);
      }
    }
    if (!decoded.notificationUUID || !decoded.notificationType) throw new InvalidSignatureError("notification is missing fields");
    return {
      notificationUUID: decoded.notificationUUID,
      notificationType: String(decoded.notificationType),
      subtype: decoded.subtype ? String(decoded.subtype) : null,
      signedDate: decoded.signedDate ?? 0,
      environment: env,
      transaction,
    };
  }
}
