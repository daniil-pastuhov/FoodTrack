/**
 * Reads what the board needs from Firestore, read-only, in a fixed number of round trips:
 * `config/runtime`, the `stats/{day}` docs `boardStatsDays` lists plus `stats/ledger` (one getAll),
 * the period's purchases by `grantedAt` (a single-field range ordered on the same field, so no
 * composite index; projected to the fields the board shows; at most `purchasesCap`, newest first),
 * and one aggregation over `users` with a positive balance. Only when the cap is hit, one count
 * aggregation over the period's purchases says how many there were.
 */
import { AggregateField, Timestamp, type Firestore } from "firebase-admin/firestore";
import { loadRuntimeConfig, statsRef } from "../model";
import type { Logger } from "../ports";
import { BOARD_PURCHASES_CAP, boardStatsDays, periodRange, purchaseRow, type BoardInput, type PurchaseRow } from "./boardData";

/** Firestore bills an aggregation as one read per 1,000 index entries it matches, at least one. */
const aggregationReads = (matched: number) => Math.max(1, Math.ceil(matched / 1000));

export async function readBoardInput(
  db: Firestore,
  opts: { project: string; days: number; nowMs: number; logger?: Logger; purchasesCap?: number },
): Promise<BoardInput> {
  const cap = opts.purchasesCap ?? BOARD_PURCHASES_CAP;
  const statDays = boardStatsDays(opts.nowMs, opts.days);
  const { startMs, endMs } = periodRange(opts.nowMs, opts.days);
  const ledgerRef = db.doc("stats/ledger");
  const periodPurchases = () => db.collection("purchases")
    .where("grantedAt", ">=", Timestamp.fromMillis(startMs))
    .where("grantedAt", "<", Timestamp.fromMillis(endMs));
  const [config, snaps, purchaseSnap, holderAgg] = await Promise.all([
    loadRuntimeConfig(db, opts.logger),
    db.getAll(...statDays.map((d) => statsRef(db, d)), ledgerRef),
    periodPurchases()
      .orderBy("grantedAt", "desc")
      .limit(cap + 1)
      .select("store", "env", "productId", "credits", "refundedCredits", "grantedAt")
      .get(),
    db.collection("users").where("balance", ">", 0)
      .aggregate({ accounts: AggregateField.count(), recipes: AggregateField.sum("balance") })
      .get(),
  ]);
  const stats = new Map<string, Record<string, unknown> | undefined>();
  statDays.forEach((d, i) => stats.set(d, snaps[i].data()));
  const ledger = snaps[snaps.length - 1].data() ?? {};
  const checkedAt = ledger.checkedAt as { toMillis?: () => number } | undefined;
  // One more than the cap tells a full period from one that was cut; the extra one is dropped.
  const capped = purchaseSnap.size > cap;
  const docs = capped ? purchaseSnap.docs.slice(0, cap) : purchaseSnap.docs;
  const purchases: PurchaseRow[] = [];
  for (const doc of docs) {
    const row = purchaseRow(doc.data());
    if (row) purchases.push(row);
  }
  let purchasesCapped: BoardInput["purchasesCapped"] = null;
  let countReads = 0;
  if (capped) {
    const total = (await periodPurchases().count().get()).data().count;
    countReads = aggregationReads(total);
    const oldest = purchases.reduce((min, p) => Math.min(min, p.grantedAtMs), Number.POSITIVE_INFINITY);
    purchasesCapped = { total, read: cap, oldestReadMs: Number.isFinite(oldest) ? oldest : startMs };
    opts.logger?.warn("board_purchases_capped", { total, read: cap });
  }
  const accounts = holderAgg.data().accounts ?? 0;
  return {
    project: opts.project,
    generatedAtMs: opts.nowMs,
    days: opts.days,
    config,
    stats,
    purchases,
    ledger: {
      negativeBalanceTotal: typeof ledger.negativeBalanceTotal === "number" ? ledger.negativeBalanceTotal : 0,
      checkedAtMs: typeof checkedAt?.toMillis === "function" ? checkedAt.toMillis() : null,
    },
    holders: { accounts, recipes: holderAgg.data().recipes ?? 0 },
    purchasesCapped,
    // config/runtime + the stats docs and ledger + one per purchase read + the aggregation(s).
    reads: 1 + snaps.length + Math.max(1, purchaseSnap.size) + aggregationReads(accounts) + countReads,
  };
}
