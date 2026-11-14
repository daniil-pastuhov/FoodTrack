/**
 * Interfaces to everything outside Firestore: the two stores, the LLM and Apple's token revocation.
 * Production implementations live in src/adapters; tests use fakes.
 */
import type { Auth } from "firebase-admin/auth";
import type { Firestore } from "firebase-admin/firestore";
import type { GenerateRequest, Menu, Recipe } from "./contract";
import type { Prompt } from "./adapters/prompt";
import type { RuntimeConfig } from "./model";

/** A store error that should make the client try again later (network, 5xx, quota, misconfiguration). */
export class RetryableStoreError extends Error {}

// --- Google Play ------------------------------------------------------------------------------

export interface PlayPurchase {
  productId: string;
  quantity: number;
  purchaseState: "PURCHASED" | "PENDING" | "CANCELLED" | "UNSPECIFIED";
  consumed: boolean;
  /** Purchased by a licence tester (testPurchaseContext present). */
  isTest: boolean;
  orderId: string | null;
  /** Units not yet voided; quantity minus refundableQuantity have been refunded. */
  refundableQuantity: number;
  /** BillingFlowParams.setObfuscatedAccountId as set by the client at purchase, or null. */
  obfuscatedExternalAccountId: string | null;
}

export interface VoidedPurchase {
  purchaseToken: string;
  orderId: string | null;
  voidedTimeMillis: number;
}

export interface PlayVerifier {
  /** null when Play says the token does not exist or is invalid (a permanent rejection). Throws RetryableStoreError. */
  getPurchase(purchaseToken: string): Promise<PlayPurchase | null>;
  /** Throws RetryableStoreError on a failure worth retrying. */
  consume(productId: string, purchaseToken: string): Promise<void>;
  /** Fully voided one-time purchases seen by Google since `startTimeMillis`. */
  listVoided(startTimeMillis: number): Promise<VoidedPurchase[]>;
}

// --- App Store --------------------------------------------------------------------------------

export type AppleEnvironment = "production" | "sandbox";

export interface AppleTransaction {
  transactionId: string;
  originalTransactionId: string;
  bundleId: string;
  productId: string;
  quantity: number;
  /** StoreKit product type, e.g. "Consumable". */
  type: string;
  environment: AppleEnvironment;
  revocationDate: number | null;
  /** Milliunits (100000 = 100 %) of the transaction refunded, when the store reports a partial refund. */
  revocationPercentage: number | null;
  signedDate: number;
  /** The appAccountToken the client set at purchase (a UUID), or null. */
  appAccountToken: string | null;
  /** Stable per Apple Account per app; null on transactions from before Apple added it. */
  appTransactionId: string | null;
}

export interface AppleNotification {
  notificationUUID: string;
  notificationType: string;
  subtype: string | null;
  signedDate: number;
  environment: AppleEnvironment;
  /** The transaction the notification is about, already signature-verified. */
  transaction: AppleTransaction | null;
}

/** Thrown when a signed payload is not genuine, is for another app, or for a refused environment (Xcode). */
export class InvalidSignatureError extends Error {}

export interface AppStoreVerifier {
  /** Verifies the JWS chain to Apple's root and the bundle id/environment. Throws InvalidSignatureError. */
  verifyTransaction(jws: string): Promise<AppleTransaction>;
  /** Fetches the transaction fresh from the App Store Server API. null when Apple doesn't know it. */
  fetchTransaction(transactionId: string, environment: AppleEnvironment): Promise<AppleTransaction | null>;
  /** Verifies an App Store Server Notifications v2 `signedPayload`. Throws InvalidSignatureError. */
  verifyNotification(signedPayload: string): Promise<AppleNotification>;
  /**
   * App Store Server API "Send Consumption Information" (v2). Throws RetryableStoreError when
   * worth retrying (network, 429, 5xx, missing key) and ConsumptionRejectedError when Apple refused
   * the request for good (other 4xx).
   */
  sendConsumptionInformation(transactionId: string, environment: AppleEnvironment, info: ConsumptionInfo): Promise<void>;
}

/** The current (v2) ConsumptionRequest body. Apple: a deliveryStatus other than DELIVERED needs consumptionPercentage 0. */
export interface ConsumptionInfo {
  customerConsented: true;
  /** Milliunits, 0 to 100000. */
  consumptionPercentage: number;
  deliveryStatus: "DELIVERED" | "UNDELIVERED_OTHER";
  sampleContentProvided: boolean;
}

/** Apple refused a consumption answer for good (a 4xx other than 429). */
export class ConsumptionRejectedError extends Error {}

// --- Sign in with Apple -----------------------------------------------------------------------

/**
 * A Sign in with Apple REST call failed. `rejected`: Apple answered 4xx (an expired, used or
 * foreign code, an invalid client secret); `retryable`: network, timeout, 5xx or misconfiguration.
 * The message carries only the endpoint, the HTTP status and Apple's `error` code, never a code or
 * a token.
 */
export class AppleTokenError extends Error {
  constructor(
    readonly kind: "rejected" | "retryable",
    message: string,
  ) {
    super(message);
  }
}

export interface AppleTokenRevoker {
  /** Exchanges a fresh Sign in with Apple authorization code for a token and revokes it. Throws AppleTokenError. */
  revokeWithAuthorizationCode(authorizationCode: string): Promise<void>;
  /**
   * Exchanges an authorization code (single use, valid five minutes) at /auth/token and returns
   * the refresh token. Throws AppleTokenError.
   */
  exchangeAuthorizationCode(authorizationCode: string): Promise<string>;
  /** Revokes a refresh token at /auth/revoke (Apple answers 200 for an already invalid one too). Throws AppleTokenError. */
  revokeRefreshToken(refreshToken: string): Promise<void>;
}

// --- Application-level encryption -------------------------------------------------------------

/**
 * A secret sealed with envelope encryption: a fresh AES-256-GCM data key per value, itself
 * encrypted ("wrapped") by a key-encryption key the plaintext never reaches (Cloud KMS in
 * production). All fields are base64 except `kek`, the name of the key that wrapped the data key.
 */
export interface SealedSecret {
  v: 1;
  kek: string;
  wrappedKey: string;
  iv: string;
  ciphertext: string;
  tag: string;
}

export interface SecretSealer {
  /** `context` is bound to the value as additional authenticated data: opening needs the same context. */
  seal(plaintext: string, context: string): Promise<SealedSecret>;
  /** Throws when the value was tampered with, sealed for another context, or the key is unavailable. */
  open(sealed: SealedSecret, context: string): Promise<string>;
}

// --- LLM --------------------------------------------------------------------------------------

/**
 * Failure classes. `infra` (timeout, 5xx, quota, token-ceiling abort) is always refunded, up to a
 * per-user daily ceiling after which generation is refused; `content` (safety block, MAX_TOKENS,
 * unusable output) is refunded within an allowance tied to the credits the user has paid for.
 */
export type FailureClass = "infra" | "content";

/** Token counts of one LLM call, from the response's usageMetadata. */
export interface LlmUsage {
  inputTokens: number;
  /** Visible output (candidatesTokenCount). */
  outputTokens: number;
  thinkingTokens: number;
  model: string;
  finishReason: string | null;
}

export class GenerationError extends Error {
  /**
   * `usage`: what the call is known to have used. Absent means unknown (the call may have been
   * billed for anything up to the worst case), so the spend estimate keeps the reserved worst case.
   */
  /**
   * `safety`: set when the model's own safety system refused the prompt or the answer (a fixed
   * code such as `PROMPT_BLOCKED` or `FINISH_SAFETY`); such a failure counts against the account's
   * daily prompt-rejection limit.
   */
  constructor(
    readonly failureClass: FailureClass,
    message: string,
    readonly usage?: LlmUsage,
    readonly safety?: string,
  ) {
    super(message);
  }
}

export interface GenerationLimits {
  maxOutputTokens: number;
  /** Abort the call, as an infrastructure failure, once output + thinking is observed above this. */
  hardTokenCeiling: number;
}

export interface GenerationResult {
  /** The model's parsed JSON, validated by the caller against the expected shape. */
  output: unknown;
  /** Absent when the model reported no usage: the call is then booked at the reserved worst case. */
  usage?: LlmUsage;
}

export interface RecipeGenerator {
  /**
   * Sends `prompt` (built by the core from `request`; the request is passed for fakes). Throws
   * GenerationError. Must honour `signal` and `limits`.
   */
  generate(request: GenerateRequest, prompt: Prompt, signal: AbortSignal, limits: GenerationLimits): Promise<GenerationResult>;
}

/** The optional second check on a validated answer (config/runtime `outputJudge`). */
export type JudgeVerdict = "ok" | "off_topic" | "unsafe_food" | "harmful";

export interface JudgeResult {
  verdict: JudgeVerdict;
  /** Absent when unknown: the call is then booked at its reserved worst case. */
  usage?: LlmUsage;
}

export interface OutputJudge {
  /**
   * Classifies a validated recipe or menu. Sees only the answer (never the user's input), and
   * answers with an enum. Throws GenerationError ("content" when the judge's own safety filter
   * blocked the text, "infra" otherwise).
   */
  judge(output: { recipe?: Recipe; menu?: Menu }, signal: AbortSignal): Promise<JudgeResult>;
}

// --- Wiring -----------------------------------------------------------------------------------

export interface Logger {
  info(message: string, data?: Record<string, unknown>): void;
  warn(message: string, data?: Record<string, unknown>): void;
  error(message: string, data?: Record<string, unknown>): void;
}

export interface Deps {
  db: Firestore;
  auth: Auth;
  play: PlayVerifier;
  appStore: AppStoreVerifier;
  generator: RecipeGenerator;
  judge: OutputJudge;
  appleRevoker: AppleTokenRevoker;
  /** Encrypts the stored Sign in with Apple refresh tokens (adapters/envelope.ts). */
  sealer: SecretSealer;
  now(): number;
  logger: Logger;
  /** `config/runtime`, parsed fail-closed (cached for CONFIG_CACHE_MS in production). */
  config(): Promise<RuntimeConfig>;
}

/** The parts of a verified Firebase ID token the core needs. */
export interface Caller {
  uid: string;
  signInProvider: string;
  /** Seconds since epoch of the user's last sign-in. */
  authTime: number;
  /** The Firebase app id from the verified App Check token, or null when there is none. */
  appId: string | null;
  /** The ID token still carries a `name` or `picture` claim (the Auth user has a display name or photo). */
  profileClaims: boolean;
  /**
   * Sign-in providers linked to the user when the ID token was issued (the keys of its
   * `firebase.identities` claim, e.g. `apple.com`, `google.com`). Up to an hour stale.
   */
  providers: string[];
  /** Seconds since epoch when the ID token was issued (`iat`). */
  issuedAt: number;
}
