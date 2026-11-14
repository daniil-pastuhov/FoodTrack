/**
 * Purchase verification and refunds. The only way a balance goes up is a store-verified purchase
 * whose `purchases/` doc does not exist yet; that doc is never deleted, so it is the replay guard.
 *
 * Order of checks in verifyPurchase, cheapest first: input validation, the caller's app must match
 * the store (App Check app id), the per-uid throttle, local signature verification (Apple), the
 * purchase account token (Apple), the local purchase doc (an already-settled purchase is answered
 * without any store call), and only then the store API.
 */
import { createHash } from "node:crypto";
import { FieldValue, Timestamp, type Transaction } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { CAPABILITIES, fail, validateVerifyRequest, type VerifyResponse, type VerifyStatus } from "../contract";
import {
  ANDROID_FIREBASE_APP_ID,
  CONSUME_MAX_ATTEMPTS,
  IOS_BUNDLE_ID,
  IOS_FIREBASE_APP_ID,
  MERGE_CHAIN_MAX,
  PRODUCT_CREDITS,
} from "../constants";
import {
  assertOpen,
  buyerRef,
  emptyUser,
  purchaseAccountToken,
  purchaseRef,
  resolveHolder,
  statsRef,
  uidHash,
  userRef,
  utcDay,
  type BuyerDoc,
  type PurchaseDoc,
  type RuntimeConfig,
  type UserDoc,
} from "../model";
import { LogSampler } from "./guard";
import {
  InvalidSignatureError,
  REFUSED_ENVIRONMENT,
  RetryableStoreError,
  type AppleTransaction,
  type Caller,
  type Deps,
} from "../ports";
import {
  appleBuyerKeys,
  blockFlag,
  effectivelyBlocked,
  fifoConsumed,
  flushErrors,
  mergeKeys,
  playBuyerKeys,
  readBuyers,
  uidBuyerKey,
  writeRefundAfterUse,
  type PendingLogs,
} from "./buyers";

export const playPurchaseId = (purchaseToken: string) =>
  `play_${createHash("sha256").update(purchaseToken).digest("hex")}`;
export const applePurchaseId = (env: "production" | "sandbox", transactionId: string) =>
  `appstore_${env}_${transactionId}`;

const PERCENT_MILLIUNITS = 100_000;

/** Credits of a purchase Apple reports as (partly) refunded: all of them unless a percentage is given. */
export function appleRefundedCredits(credits: number, tx: Pick<AppleTransaction, "revocationDate" | "revocationPercentage">): number {
  if (tx.revocationDate === null) return 0;
  if (tx.revocationPercentage === null) return credits;
  return Math.min(credits, Math.ceil((credits * tx.revocationPercentage) / PERCENT_MILLIUNITS));
}

export async function readBalance(deps: Deps, uid: string): Promise<number> {
  const user = (await userRef(deps.db, uid).get()).data();
  return user?.balance ?? 0;
}

/** Play purchases only from the Android app, App Store purchases only from the iOS app. */
function assertStoreMatchesApp(caller: Caller, store: "play" | "appstore"): void {
  const want = store === "play" ? ANDROID_FIREBASE_APP_ID : IOS_FIREBASE_APP_ID;
  if (caller.appId !== want) fail("permission-denied", "STORE_MISMATCH", "This store is not available from this app");
}

/**
 * A purchase account token reported by the store must be the caller's (purchaseAccountToken), or
 * that of an account merged into the caller (a purchase made on an anonymous install that was
 * later merged). Absent: accepted (clients before the token existed).
 */
async function assertAccountToken(deps: Deps, uid: string, token: string | null): Promise<void> {
  if (!token) return;
  const presented = token.toLowerCase();
  if (presented === purchaseAccountToken(uid)) return;
  let frontier = [uid];
  for (let depth = 0; depth < MERGE_CHAIN_MAX && frontier.length; depth++) {
    const next: string[] = [];
    for (const target of frontier) {
      const q = await deps.db.collection("users").where("mergedInto", "==", target).get();
      for (const d of q.docs) {
        if (purchaseAccountToken(d.id) === presented) return;
        next.push(d.id);
      }
    }
    frontier = next;
  }
  fail("failed-precondition", "PURCHASE_ACCOUNT_MISMATCH", "This purchase was made by another account");
}

/** Per-uid throttle on verifyPurchase, counted before any store call. */
async function throttleVerify(deps: Deps, uid: string, config: RuntimeConfig): Promise<void> {
  const minute = Math.floor(deps.now() / 60_000);
  await deps.db.runTransaction(async (tx) => {
    const ref = userRef(deps.db, uid);
    const user = (await tx.get(ref)).data();
    assertOpen(user);
    const w = user?.verifyWindow?.start === minute ? user.verifyWindow : { start: minute, count: 0 };
    if (w.count >= config.verifyPerMinute) fail("resource-exhausted", "RATE_LIMITED", "Too many purchase checks");
    const next = { start: minute, count: w.count + 1 };
    if (user) tx.update(ref, { verifyWindow: next });
    else tx.set(ref, { ...emptyUser(), verifyWindow: next, createdAt: Timestamp.fromMillis(deps.now()) });
  });
}

/** A doc created by a refund notification for a never-submitted purchase whose refund was since reversed. */
const redeemable = (doc: PurchaseDoc) => doc.uid === null && doc.grantedAt === null && doc.state !== "refunded";

/** The answer for a purchase doc that already settled the matter, without asking the store. */
function settledStatus(doc: PurchaseDoc): VerifyStatus {
  return doc.state === "refunded" && doc.grantedAt === null ? "REJECTED_PERMANENT" : "ALREADY_GRANTED";
}

/** Records (or withdraws) the caller's consumption consent on a purchase the caller redeemed. */
async function recordConsent(deps: Deps, id: string, uid: string, consent: boolean | null): Promise<void> {
  if (consent === null) return;
  await deps.db.runTransaction(async (tx) => {
    const ref = purchaseRef(deps.db, id);
    const doc = (await tx.get(ref)).data();
    if (!doc || doc.uid !== uid || doc.consumptionConsent === consent) return;
    tx.update(ref, { consumptionConsent: consent, consumptionConsentAt: Timestamp.fromMillis(deps.now()) });
  });
}

interface GrantInput {
  id: string;
  store: "play" | "appstore";
  env: "production" | "sandbox";
  productId: string;
  quantity: number;
  orderId?: string | null;
  transactionId?: string;
  purchaseToken?: string;
  /** Credits the store already reports refunded at submission (Play refundableQuantity, Apple revocation). */
  preRefundedCredits: number;
  accountToken: string | null;
  buyerKeys: string[];
  consumptionConsent: boolean | null;
}

type GrantOutcome = {
  status: VerifyStatus;
  /** Why it was not granted, when it wasn't (see NotGrantedReason). */
  reason?: NotGrantedReason;
  needsConsume: boolean;
  byBlockedBuyer?: boolean;
  net?: number;
  /** Negative balances of other accounts of the same buyer, moved onto this one. */
  debts?: { fromUid: string; amount: number }[];
};

/** Other accounts of a buyer whose debt a purchase picks up, at most. */
const DEBT_ACCOUNTS_MAX = 5;

/**
 * One transaction: creates the purchase doc as granted and raises the balance by the credits not
 * already refunded, or does nothing if the doc exists. A doc created by a refund notification for a
 * never-submitted purchase is redeemable only if the refund has since been reversed. Reads the
 * shared stats doc only for sandbox purchases.
 */
async function grant(deps: Deps, uid: string, input: GrantInput, config: RuntimeConfig): Promise<GrantOutcome> {
  const outcome = await grantTx(deps, uid, input, config);
  for (const d of outcome.debts ?? []) {
    deps.logger.warn("debt_followed_buyer", { purchaseId: input.id, fromUidHash: uidHash(d.fromUid), toUidHash: uidHash(uid), amount: d.amount });
  }
  if (outcome.byBlockedBuyer) {
    // They paid, so the credits are granted; the client hides buying (purchasesBlocked).
    deps.logger.warn("purchase_by_blocked_buyer", { purchaseId: input.id, store: input.store, uidHash: uidHash(uid), credits: outcome.net });
  }
  return outcome;
}

async function grantTx(deps: Deps, uid: string, input: GrantInput, config: RuntimeConfig): Promise<GrantOutcome> {
  const credits = PRODUCT_CREDITS[input.productId] * input.quantity;
  const now = deps.now();
  return deps.db.runTransaction(async (tx): Promise<GrantOutcome> => {
    const pRef = purchaseRef(deps.db, input.id);
    const uRef = userRef(deps.db, uid);
    const sRef = statsRef(deps.db, utcDay(now));
    const [pSnap, uSnap, ...buyerSnaps] = await Promise.all([
      tx.get(pRef), tx.get(uRef), ...input.buyerKeys.map((k) => tx.get(buyerRef(deps.db, k))),
    ]);
    const user = uSnap.data();
    assertOpen(user);
    const existing = pSnap.data();

    if (existing && !redeemable(existing)) {
      const mine = existing.uid === uid;
      const status = settledStatus(existing);
      return {
        status,
        ...(status === "REJECTED_PERMANENT" ? { reason: "REFUNDED_BEFORE_GRANT" as const } : {}),
        needsConsume: mine && existing.store === "play" && existing.state === "granted" && !!existing.purchaseToken,
      };
    }

    const alreadyRefunded = Math.min(credits, Math.max(existing?.refundedCredits ?? 0, input.preRefundedCredits));
    // A debt left on another account of the same buyer (e.g. an anonymous install with a negative
    // balance that was abandoned at sign-in without mergeBalance) follows the buyer to this one,
    // as mergeBalance would have moved it. Read here, before any write.
    const debts: { fromUid: string; amount: number }[] = [];
    if (alreadyRefunded < credits) {
      for (const key of input.buyerKeys.filter((k) => k !== uidBuyerKey(uid))) {
        const q = await tx.get(deps.db.collection("users").where("buyerKeys", "array-contains", key).limit(DEBT_ACCOUNTS_MAX));
        for (const d of q.docs) {
          const other = d.data() as UserDoc;
          if (d.id === uid || debts.some((x) => x.fromUid === d.id) || debts.length >= DEBT_ACCOUNTS_MAX) continue;
          if (other.balance < 0 && other.inFlight === 0 && other.deletedAt === undefined && other.mergedInto === undefined) {
            debts.push({ fromUid: d.id, amount: other.balance });
          }
        }
      }
    }
    if (alreadyRefunded >= credits) {
      // Refunded before it was submitted: record it so it can never be redeemed.
      if (!existing) {
        tx.set(pRef, {
          store: input.store, uid: null, env: input.env, productId: input.productId, quantity: input.quantity,
          credits, refundedCredits: credits, state: "refunded", grantedAt: null,
          ...(input.transactionId ? { transactionId: input.transactionId } : {}),
        });
      }
      return { status: "REJECTED_PERMANENT", reason: "REFUNDED_BEFORE_GRANT", needsConsume: false };
    }

    if (input.env === "sandbox") {
      // Sandbox purchases cost nothing, so they are capped by count and by credits: one
      // recipes_100 bought with quantity 10 would otherwise be 1000 free credits.
      if (!config.allowSandbox) return { status: "REJECTED_PERMANENT", reason: "SANDBOX_NOT_ALLOWED", needsConsume: false };
      if (credits > config.sandboxDailyCredits) return { status: "REJECTED_PERMANENT", reason: "SANDBOX_TOO_LARGE", needsConsume: false };
      const stats = (await tx.get(sRef)).data() ?? {};
      const granted = (stats.sandboxGrants as number | undefined) ?? 0;
      const grantedCredits = (stats.sandboxCredits as number | undefined) ?? 0;
      if (granted >= config.sandboxDailyCap || grantedCredits + credits > config.sandboxDailyCredits) {
        return { status: "RETRY", reason: "SANDBOX_DAILY_CAP", needsConsume: false };
      }
      tx.set(sRef, { sandboxGrants: FieldValue.increment(1), sandboxCredits: FieldValue.increment(credits) }, { merge: true });
    } else {
      // Revenue for the spend ceiling (dailySpendCeiling): a blind increment, no read.
      tx.set(sRef, { netCreditsGranted: FieldValue.increment(credits - alreadyRefunded) }, { merge: true });
    }

    const net = credits - alreadyRefunded;
    const consent = input.consumptionConsent === true;
    const doc: PurchaseDoc = {
      store: input.store,
      uid,
      env: input.env,
      productId: input.productId,
      quantity: input.quantity,
      credits,
      refundedCredits: alreadyRefunded,
      state: alreadyRefunded > 0 ? "refunded" : "granted",
      grantedAt: Timestamp.fromMillis(now),
      buyerKeys: input.buyerKeys,
      ...(input.accountToken ? { accountToken: input.accountToken } : {}),
      ...(consent ? { consumptionConsent: true, consumptionConsentAt: Timestamp.fromMillis(now) } : {}),
      ...(input.orderId !== undefined ? { orderId: input.orderId } : {}),
      ...(input.transactionId ? { transactionId: input.transactionId } : {}),
      ...(input.purchaseToken ? { purchaseToken: input.purchaseToken, consumedAt: null, consumePending: true, consumeAttempts: 0 } : {}),
      ...(existing?.refundEventAt !== undefined ? { refundEventAt: existing.refundEventAt } : {}),
    };
    tx.set(pRef, doc);
    const base = user ?? { ...emptyUser(), createdAt: Timestamp.fromMillis(now) };
    tx.set(uRef, {
      ...base,
      balance: base.balance + net + debts.reduce((s, d) => s + d.amount, 0),
      purchasedCredits: base.purchasedCredits + credits,
      refundedCredits: base.refundedCredits + alreadyRefunded,
      buyerKeys: mergeKeys(base.buyerKeys, input.buyerKeys),
    } as UserDoc);
    const byBlockedBuyer = buyerSnaps.some((s) => effectivelyBlocked(s.data() as BuyerDoc | undefined, config.refundAbuseThreshold));
    for (const d of debts) {
      tx.update(userRef(deps.db, d.fromUid), { balance: 0, debtMovedTo: uid, debtMovedAt: Timestamp.fromMillis(now) });
    }
    return { status: "GRANTED", needsConsume: input.store === "play", byBlockedBuyer, net, debts };
  });
}

/** Consumes a granted Play purchase and marks it consumed. Failures are left for the next resubmission or the sweeper. */
export async function consumePlay(deps: Deps, id: string, productId: string, purchaseToken: string): Promise<boolean> {
  const ref = purchaseRef(deps.db, id);
  try {
    await deps.play.consume(productId, purchaseToken);
  } catch (e) {
    const attempts = ((await ref.get().catch(() => null))?.data()?.consumeAttempts ?? 0) + 1;
    if (attempts >= CONSUME_MAX_ATTEMPTS) {
      // Play keeps an unconsumed consumable unbuyable again for this user; the owner has to look.
      deps.logger.error("play_consume_gave_up", { purchaseId: id, attempts, error: String(e).slice(0, 200) });
      await ref.update({
        consumeAttempts: attempts, consumePending: false, consumeGaveUpAt: Timestamp.fromMillis(deps.now()),
      }).catch(() => undefined);
    } else {
      deps.logger.warn("play_consume_failed", { purchaseId: id, attempts, error: String(e).slice(0, 200) });
      await ref.update({ consumeAttempts: FieldValue.increment(1) }).catch(() => undefined);
    }
    return false;
  }
  await deps.db.runTransaction(async (tx) => {
    const doc = (await tx.get(ref)).data();
    if (!doc) return;
    tx.update(ref, {
      consumedAt: Timestamp.fromMillis(deps.now()),
      purchaseToken: FieldValue.delete(),
      consumePending: FieldValue.delete(),
      ...(doc.state === "granted" ? { state: "consumed" } : {}),
    });
  });
  return true;
}

/**
 * Why `verifyPurchase` did not grant a purchase: fixed codes for the `purchase_not_granted` log
 * line (README "Logs to alert on"), one per rule that can refuse or defer it.
 */
export type NotGrantedReason =
  /** Settled before, or reported refunded by the store, without ever being granted. */
  | "REFUNDED_BEFORE_GRANT"
  /** A sandbox (or Play test) purchase while `allowSandbox` is off. */
  | "SANDBOX_NOT_ALLOWED"
  /** One sandbox purchase worth more than `sandboxDailyCredits`. */
  | "SANDBOX_TOO_LARGE"
  /** Today's sandbox grants or credits are used up (RETRY). */
  | "SANDBOX_DAILY_CAP"
  /** The store couldn't be asked (RETRY). */
  | "STORE_UNAVAILABLE"
  /** Play: the payment is still pending (RETRY). */
  | "PAYMENT_PENDING"
  /** Play: the store does not know the token; Apple: no transaction with this id, or a different one. */
  | "NOT_FOUND"
  /** Play: cancelled or otherwise not purchased. */
  | "NOT_PURCHASED"
  /** A product that is not one of our packs, or a quantity below 1. */
  | "UNKNOWN_PRODUCT"
  /** Apple: another app's bundle id, or not a consumable. */
  | "WRONG_BUNDLE"
  /** Apple: an Xcode or LocalTesting transaction, which only the StoreKit configuration file signs. */
  | "XCODE_ENVIRONMENT"
  /** Apple: the JWS is malformed or its signature does not check out. */
  | "INVALID_SIGNATURE"
  /** The purchase's account token is another account's (PURCHASE_ACCOUNT_MISMATCH, an error). */
  | "ACCOUNT_MISMATCH"
  /** This app may not redeem that store's purchases (STORE_MISMATCH, an error). */
  | "STORE_MISMATCH";

/** The rules that point at a fault or a forged request rather than at ordinary life: logged as warnings. */
const SURPRISING_REASONS: ReadonlySet<NotGrantedReason> = new Set<NotGrantedReason>([
  "NOT_FOUND", "UNKNOWN_PRODUCT", "WRONG_BUNDLE", "INVALID_SIGNATURE", "ACCOUNT_MISMATCH", "STORE_MISMATCH",
]);

interface Verdict {
  status: VerifyStatus;
  reason?: NotGrantedReason;
  env?: string;
  productId?: string;
}

/**
 * At most 10 `purchase_not_granted` lines a minute per reason and instance, with the count left
 * out: a client resubmitting a RETRY (or a looping forger) cannot flood the logs.
 */
const notGrantedLogs = new Map<NotGrantedReason, LogSampler>();

function logNotGranted(deps: Deps, caller: Caller, store: "play" | "appstore", status: VerifyStatus | "REFUSED", v: Omit<Verdict, "status">): void {
  if (!v.reason) return;
  let sampler = notGrantedLogs.get(v.reason);
  if (!sampler) {
    sampler = new LogSampler(60_000, 10);
    notGrantedLogs.set(v.reason, sampler);
  }
  const sample = sampler.take(deps.now());
  if (!sample.log) return;
  const fields = {
    status,
    reason: v.reason,
    store,
    env: v.env ?? null,
    // From the store's verified answer where there is one; never the token, the JWS or a transaction id.
    productId: v.productId ? String(v.productId).slice(0, 64) : null,
    uidHash: uidHash(caller.uid),
    suppressedSinceLast: sample.suppressed,
  };
  if (SURPRISING_REASONS.has(v.reason)) deps.logger.warn("purchase_not_granted", fields);
  else deps.logger.info("purchase_not_granted", fields);
}

async function verifyPlay(deps: Deps, caller: Caller, purchaseToken: string, consent: boolean | null, config: RuntimeConfig): Promise<Verdict> {
  const id = playPurchaseId(purchaseToken);
  const existing = (await purchaseRef(deps.db, id).get()).data();
  if (existing && !redeemable(existing)) {
    // Settled before: no Play call, except to retry an owed consume of the caller's own purchase.
    if (existing.uid === caller.uid) {
      await recordConsent(deps, id, caller.uid, consent);
      if (existing.state === "granted" && existing.consumePending && existing.purchaseToken && existing.productId) {
        await consumePlay(deps, id, existing.productId, existing.purchaseToken);
      }
    }
    const status = settledStatus(existing);
    return status === "REJECTED_PERMANENT"
      ? { status, reason: "REFUNDED_BEFORE_GRANT", env: existing.env, productId: existing.productId ?? undefined }
      : { status };
  }
  let purchase;
  try {
    purchase = await deps.play.getPurchase(purchaseToken);
  } catch (e) {
    if (e instanceof RetryableStoreError) return { status: "RETRY", reason: "STORE_UNAVAILABLE" };
    throw e;
  }
  if (!purchase) return { status: "REJECTED_PERMANENT", reason: "NOT_FOUND" };
  const seen = { env: purchase.isTest ? "sandbox" : "production", productId: purchase.productId };
  if (purchase.purchaseState === "PENDING") return { status: "RETRY", reason: "PAYMENT_PENDING", ...seen };
  if (purchase.purchaseState !== "PURCHASED") return { status: "REJECTED_PERMANENT", reason: "NOT_PURCHASED", ...seen };
  if (!(purchase.productId in PRODUCT_CREDITS) || purchase.quantity < 1) {
    deps.logger.warn("play_unknown_product", { productId: String(purchase.productId).slice(0, 64) });
    return { status: "REJECTED_PERMANENT", reason: "UNKNOWN_PRODUCT", ...seen };
  }
  await assertAccountToken(deps, caller.uid, purchase.obfuscatedExternalAccountId);
  const refundable = Math.max(0, Math.min(purchase.quantity, purchase.refundableQuantity));
  const outcome = await grant(deps, caller.uid, {
    id,
    store: "play",
    env: purchase.isTest ? "sandbox" : "production",
    productId: purchase.productId,
    quantity: purchase.quantity,
    orderId: purchase.orderId,
    purchaseToken,
    preRefundedCredits: PRODUCT_CREDITS[purchase.productId] * (purchase.quantity - refundable),
    accountToken: purchase.obfuscatedExternalAccountId,
    buyerKeys: await playBuyerKeys(deps, caller.uid),
    consumptionConsent: consent,
  }, config);
  if (outcome.status === "ALREADY_GRANTED") await recordConsent(deps, id, caller.uid, consent);
  if (outcome.needsConsume) await consumePlay(deps, id, purchase.productId, purchaseToken);
  return { status: outcome.status, ...(outcome.reason ? { reason: outcome.reason } : {}), ...seen };
}

function checkAppleTransaction(tx: AppleTransaction): boolean {
  return tx.bundleId === IOS_BUNDLE_ID && tx.type === "Consumable" && tx.productId in PRODUCT_CREDITS && tx.quantity >= 1;
}

/** Why an Apple transaction is not one of ours, or null when it is. */
function appleMismatch(tx: AppleTransaction): NotGrantedReason | null {
  if (tx.bundleId !== IOS_BUNDLE_ID || tx.type !== "Consumable") return "WRONG_BUNDLE";
  if (!(tx.productId in PRODUCT_CREDITS) || tx.quantity < 1) return "UNKNOWN_PRODUCT";
  return null;
}

async function verifyApple(deps: Deps, caller: Caller, jws: string, consent: boolean | null, config: RuntimeConfig): Promise<Verdict> {
  let signed: AppleTransaction;
  try {
    signed = await deps.appStore.verifyTransaction(jws);
  } catch (e) {
    if (e instanceof InvalidSignatureError) {
      return { status: "REJECTED_PERMANENT", reason: e.message === REFUSED_ENVIRONMENT ? "XCODE_ENVIRONMENT" : "INVALID_SIGNATURE" };
    }
    if (e instanceof RetryableStoreError) return { status: "RETRY", reason: "STORE_UNAVAILABLE" };
    throw e;
  }
  const seen = { env: signed.environment, productId: signed.productId };
  if (!checkAppleTransaction(signed)) return { status: "REJECTED_PERMANENT", reason: appleMismatch(signed) ?? "WRONG_BUNDLE", ...seen };
  await assertAccountToken(deps, caller.uid, signed.appAccountToken);
  const id = applePurchaseId(signed.environment, signed.transactionId);
  const existing = (await purchaseRef(deps.db, id).get()).data();
  if (existing && !redeemable(existing)) {
    if (existing.uid === caller.uid) await recordConsent(deps, id, caller.uid, consent);
    const status = settledStatus(existing);
    return status === "REJECTED_PERMANENT" ? { status, reason: "REFUNDED_BEFORE_GRANT", ...seen } : { status };
  }

  // Fresh copy from Apple: catches a refund issued before the client submitted the transaction.
  let fresh: AppleTransaction | null;
  try {
    fresh = await deps.appStore.fetchTransaction(signed.transactionId, signed.environment);
  } catch (e) {
    if (e instanceof RetryableStoreError || e instanceof InvalidSignatureError) return { status: "RETRY", reason: "STORE_UNAVAILABLE", ...seen };
    throw e;
  }
  if (!fresh || fresh.transactionId !== signed.transactionId) return { status: "REJECTED_PERMANENT", reason: "NOT_FOUND", ...seen };
  if (!checkAppleTransaction(fresh)) return { status: "REJECTED_PERMANENT", reason: appleMismatch(fresh) ?? "WRONG_BUNDLE", ...seen };
  const credits = PRODUCT_CREDITS[fresh.productId] * fresh.quantity;
  const outcome = await grant(deps, caller.uid, {
    id,
    store: "appstore",
    env: fresh.environment,
    productId: fresh.productId,
    quantity: fresh.quantity,
    transactionId: fresh.transactionId,
    preRefundedCredits: appleRefundedCredits(credits, fresh),
    accountToken: fresh.appAccountToken ?? signed.appAccountToken,
    buyerKeys: appleBuyerKeys(fresh.appTransactionId ?? signed.appTransactionId, caller.uid),
    consumptionConsent: consent,
  }, config);
  if (outcome.status === "ALREADY_GRANTED") await recordConsent(deps, id, caller.uid, consent);
  return { status: outcome.status, ...(outcome.reason ? { reason: outcome.reason } : {}), env: fresh.environment, productId: fresh.productId };
}

export async function verifyPurchase(deps: Deps, caller: Caller, data: unknown): Promise<VerifyResponse> {
  const request = validateVerifyRequest(data);
  const config = await deps.config();
  let verdict: Verdict;
  try {
    assertStoreMatchesApp(caller, request.store);
    await throttleVerify(deps, caller.uid, config);
    verdict = request.store === "play"
      ? await verifyPlay(deps, caller, request.purchaseToken, request.consumptionConsent, config)
      : await verifyApple(deps, caller, request.signedTransaction, request.consumptionConsent, config);
  } catch (e) {
    const reason = e instanceof HttpsError ? (e.details as { reason?: string } | undefined)?.reason : undefined;
    if (reason === "PURCHASE_ACCOUNT_MISMATCH") logNotGranted(deps, caller, request.store, "REFUSED", { reason: "ACCOUNT_MISMATCH" });
    if (reason === "STORE_MISMATCH") logNotGranted(deps, caller, request.store, "REFUSED", { reason: "STORE_MISMATCH" });
    throw e;
  }
  const status = verdict.status;
  logNotGranted(deps, caller, request.store, status, verdict);
  const user = (await userRef(deps.db, caller.uid).get()).data();
  return { status, balance: user?.balance ?? 0, costs: config.costs, ...CAPABILITIES, ...(await blockFlag(deps.db, user, config)) };
}

// ---------------------------------------------------------------------------------------------
// Refunds

export interface RefundEvent {
  purchaseId: string;
  /**
   * The purchase's refunded credits after this event, from the doc's credits (0 for a full
   * reversal). Receives null when the doc does not exist yet.
   */
  targetRefunded(doc: PurchaseDoc | null): number;
  /** Store event time; an event older than the last one applied to the doc is ignored. */
  eventAt: number;
  /** REFUND_REVERSED: a missing doc is not created. */
  reversal?: boolean;
  /** Used to create a `refunded` doc when the purchase was never submitted. */
  whenMissing: Omit<PurchaseDoc, "refundedCredits" | "state" | "grantedAt" | "uid">;
}

export type RefundResult = "applied" | "duplicate" | "stale" | "created";

/**
 * Moves a purchase to its refunded (or reversed) state and adjusts the holder's balance by the
 * difference, once: a repeated or older event changes nothing. The balance may go negative. A
 * refund that takes credits already spent (FIFO, core/buyers.ts) is recorded against the buyer.
 */
export async function applyRefund(deps: Deps, event: RefundEvent, config: RuntimeConfig): Promise<RefundResult> {
  let logs: PendingLogs = [];
  const result = await deps.db.runTransaction(async (tx: Transaction): Promise<RefundResult> => {
    logs = [];
    const ref = purchaseRef(deps.db, event.purchaseId);
    const doc = (await tx.get(ref)).data();
    if (!doc) {
      // A reversal of a purchase never seen needs no record; a refund does, so the purchase can
      // never be redeemed afterwards.
      if (event.reversal) return "duplicate";
      tx.set(ref, {
        ...event.whenMissing,
        uid: null,
        grantedAt: null,
        refundedCredits: event.targetRefunded(null),
        state: "refunded",
        refundEventAt: event.eventAt,
      });
      return "created";
    }
    if (doc.refundEventAt !== undefined && event.eventAt < doc.refundEventAt) return "stale";
    const target = Math.max(0, Math.min(doc.credits, event.targetRefunded(doc)));
    const delta = target - doc.refundedCredits;
    if (delta === 0) {
      tx.update(ref, { refundEventAt: event.eventAt });
      return "duplicate";
    }
    // Every read before any write.
    let holder: { uid: string; user: UserDoc | undefined } | null = null;
    // A deleted account's purchases keep `formerUid`: its tombstone holds the ledger, and its
    // balance is never touched (deletedAt), but a refund after use still counts against the buyer.
    const owner = doc.uid ?? doc.formerUid ?? null;
    if (owner && doc.grantedAt) holder = await resolveHolder(tx, deps.db, owner, MERGE_CHAIN_MAX);
    const consumed = holder ? await fifoConsumed(tx, deps.db, event.purchaseId, doc, holder) : null;
    const buyers = consumed !== null ? await readBuyers(tx, deps.db, doc) : null;

    const restored: PurchaseDoc["state"] = doc.consumedAt ? "consumed" : "granted";
    const spentCreditsTaken = consumed === null ? 0 : Math.max(0, target - (doc.credits - consumed));
    tx.update(ref, {
      refundedCredits: target,
      state: target > 0 ? "refunded" : restored,
      refundEventAt: event.eventAt,
      // A refunded Play purchase needs no consume; a reversed one does again if it never was.
      ...(doc.purchaseToken ? { consumePending: target === 0 } : {}),
      ...(consumed !== null ? { refundAfterUse: spentCreditsTaken > 0 } : {}),
    });
    if (holder?.user && holder.user.deletedAt === undefined) {
      tx.update(userRef(deps.db, holder.uid), {
        balance: FieldValue.increment(-delta),
        refundedCredits: FieldValue.increment(delta),
      });
    }
    if (doc.grantedAt && doc.env === "production") {
      tx.set(statsRef(deps.db, utcDay(deps.now())), { netCreditsGranted: FieldValue.increment(-delta) }, { merge: true });
    }
    if (buyers) logs = writeRefundAfterUse(deps, tx, buyers, event.purchaseId, doc, spentCreditsTaken, config);
    return "applied";
  });
  flushErrors(deps, logs);
  return result;
}
