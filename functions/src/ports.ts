/**
 * Interfaces to everything outside Firestore: the two stores, the LLM and Apple's token revocation.
 * Production implementations live in src/adapters; tests use fakes.
 */
import type { Auth } from "firebase-admin/auth";
import type { Firestore } from "firebase-admin/firestore";
import type { GenerateRequest } from "./contract";

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
}

// --- Sign in with Apple -----------------------------------------------------------------------

export interface AppleTokenRevoker {
  /** Exchanges a fresh Sign in with Apple authorization code for a token and revokes it. */
  revokeWithAuthorizationCode(authorizationCode: string): Promise<void>;
}

// --- LLM --------------------------------------------------------------------------------------

/**
 * Failure classes. `infra` (timeout, 5xx, quota) is always refunded; `content` (safety block,
 * unusable output) is refunded at most CONTENT_REFUNDS_PER_DAY times per user per day.
 */
export type FailureClass = "infra" | "content";

export class GenerationError extends Error {
  constructor(readonly failureClass: FailureClass, message: string) {
    super(message);
  }
}

export interface RecipeGenerator {
  /**
   * Returns the model's parsed JSON (validated by the caller against the expected shape), or
   * throws GenerationError. Must honour `signal`.
   */
  generate(request: GenerateRequest, signal: AbortSignal): Promise<unknown>;
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
  appleRevoker: AppleTokenRevoker;
  now(): number;
  logger: Logger;
}

/** The parts of a verified Firebase ID token the core needs. */
export interface Caller {
  uid: string;
  signInProvider: string;
  /** Seconds since epoch of the user's last sign-in. */
  authTime: number;
}
