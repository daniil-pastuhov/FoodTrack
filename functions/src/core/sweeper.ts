/**
 * Every 5 minutes: settles reservations left pending past their deadline (the invocation died or
 * timed out) as infrastructure failures, retries Play consumes and owed Apple consumption answers,
 * and watches the total of negative balances. Each item is handled on its own (one failure does
 * not stop the run), and each queue is read in pages in a fixed order, so stuck items cannot
 * starve the rest.
 */
import { AggregateField, Timestamp, type Query } from "firebase-admin/firestore";
import { CONSUME_RETRY_MIN_AGE_MS, SWEEP_GRACE_MS, SWEEP_MAX_PAGES, SWEEP_PAGE } from "../constants";
import { type GenerationDoc, type PurchaseDoc } from "../model";
import type { Deps } from "../ports";
import { answerConsumption } from "./consumption";
import { logLlmCall, settle, type Outcome } from "./generation";
import { consumePlay } from "./purchases";

export interface SweepResult {
  settled: number;
  consumed: number;
  consumptionAnswers: number;
  failures: number;
}

/** Runs `each` over every document of `query` (ordered by the caller), page by page. */
async function forEachPage(
  query: Query,
  pageSize: number,
  each: (snap: FirebaseFirestore.QueryDocumentSnapshot) => Promise<void>,
): Promise<void> {
  let q = query.limit(pageSize);
  for (let page = 0; page < SWEEP_MAX_PAGES; page++) {
    const snaps = await q.get();
    for (const snap of snaps.docs) await each(snap);
    if (snaps.size < pageSize) return;
    q = query.startAfter(snaps.docs[snaps.size - 1]).limit(pageSize);
  }
}

export async function sweep(deps: Deps, pageSize = SWEEP_PAGE): Promise<SweepResult> {
  const now = deps.now();
  const result: SweepResult = { settled: 0, consumed: 0, consumptionAnswers: 0, failures: 0 };
  const failed = (queue: string, id: string, e: unknown) => {
    result.failures++;
    deps.logger.error("sweep_item_failed", { queue, id, error: String(e).slice(0, 200) });
  };
  const config = await deps.config();

  const stale = deps.db
    .collectionGroup("generations")
    .where("state", "==", "pending")
    .where("deadlineAt", "<", Timestamp.fromMillis(now - SWEEP_GRACE_MS))
    .orderBy("deadlineAt");
  await forEachPage(stale, pageSize, async (snap) => {
    const uid = snap.ref.parent.parent?.id;
    const gen = snap.data() as GenerationDoc;
    if (!uid || gen.state !== "pending") return;
    try {
      // The invocation died: its usage is unknown, so settle books the reserved worst case.
      const outcome: Outcome = { ok: false, failureClass: "infra", reason: "invocation died (sweeper)", swept: true };
      const res = await settle(deps, uid, snap.id, outcome, config);
      if (res.settledHere) {
        result.settled++;
        logLlmCall(deps, uid, gen, outcome, res, null);
      }
    } catch (e) {
      failed("generations", snap.id, e);
    }
  });

  // Oldest grant first; a stuck one leaves the queue after CONSUME_MAX_ATTEMPTS (play_consume_gave_up).
  const unconsumed = deps.db.collection("purchases").where("consumePending", "==", true).orderBy("grantedAt");
  await forEachPage(unconsumed, pageSize, async (snap) => {
    const doc = snap.data() as PurchaseDoc;
    if (!doc.purchaseToken || !doc.productId || !doc.grantedAt) return;
    if (now - doc.grantedAt.toMillis() < CONSUME_RETRY_MIN_AGE_MS) return;
    try {
      if (await consumePlay(deps, snap.id, doc.productId, doc.purchaseToken)) result.consumed++;
    } catch (e) {
      failed("consume", snap.id, e);
    }
  });

  const owed = deps.db.collection("purchases").where("consumptionPending", "==", true).orderBy("consumption.deadlineAt");
  await forEachPage(owed, pageSize, async (snap) => {
    try {
      if ((await answerConsumption(deps, snap.id, config)) === "answered") result.consumptionAnswers++;
    } catch (e) {
      failed("consumption", snap.id, e);
    }
  });

  try {
    await watchNegativeBalances(deps);
  } catch (e) {
    failed("negativeBalances", "-", e);
  }
  return result;
}

/**
 * Sums the negative balances (store refunds of spent credits) and logs `negative_balance_total_grew`
 * when the total got more negative since the last run. One aggregation query (billed as one read
 * per 1,000 matching index entries).
 */
export async function watchNegativeBalances(deps: Deps): Promise<number> {
  const agg = await deps.db.collection("users").where("balance", "<", 0)
    .aggregate({ total: AggregateField.sum("balance"), accounts: AggregateField.count() }).get();
  const total = agg.data().total ?? 0;
  const ref = deps.db.doc("stats/ledger");
  const last = ((await ref.get()).data()?.negativeBalanceTotal as number | undefined) ?? 0;
  if (total < last) {
    deps.logger.error("negative_balance_total_grew", { total, previous: last, accounts: agg.data().accounts });
  }
  if (total !== last) await ref.set({ negativeBalanceTotal: total, checkedAt: Timestamp.fromMillis(deps.now()) }, { merge: true });
  return total;
}
