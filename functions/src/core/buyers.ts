/**
 * Who bought what, and what of it was used: the FIFO attribution of spent credits to purchases
 * (used to answer Apple's consumption requests and to detect refunds after use), buyer keys, and
 * the refund-after-use blocklist (`buyers/{key}`).
 *
 * FIFO rule: credits are fungible, so a purchase counts as consumed to the extent the credits the
 * account has spent (`consumedCredits`, lifetime, including accounts merged into it) exceed the
 * credits granted to it before that purchase (net of refunds of those earlier purchases), capped at
 * the purchase's own credits. Order is by `grantedAt`, then purchase id.
 */
import { createHash } from "node:crypto";
import { Timestamp, type Firestore, type Transaction } from "firebase-admin/firestore";
import { MERGE_CHAIN_MAX } from "../constants";
import {
  buyerRef,
  uidHash,
  type BuyerDoc,
  type PurchaseDoc,
  type RuntimeConfig,
  type UserDoc,
} from "../model";
import type { Deps } from "../ports";

/** Firestore's `in` filter takes at most 30 values; an account with more merged sources is not attributed. */
const IN_MAX = 30;
/** buyerKeys kept on a user doc. */
const USER_KEYS_MAX = 20;

export const uidBuyerKey = (uid: string) => `uid_${uid}`;
export const appleBuyerKey = (appTransactionId: string) => `apple_${appTransactionId}`;
export const providerBuyerKey = (providerId: string, providerUid: string) =>
  `prov_${createHash("sha256").update(`foodtracker-buyer:${providerId}:${providerUid}`).digest("hex").slice(0, 32)}`;

/**
 * Buyer keys of a Play purchase: the uid that redeems it plus a hash of each linked sign-in
 * provider's user id (Play has no stable buyer id). The Auth lookup is best effort.
 */
export async function playBuyerKeys(deps: Deps, uid: string): Promise<string[]> {
  const keys = [uidBuyerKey(uid)];
  try {
    const user = await deps.auth.getUser(uid);
    for (const p of user.providerData) {
      if (p.providerId && p.uid) keys.push(providerBuyerKey(p.providerId, p.uid));
    }
  } catch {
    // No Auth user (tests, or deleted meanwhile): the uid key alone.
  }
  return keys;
}

/** Buyer key of an App Store purchase: Apple's appTransactionId, else the uid that redeems it. */
export function appleBuyerKeys(appTransactionId: string | null, uid: string): string[] {
  return [appTransactionId ? appleBuyerKey(appTransactionId) : uidBuyerKey(uid)];
}

/** Adds keys to a user's buyerKeys, deduplicated and capped. */
export function mergeKeys(a: readonly string[] | undefined, b: readonly string[] | undefined): string[] {
  return [...new Set([...(a ?? []), ...(b ?? [])])].slice(0, USER_KEYS_MAX);
}

export function effectivelyBlocked(b: BuyerDoc | undefined, threshold: number): boolean {
  if (!b) return false;
  if (b.blockedOverride === true) return b.blocked === true;
  return (b.refundAfterUseCount ?? 0) >= threshold;
}

/** Whether any of the account's buyer keys is blocked. No read for an account that never bought. */
export async function purchasesBlocked(db: Firestore, user: UserDoc | undefined, config: RuntimeConfig): Promise<boolean> {
  const keys = user?.buyerKeys ?? [];
  if (keys.length === 0) return false;
  const snaps = await db.getAll(...keys.map((k) => buyerRef(db, k)));
  return snaps.some((s) => effectivelyBlocked(s.data() as BuyerDoc | undefined, config.refundAbuseThreshold));
}

/** `{ purchasesBlocked: true }` or nothing, to spread into a response. */
export async function blockFlag(db: Firestore, user: UserDoc | undefined, config: RuntimeConfig): Promise<{ purchasesBlocked?: true }> {
  return (await purchasesBlocked(db, user, config)) ? { purchasesBlocked: true } : {};
}

/** The holder and every account merged into it (transitively), read inside `tx`. */
async function accountUids(tx: Transaction, db: Firestore, holder: string): Promise<string[]> {
  const out = [holder];
  let frontier = [holder];
  for (let depth = 0; depth < MERGE_CHAIN_MAX && frontier.length && out.length <= IN_MAX; depth++) {
    const next: string[] = [];
    for (const uid of frontier) {
      const q = await tx.get(db.collection("users").where("mergedInto", "==", uid));
      for (const d of q.docs) if (!out.includes(d.id)) next.push(d.id);
    }
    out.push(...next);
    frontier = next;
  }
  return out;
}

/**
 * FIFO-consumed credits of one purchase, read inside `tx`. null when the ledger cannot say (the
 * purchase was never granted, its account was deleted, or too many merged accounts).
 */
export async function fifoConsumed(
  tx: Transaction,
  db: Firestore,
  purchaseId: string,
  purchase: PurchaseDoc,
  holder: { uid: string; user: UserDoc | undefined },
): Promise<number | null> {
  if (!purchase.grantedAt || !holder.user) return null;
  const uids = await accountUids(tx, db, holder.uid);
  if (uids.length > IN_MAX) return null;
  // Purchases of a deleted account have uid null and formerUid set.
  const [owned, detached] = await Promise.all([
    tx.get(db.collection("purchases").where("uid", "in", uids)),
    tx.get(db.collection("purchases").where("formerUid", "in", uids)),
  ]);
  const all = { docs: [...owned.docs, ...detached.docs] };
  const at = purchase.grantedAt.toMillis();
  let before = 0;
  for (const d of all.docs) {
    if (d.id === purchaseId) continue;
    const p = d.data() as PurchaseDoc;
    if (!p.grantedAt) continue;
    const t = p.grantedAt.toMillis();
    if (t < at || (t === at && d.id < purchaseId)) before += p.credits - p.refundedCredits;
  }
  const spent = holder.user.consumedCredits ?? 0;
  return Math.max(0, Math.min(purchase.credits, spent - before));
}

export interface BuyerReads {
  keys: string[];
  docs: (BuyerDoc | undefined)[];
}

/** Reads the buyer docs of a purchase inside `tx` (before any write). */
export async function readBuyers(tx: Transaction, db: Firestore, purchase: PurchaseDoc): Promise<BuyerReads> {
  const owner = purchase.uid ?? purchase.formerUid;
  const keys = purchase.buyerKeys?.length ? purchase.buyerKeys : owner ? [uidBuyerKey(owner)] : [];
  const docs = keys.length ? (await tx.getAll(...keys.map((k) => buyerRef(db, k)))).map((s) => s.data() as BuyerDoc | undefined) : [];
  return { keys, docs };
}

/**
 * Records (or withdraws, after a reversal) the refund-after-use event of one purchase on each of its
 * buyer docs, once per purchase however many notifications arrive, and applies the block policy.
 * Writes only; the reads were done by readBuyers.
 */
export function writeRefundAfterUse(
  deps: Deps,
  tx: Transaction,
  reads: BuyerReads,
  purchaseId: string,
  purchase: PurchaseDoc,
  spentCreditsTaken: number,
  config: RuntimeConfig,
): PendingLogs {
  const logs: PendingLogs = [];
  const active = spentCreditsTaken > 0;
  const now = Timestamp.fromMillis(deps.now());
  reads.keys.forEach((key, i) => {
    const doc = reads.docs[i];
    const wasActive = doc?.events?.[purchaseId]?.active === true;
    if (wasActive === active) return;
    const count = Math.max(0, (doc?.refundAfterUseCount ?? 0) + (active ? 1 : -1));
    const override = doc?.blockedOverride === true;
    const blocked = override ? doc?.blocked === true : count >= config.refundAbuseThreshold;
    const event = { at: now, store: purchase.store, spentCreditsTaken, active };
    const update: Record<string, unknown> = {
      [`events.${purchaseId}`]: event,
      refundAfterUseCount: count,
      blocked,
    };
    if (blocked && !doc?.blocked) update.blockedAt = now;
    if (doc) tx.update(buyerRef(deps.db, key), update);
    else tx.set(buyerRef(deps.db, key), { events: { [purchaseId]: event }, refundAfterUseCount: count, blocked, createdAt: now, ...(blocked ? { blockedAt: now } : {}) });
    if (active) {
      logs.push(["refund_after_use", {
        purchaseId, store: purchase.store, buyerKeyHash: uidHash(key), spentCreditsTaken, refundAfterUseCount: count,
      }]);
    }
    if (blocked && !doc?.blocked && !override) {
      logs.push(["buyer_blocked", { buyerKeyHash: uidHash(key), refundAfterUseCount: count, threshold: config.refundAbuseThreshold }]);
    }
  });
  return logs;
}

/** Error log lines to write once the transaction committed (a retried transaction must not log twice). */
export type PendingLogs = [message: string, data: Record<string, unknown>][];

export function flushErrors(deps: Deps, logs: PendingLogs): void {
  for (const [message, data] of logs) deps.logger.error(message, data);
}
