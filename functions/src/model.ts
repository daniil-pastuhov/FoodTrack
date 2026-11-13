/** Firestore document shapes, paths and small helpers. Only the Admin SDK touches these documents. */
import { createHash } from "node:crypto";
import type { DocumentReference, Firestore, Timestamp, Transaction } from "firebase-admin/firestore";
import { fail } from "./contract";
import {
  CONFIG_CACHE_MS,
  COST_KEYS,
  COST_MAX,
  DEFAULT_COSTS,
  DEFAULT_PRICES,
  PURCHASE_TOKEN_NAMESPACE,
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
  /** Input-caused failures refunded over the account's lifetime (see paidAllowance). */
  contentRefundsUsed?: number;
  /** Unknown-usage infrastructure failures (timeouts) refunded over the account's lifetime (see paidAllowance). */
  timeoutRefundsUsed?: number;
  /**
   * Buyer keys (`buyers/{key}`) of every purchase granted to this account or to accounts merged into
   * it; a blocked one sets `purchasesBlocked` in responses.
   */
  buyerKeys?: string[];
  /** Infrastructure failures refunded in the current UTC day (ceiling: infraRefundsPerDay). */
  infraRefunds?: { day: string; count: number };
  /** Estimated LLM spend in micro-USD in the current UTC day: worst case while in flight, actual after. */
  spendWindow?: { day: string; microUsd: number };
  mergedInto?: string;
  mergedAt?: Timestamp;
  deletedAt?: Timestamp;
  /** Balance that was lost when the account was deleted. */
  forfeitedBalance?: number;
  /** When the Auth user's displayName and photoURL were last cleared (scrubProfile). */
  profileScrubbedAt?: Timestamp;
  /** Set when this account's negative balance was moved to another account of the same buyer. */
  debtMovedTo?: string;
  debtMovedAt?: Timestamp;
  createdAt?: Timestamp;
}

export type PurchaseState = "granted" | "consumed" | "refunded";

export interface PurchaseDoc {
  store: "play" | "appstore";
  /** Owner. null for a purchase never submitted (refund seen first) or whose account was deleted. */
  uid: string | null;
  /** The owner before deleteAccount nulled `uid`; keeps the FIFO ledger and buyer attribution. */
  formerUid?: string;
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
  /** Set when the sweeper stopped retrying the consume after CONSUME_MAX_ATTEMPTS. */
  consumeGaveUpAt?: Timestamp;
  /** The purchase account token the store reported (obfuscatedExternalAccountId / appAccountToken). */
  accountToken?: string;
  /** buyers/{key} documents this purchase counts under (see buyerKeysFor in core/buyers.ts). */
  buyerKeys?: string[];
  /** iOS: the buyer agreed that consumption data may be sent to Apple if they ask for a refund. */
  consumptionConsent?: boolean;
  consumptionConsentAt?: Timestamp;
  /** The CONSUMPTION_REQUEST being answered (or last answered); see core/consumption.ts. */
  consumption?: ConsumptionState;
  /** True while a consumption answer is owed (the sweeper retries until `consumption.deadlineAt`). */
  consumptionPending?: boolean;
  /** Set while a refund of this purchase takes credits that had been spent (FIFO, core/buyers.ts). */
  refundAfterUse?: boolean;
}

export interface ConsumptionState {
  notificationUUID: string;
  requestedAt: Timestamp;
  deadlineAt: Timestamp;
  attempts: number;
  answeredAt?: Timestamp;
  /** What was (or will be) sent, for the record. */
  consumptionPercentage?: number;
  /** Fixed reason code of the last failure. */
  lastError?: string;
  gaveUp?: boolean;
}

/**
 * buyers/{key}: refund-after-use events of one buyer. Never deleted. Holds no personal data: the key
 * is an Apple appTransactionId, a Firebase uid, or a hash of a sign-in provider's user id.
 */
export interface BuyerDoc {
  /** Active refund-after-use events, by purchase id. */
  events: Record<string, { at: Timestamp; store: "play" | "appstore"; spentCreditsTaken: number; active: boolean }>;
  refundAfterUseCount: number;
  /** Written by the policy, unless `blockedOverride` is true: then it is the owner's decision. */
  blocked: boolean;
  blockedOverride?: boolean;
  blockedAt?: Timestamp;
  createdAt: Timestamp;
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
  /**
   * The daily spend ceiling (all users, micro-USD; at it generation is paused) is
   * min(dailySpendMaxMicroUsd, dailySpendBaseMicroUsd + dailySpendRevenueFraction x
   * netMicroUsdPerCredit x production credits granted, net of refunds, over the last 7 UTC days).
   */
  dailySpendBaseMicroUsd: number;
  dailySpendMaxMicroUsd: number;
  dailySpendRevenueFraction: number;
  /** Conservative estimate of what one production credit nets after store fees and VAT, in micro-USD. */
  netMicroUsdPerCredit: number;
  /** Estimated LLM spend per user per UTC day, in micro-USD. */
  perUserDailySpendMicroUsd: number;
  /** Refund-after-use events that block a buyer (purchasesBlocked). */
  refundAbuseThreshold: number;
  /** Sent to Apple as sampleContentProvided: the pack sheet explains what a credit buys before purchase. */
  consumptionSampleContentProvided: boolean;
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
  // See README "Spend ceilings" for the arithmetic: at most USD 2.00 a day (USD 60 a month) of
  // estimated LLM spend, USD 1.20 a day without sales, USD 0.45 per user (2 three-course menus).
  dailySpendBaseMicroUsd: 1_200_000,
  dailySpendMaxMicroUsd: 2_000_000,
  dailySpendRevenueFraction: 0.25,
  netMicroUsdPerCredit: 120_000,
  perUserDailySpendMicroUsd: 450_000,
  refundAbuseThreshold: 2,
  consumptionSampleContentProvided: true,
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
  "dailySpendBaseMicroUsd",
  "dailySpendMaxMicroUsd",
  "netMicroUsdPerCredit",
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
  for (const key of ["generationEnabled", "allowSandbox", "consumptionSampleContentProvided"] as const) {
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
  if (data.dailySpendRevenueFraction !== undefined) {
    const f = data.dailySpendRevenueFraction;
    if (typeof f === "number" && Number.isFinite(f) && f >= 0 && f <= 1) out.dailySpendRevenueFraction = f;
    else bad.push("dailySpendRevenueFraction");
  }
  if (data.refundAbuseThreshold !== undefined) {
    const t = data.refundAbuseThreshold;
    if (isCount(t) && t >= 1) out.refundAbuseThreshold = t;
    else bad.push("refundAbuseThreshold");
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

/**
 * `config/runtime` cached in memory for `ttlMs`, so a flood of cheap calls is not also a flood of
 * config reads. Concurrent loads share one read. A failed read is never papered over with a stale
 * copy: the call fails (fail closed) and the next call reads again.
 */
export class RuntimeConfigCache {
  private value: RuntimeConfig | undefined;
  private loadedAt = Number.NEGATIVE_INFINITY;
  private inFlight: Promise<RuntimeConfig> | undefined;

  constructor(
    private readonly load: () => Promise<RuntimeConfig>,
    private readonly now: () => number,
    private readonly ttlMs = CONFIG_CACHE_MS,
  ) {}

  get(): Promise<RuntimeConfig> {
    if (this.value && this.now() - this.loadedAt < this.ttlMs) return Promise.resolve(this.value);
    this.inFlight ??= this.load().then(
      (v) => {
        this.value = v;
        this.loadedAt = this.now();
        this.inFlight = undefined;
        return v;
      },
      (e: unknown) => {
        this.value = undefined;
        this.inFlight = undefined;
        throw e;
      },
    );
    return this.inFlight;
  }
}

/** The revenue-aware daily spend ceiling (see RuntimeConfig.dailySpendBaseMicroUsd). */
export function dailySpendCeiling(config: RuntimeConfig, netCredits7d: number): number {
  const fromRevenue = Math.floor(
    config.dailySpendRevenueFraction * Math.max(0, netCredits7d) * config.netMicroUsdPerCredit,
  );
  return Math.min(config.dailySpendMaxMicroUsd, config.dailySpendBaseMicroUsd + fromRevenue);
}

/**
 * The purchase account token of a uid: UUIDv5 (RFC 9562) of the uid's UTF-8 bytes in
 * PURCHASE_TOKEN_NAMESPACE, lower case. Clients set it as Play's obfuscatedAccountId and Apple's
 * appAccountToken at purchase.
 */
export function purchaseAccountToken(uid: string): string {
  return uuidV5(PURCHASE_TOKEN_NAMESPACE, uid);
}

/** RFC 9562 UUID version 5 (SHA-1) of `name`'s UTF-8 bytes in `namespace`, lower case. */
export function uuidV5(namespace: string, name: string): string {
  const ns = Buffer.from(namespace.replace(/-/g, ""), "hex");
  const h = createHash("sha1").update(ns).update(Buffer.from(name, "utf8")).digest().subarray(0, 16);
  h[6] = (h[6] & 0x0f) | 0x50;
  h[8] = (h[8] & 0x3f) | 0x80;
  const hex = h.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
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

/** Lifetime refundable failures of a kind: one per `perRefund` paid credits (net of store refunds), min 1 once paid. */
export function paidAllowance(user: UserDoc, perRefund: number): number {
  const paid = (user.purchasedCredits ?? 0) - (user.refundedCredits ?? 0);
  return paid > 0 ? Math.max(1, Math.floor(paid / perRefund)) : 0;
}

export const userRef = (db: Firestore, uid: string) => db.collection("users").doc(uid) as DocumentReference<UserDoc>;
export const generationRef = (db: Firestore, uid: string, requestId: string) =>
  userRef(db, uid).collection("generations").doc(requestId) as DocumentReference<GenerationDoc>;
export const purchaseRef = (db: Firestore, id: string) =>
  db.collection("purchases").doc(id) as DocumentReference<PurchaseDoc>;
export const statsRef = (db: Firestore, day: string) => db.collection("stats").doc(day.replace(/-/g, ""));
export const buyerRef = (db: Firestore, key: string) => db.collection("buyers").doc(key) as DocumentReference<BuyerDoc>;

export function utcDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

export function emptyUser(): UserDoc {
  return {
    balance: 0, inFlight: 0, purchasedCredits: 0, consumedCredits: 0, refundedCredits: 0,
    contentRefundsUsed: 0, timeoutRefundsUsed: 0,
  };
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
