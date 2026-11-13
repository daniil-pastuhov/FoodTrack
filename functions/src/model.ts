/** Firestore document shapes, paths and small helpers. Only the Admin SDK touches these documents. */
import type { DocumentReference, Firestore, Timestamp, Transaction } from "firebase-admin/firestore";
import { fail } from "./contract";
import type { GenerationKind } from "./constants";
import type { FailureClass } from "./ports";
import type { Menu, Recipe } from "./contract";

export interface UserDoc {
  balance: number;
  inFlight: number;
  purchasedCredits: number;
  consumedCredits: number;
  refundedCredits: number;
  /** Rate-limit windows: generations started in the current minute and UTC day. */
  minuteWindow?: { start: number; count: number };
  dayWindow?: { day: string; count: number };
  /** verifyPurchase calls in the current minute. */
  verifyWindow?: { start: number; count: number };
  /** Input-caused failures refunded today (CONTENT_REFUNDS_PER_DAY). */
  contentRefunds?: { day: string; count: number };
  mergedInto?: string;
  mergedAt?: Timestamp;
  deletedAt?: Timestamp;
  /** Balance that was lost when the account was deleted. */
  forfeitedBalance?: number;
  createdAt?: Timestamp;
}

export type PurchaseState = "granted" | "consumed" | "refunded";

export interface PurchaseDoc {
  store: "play" | "appstore";
  /** Owner. null for a purchase never submitted (refund seen first) or whose account was deleted. */
  uid: string | null;
  env: "production" | "sandbox";
  productId: string | null;
  quantity: number;
  /** Credits the purchase is worth: PRODUCT_CREDITS[productId] * quantity. */
  credits: number;
  /** Credits taken back by refunds so far (partial refunds make this less than `credits`). */
  refundedCredits: number;
  state: PurchaseState;
  /** Set when the credits were added to a balance. A doc without it was never redeemed. */
  grantedAt: Timestamp | null;
  /** Play only: set once the server consumed the purchase. */
  consumedAt?: Timestamp | null;
  /** Play only, kept until consumed so the sweeper can retry the consume. */
  purchaseToken?: string;
  orderId?: string | null;
  transactionId?: string;
  /** Store event time (ms) of the last refund/reversal applied; older events are ignored. */
  refundEventAt?: number;
  consumptionRequestedAt?: Timestamp;
  consumeAttempts?: number;
  /** Play only: true while a server-side consume is still owed (the sweeper's work list). */
  consumePending?: boolean;
}

export type GenerationState = "pending" | "done" | "failed";

export interface GenerationDoc {
  state: GenerationState;
  kind: GenerationKind;
  courses?: 2 | 3;
  cost: number;
  createdAt: Timestamp;
  deadlineAt: Timestamp;
  /** Whether the reserved credits stayed spent. */
  charged?: boolean;
  failureClass?: FailureClass;
  failureReason?: string;
  recipe?: Recipe;
  menu?: Menu;
  /** Set on terminal states only; Firestore TTL deletes the doc after it. */
  expiresAt?: Timestamp;
}

export interface RuntimeConfig {
  generationEnabled: boolean;
  allowSandbox: boolean;
  /** Credits' worth of generations (a menu counts 3) allowed per UTC day across all users. */
  dailyCap: number;
  /** Sandbox / TestFlight / licence-tester purchases that may grant credits per UTC day. */
  sandboxDailyCap: number;
  perUserPerMinute: number;
  perUserPerDay: number;
  verifyPerMinute: number;
  /** The kill switch flips when a budget alert reports cost >= budget * this. */
  budgetKillFraction: number;
}

export const DEFAULT_RUNTIME_CONFIG: RuntimeConfig = {
  generationEnabled: true,
  allowSandbox: false,
  dailyCap: 1000,
  sandboxDailyCap: 20,
  perUserPerMinute: 3,
  perUserPerDay: 40,
  verifyPerMinute: 10,
  budgetKillFraction: 1,
};

export async function loadRuntimeConfig(db: Firestore): Promise<RuntimeConfig> {
  const snap = await db.doc("config/runtime").get();
  const data = (snap.data() ?? {}) as Partial<RuntimeConfig>;
  const out = { ...DEFAULT_RUNTIME_CONFIG };
  for (const key of Object.keys(DEFAULT_RUNTIME_CONFIG) as (keyof RuntimeConfig)[]) {
    const v = data[key];
    if (typeof v === typeof DEFAULT_RUNTIME_CONFIG[key]) (out as Record<string, unknown>)[key] = v;
  }
  return out;
}

export const userRef = (db: Firestore, uid: string) => db.collection("users").doc(uid) as DocumentReference<UserDoc>;
export const generationRef = (db: Firestore, uid: string, requestId: string) =>
  userRef(db, uid).collection("generations").doc(requestId) as DocumentReference<GenerationDoc>;
export const purchaseRef = (db: Firestore, id: string) =>
  db.collection("purchases").doc(id) as DocumentReference<PurchaseDoc>;
export const statsRef = (db: Firestore, day: string) => db.collection("stats").doc(day.replace(/-/g, ""));

export function utcDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

export function emptyUser(): UserDoc {
  return { balance: 0, inFlight: 0, purchasedCredits: 0, consumedCredits: 0, refundedCredits: 0 };
}

export function isClosed(user: UserDoc | undefined): boolean {
  return !!user && (user.deletedAt !== undefined || user.mergedInto !== undefined);
}

/** Every mutating transaction refuses to act for a deleted or merged-away account (its ID token stays valid up to an hour). */
export function assertOpen(user: UserDoc | undefined): void {
  if (isClosed(user)) fail("failed-precondition", "ACCOUNT_CLOSED", "This account was deleted or merged");
}

/** Follows `mergedInto` from `uid` to the account that holds its credits now. Reads inside `tx`. */
export async function resolveHolder(
  tx: Transaction,
  db: Firestore,
  uid: string,
  maxHops: number,
): Promise<{ uid: string; user: UserDoc | undefined }> {
  let current = uid;
  for (let hop = 0; ; hop++) {
    const user = (await tx.get(userRef(db, current))).data();
    if (!user?.mergedInto || hop >= maxHops) return { uid: current, user };
    current = user.mergedInto;
  }
}
