/** Firestore document shapes, paths and small helpers. Only the Admin SDK touches these documents. */
import { createHash } from "node:crypto";
import type { DocumentReference, Firestore, Timestamp, Transaction } from "firebase-admin/firestore";
import { fail } from "./contract";
import {
  COST_KEYS,
  COST_MAX,
  DEFAULT_COSTS,
  DEFAULT_PRICES,
  type CostKey,
  type Costs,
  type GenerationKind,
} from "./constants";
import type { FailureClass, LlmUsage, Logger } from "./ports";
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
  /** Input-caused failures refunded over the account's lifetime (see contentRefundAllowance). */
  contentRefundsUsed?: number;
  /** Infrastructure failures refunded in the current UTC day (ceiling: infraRefundsPerDay). */
  infraRefunds?: { day: string; count: number };
  /** Estimated LLM spend in micro-USD in the current UTC day: worst case while in flight, actual after. */
  spendWindow?: { day: string; microUsd: number };
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
  /** Credits charged by the reservation; settle and refund use this, never the current table. */
  cost: number;
  /** sha256 of the validated request (inputHashOf): a requestId is bound to its inputs. */
  inputHash?: string;
  /** Worst-case estimated cost reserved in stats/{statsDay} and in the user's spend window. */
  reservedMicroUsd?: number;
  /** The UTC day (YYYY-MM-DD) the reservation was counted in, so settle corrects that same day. */
  statsDay?: string;
  /** Usage of the LLM call, recorded at settle (absent: unknown, e.g. settled by the sweeper). */
  usage?: LlmUsage;
  /** Estimated cost recorded at settle (the reserved worst case when usage is unknown). */
  actualMicroUsd?: number;
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

export interface Prices {
  inputUsdPerMTok: number;
  outputUsdPerMTok: number;
}

export interface RuntimeConfig {
  generationEnabled: boolean;
  allowSandbox: boolean;
  /** Credits' worth of generations (each counts its cost) allowed per UTC day across all users. */
  dailyCap: number;
  /** Sandbox / TestFlight / licence-tester purchases that may grant credits per UTC day. */
  sandboxDailyCap: number;
  /** Credits sandbox purchases may grant per UTC day across all users (one recipes_100 x 10 is 1000). */
  sandboxDailyCredits: number;
  perUserPerMinute: number;
  perUserPerDay: number;
  verifyPerMinute: number;
  /** Refunded infrastructure failures per user per UTC day; at the ceiling, generation is refused. */
  infraRefundsPerDay: number;
  /** Estimated LLM spend per UTC day, all users, in micro-USD. At it, generation is paused. */
  dailySpendCapMicroUsd: number;
  /** Estimated LLM spend per user per UTC day, in micro-USD. */
  perUserDailySpendMicroUsd: number;
  /** The kill switch flips when a budget alert reports cost >= budget * this. */
  budgetKillFraction: number;
  costs: Costs;
  prices: Prices;
}

export const DEFAULT_RUNTIME_CONFIG: Readonly<RuntimeConfig> = {
  generationEnabled: true,
  allowSandbox: false,
  dailyCap: 1000,
  sandboxDailyCap: 20,
  sandboxDailyCredits: 200,
  perUserPerMinute: 3,
  perUserPerDay: 40,
  verifyPerMinute: 10,
  infraRefundsPerDay: 3,
  // USD 2 a day (~USD 60 a month), well under the 1000 NOK (~USD 90) monthly budget.
  dailySpendCapMicroUsd: 2_000_000,
  perUserDailySpendMicroUsd: 1_000_000,
  budgetKillFraction: 1,
  costs: DEFAULT_COSTS,
  prices: { ...DEFAULT_PRICES },
};

const COUNT_FIELDS = [
  "dailyCap",
  "sandboxDailyCap",
  "sandboxDailyCredits",
  "perUserPerMinute",
  "perUserPerDay",
  "verifyPerMinute",
  "infraRefundsPerDay",
  "dailySpendCapMicroUsd",
  "perUserDailySpendMicroUsd",
] as const;

const isCount = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v) && v >= 0;
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

/** A costs override, or null when any part of it is invalid (then the whole default table applies). */
export function validCosts(v: unknown): Costs | null {
  if (!isObj(v)) return null;
  for (const k of COST_KEYS) {
    const n = v[k];
    if (typeof n !== "number" || !Number.isInteger(n) || n < 1 || n > COST_MAX) return null;
  }
  const out = { recipe: v.recipe as number, menu2: v.menu2 as number, menu3: v.menu3 as number };
  if (out.menu2 < out.recipe || out.menu3 < out.menu2) return null;
  return out;
}

function validPrices(v: unknown): Prices | null {
  if (!isObj(v)) return null;
  const ok = (n: unknown) => typeof n === "number" && Number.isFinite(n) && n >= 0.01 && n <= 1000;
  if (!ok(v.inputUsdPerMTok) || !ok(v.outputUsdPerMTok)) return null;
  return { inputUsdPerMTok: v.inputUsdPerMTok as number, outputUsdPerMTok: v.outputUsdPerMTok as number };
}

/**
 * Parses `config/runtime`. A missing field takes its default. A present but invalid field never
 * fails open: `generationEnabled` and `allowSandbox` read as false, limits and tables take their
 * defaults (the safe values), and a warning names the fields.
 */
export function parseRuntimeConfig(data: Record<string, unknown>, logger?: Logger): RuntimeConfig {
  const out: RuntimeConfig = { ...DEFAULT_RUNTIME_CONFIG };
  const bad: string[] = [];
  for (const key of ["generationEnabled", "allowSandbox"] as const) {
    if (data[key] === undefined) continue;
    if (typeof data[key] === "boolean") {
      out[key] = data[key] as boolean;
    } else {
      out[key] = false;
      bad.push(key);
    }
  }
  for (const key of COUNT_FIELDS) {
    if (data[key] === undefined) continue;
    if (isCount(data[key])) out[key] = data[key] as number;
    else bad.push(key);
  }
  if (data.budgetKillFraction !== undefined) {
    const f = data.budgetKillFraction;
    if (typeof f === "number" && Number.isFinite(f) && f > 0 && f <= 2) out.budgetKillFraction = f;
    else bad.push("budgetKillFraction");
  }
  if (data.costs !== undefined) {
    const c = validCosts(data.costs);
    if (c) out.costs = c;
    else bad.push("costs");
  }
  if (data.prices !== undefined) {
    const p = validPrices(data.prices);
    if (p) out.prices = p;
    else bad.push("prices");
  }
  if (bad.length) logger?.warn("config/runtime has invalid fields; safe values used", { fields: bad });
  return out;
}

export async function loadRuntimeConfig(db: Firestore, logger?: Logger): Promise<RuntimeConfig> {
  const snap = await db.doc("config/runtime").get();
  return parseRuntimeConfig((snap.data() ?? {}) as Record<string, unknown>, logger);
}

export function costKeyOf(kind: GenerationKind, courses?: 2 | 3): CostKey {
  if (kind === "recipe") return "recipe";
  return courses === 2 ? "menu2" : "menu3";
}

/** Estimated cost in micro-USD, rounded up. Thinking tokens are billed as output. */
export function microUsd(prices: Prices, inputTokens: number, outputTokens: number): number {
  return Math.ceil(inputTokens * prices.inputUsdPerMTok + outputTokens * prices.outputUsdPerMTok);
}

/** A stable pseudonym for a uid in logs, so log lines correlate without holding the uid. */
export function uidHash(uid: string): string {
  return createHash("sha256").update(`foodtracker-log:${uid}`).digest("hex").slice(0, 16);
}

/** Lifetime refundable content failures: one per `perRefund` paid credits (net of store refunds), min 1 once paid. */
export function contentRefundAllowance(user: UserDoc, perRefund: number): number {
  const paid = (user.purchasedCredits ?? 0) - (user.refundedCredits ?? 0);
  return paid > 0 ? Math.max(1, Math.floor(paid / perRefund)) : 0;
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
  return { balance: 0, inFlight: 0, purchasedCredits: 0, consumedCredits: 0, refundedCredits: 0, contentRefundsUsed: 0 };
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
