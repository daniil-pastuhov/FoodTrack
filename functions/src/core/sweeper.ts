/**
 * Every 5 minutes: settles reservations left pending past their deadline (the invocation died or
 * timed out) as infrastructure failures, which refunds them, and retries Play consumes.
 */
import { Timestamp } from "firebase-admin/firestore";
import { CONSUME_RETRY_MIN_AGE_MS, SWEEP_GRACE_MS } from "../constants";
import { loadRuntimeConfig, type GenerationDoc, type PurchaseDoc } from "../model";
import type { Deps } from "../ports";
import { logLlmCall, settle, type Outcome } from "./generation";
import { consumePlay } from "./purchases";

export interface SweepResult {
  settled: number;
  consumed: number;
}

export async function sweep(deps: Deps, limit = 200): Promise<SweepResult> {
  const now = deps.now();
  const stale = await deps.db
    .collectionGroup("generations")
    .where("state", "==", "pending")
    .where("deadlineAt", "<", Timestamp.fromMillis(now - SWEEP_GRACE_MS))
    .limit(limit)
    .get();
  let settled = 0;
  const config = stale.empty ? null : await loadRuntimeConfig(deps.db, deps.logger);
  for (const snap of stale.docs) {
    const uid = snap.ref.parent.parent?.id;
    const gen = snap.data() as GenerationDoc;
    if (!uid || gen.state !== "pending") continue;
    // The invocation died: its usage is unknown, so settle books the reserved worst case.
    const outcome: Outcome = { ok: false, failureClass: "infra", reason: "timeout (sweeper)" };
    const res = await settle(deps, uid, snap.id, outcome, config!);
    if (res.settledHere) {
      settled++;
      logLlmCall(deps, uid, gen, outcome, res, null);
    }
  }

  const unconsumed = await deps.db
    .collection("purchases")
    .where("consumePending", "==", true)
    .limit(limit)
    .get();
  let consumed = 0;
  for (const snap of unconsumed.docs) {
    const doc = snap.data() as PurchaseDoc;
    if (!doc.purchaseToken || !doc.productId || !doc.grantedAt) continue;
    if (now - doc.grantedAt.toMillis() < CONSUME_RETRY_MIN_AGE_MS) continue;
    if (await consumePlay(deps, snap.id, doc.productId, doc.purchaseToken)) consumed++;
  }
  return { settled, consumed };
}
