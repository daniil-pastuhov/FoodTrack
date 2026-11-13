/**
 * Cloud Functions (2nd gen) entry point: binds the core logic in src/core to triggers, params and
 * secrets. Production adapters are built lazily on first use, when secrets are available.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { setGlobalOptions } from "firebase-functions/v2";
import { HttpsError, onCall, onRequest, type CallableRequest } from "firebase-functions/v2/https";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
import { defineInt, defineSecret, defineString } from "firebase-functions/params";
import { CONSUME_APP_CHECK_TOKENS, GENERATE_TIMEOUT_SECONDS, IOS_BUNDLE_ID, REGION } from "./constants";
import { loadRuntimeConfig } from "./model";
import type { Caller, Deps } from "./ports";
import { GeminiRecipeGenerator } from "./adapters/gemini";
import { GooglePlayVerifier } from "./adapters/play";
import { AppleAppStoreVerifier } from "./adapters/appstore";
import { AppleSignInRevoker } from "./adapters/appleRevoke";
import { EmulatorAppStore, EmulatorGenerator, EmulatorPlay, emulatorFakesEnabled } from "./emulatorFakes";
import { generateRecipe as generateCore } from "./core/generation";
import { verifyPurchase as verifyCore } from "./core/purchases";
import { deleteAccount as deleteCore, getBalance as balanceCore, mergeBalance as mergeCore } from "./core/account";
import { sweep } from "./core/sweeper";
import { CallThrottle, LogSampler, assertFreshAppCheck, clientIp } from "./core/guard";
import {
  handleAppleNotification,
  handleBudgetAlert,
  handlePlayNotification,
  pollVoidedPurchases,
} from "./core/notifications";

// --- Params and secrets (see README "Parameters") ---------------------------------------------

const serviceAccount = defineString("FUNCTIONS_SERVICE_ACCOUNT", {
  default: "recipe-functions@",
  description: "Least-privilege service account the functions run as (name@ expands to this project).",
});
const geminiModel = defineString("GEMINI_MODEL", { default: "gemini-3.8-flash", description: "Vertex AI model id" });
const geminiThinking = defineString("GEMINI_THINKING_LEVEL", {
  default: "LOW",
  description: "Gemini 3+ thinking level (MINIMAL where the model supports it, else LOW). Ignored for gemini-2.x.",
});
const vertexLocation = defineString("VERTEX_LOCATION", { default: "global" });
const appleAppId = defineInt("APPLE_APP_ID", { description: "Numeric App Store app id (App Store Connect > App Information)" });
const appStoreKeyId = defineString("APPSTORE_KEY_ID", { description: "App Store Server API (In-App Purchase) key id" });
const appStoreIssuerId = defineString("APPSTORE_ISSUER_ID", { description: "App Store Connect API issuer id" });
const appStorePrivateKey = defineSecret("APPSTORE_PRIVATE_KEY");
const appleTeamId = defineString("APPLE_TEAM_ID", { description: "Apple developer team id" });
const siwaKeyId = defineString("SIWA_KEY_ID", { description: "Sign in with Apple key id" });
const siwaPrivateKey = defineSecret("SIWA_PRIVATE_KEY");
const playRtdnTopic = defineString("PLAY_RTDN_TOPIC", { default: "play-rtdn" });
const budgetTopic = defineString("BUDGET_ALERT_TOPIC", { default: "budget-alerts" });

setGlobalOptions({ region: REGION, serviceAccount, maxInstances: 3, memory: "256MiB" });

initializeApp();

// --- Lazy production dependencies -------------------------------------------------------------

let deps: Deps | undefined;

function appleRoots(): Buffer[] {
  return [readFileSync(join(__dirname, "..", "certs", "AppleRootCA-G3.cer"))];
}

function getDeps(): Deps {
  if (!deps && emulatorFakesEnabled()) {
    logger.warn("EMULATOR_FAKES: fake stores and LLM in use (emulator only)");
    deps = {
      db: getFirestore(),
      auth: getAuth(),
      play: new EmulatorPlay(),
      appStore: new EmulatorAppStore(),
      generator: new EmulatorGenerator(),
      appleRevoker: { revokeWithAuthorizationCode: async () => undefined },
      now: () => Date.now(),
      logger,
    };
  }
  deps ??= {
    db: getFirestore(),
    auth: getAuth(),
    play: new GooglePlayVerifier(),
    appStore: new AppleAppStoreVerifier({
      rootCertificates: appleRoots(),
      appAppleId: appleAppId.value(),
      enableOnlineChecks: true,
      apiKey: process.env.APPSTORE_PRIVATE_KEY
        ? { privateKey: appStorePrivateKey.value(), keyId: appStoreKeyId.value(), issuerId: appStoreIssuerId.value() }
        : undefined,
    }),
    generator: new GeminiRecipeGenerator({
      project: process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT ?? "",
      location: vertexLocation.value(),
      model: geminiModel.value(),
      thinkingLevel: geminiThinking.value(),
    }),
    appleRevoker: {
      revokeWithAuthorizationCode: (code) =>
        new AppleSignInRevoker({
          teamId: appleTeamId.value(),
          keyId: siwaKeyId.value(),
          privateKey: siwaPrivateKey.value(),
          clientId: IOS_BUNDLE_ID,
        }).revokeWithAuthorizationCode(code),
    },
    now: () => Date.now(),
    logger,
  };
  return deps;
}

const throttle = new CallThrottle();

/**
 * The verified caller, after the checks that cost no Firestore access: auth, App Check replay
 * (`consume` callables), and the per-instance uid and IP throttles.
 */
function callerOf(request: CallableRequest, consume = false): Caller {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required");
  assertFreshAppCheck(request.app, consume);
  const raw = request.rawRequest;
  throttle.admit(request.auth.uid, clientIp(raw.headers, raw.socket?.remoteAddress), Date.now());
  return {
    uid: request.auth.uid,
    signInProvider: request.auth.token.firebase?.sign_in_provider ?? "",
    authTime: request.auth.token.auth_time ?? 0,
  };
}

/** Logs unexpected errors and hides their details from the client. */
async function guarded<T>(name: string, fn: () => Promise<T>): Promise<T> {
  try {
    return await fn();
  } catch (e) {
    if (e instanceof HttpsError) throw e;
    logger.error(`${name} failed`, { error: String(e), stack: (e as Error).stack });
    throw new HttpsError("internal", "Something went wrong; try again");
  }
}

// Public invoker is required for callables (Firebase Auth and App Check are checked in the
// function). No CORS: the only clients are the mobile apps, so browsers get no access.
const callable = { enforceAppCheck: true, invoker: "public", cors: false } as const;

// --- Callables --------------------------------------------------------------------------------

export const verifyPurchase = onCall(
  {
    ...callable,
    consumeAppCheckToken: CONSUME_APP_CHECK_TOKENS,
    secrets: [appStorePrivateKey],
    timeoutSeconds: 60,
    maxInstances: 3,
  },
  (request) =>
    guarded("verifyPurchase", () => verifyCore(getDeps(), callerOf(request, CONSUME_APP_CHECK_TOKENS), request.data)),
);

// Concurrency 20 x 5 instances = 100 calls at once, far more than the spend breaker lets reserve.
export const generateRecipe = onCall(
  {
    ...callable,
    consumeAppCheckToken: CONSUME_APP_CHECK_TOKENS,
    timeoutSeconds: GENERATE_TIMEOUT_SECONDS,
    maxInstances: 5,
    concurrency: 20,
    cpu: 1,
    memory: "512MiB",
  },
  (request) =>
    guarded("generateRecipe", () => generateCore(getDeps(), callerOf(request, CONSUME_APP_CHECK_TOKENS), request.data)),
);

export const getBalance = onCall({ ...callable, timeoutSeconds: 10, maxInstances: 3 }, (request) =>
  guarded("getBalance", () => balanceCore(getDeps(), callerOf(request))),
);

export const mergeBalance = onCall({ ...callable, timeoutSeconds: 30, maxInstances: 2 }, (request) =>
  guarded("mergeBalance", () => mergeCore(getDeps(), callerOf(request), request.data)),
);

export const deleteAccount = onCall(
  { ...callable, secrets: [siwaPrivateKey], timeoutSeconds: 120, maxInstances: 2 },
  (request) => guarded("deleteAccount", () => deleteCore(getDeps(), callerOf(request), request.data)),
);

// --- Background -------------------------------------------------------------------------------

export const sweepReservations = onSchedule({ schedule: "every 5 minutes", timeoutSeconds: 300, maxInstances: 1 }, async () => {
  const result = await sweep(getDeps());
  if (result.settled || result.consumed) logger.info("Sweep", { ...result });
});

export const pollPlayVoidedPurchases = onSchedule(
  { schedule: "every day 04:00", timeZone: "Etc/UTC", timeoutSeconds: 540, maxInstances: 1 },
  async () => {
    const result = await pollVoidedPurchases(getDeps());
    logger.info("Voided purchases poll", { ...result });
  },
);

function decodePubSub(data: string | undefined): unknown {
  if (!data) return null;
  try {
    return JSON.parse(Buffer.from(data, "base64").toString("utf8"));
  } catch {
    return null;
  }
}

export const playNotifications = onMessagePublished(
  { topic: playRtdnTopic, retry: true, maxInstances: 2 },
  async (event) => {
    const outcome = await handlePlayNotification(getDeps(), decodePubSub(event.data.message.data));
    logger.info("Play RTDN", { outcome, messageId: event.data.message.messageId });
  },
);

export const budgetKillSwitch = onMessagePublished({ topic: budgetTopic, maxInstances: 1 }, async (event) => {
  const d = getDeps();
  const config = await loadRuntimeConfig(d.db, d.logger);
  await handleBudgetAlert(d, decodePubSub(event.data.message.data), config.budgetKillFraction);
});

/** Apple's signed payloads are a few KB; anything far larger is not from Apple. */
const APPLE_BODY_MAX_BYTES = 64 * 1024;
const rejectedLog = new LogSampler(60_000);

export const appStoreNotifications = onRequest(
  { invoker: "public", maxInstances: 2, timeoutSeconds: 30 },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).send("POST only");
      return;
    }
    if ((req.rawBody?.length ?? 0) > APPLE_BODY_MAX_BYTES || !req.is("application/json")) {
      res.status(400).send("rejected");
      return;
    }
    try {
      const result = await handleAppleNotification(getDeps(), req.body);
      if (result.httpStatus === 200) {
        logger.info("App Store notification", { ...result });
      } else {
        // A flood of forged bodies must not become a flood of log lines.
        const sample = rejectedLog.take(Date.now());
        if (sample.log) logger.warn("App Store notification rejected", { ...result, suppressedSinceLast: sample.suppressed });
      }
      res.status(result.httpStatus).send(result.httpStatus === 200 ? "OK" : "rejected");
    } catch (e) {
      logger.error("App Store notification failed", { error: String(e) });
      res.status(500).send("error");
    }
  },
);
