/**
 * Store refund signals: Play real-time developer notifications (Pub/Sub), the daily Play Voided
 * Purchases poll, App Store Server Notifications v2, and the GCP budget alert kill switch.
 */
import { Timestamp } from "firebase-admin/firestore";
import { ANDROID_PACKAGE, PRODUCT_CREDITS, VOIDED_POLL_WINDOW_MS } from "../constants";
import { purchaseRef, type PurchaseDoc, type RuntimeConfig } from "../model";
import { InvalidSignatureError, RetryableStoreError, type Deps, type PlayPurchase } from "../ports";
import { handleConsumptionRequest } from "./consumption";
import { appleRefundedCredits, applePurchaseId, applyRefund, playPurchaseId, type RefundResult } from "./purchases";

const creditsOf = (productId: string | null | undefined, quantity: number) =>
  productId && productId in PRODUCT_CREDITS ? PRODUCT_CREDITS[productId] * quantity : 0;

// --- Google Play ------------------------------------------------------------------------------

interface DeveloperNotification {
  packageName?: string;
  eventTimeMillis?: string | number;
  voidedPurchaseNotification?: { purchaseToken?: string; orderId?: string; productType?: number; refundType?: number };
  testNotification?: unknown;
}

const PRODUCT_TYPE_ONE_TIME = 2;
const REFUND_TYPE_FULL = 1;
const REFUND_TYPE_PARTIAL = 2;

/** Refunds one Play purchase. `partial` re-reads the purchase from Play for the remaining refundable quantity. */
async function refundPlay(
  deps: Deps,
  purchaseToken: string,
  orderId: string | null,
  eventAt: number,
  partial: boolean,
  config: RuntimeConfig,
): Promise<RefundResult | "skipped"> {
  const id = playPurchaseId(purchaseToken);
  const existing = (await purchaseRef(deps.db, id).get()).data();
  let live: PlayPurchase | null = null;
  if (partial || !existing) {
    // Throws RetryableStoreError, which makes Pub/Sub redeliver.
    live = await deps.play.getPurchase(purchaseToken);
  }
  if (partial && !live) {
    // The refunded quantity is only known from Play; a partial refund must never be applied as a
    // full one. Logged for a manual look.
    deps.logger.error("Partial Play refund for a purchase Play no longer returns; not applied", { id });
    return "skipped";
  }
  const productId = existing?.productId ?? live?.productId ?? null;
  const quantity = existing?.quantity ?? live?.quantity ?? 1;
  const perUnit = creditsOf(productId, 1);
  return applyRefund(deps, {
    purchaseId: id,
    eventAt,
    targetRefunded: (doc: PurchaseDoc | null) => {
      const credits = doc?.credits ?? creditsOf(productId, quantity);
      if (partial) return Math.min(credits, (live!.quantity - live!.refundableQuantity) * perUnit);
      return credits;
    },
    whenMissing: {
      store: "play",
      env: live?.isTest ? "sandbox" : "production",
      productId,
      quantity,
      credits: creditsOf(productId, quantity),
      orderId,
    },
  }, config);
}

/** Handles one decoded RTDN. Throws (so Pub/Sub retries) only on transient failures. */
export async function handlePlayNotification(deps: Deps, message: unknown): Promise<string> {
  const n = message as DeveloperNotification;
  if (!n || typeof n !== "object") return "ignored: not an object";
  if (n.packageName !== ANDROID_PACKAGE) return "ignored: other package";
  if (n.testNotification) return "test";
  const v = n.voidedPurchaseNotification;
  if (!v) return "ignored: not a voided purchase";
  if (v.productType !== PRODUCT_TYPE_ONE_TIME || typeof v.purchaseToken !== "string" || !v.purchaseToken) {
    return "ignored: not a one-time product";
  }
  if (v.refundType !== REFUND_TYPE_FULL && v.refundType !== REFUND_TYPE_PARTIAL) return "ignored: unknown refund type";
  const eventAt = Number(n.eventTimeMillis) || deps.now();
  const config = await deps.config();
  return refundPlay(deps, v.purchaseToken, v.orderId ?? null, eventAt, v.refundType === REFUND_TYPE_PARTIAL, config);
}

/** The daily poll: catches voids whose RTDN was lost. Only full voids are listed (no partial-refund entries). */
export async function pollVoidedPurchases(deps: Deps): Promise<{ seen: number; applied: number }> {
  const voided = await deps.play.listVoided(deps.now() - VOIDED_POLL_WINDOW_MS);
  const config = await deps.config();
  let applied = 0;
  for (const v of voided) {
    try {
      const r = await refundPlay(deps, v.purchaseToken, v.orderId, v.voidedTimeMillis, false, config);
      if (r === "applied" || r === "created") applied++;
    } catch (e) {
      if (!(e instanceof RetryableStoreError)) throw e;
      deps.logger.warn("Voided poll: purchase lookup failed; next poll retries", { error: String(e).slice(0, 200) });
    }
  }
  return { seen: voided.length, applied };
}

// --- App Store --------------------------------------------------------------------------------

/**
 * Apple's signedPayload for a consumable is about 15 KB: the notification JWS carries a 3-certificate
 * x5c header (~5 KB once base64url-encoded) and a payload holding the transaction JWS (~6.5 KB with
 * its own x5c), base64url-encoded again (x 4/3). 24 KB leaves room for fields Apple adds; anything
 * larger is not a notification about our consumables.
 */
export const APPLE_SIGNED_PAYLOAD_MAX = 24 * 1024;

/**
 * `outcome` is a fixed code (never a value taken from the request), so it is safe to log. Only a
 * signature-verified notification can produce a 200.
 */
export type AppleNotificationResult =
  | { httpStatus: 200; outcome: string }
  | { httpStatus: 400; outcome: "MISSING_PAYLOAD" | "PAYLOAD_TOO_LARGE" | "INVALID_SIGNATURE" }
  | { httpStatus: 500; outcome: "VERIFICATION_ERROR" };

/** Apple's notification types are upper-case words; anything else is reported as OTHER. */
const typeCode = (t: string) => (/^[A-Z_]{1,40}$/.test(t) ? t : "OTHER");

/**
 * Handles one App Store Server Notifications v2 request body. A payload that fails signature
 * verification is answered 400 and changes nothing; a transient failure is answered 500 so Apple
 * retries. Logs nothing for a rejected body: the caller logs the fixed outcome, sampled.
 */
export async function handleAppleNotification(deps: Deps, body: unknown): Promise<AppleNotificationResult> {
  const signedPayload = (body as { signedPayload?: unknown } | null)?.signedPayload;
  if (typeof signedPayload !== "string") return { httpStatus: 400, outcome: "MISSING_PAYLOAD" };
  if (signedPayload.length > APPLE_SIGNED_PAYLOAD_MAX) return { httpStatus: 400, outcome: "PAYLOAD_TOO_LARGE" };
  let n;
  try {
    n = await deps.appStore.verifyNotification(signedPayload);
  } catch (e) {
    if (e instanceof InvalidSignatureError) return { httpStatus: 400, outcome: "INVALID_SIGNATURE" };
    return { httpStatus: 500, outcome: "VERIFICATION_ERROR" };
  }
  const tx = n.transaction;
  if (n.notificationType === "TEST") return { httpStatus: 200, outcome: "test" };
  if (!tx) return { httpStatus: 200, outcome: `ignored: ${typeCode(n.notificationType)} without transaction` };
  if (tx.type !== "Consumable") return { httpStatus: 200, outcome: "ignored: not a consumable" };
  const config = await deps.config();

  const id = applePurchaseId(tx.environment, tx.transactionId);
  const whenMissing = {
    store: "appstore" as const,
    env: tx.environment,
    productId: tx.productId,
    quantity: tx.quantity,
    credits: creditsOf(tx.productId, tx.quantity),
    transactionId: tx.transactionId,
  };
  switch (n.notificationType) {
    case "REFUND":
    case "REVOKE": {
      // A refund notification is a refund even if the embedded copy lacks revocationDate.
      const revoked = { revocationDate: tx.revocationDate ?? n.signedDate, revocationPercentage: tx.revocationPercentage };
      const r = await applyRefund(deps, {
        purchaseId: id,
        eventAt: n.signedDate,
        targetRefunded: (doc) => appleRefundedCredits(doc?.credits ?? whenMissing.credits, revoked),
        whenMissing,
      }, config);
      return { httpStatus: 200, outcome: `refund ${r}` };
    }
    case "REFUND_REVERSED": {
      // A reversal restores the credits. Only when the transaction still reports a partial
      // refund (revocationDate and a revocationPercentage) does that share stay refunded; a
      // revocationDate without a percentage is not read as "still fully refunded", since Apple does
      // not document whether it clears the date on reversal.
      const remaining = tx.revocationPercentage !== null && tx.revocationPercentage < 100_000 ? tx : null;
      const r = await applyRefund(deps, {
        purchaseId: id,
        eventAt: n.signedDate,
        reversal: true,
        targetRefunded: (doc) => (remaining ? appleRefundedCredits(doc?.credits ?? whenMissing.credits, remaining) : 0),
        whenMissing,
      }, config);
      return { httpStatus: 200, outcome: `reversal ${r}` };
    }
    case "CONSUMPTION_REQUEST": {
      const r = await handleConsumptionRequest(deps, n, config);
      return { httpStatus: 200, outcome: `consumption request: ${r}` };
    }
    default:
      return { httpStatus: 200, outcome: `ignored: ${typeCode(n.notificationType)}` };
  }
}

// --- Budget kill switch -----------------------------------------------------------------------

interface BudgetNotification {
  costAmount?: number;
  budgetAmount?: number;
  budgetDisplayName?: string;
}

/** Turns generation off when a budget notification reports spend at or over the configured fraction. Never turns it back on. */
export async function handleBudgetAlert(deps: Deps, message: unknown, killFraction: number): Promise<boolean> {
  const b = message as BudgetNotification;
  if (typeof b?.costAmount !== "number" || typeof b?.budgetAmount !== "number" || b.budgetAmount <= 0) return false;
  if (b.costAmount < b.budgetAmount * killFraction) return false;
  await deps.db.doc("config/runtime").set(
    {
      generationEnabled: false,
      disabledBy: `budget alert ${b.budgetDisplayName ?? ""}: ${b.costAmount} of ${b.budgetAmount}`.trim(),
      disabledAt: Timestamp.fromMillis(deps.now()),
    },
    { merge: true },
  );
  deps.logger.error("Budget kill switch flipped: generation disabled", { cost: b.costAmount, budget: b.budgetAmount });
  return true;
}
