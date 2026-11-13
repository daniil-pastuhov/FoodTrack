/**
 * PlayVerifier over the Google Play Developer API (androidpublisher v3), authenticated as the
 * function's service account, which must be a Play Console user with order-management access.
 *
 * - purchases.productsv2.getproductpurchasev2: purchase state, product id and quantity from Play, not the client.
 * - purchases.products.consume: the v1 endpoint (productsv2 has no consume).
 * - purchases.voidedpurchases.list: the daily poll.
 */
import { GoogleAuth } from "google-auth-library";
import { ANDROID_PACKAGE } from "../constants";
import { RetryableStoreError, type PlayPurchase, type PlayVerifier, type VoidedPurchase } from "../ports";

const BASE = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${ANDROID_PACKAGE}/purchases`;

interface ProductPurchaseV2 {
  productLineItem?: {
    productId?: string;
    productOfferDetails?: { quantity?: number; refundableQuantity?: number; consumptionState?: string };
  }[];
  purchaseStateContext?: { purchaseState?: string };
  testPurchaseContext?: unknown;
  orderId?: string;
  obfuscatedExternalAccountId?: string;
}

interface VoidedList {
  voidedPurchases?: { purchaseToken?: string; orderId?: string; voidedTimeMillis?: string }[];
  tokenPagination?: { nextPageToken?: string };
}

function statusOf(e: unknown): number | undefined {
  return (e as { response?: { status?: number }; status?: number }).response?.status ?? (e as { status?: number }).status;
}

export function parseProductPurchaseV2(body: ProductPurchaseV2): PlayPurchase | null {
  const items = body.productLineItem ?? [];
  if (items.length !== 1 || !items[0].productId) return null;
  const item = items[0];
  const quantity = item.productOfferDetails?.quantity ?? 1;
  const state = body.purchaseStateContext?.purchaseState;
  return {
    productId: item.productId!,
    quantity,
    purchaseState: state === "PURCHASED" || state === "PENDING" || state === "CANCELLED" ? state : "UNSPECIFIED",
    consumed: item.productOfferDetails?.consumptionState === "CONSUMPTION_STATE_CONSUMED",
    isTest: body.testPurchaseContext !== undefined,
    orderId: body.orderId ?? null,
    refundableQuantity: item.productOfferDetails?.refundableQuantity ?? quantity,
    obfuscatedExternalAccountId: body.obfuscatedExternalAccountId || null,
  };
}

export class GooglePlayVerifier implements PlayVerifier {
  private readonly auth = new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });

  private async call<T>(method: "GET" | "POST", url: string, params?: Record<string, string>): Promise<T> {
    try {
      const res = await this.auth.request<T>({ method, url, params, timeout: 15_000, retry: false });
      return res.data;
    } catch (e) {
      throw Object.assign(new RetryableStoreError(`Play API ${method} failed: ${statusOf(e) ?? "network"}`), {
        status: statusOf(e),
      });
    }
  }

  async getPurchase(purchaseToken: string): Promise<PlayPurchase | null> {
    try {
      const body = await this.call<ProductPurchaseV2>("GET", `${BASE}/productsv2/tokens/${encodeURIComponent(purchaseToken)}`);
      return parseProductPurchaseV2(body);
    } catch (e) {
      // 400/404/410: Play does not know the token (or it expired). 401/403 is our permission setup,
      // never the buyer's fault, so it stays retryable.
      const status = (e as { status?: number }).status;
      if (status === 400 || status === 404 || status === 410) return null;
      throw e;
    }
  }

  async consume(productId: string, purchaseToken: string): Promise<void> {
    await this.call<unknown>(
      "POST",
      `${BASE}/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:consume`,
    );
  }

  async listVoided(startTimeMillis: number): Promise<VoidedPurchase[]> {
    const out: VoidedPurchase[] = [];
    let token: string | undefined;
    for (let page = 0; page < 50; page++) {
      const params: Record<string, string> = token
        ? { token }
        : { startTime: String(startTimeMillis), maxResults: "1000", type: "0" };
      const body = await this.call<VoidedList>("GET", `${BASE}/voidedpurchases`, params);
      for (const v of body.voidedPurchases ?? []) {
        if (v.purchaseToken) {
          out.push({ purchaseToken: v.purchaseToken, orderId: v.orderId ?? null, voidedTimeMillis: Number(v.voidedTimeMillis) || 0 });
        }
      }
      token = body.tokenPagination?.nextPageToken;
      if (!token) break;
    }
    return out;
  }
}
