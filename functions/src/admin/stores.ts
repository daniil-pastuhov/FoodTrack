/**
 * The admin command's live store lookups and secrets, all with Application Default Credentials the
 * operator provides at run time (README "Deletion requests by email"). No key file is read: the
 * App Store and Sign in with Apple keys come from Secret Manager, where the deployed functions
 * read them too.
 */
import { AppStoreServerAPIClient, APIException, Environment } from "@apple/app-store-server-library";
import { GoogleAuth } from "google-auth-library";
import { ANDROID_PACKAGE, IOS_BUNDLE_ID } from "../constants";
import { AppleAppStoreVerifier } from "../adapters/appstore";
import type { AppleTransaction, Logger } from "../ports";
import { AdminRefusal, type AdminStoreLookups } from "./accountRequests";

const statusOf = (e: unknown) =>
  (e as { response?: { status?: number }; status?: number }).response?.status ?? (e as { status?: number }).status;

const cloud = new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/cloud-platform"] });

/** The latest version of a Secret Manager secret of `project`. */
export async function accessSecret(project: string, name: string): Promise<string> {
  try {
    const res = await cloud.request<{ payload?: { data?: string } }>({
      method: "GET",
      url: `https://secretmanager.googleapis.com/v1/projects/${project}/secrets/${name}/versions/latest:access`,
      timeout: 15_000,
      retry: false,
    });
    const data = res.data.payload?.data;
    if (!data) throw new Error("empty payload");
    return Buffer.from(data, "base64").toString("utf8");
  } catch (e) {
    // Never the payload: only the secret's name and the HTTP status.
    throw new AdminRefusal(`Could not read secret ${name} from Secret Manager (${statusOf(e) ?? "network"}). ` +
      "Your credentials need Secret Manager Secret Accessor on it.");
  }
}

/**
 * Copies every log line to the live project's Cloud Logging (log `admin`, resource `global`), so
 * `admin_account_deleted` is on record where the functions' logs are. `flush` waits for the writes;
 * a failed write is reported on stderr and never undoes anything.
 */
export function cloudLogger(project: string, local: Logger): { logger: Logger; flush(): Promise<void> } {
  const pending: Promise<void>[] = [];
  const write = (severity: string, message: string, data?: Record<string, unknown>) => {
    pending.push(
      cloud
        .request({
          method: "POST",
          url: "https://logging.googleapis.com/v2/entries:write",
          data: {
            logName: `projects/${project}/logs/admin`,
            resource: { type: "global", labels: { project_id: project } },
            entries: [{ severity, jsonPayload: { message, ...data } }],
          },
          timeout: 15_000,
          retry: false,
        })
        .then(
          () => undefined,
          (e) => console.error(`Could not write "${message}" to Cloud Logging (${statusOf(e) ?? "network"}); it is printed above.`),
        ),
    );
  };
  return {
    logger: {
      info: (m, d) => (local.info(m, d), write("INFO", m, d)),
      warn: (m, d) => (local.warn(m, d), write("WARNING", m, d)),
      error: (m, d) => (local.error(m, d), write("ERROR", m, d)),
    },
    flush: async () => {
      await Promise.all(pending);
    },
  };
}

export interface AppStoreParams {
  appAppleId: number;
  keyId: string;
  issuerId: string;
  rootCertificates: Buffer[];
}

export class LiveStoreLookups implements AdminStoreLookups {
  private readonly play = new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });

  constructor(
    private readonly project: string,
    private readonly appStore: AppStoreParams,
  ) {}

  async playOrderToken(orderId: string): Promise<string | null> {
    try {
      const res = await this.play.request<{ purchaseToken?: string }>({
        method: "GET",
        url: `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${ANDROID_PACKAGE}/orders/${encodeURIComponent(orderId)}`,
        timeout: 15_000,
        retry: false,
      });
      return res.data.purchaseToken ?? null;
    } catch (e) {
      const status = statusOf(e);
      if (status === 404) return null;
      throw new AdminRefusal(`Play Developer API orders.get failed (${status ?? "network"}). ` +
        "Your credentials need the androidpublisher scope and Play Console access to the app's orders.");
    }
  }

  async appleOrderTransactions(orderId: string): Promise<AppleTransaction[]> {
    const key = await accessSecret(this.project, "APPSTORE_PRIVATE_KEY");
    // Order ids exist only for real (production) purchases: sandbox purchases have no receipt.
    const client = new AppStoreServerAPIClient(key, this.appStore.keyId, this.appStore.issuerId, IOS_BUNDLE_ID, Environment.PRODUCTION);
    let signed: string[];
    try {
      const res = await client.lookUpOrderId(orderId);
      signed = res.status === 0 ? (res.signedTransactions ?? []) : [];
    } catch (e) {
      const status = e instanceof APIException ? e.httpStatusCode : undefined;
      throw new AdminRefusal(`App Store Server API Look Up Order ID failed (${status ?? "network"}).`);
    }
    const verifier = new AppleAppStoreVerifier({
      rootCertificates: this.appStore.rootCertificates,
      appAppleId: this.appStore.appAppleId,
      enableOnlineChecks: true,
    });
    const out: AppleTransaction[] = [];
    for (const jws of signed) out.push(await verifier.verifyTransaction(jws));
    return out.filter((t) => t.bundleId === IOS_BUNDLE_ID);
  }
}

/** The emulators have no stores: order lookups need the live project (Firestore's own order ids still work). */
export const emulatorStoreLookups: AdminStoreLookups = {
  async playOrderToken() {
    return null;
  },
  async appleOrderTransactions() {
    throw new AdminRefusal("--apple-order asks Apple's App Store Server API, which only the live project can use.");
  },
};
