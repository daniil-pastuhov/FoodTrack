/**
 * Reads what the board needs from Firestore, read-only, in a fixed number of round trips:
 * `config/runtime`, the `stats/{day}` docs `boardStatsDays` lists plus `stats/ledger` (one getAll),
 * the period's purchases by `grantedAt` (a single-field range, so no composite index; projected to
 * the fields the board shows), and one aggregation over `users` with a positive balance.
 */
import { AggregateField, Timestamp, type Firestore } from "firebase-admin/firestore";
import { loadRuntimeConfig, statsRef } from "../model";
import type { Logger } from "../ports";
import { boardStatsDays, periodRange, purchaseRow, type BoardInput, type PurchaseRow } from "./boardData";

/** Firestore bills an aggregation as one read per 1,000 index entries it matches, at least one. */
const aggregationReads = (matched: number) => Math.max(1, Math.ceil(matched / 1000));

export async function readBoardInput(
  db: Firestore,
  opts: { project: string; days: number; nowMs: number; logger?: Logger },
): Promise<BoardInput> {
  const statDays = boardStatsDays(opts.nowMs, opts.days);
  const { startMs, endMs } = periodRange(opts.nowMs, opts.days);
  const ledgerRef = db.doc("stats/ledger");
  const [config, snaps, purchaseSnap, holderAgg] = await Promise.all([
    loadRuntimeConfig(db, opts.logger),
    db.getAll(...statDays.map((d) => statsRef(db, d)), ledgerRef),
    db.collection("purchases")
      .where("grantedAt", ">=", Timestamp.fromMillis(startMs))
      .where("grantedAt", "<", Timestamp.fromMillis(endMs))
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
  const purchases: PurchaseRow[] = [];
  for (const doc of purchaseSnap.docs) {
    const row = purchaseRow(doc.data());
    if (row) purchases.push(row);
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
    // config/runtime + the stats docs and ledger + one per purchase + the aggregation.
    reads: 1 + snaps.length + Math.max(1, purchaseSnap.size) + aggregationReads(accounts),
  };
}
