/**
 * Answers Apple's CONSUMPTION_REQUEST (App Store Server API "Send Consumption Information", v2)
 * for purchases whose buyer gave `consumptionConsent` in verifyPurchase. Apple: "If the customer
 * provided consent, respond by calling this API ... If not, don't respond" and "Respond within 12
 * hours of receiving the CONSUMPTION_REQUEST notification."
 *
 * What is sent, all from our ledger:
 * - customerConsented: true (only consented purchases are answered).
 * - deliveryStatus: DELIVERED (the credits were added to the buyer's balance; only granted
 *   purchases can carry consent).
 * - consumptionPercentage: the FIFO-consumed share of the purchase's credits, in milliunits
 *   (core/buyers.ts fifoConsumed).
 * - sampleContentProvided: config/runtime.consumptionSampleContentProvided (default true: the pack
 *   sheet explains what a credit buys before purchase).
 * - refundPreference: not sent (no preference is expressed).
 * The v2 body has no account tenure, platform or play time fields (those were in the deprecated v1).
 */
import { Timestamp } from "firebase-admin/firestore";
import { CONSUMPTION_ANSWER_WINDOW_MS, MERGE_CHAIN_MAX } from "../constants";
import { purchaseRef, resolveHolder, type ConsumptionState, type PurchaseDoc, type RuntimeConfig } from "../model";
import {
  ConsumptionRejectedError,
  RetryableStoreError,
  type AppleNotification,
  type ConsumptionInfo,
  type Deps,
} from "../ports";
import { fifoConsumed } from "./buyers";
import { applePurchaseId } from "./purchases";

const MILLIUNITS = 100_000;

export type ConsumptionOutcome =
  | "not consented"
  | "unknown purchase"
  | "duplicate"
  | "answered"
  | "retry later"
  | "rejected by apple"
  | "ledger unknown"
  | "expired";

/** Records a CONSUMPTION_REQUEST and answers it when the buyer consented. */
export async function handleConsumptionRequest(
  deps: Deps,
  n: AppleNotification,
  config: RuntimeConfig,
): Promise<ConsumptionOutcome> {
  const tx = n.transaction!;
  const id = applePurchaseId(tx.environment, tx.transactionId);
  const now = deps.now();
  const recorded = await deps.db.runTransaction(async (t): Promise<ConsumptionOutcome | "answer"> => {
    const ref = purchaseRef(deps.db, id);
    const doc = (await t.get(ref)).data();
    if (!doc) return "unknown purchase";
    if (doc.consumption?.notificationUUID === n.notificationUUID) return "duplicate";
    if (doc.consumptionConsent !== true) {
      t.update(ref, { consumptionRequestedAt: Timestamp.fromMillis(now) });
      return "not consented";
    }
    const received = Math.min(now, n.signedDate || now);
    const consumption: ConsumptionState = {
      notificationUUID: n.notificationUUID,
      requestedAt: Timestamp.fromMillis(now),
      deadlineAt: Timestamp.fromMillis(received + CONSUMPTION_ANSWER_WINDOW_MS),
      attempts: 0,
    };
    t.update(ref, { consumptionRequestedAt: Timestamp.fromMillis(now), consumption, consumptionPending: true });
    return "answer";
  });
  // Logged for every request so the owner can find them: jsonPayload.message="apple_consumption_request".
  deps.logger.info("apple_consumption_request", { purchaseId: id, outcome: recorded === "answer" ? "consented" : recorded });
  if (recorded !== "answer") return recorded;
  return answerConsumption(deps, id, config);
}

/** The consumption body for one purchase, from the ledger; null when the ledger cannot say. */
export async function consumptionInfo(deps: Deps, id: string, config: RuntimeConfig): Promise<ConsumptionInfo | null> {
  return deps.db.runTransaction(async (t) => {
    const doc = (await t.get(purchaseRef(deps.db, id))).data();
    const owner = doc?.uid ?? doc?.formerUid;
    if (!doc || !owner || !doc.grantedAt || doc.credits <= 0) return null;
    const holder = await resolveHolder(t, deps.db, owner, MERGE_CHAIN_MAX);
    const consumed = await fifoConsumed(t, deps.db, id, doc, holder);
    if (consumed === null) return null;
    return {
      customerConsented: true as const,
      consumptionPercentage: Math.round((consumed / doc.credits) * MILLIUNITS),
      deliveryStatus: "DELIVERED" as const,
      sampleContentProvided: config.consumptionSampleContentProvided,
    };
  }, { readOnly: true });
}

/** Sends (or re-sends) the owed answer for one purchase. Idempotent; used live and by the sweeper. */
export async function answerConsumption(deps: Deps, id: string, config: RuntimeConfig): Promise<ConsumptionOutcome> {
  const ref = purchaseRef(deps.db, id);
  const doc = (await ref.get()).data() as PurchaseDoc | undefined;
  const state = doc?.consumption;
  if (!doc || !state || !doc.consumptionPending) return "duplicate";
  const base = { purchaseId: id, notificationUUID: state.notificationUUID };
  if (deps.now() > state.deadlineAt.toMillis()) {
    deps.logger.error("apple_consumption_answer_expired", { ...base, attempts: state.attempts });
    await ref.update({ consumptionPending: false, "consumption.gaveUp": true, "consumption.lastError": "EXPIRED" });
    return "expired";
  }
  if (doc.consumptionConsent !== true) {
    // Consent withdrawn since the request: Apple says not to send further data.
    await ref.update({ consumptionPending: false, "consumption.lastError": "CONSENT_WITHDRAWN" });
    return "not consented";
  }
  const info = await consumptionInfo(deps, id, config);
  if (!info) {
    deps.logger.warn("apple_consumption_answer_failed", { ...base, reason: "LEDGER_UNKNOWN" });
    await ref.update({ consumptionPending: false, "consumption.gaveUp": true, "consumption.lastError": "LEDGER_UNKNOWN" });
    return "ledger unknown";
  }
  try {
    await deps.appStore.sendConsumptionInformation(doc.transactionId!, doc.env, info);
  } catch (e) {
    const retryable = e instanceof RetryableStoreError || !(e instanceof ConsumptionRejectedError);
    const reason = retryable ? "APPLE_RETRYABLE" : "APPLE_REJECTED";
    deps.logger[retryable ? "warn" : "error"]("apple_consumption_answer_failed", { ...base, reason, attempts: state.attempts + 1 });
    await ref.update({
      "consumption.attempts": state.attempts + 1,
      "consumption.lastError": reason,
      ...(retryable ? {} : { consumptionPending: false, "consumption.gaveUp": true }),
    });
    return retryable ? "retry later" : "rejected by apple";
  }
  await ref.update({
    consumptionPending: false,
    "consumption.answeredAt": Timestamp.fromMillis(deps.now()),
    "consumption.attempts": state.attempts + 1,
    "consumption.consumptionPercentage": info.consumptionPercentage,
  });
  deps.logger.info("apple_consumption_answered", { ...base, consumptionPercentage: info.consumptionPercentage });
  return "answered";
}
