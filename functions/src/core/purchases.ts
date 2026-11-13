/**
 * Purchase verification and refunds. The only way a balance goes up is a store-verified purchase
 * whose `purchases/` doc does not exist yet; that doc is never deleted, so it is the replay guard.
 */
import { createHash } from "node:crypto";
import { FieldValue, Timestamp, type Transaction } from "firebase-admin/firestore";
import { fail, validateVerifyRequest, type VerifyResponse, type VerifyStatus } from "../contract";
import { IOS_BUNDLE_ID, MERGE_CHAIN_MAX, PRODUCT_CREDITS } from "../constants";
import {
  assertOpen,
  emptyUser,
  loadRuntimeConfig,
  purchaseRef,
  resolveHolder,
  statsRef,
  userRef,
  utcDay,
  type PurchaseDoc,
  type RuntimeConfig,
  type UserDoc,
} from "../model";
import {
  InvalidSignatureError,
  RetryableStoreError,
  type AppleTransaction,
  type Caller,
  type Deps,
} from "../ports";

export const playPurchaseId = (purchaseToken: string) =>
  `play_${createHash("sha256").update(purchaseToken).digest("hex")}`;
export const applePurchaseId = (env: "production" | "sandbox", transactionId: string) =>
  `appstore_${env}_${transactionId}`;

export async function readBalance(deps: Deps, uid: string): Promise<number> {
  const user = (await userRef(deps.db, uid).get()).data();
  return user?.balance ?? 0;
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

interface GrantInput {
  id: string;
  store: "play" | "appstore";
  env: "production" | "sandbox";
  productId: string;
  quantity: number;
  orderId?: string | null;
  transactionId?: string;
  purchaseToken?: string;
  /** Already refunded at the store when submitted (Apple fresh fetch). */
  revoked: boolean;
}

type GrantOutcome = { status: VerifyStatus; balance: number; needsConsume: boolean };

/**
 * One transaction: creates the purchase doc as granted and raises the balance, or does nothing if
 * the doc exists. A doc created by a refund notification for a never-submitted purchase is
 * redeemable only if the refund has since been reversed.
 */
async function grant(deps: Deps, uid: string, input: GrantInput, config: RuntimeConfig): Promise<GrantOutcome> {
  const credits = PRODUCT_CREDITS[input.productId] * input.quantity;
  const now = deps.now();
  return deps.db.runTransaction(async (tx) => {
    const pRef = purchaseRef(deps.db, input.id);
    const uRef = userRef(deps.db, uid);
    const sRef = statsRef(deps.db, utcDay(now));
    const [pSnap, uSnap, sSnap] = await Promise.all([tx.get(pRef), tx.get(uRef), tx.get(sRef)]);
    const user = uSnap.data();
    assertOpen(user);
    const balance = user?.balance ?? 0;
    const existing = pSnap.data();

    if (existing) {
      const redeemable = existing.uid === null && existing.grantedAt === null && existing.state !== "refunded";
      if (!redeemable) {
        if (existing.state === "refunded" && existing.grantedAt === null) {
          return { status: "REJECTED_PERMANENT", balance, needsConsume: false };
        }
        const mine = existing.uid === uid;
        return {
          status: "ALREADY_GRANTED",
          balance,
          needsConsume: mine && existing.store === "play" && existing.state === "granted",
        };
      }
    }

    if (input.revoked) {
      // Refunded before it was submitted: record it so it can never be redeemed.
      if (!existing) {
        tx.set(pRef, {
          store: input.store, uid: null, env: input.env, productId: input.productId, quantity: input.quantity,
          credits, refundedCredits: credits, state: "refunded", grantedAt: null,
          ...(input.transactionId ? { transactionId: input.transactionId } : {}),
        });
      }
      return { status: "REJECTED_PERMANENT", balance, needsConsume: false };
    }

    if (input.env === "sandbox") {
      const granted = (sSnap.data()?.sandboxGrants as number | undefined) ?? 0;
      if (!config.allowSandbox) return { status: "REJECTED_PERMANENT", balance, needsConsume: false };
      if (granted >= config.sandboxDailyCap) return { status: "RETRY", balance, needsConsume: false };
      tx.set(sRef, { sandboxGrants: FieldValue.increment(1) }, { merge: true });
    }

    const alreadyRefunded = existing?.refundedCredits ?? 0;
    const net = credits - alreadyRefunded;
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
      ...(input.orderId !== undefined ? { orderId: input.orderId } : {}),
      ...(input.transactionId ? { transactionId: input.transactionId } : {}),
      ...(input.purchaseToken ? { purchaseToken: input.purchaseToken, consumedAt: null, consumePending: true, consumeAttempts: 0 } : {}),
      ...(existing?.refundEventAt !== undefined ? { refundEventAt: existing.refundEventAt } : {}),
    };
    tx.set(pRef, doc);
    const base = user ?? { ...emptyUser(), createdAt: Timestamp.fromMillis(now) };
    tx.set(uRef, {
      ...base,
      balance: base.balance + net,
      purchasedCredits: base.purchasedCredits + credits,
      refundedCredits: base.refundedCredits + alreadyRefunded,
    } as UserDoc);
    return { status: "GRANTED", balance: base.balance + net, needsConsume: input.store === "play" };
  });
}

/** Consumes a granted Play purchase and marks it consumed. Failures are left for the next resubmission or the sweeper. */
export async function consumePlay(deps: Deps, id: string, productId: string, purchaseToken: string): Promise<boolean> {
  const ref = purchaseRef(deps.db, id);
  try {
    await deps.play.consume(productId, purchaseToken);
  } catch (e) {
    deps.logger.warn("Play consume failed; will retry", { id, error: String(e) });
    await ref.update({ consumeAttempts: FieldValue.increment(1) }).catch(() => undefined);
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

async function verifyPlay(deps: Deps, caller: Caller, purchaseToken: string, config: RuntimeConfig): Promise<VerifyResponse> {
  const id = playPurchaseId(purchaseToken);
  let purchase;
  try {
    purchase = await deps.play.getPurchase(purchaseToken);
  } catch (e) {
    if (e instanceof RetryableStoreError) return { status: "RETRY", balance: await readBalance(deps, caller.uid) };
    throw e;
  }
  const balanceNow = () => readBalance(deps, caller.uid);
  if (!purchase) return { status: "REJECTED_PERMANENT", balance: await balanceNow() };
  if (purchase.purchaseState === "PENDING") return { status: "RETRY", balance: await balanceNow() };
  if (purchase.purchaseState !== "PURCHASED") return { status: "REJECTED_PERMANENT", balance: await balanceNow() };
  if (!(purchase.productId in PRODUCT_CREDITS) || purchase.quantity < 1) {
    deps.logger.warn("Unknown Play product", { productId: purchase.productId });
    return { status: "REJECTED_PERMANENT", balance: await balanceNow() };
  }
  const outcome = await grant(deps, caller.uid, {
    id,
    store: "play",
    env: purchase.isTest ? "sandbox" : "production",
    productId: purchase.productId,
    quantity: purchase.quantity,
    orderId: purchase.orderId,
    purchaseToken,
    revoked: false,
  }, config);
  if (outcome.needsConsume) await consumePlay(deps, id, purchase.productId, purchaseToken);
  return { status: outcome.status, balance: outcome.balance };
}

function checkAppleTransaction(tx: AppleTransaction): boolean {
  return tx.bundleId === IOS_BUNDLE_ID && tx.type === "Consumable" && tx.productId in PRODUCT_CREDITS && tx.quantity >= 1;
}

async function verifyApple(deps: Deps, caller: Caller, jws: string, config: RuntimeConfig): Promise<VerifyResponse> {
  const balanceNow = () => readBalance(deps, caller.uid);
  let signed: AppleTransaction;
  try {
    signed = await deps.appStore.verifyTransaction(jws);
  } catch (e) {
    if (e instanceof InvalidSignatureError) return { status: "REJECTED_PERMANENT", balance: await balanceNow() };
    if (e instanceof RetryableStoreError) return { status: "RETRY", balance: await balanceNow() };
    throw e;
  }
  if (!checkAppleTransaction(signed)) return { status: "REJECTED_PERMANENT", balance: await balanceNow() };
  const id = applePurchaseId(signed.environment, signed.transactionId);

  // Fresh copy from Apple: catches a refund issued before the client submitted the transaction.
  let fresh: AppleTransaction | null;
  try {
    fresh = await deps.appStore.fetchTransaction(signed.transactionId, signed.environment);
  } catch (e) {
    if (e instanceof RetryableStoreError || e instanceof InvalidSignatureError) {
      return { status: "RETRY", balance: await balanceNow() };
    }
    throw e;
  }
  if (!fresh || fresh.transactionId !== signed.transactionId || !checkAppleTransaction(fresh)) {
    return { status: "REJECTED_PERMANENT", balance: await balanceNow() };
  }
  const outcome = await grant(deps, caller.uid, {
    id,
    store: "appstore",
    env: fresh.environment,
    productId: fresh.productId,
    quantity: fresh.quantity,
    transactionId: fresh.transactionId,
    revoked: fresh.revocationDate !== null,
  }, config);
  return { status: outcome.status, balance: outcome.balance };
}

export async function verifyPurchase(deps: Deps, caller: Caller, data: unknown): Promise<VerifyResponse> {
  const request = validateVerifyRequest(data);
  const config = await loadRuntimeConfig(deps.db);
  await throttleVerify(deps, caller.uid, config);
  return request.store === "play"
    ? verifyPlay(deps, caller, request.purchaseToken, config)
    : verifyApple(deps, caller, request.signedTransaction, config);
}

// ---------------------------------------------------------------------------------------------
// Refunds

export interface RefundEvent {
  purchaseId: string;
  /**
   * The purchase's refunded credits after this event, from the doc's credits. Return 0 for a
   * reversal. Receives null when the doc does not exist yet.
   */
  targetRefunded(doc: PurchaseDoc | null): number;
  /** Store event time; an event older than the last one applied to the doc is ignored. */
  eventAt: number;
  /** REFUND_REVERSED: the target is 0 and a missing doc is not created. */
  reversal?: boolean;
  /** Used to create a `refunded` doc when the purchase was never submitted. */
  whenMissing: Omit<PurchaseDoc, "refundedCredits" | "state" | "grantedAt" | "uid">;
}

export type RefundResult = "applied" | "duplicate" | "stale" | "created";

/**
 * Moves a purchase to its refunded (or reversed) state and adjusts the holder's balance by the
 * difference, once: a repeated or older event changes nothing. The balance may go negative.
 */
export async function applyRefund(deps: Deps, event: RefundEvent): Promise<RefundResult> {
  return deps.db.runTransaction(async (tx: Transaction) => {
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
    let holder: { uid: string; user: UserDoc | undefined } | null = null;
    if (doc.uid && doc.grantedAt) holder = await resolveHolder(tx, deps.db, doc.uid, MERGE_CHAIN_MAX);

    const restored: PurchaseDoc["state"] = doc.consumedAt ? "consumed" : "granted";
    tx.update(ref, {
      refundedCredits: target,
      state: target > 0 ? "refunded" : restored,
      refundEventAt: event.eventAt,
      // A refunded Play purchase needs no consume; a reversed one does again if it never was.
      ...(doc.purchaseToken ? { consumePending: target === 0 } : {}),
    });
    if (holder?.user && holder.user.deletedAt === undefined) {
      tx.update(userRef(deps.db, holder.uid), {
        balance: FieldValue.increment(-delta),
        refundedCredits: FieldValue.increment(delta),
      });
    }
    return "applied";
  });
}
