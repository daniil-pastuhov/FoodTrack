/**
 * Every 5 minutes: settles reservations left pending past their deadline (the invocation died or
 * timed out) as infrastructure failures, which refunds them, and retries Play consumes.
 */
import { Timestamp } from "firebase-admin/firestore";
import { CONSUME_RETRY_MIN_AGE_MS, SWEEP_GRACE_MS } from "../constants";
import type { GenerationDoc, PurchaseDoc } from "../model";
import type { Deps } from "../ports";
import { settle } from "./generation";
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
  for (const snap of stale.docs) {
    const uid = snap.ref.parent.parent?.id;
    if (!uid || (snap.data() as GenerationDoc).state !== "pending") continue;
    const res = await settle(deps, uid, snap.id, { ok: false, failureClass: "infra", reason: "timeout (sweeper)" });
    if (res.status === "FAILED") settled++;
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
