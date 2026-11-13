/**
 * Store refund signals: Play real-time developer notifications (Pub/Sub), the daily Play Voided
 * Purchases poll, App Store Server Notifications v2, and the GCP budget alert kill switch.
 */
import { Timestamp } from "firebase-admin/firestore";
import { ANDROID_PACKAGE, PRODUCT_CREDITS, VOIDED_POLL_WINDOW_MS } from "../constants";
import { purchaseRef, type PurchaseDoc } from "../model";
import { InvalidSignatureError, RetryableStoreError, type AppleTransaction, type Deps, type PlayPurchase } from "../ports";
import { applePurchaseId, applyRefund, playPurchaseId, type RefundResult } from "./purchases";

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
  });
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
  return refundPlay(deps, v.purchaseToken, v.orderId ?? null, eventAt, v.refundType === REFUND_TYPE_PARTIAL);
}

/** The daily poll: catches voids whose RTDN was lost. Only full voids are listed (no partial-refund entries). */
export async function pollVoidedPurchases(deps: Deps): Promise<{ seen: number; applied: number }> {
  const voided = await deps.play.listVoided(deps.now() - VOIDED_POLL_WINDOW_MS);
  let applied = 0;
  for (const v of voided) {
    try {
      const r = await refundPlay(deps, v.purchaseToken, v.orderId, v.voidedTimeMillis, false);
      if (r === "applied" || r === "created") applied++;
    } catch (e) {
      if (!(e instanceof RetryableStoreError)) throw e;
      deps.logger.warn("Voided poll: purchase lookup failed; next poll retries", { error: String(e) });
    }
  }
  return { seen: voided.length, applied };
}

// --- App Store --------------------------------------------------------------------------------

const PERCENT_MILLIUNITS = 100_000;

function appleRefundedCredits(credits: number, tx: AppleTransaction): number {
  if (tx.revocationPercentage === null) return credits;
  return Math.min(credits, Math.ceil((credits * tx.revocationPercentage) / PERCENT_MILLIUNITS));
}

export type AppleNotificationResult =
  | { httpStatus: 200; outcome: string }
  | { httpStatus: 400; outcome: string }
  | { httpStatus: 500; outcome: string };

/**
 * Handles one App Store Server Notifications v2 request body. A payload that fails signature
 * verification is answered 400 and changes nothing; a transient failure is answered 500 so Apple
 * retries.
 */
export async function handleAppleNotification(deps: Deps, body: unknown): Promise<AppleNotificationResult> {
  const signedPayload = (body as { signedPayload?: unknown } | null)?.signedPayload;
  if (typeof signedPayload !== "string" || signedPayload.length > 200_000) {
    return { httpStatus: 400, outcome: "missing signedPayload" };
  }
  let n;
  try {
    n = await deps.appStore.verifyNotification(signedPayload);
  } catch (e) {
    if (e instanceof InvalidSignatureError) {
      deps.logger.warn("Rejected App Store notification", { error: e.message });
      return { httpStatus: 400, outcome: "invalid signature" };
    }
    deps.logger.error("App Store notification verification failed", { error: String(e) });
    return { httpStatus: 500, outcome: "verification error" };
  }
  const tx = n.transaction;
  if (n.notificationType === "TEST") return { httpStatus: 200, outcome: "test" };
  if (!tx) return { httpStatus: 200, outcome: `ignored: ${n.notificationType} without transaction` };
  if (tx.type !== "Consumable") return { httpStatus: 200, outcome: "ignored: not a consumable" };

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
      const r = await applyRefund(deps, {
        purchaseId: id,
        eventAt: n.signedDate,
        targetRefunded: (doc) => appleRefundedCredits(doc?.credits ?? whenMissing.credits, tx),
        whenMissing,
      });
      return { httpStatus: 200, outcome: `refund ${r}` };
    }
    case "REFUND_REVERSED": {
      const r = await applyRefund(deps, {
        purchaseId: id, eventAt: n.signedDate, reversal: true, targetRefunded: () => 0, whenMissing,
      });
      return { httpStatus: 200, outcome: `reversal ${r}` };
    }
    case "CONSUMPTION_REQUEST": {
      // Answering needs the customer's consent to share consumption data, which the app does not
      // collect, so the request is only recorded. Apple decides without it.
      const ref = purchaseRef(deps.db, id);
      const snap = await ref.get();
      if (snap.exists) await ref.update({ consumptionRequestedAt: Timestamp.fromMillis(deps.now()) });
      return { httpStatus: 200, outcome: "consumption request recorded" };
    }
    default:
      return { httpStatus: 200, outcome: `ignored: ${n.notificationType}` };
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
