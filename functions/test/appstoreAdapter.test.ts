/**
 * The real AppleAppStoreVerifier against a throwaway certificate chain shaped like Apple's (root,
 * intermediate with Apple's WWDR OID, leaf with the receipt-signing OID), built with openssl.
 * Proves the signature check: payloads signed outside the trusted chain are rejected.
 */
import { execFileSync } from "node:child_process";
import { X509Certificate, createPrivateKey, sign } from "node:crypto";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AppleAppStoreVerifier } from "../src/adapters/appstore";
import { InvalidSignatureError } from "../src/ports";

interface Chain {
  rootDer: Buffer;
  x5c: string[];
  leafKey: string;
}

function openssl(dir: string, ...args: string[]) {
  execFileSync("openssl", args, { cwd: dir, stdio: "pipe" });
}

function makeChain(): Chain {
  const dir = mkdtempSync(join(tmpdir(), "apple-pki-"));
  writeFileSync(join(dir, "ext.cnf"), [
    "[root]", "basicConstraints=critical,CA:true", "keyUsage=critical,keyCertSign,cRLSign",
    "[intermediate]", "basicConstraints=critical,CA:true,pathlen:0", "keyUsage=critical,keyCertSign,cRLSign",
    "1.2.840.113635.100.6.2.1=ASN1:NULL",
    "[leaf]", "basicConstraints=critical,CA:false", "keyUsage=critical,digitalSignature",
    "1.2.840.113635.100.6.11.1=ASN1:NULL",
  ].join("\n"));
  for (const name of ["root", "intermediate", "leaf"]) {
    openssl(dir, "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", `${name}.key`);
  }
  openssl(dir, "req", "-new", "-key", "root.key", "-subj", "/CN=Test Root", "-config", "/dev/null", "-out", "root.csr");
  openssl(dir, "x509", "-req", "-in", "root.csr", "-signkey", "root.key", "-days", "3650",
    "-extfile", "ext.cnf", "-extensions", "root", "-out", "root.pem");
  for (const [name, issuer] of [["intermediate", "root"], ["leaf", "intermediate"]]) {
    openssl(dir, "req", "-new", "-key", `${name}.key`, "-subj", `/CN=Test ${name}`, "-config", "/dev/null", "-out", `${name}.csr`);
    openssl(dir, "x509", "-req", "-in", `${name}.csr`, "-CA", `${issuer}.pem`, "-CAkey", `${issuer}.key`, "-CAcreateserial",
      "-days", "3650", "-extfile", "ext.cnf", "-extensions", name, "-out", `${name}.pem`);
  }
  const der = (name: string) => {
    openssl(dir, "x509", "-in", `${name}.pem`, "-outform", "DER", "-out", `${name}.der`);
    return readFileSync(join(dir, `${name}.der`));
  };
  const rootDer = der("root");
  return {
    rootDer,
    x5c: [der("leaf"), der("intermediate"), rootDer].map((b) => b.toString("base64")),
    leafKey: readFileSync(join(dir, "leaf.key"), "utf8"),
  };
}

function jws(chain: Chain, payload: Record<string, unknown>): string {
  const header = Buffer.from(JSON.stringify({ alg: "ES256", x5c: chain.x5c })).toString("base64url");
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const sig = sign("sha256", Buffer.from(`${header}.${body}`), { key: createPrivateKey(chain.leafKey), dsaEncoding: "ieee-p1363" });
  return `${header}.${body}.${sig.toString("base64url")}`;
}

function transaction(over: Record<string, unknown> = {}) {
  const now = Date.now();
  return {
    transactionId: "2000000123", originalTransactionId: "2000000123", bundleId: "com.foodbest.ios",
    productId: "recipes_10", quantity: 1, type: "Consumable", environment: "Production",
    purchaseDate: now, originalPurchaseDate: now, signedDate: now, inAppOwnershipType: "PURCHASED",
    storefront: "NOR", storefrontId: "143457", transactionReason: "PURCHASE", currency: "NOK", price: 49000,
    ...over,
  };
}

let trusted: Chain;
let untrusted: Chain;
let verifier: AppleAppStoreVerifier;

beforeAll(() => {
  trusted = makeChain();
  untrusted = makeChain();
  verifier = new AppleAppStoreVerifier({ rootCertificates: [trusted.rootDer], appAppleId: 1234567890, enableOnlineChecks: false });
});

describe("AppleAppStoreVerifier", () => {
  test("accepts a transaction signed through the trusted chain", async () => {
    const tx = await verifier.verifyTransaction(jws(trusted, transaction({ quantity: 2 })));
    expect(tx).toMatchObject({ transactionId: "2000000123", productId: "recipes_10", quantity: 2, environment: "production", type: "Consumable" });
  });

  test("accepts sandbox transactions as sandbox", async () => {
    expect((await verifier.verifyTransaction(jws(trusted, transaction({ environment: "Sandbox" })))).environment).toBe("sandbox");
  });

  test("rejects a transaction signed by another chain", async () => {
    await expect(verifier.verifyTransaction(jws(untrusted, transaction()))).rejects.toBeInstanceOf(InvalidSignatureError);
  });

  test("rejects a payload altered after signing", async () => {
    const [h, , s] = jws(trusted, transaction()).split(".");
    const forgedBody = Buffer.from(JSON.stringify(transaction({ productId: "recipes_100" }))).toString("base64url");
    await expect(verifier.verifyTransaction(`${h}.${forgedBody}.${s}`)).rejects.toBeInstanceOf(InvalidSignatureError);
  });

  test("rejects another app's bundle id", async () => {
    await expect(verifier.verifyTransaction(jws(trusted, transaction({ bundleId: "com.other.app" })))).rejects.toBeInstanceOf(InvalidSignatureError);
  });

  test("refuses Xcode and LocalTesting transactions, which carry no App Store signature", async () => {
    for (const environment of ["Xcode", "LocalTesting"]) {
      await expect(verifier.verifyTransaction(jws(untrusted, transaction({ environment })))).rejects.toBeInstanceOf(InvalidSignatureError);
    }
  });

  test("verifies a notification and its embedded transaction", async () => {
    const signedTransactionInfo = jws(trusted, transaction({ revocationDate: Date.now(), revocationReason: 0 }));
    const payload = jws(trusted, {
      notificationType: "REFUND", notificationUUID: "6f1f0a8e-3c1b-4a83-9f3a-0c2a2b7b1e11", version: "2.0", signedDate: Date.now(),
      data: { bundleId: "com.foodbest.ios", appAppleId: 1234567890, environment: "Production", signedTransactionInfo },
    });
    const n = await verifier.verifyNotification(payload);
    expect(n).toMatchObject({ notificationType: "REFUND", environment: "production" });
    expect(n.transaction?.revocationDate).not.toBeNull();
  });

  test("rejects a forged notification", async () => {
    const payload = jws(untrusted, {
      notificationType: "REFUND", notificationUUID: "6f1f0a8e-3c1b-4a83-9f3a-0c2a2b7b1e12", version: "2.0", signedDate: Date.now(),
      data: { bundleId: "com.foodbest.ios", appAppleId: 1234567890, environment: "Production", signedTransactionInfo: jws(untrusted, transaction()) },
    });
    await expect(verifier.verifyNotification(payload)).rejects.toBeInstanceOf(InvalidSignatureError);
  });

  test("the bundled Apple root is Apple Root CA - G3", () => {
    const cert = new X509Certificate(readFileSync(join(__dirname, "..", "certs", "AppleRootCA-G3.cer")));
    expect(cert.subject).toContain("CN=Apple Root CA - G3");
    expect(cert.fingerprint256).toBe(
      "63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79",
    );
  });
});
