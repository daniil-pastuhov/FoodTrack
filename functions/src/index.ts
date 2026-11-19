/**
 * Cloud Functions (2nd gen) entry point: binds the core logic in src/core to triggers, params and
 * secrets. Production adapters are built lazily on first use, when secrets are available.
 *
 * Every function sets its own maxInstances and concurrency; with the global 1/6 vCPU
 * ("gcf_gen1" at 256 MiB) concurrency must be 1, so parallel work means more instances. README
 * "Function limits" has the worst-case bill these limits allow.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
// Loads @google-cloud/storage only when getStorage() is first called (redrawBoard), not at cold start.
import { getStorage } from "firebase-admin/storage";
import { setGlobalOptions } from "firebase-functions/v2";
import { HttpsError, onCall, onRequest, type CallableRequest } from "firebase-functions/v2/https";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
import { defineInt, defineSecret, defineString } from "firebase-functions/params";
import {
  CONSUME_APP_CHECK_TOKENS,
  DEFAULT_IMAGE_SELECTION,
  GENERATE_TIMEOUT_SECONDS,
  IOS_BUNDLE_ID,
  JUDGE,
  KMS_APPLE_TOKEN_KEY,
  KMS_KEY_RING,
  KMS_LOCATION,
  PROMPT_REVIEW,
  REGION,
} from "./constants";
import { RuntimeConfigCache, loadRuntimeConfig, validImageSelection, type ImageSelection, type RuntimeConfig } from "./model";
import type { Caller, Deps, TextTarget } from "./ports";
import { GeminiOutputJudge, GeminiPromptReviewer } from "./adapters/gemini";
import { lastCompleteWeek, runPromptReview } from "./core/promptReview";
import { runWeeklyJobs, runWeeklyReport } from "./core/weeklyReport";
import { FirestoreJudgeLog } from "./core/judgeLog";
import { SecretReader } from "./adapters/secretManager";
import { allProviders, imageProviderIds, imageProviders, providerSecrets, textProviderById, textProviders } from "./providers/registry";
import { ImageRouter, TextRouter } from "./providers/router";
import { checkProviderBalances as checkProviderBalancesCore } from "./providers/balance";
import { assessModels, logModelLifecycle, modelsInUse } from "./providers/lifecycle";
import type { ProviderContext, ProviderDescriptor } from "./providers/types";
import { GooglePlayVerifier } from "./adapters/play";
import { AppleAppStoreVerifier } from "./adapters/appstore";
import { AppleSignInRevoker } from "./adapters/appleRevoke";
import { EnvelopeSealer, KmsKeyWrapper, LocalKeyWrapper } from "./adapters/envelope";
import {
  EmulatorAppStore,
  EmulatorAppleTokens,
  EmulatorGenerator,
  EmulatorImageGenerator,
  EmulatorJudge,
  EmulatorPlay,
  EmulatorPromptReviewer,
  RecordingMailer,
  emulatorFakesEnabled,
} from "./emulatorFakes";
import { gmailMailerOf } from "./adapters/gmailSmtp";
import { sendWeeklyEmails } from "./core/reportEmail";
import { registerAppleSignIn as registerAppleCore } from "./core/appleToken";
import { generateRecipe as generateCore, getGeneration as getGenerationCore } from "./core/generation";
import { verifyPurchase as verifyCore } from "./core/purchases";
import {
  deleteAccount as deleteCore,
  getBalance as balanceCore,
  mergeBalance as mergeCore,
  scrubProfile,
} from "./core/account";
import { sweep } from "./core/sweeper";
import { getTasteQuiz as tasteQuizCore } from "./taste/quiz";
import { reportRecipe as reportCore } from "./core/reports";
import {
  WelcomeDayCounter,
  claimWelcome as claimWelcomeCore,
  getWelcomeOffer as welcomeOfferCore,
  readDayGrants,
} from "./core/welcome";
import { CallThrottle, LogSampler, assertFreshAppCheck, clientIp } from "./core/guard";
import {
  APPLE_SIGNED_PAYLOAD_MAX,
  handleAppleNotification,
  handleBudgetAlert,
  handlePlayNotification,
  pollVoidedPurchases,
} from "./core/notifications";
import { boardBrowserUrl, redrawBoard as redrawBoardCore } from "./board/hosted";
import { CloudStorageBoardBucket } from "./adapters/boardBucket";
import {
  getProviderSwitches as providerSwitchesCore,
  setProviderSwitch as setProviderSwitchCore,
  ownerConsoleOrigins,
  type OwnerEnv,
} from "./core/providerSwitch";
import { OwnerCallGate } from "./core/ownerCall";
import { getOwnerSettings as ownerSettingsCore, setOwnerSettings as setOwnerSettingsCore } from "./core/ownerSettings";

// --- Params and secrets (see README "Parameters") ---------------------------------------------

const serviceAccount = defineString("FUNCTIONS_SERVICE_ACCOUNT", {
  default: "recipe-functions@",
  description: "Least-privilege service account the functions run as (name@ expands to this project).",
});
// Every provider's own params (GEMINI_MODEL, GEMINI_THINKING_LEVEL, VERTEX_LOCATION,
// GEMINI_JAILBREAK_FILTER, VERTEX_IMAGE_LOCATION, ...) and the param naming each provider's
// secret (OPENAI_API_KEY_SECRET, ...) are declared by its adapter (providers/registry.ts) and
// defined here, once each, so a new provider needs no line in this file.
const providerParams = new Map<string, ReturnType<typeof defineString>>();
for (const d of allProviders()) {
  for (const p of d.params ?? []) {
    if (!providerParams.has(p.name)) providerParams.set(p.name, defineString(p.name, { default: p.default, ...(p.description ? { description: p.description } : {}) }));
  }
}
const secretParams = new Map<string, ReturnType<typeof defineString>>();
for (const s of providerSecrets()) {
  secretParams.set(s.id, defineString(s.param, {
    default: s.name,
    description: `Secret Manager secret holding the ${s.id} API key; read at run time only while a provider using it is selected.`,
  }));
}
function providerParam(name: string): string {
  const p = providerParams.get(name);
  if (!p) throw new Error(`provider param ${name} is not declared`);
  return p.value();
}
// The recipe text's default provider (config/runtime.text overrides it at run time); its model is
// the provider's own default param (GEMINI_MODEL for google).
const textProvider = defineString("TEXT_PROVIDER", {
  default: "google",
  description: `Default provider of the recipe text: one of ${textProviders().map((d) => d.id).join(", ")} (config/runtime.text.provider overrides it).`,
});
const promptReviewModel = defineString("PROMPT_REVIEW_MODEL", {
  default: PROMPT_REVIEW.model,
  description: "Vertex AI model id of the weekly prompt review (core/promptReview.ts); empty means GEMINI_MODEL. Booked at the google text provider's prices.",
});
const judgeModel = defineString("GEMINI_JUDGE_MODEL", {
  default: JUDGE.model,
  description: "Vertex AI model id of the output judge (config/runtime outputJudge), a lite model by default; empty means GEMINI_MODEL. Booked at its JUDGE_PRICES entry, else at prices.",
});
// Recipe pictures (README "Recipe pictures"). The provider and model are only defaults:
// config/runtime.image overrides them at run time, without a deploy.
const imageProvider = defineString("IMAGE_PROVIDER", {
  default: DEFAULT_IMAGE_SELECTION.provider,
  // The ids come from the registry, so the description lists every picture adapter (and off).
  description: `Default provider of recipe pictures: one of ${imageProviderIds().join(", ")} (config/runtime.image.provider overrides it).`,
});
const imageModel = defineString("IMAGE_MODEL", {
  default: DEFAULT_IMAGE_SELECTION.model,
  description: "Default model id of recipe pictures at that provider (config/runtime.image.model overrides it).",
});
const imageFallbackModel = defineString("IMAGE_FALLBACK_MODEL", {
  default: DEFAULT_IMAGE_SELECTION.fallbackModel,
  description: "Default fallback model id at the same provider, asked once when the model fails for capacity; empty for none (config/runtime.image.fallbackModel overrides it).",
});
const appleAppId = defineInt("APPLE_APP_ID", { description: "Numeric App Store app id (App Store Connect > App Information)" });
const appStoreKeyId = defineString("APPSTORE_KEY_ID", { description: "App Store Server API (In-App Purchase) key id" });
const appStoreIssuerId = defineString("APPSTORE_ISSUER_ID", { description: "App Store Connect API issuer id" });
const appStorePrivateKey = defineSecret("APPSTORE_PRIVATE_KEY");
const appleTeamId = defineString("APPLE_TEAM_ID", { description: "Apple developer team id" });
const siwaKeyId = defineString("SIWA_KEY_ID", { description: "Sign in with Apple key id" });
const siwaPrivateKey = defineSecret("SIWA_PRIVATE_KEY");
// The owner's report emails (README "Report emails"): the Gmail app password, and the owner's own
// address (SMTP user, From and To). The address is a secret too: the repository is public. Bound
// only to weeklyPromptReview, the one function that sends.
const reportSmtpPassword = defineSecret("REPORT_SMTP_PASSWORD");
const reportEmailAddress = defineSecret("REPORT_EMAIL");
/** A bound secret's value, "" when it cannot be read (then the emails are "not configured"). */
function secretValue(s: ReturnType<typeof defineSecret>): string {
  try {
    return s.value();
  } catch {
    return "";
  }
}
const playRtdnTopic = defineString("PLAY_RTDN_TOPIC", { default: "play-rtdn" });
const budgetTopic = defineString("BUDGET_ALERT_TOPIC", { default: "budget-alerts" });
const boardBucket = defineString("BOARD_BUCKET", {
  default: "",
  description: "Private Cloud Storage bucket redrawBoard writes the owner's board to; empty means <project id>-owner-board (README \"The board\").",
});

// 1/6 vCPU and 256 MiB per instance unless a function says otherwise; one request at a time
// (fractional CPU requires concurrency 1).
setGlobalOptions({
  region: REGION, serviceAccount, maxInstances: 1, concurrency: 1, cpu: "gcf_gen1", memory: "256MiB",
});

initializeApp();

// --- Lazy production dependencies -------------------------------------------------------------

let deps: Deps | undefined;

function appleRoots(): Buffer[] {
  return [readFileSync(join(__dirname, "..", "certs", "AppleRootCA-G3.cer"))];
}

/** The picture provider and model the parameters name; invalid ones turn pictures off (fail closed). */
function imageDefaults(): ImageSelection {
  const s = validImageSelection({ provider: imageProvider.value(), model: imageModel.value(), fallbackModel: imageFallbackModel.value() }, DEFAULT_IMAGE_SELECTION);
  if (!s) logger.warn("IMAGE_PROVIDER, IMAGE_MODEL or IMAGE_FALLBACK_MODEL is invalid; recipe pictures are off");
  return s ?? { ...DEFAULT_IMAGE_SELECTION, provider: "off" };
}

/** The text provider and model the parameters name; an unknown provider is the google default (logged). */
function textDefaults(): TextTarget {
  let d = textProviderById(textProvider.value());
  if (!d) {
    logger.warn("TEXT_PROVIDER is not a text provider; the google default is used");
    d = textProviderById("google")!;
  }
  return { provider: d.id, model: d.defaultModelParam ? providerParam(d.defaultModelParam) : d.defaultModel };
}

function configSource(): () => Promise<RuntimeConfig> {
  const defaults = imageDefaults();
  const text = textDefaults();
  const cache = new RuntimeConfigCache(() => loadRuntimeConfig(getFirestore(), logger, defaults, text), () => Date.now());
  return () => cache.get();
}

/** One SecretReader per provider secret (lazy: nothing is read until a provider using it is called). */
const secretReaders = new Map<string, SecretReader>();
function secretReader(id: string): SecretReader | undefined {
  const param = secretParams.get(id);
  if (!param) return undefined;
  let r = secretReaders.get(id);
  if (!r) {
    r = new SecretReader({ project: projectId(), secret: param.value() });
    secretReaders.set(id, r);
  }
  return r;
}

/** What an adapter is built with: the project, only its own params, and its own secret, read lazily. */
function providerContext(d: ProviderDescriptor): ProviderContext {
  const own = new Set((d.params ?? []).map((p) => p.name));
  return {
    project: projectId(),
    param: (name) => {
      if (!own.has(name)) throw new Error(`${d.id} did not declare the param ${name}`);
      return providerParam(name);
    },
    secret: () => {
      const r = d.secret ? secretReader(d.secret.id) : undefined;
      return r ? r.get() : Promise.reject(new Error("secret unavailable"));
    },
  };
}

/** Every text provider, built on first use (providers/router.ts TextRouter). */
function textRouter(): TextRouter {
  return new TextRouter(Object.fromEntries(textProviders().map((d) => [d.id, () => d.create(providerContext(d))])), textDefaults());
}

/** Every picture provider, built on first use (providers/router.ts ImageRouter). */
function imageRouter(): ImageRouter {
  return new ImageRouter(Object.fromEntries(imageProviders().map((d) => [d.id, () => d.create(providerContext(d))])));
}

/** Whether the functions' service account can read a provider secret now (readiness only; the value is dropped). */
function secretReadable(id: string): Promise<boolean> {
  const r = secretReader(id);
  return r ? r.get().then(() => true, () => false) : Promise.resolve(false);
}

/**
 * Sign in with Apple REST client. SIWA_PRIVATE_KEY is bound to every function that can reach it:
 * registerAppleSignIn, deleteAccount, getBalance (Apple unlinked) and mergeBalance (a source
 * tombstone holding a token).
 */
function appleClient(): AppleSignInRevoker {
  return new AppleSignInRevoker({
    teamId: appleTeamId.value(),
    keyId: siwaKeyId.value(),
    privateKey: siwaPrivateKey.value(),
    clientId: IOS_BUNDLE_ID,
  });
}

/** Today's welcome grant count, cached per instance (the `welcomeRecipes` field beside `balance`). */
function welcomeCounter(): (day: string) => Promise<number> {
  const counter = new WelcomeDayCounter((day) => readDayGrants({ db: getFirestore() }, day), () => Date.now());
  return (day) => counter.grants(day);
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
      judge: new EmulatorJudge(),
      judgeLog: new FirestoreJudgeLog(getFirestore(), () => Date.now()),
      imageGenerator: new EmulatorImageGenerator(),
      appleRevoker: new EmulatorAppleTokens(),
      sealer: new EnvelopeSealer(new LocalKeyWrapper()),
      now: () => Date.now(),
      logger,
      config: configSource(),
      welcomeDayGrants: welcomeCounter(),
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
    generator: textRouter(),
    // The judge is always Gemini on Vertex AI, whichever provider writes the recipe.
    judge: new GeminiOutputJudge({
      project: process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT ?? "",
      location: providerParam("VERTEX_LOCATION"),
      model: judgeModel.value() || providerParam("GEMINI_MODEL"),
      // No thinkingLevel: the judge takes the least its model allows (judgeThinkingConfigFor).
      jailbreakFilter: providerParam("GEMINI_JAILBREAK_FILTER") === "true",
    }),
    // Every judge answer, kept for the owner (core/judgeLog.ts).
    judgeLog: new FirestoreJudgeLog(getFirestore(), () => Date.now()),
    imageGenerator: imageRouter(),
    // Built per call: the Sign in with Apple key is a secret bound only to the functions that use it.
    appleRevoker: {
      revokeWithAuthorizationCode: (code) => appleClient().revokeWithAuthorizationCode(code),
      exchangeAuthorizationCode: (code) => appleClient().exchangeAuthorizationCode(code),
      revokeRefreshToken: (token) => appleClient().revokeRefreshToken(token),
    },
    sealer: new EnvelopeSealer(
      new KmsKeyWrapper(
        `projects/${process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT ?? ""}/locations/${KMS_LOCATION}` +
          `/keyRings/${KMS_KEY_RING}/cryptoKeys/${KMS_APPLE_TOKEN_KEY}`,
      ),
    ),
    now: () => Date.now(),
    logger,
    config: configSource(),
    welcomeDayGrants: welcomeCounter(),
  };
  return deps;
}

/**
 * The verified caller, after the checks that cost no Firestore access: auth, App Check replay
 * (`consume` callables), and the per-instance uid, IP and whole-instance throttles.
 */
function callerOf(request: CallableRequest, throttle: CallThrottle, consume: boolean): Caller {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required");
  assertFreshAppCheck(request.app, consume);
  const raw = request.rawRequest;
  throttle.admit(request.auth.uid, clientIp(raw.headers, raw.socket?.remoteAddress), Date.now());
  const identities = request.auth.token.firebase?.identities;
  return {
    uid: request.auth.uid,
    signInProvider: request.auth.token.firebase?.sign_in_provider ?? "",
    authTime: request.auth.token.auth_time ?? 0,
    appId: request.app?.appId ?? null,
    profileClaims: Boolean(request.auth.token.name || request.auth.token.picture),
    providers: identities && typeof identities === "object" ? Object.keys(identities) : [],
    issuedAt: request.auth.token.iat ?? 0,
  };
}

/** Unexpected-error log lines: at most 5 a minute per instance, so a reproducible crash is not a log flood. */
const internalLog = new LogSampler(60_000, 5);

/**
 * Runs a callable's core with the verified caller, after clearing a signed-in caller's Auth name
 * and photo when the token shows they are still stored (see scrubProfile).
 */
async function withCaller<T>(caller: Caller, fn: (d: Deps, c: Caller) => Promise<T>): Promise<T> {
  const d = getDeps();
  await scrubProfile(d, caller);
  return fn(d, caller);
}

/** Logs unexpected errors (sampled, bounded) and hides their details from the client. */
async function guarded<T>(name: string, fn: () => Promise<T>): Promise<T> {
  try {
    return await fn();
  } catch (e) {
    if (e instanceof HttpsError) throw e;
    const sample = internalLog.take(Date.now());
    if (sample.log) {
      logger.error("callable_failed", {
        function: name,
        error: String(e).slice(0, 300),
        stack: ((e as Error).stack ?? "").slice(0, 2000),
        suppressedSinceLast: sample.suppressed,
      });
    }
    throw new HttpsError("internal", "Something went wrong; try again");
  }
}

// Public invoker is required for callables (Firebase Auth and App Check are checked in the
// function). No CORS: the only clients are the mobile apps, so browsers get no access.
const callable = { enforceAppCheck: true, invoker: "public", cors: false } as const;

/**
 * Per function: Cloud Run limits and the per-instance throttle ceiling (calls let through to
 * Firestore per minute per instance), and for generateRecipe a per-instance per-uid limit. The
 * throttles are per instance, so service-wide they are maxInstances x the number: generateRecipe's
 * 5 x 24 = 120 calls/min and 5 x 12 = 60 per uid are what 2 x 60 and 2 x 30 were at 2 instances.
 * See README "Function limits" for the bill they bound.
 */
const LIMITS = {
  generateRecipe: { maxInstances: 5, perMinute: 24, perUidPerMinute: 12 },
  getGeneration: { maxInstances: 1, perMinute: 120 },
  getBalance: { maxInstances: 1, perMinute: 120 },
  getTasteQuiz: { maxInstances: 1, perMinute: 120 },
  getWelcomeOffer: { maxInstances: 1, perMinute: 120 },
  claimWelcome: { maxInstances: 1, perMinute: 10 },
  verifyPurchase: { maxInstances: 1, perMinute: 30 },
  mergeBalance: { maxInstances: 1, perMinute: 10 },
  deleteAccount: { maxInstances: 1, perMinute: 10 },
  registerAppleSignIn: { maxInstances: 1, perMinute: 10 },
  reportRecipe: { maxInstances: 1, perMinute: 20 },
} as const;

const throttles = {
  generateRecipe: new CallThrottle(LIMITS.generateRecipe.perMinute, LIMITS.generateRecipe.perUidPerMinute),
  getGeneration: new CallThrottle(LIMITS.getGeneration.perMinute),
  getBalance: new CallThrottle(LIMITS.getBalance.perMinute),
  getTasteQuiz: new CallThrottle(LIMITS.getTasteQuiz.perMinute),
  getWelcomeOffer: new CallThrottle(LIMITS.getWelcomeOffer.perMinute),
  claimWelcome: new CallThrottle(LIMITS.claimWelcome.perMinute),
  verifyPurchase: new CallThrottle(LIMITS.verifyPurchase.perMinute),
  mergeBalance: new CallThrottle(LIMITS.mergeBalance.perMinute),
  deleteAccount: new CallThrottle(LIMITS.deleteAccount.perMinute),
  registerAppleSignIn: new CallThrottle(LIMITS.registerAppleSignIn.perMinute),
  reportRecipe: new CallThrottle(LIMITS.reportRecipe.perMinute),
};

// --- Callables --------------------------------------------------------------------------------

export const verifyPurchase = onCall(
  {
    ...callable,
    consumeAppCheckToken: CONSUME_APP_CHECK_TOKENS,
    secrets: [appStorePrivateKey],
    timeoutSeconds: 60,
    maxInstances: LIMITS.verifyPurchase.maxInstances,
    concurrency: 1,
  },
  (request) =>
    guarded("verifyPurchase", () =>
      withCaller(callerOf(request, throttles.verifyPurchase, CONSUME_APP_CHECK_TOKENS), (d, c) => verifyCore(d, c, request.data))),
);

// One generation per instance at a time: maxInstances is the most generations in flight
// service-wide. Five instances at 1/6 vCPU, not one at 1 vCPU with concurrency 5: the work is
// waiting on the model, and 5 x USD 0.40 a day busy is below 1 x USD 2.13 (README "Function limits").
export const generateRecipe = onCall(
  {
    ...callable,
    consumeAppCheckToken: CONSUME_APP_CHECK_TOKENS,
    timeoutSeconds: GENERATE_TIMEOUT_SECONDS,
    maxInstances: LIMITS.generateRecipe.maxInstances,
    concurrency: 1,
  },
  (request) =>
    guarded("generateRecipe", () =>
      withCaller(callerOf(request, throttles.generateRecipe, CONSUME_APP_CHECK_TOKENS), (d, c) => generateCore(d, c, request.data))),
);

// Polling: App Check enforced but the token is NOT consumed (limited-use tokens are for the paid calls).
export const getGeneration = onCall(
  { ...callable, timeoutSeconds: 10, maxInstances: LIMITS.getGeneration.maxInstances, concurrency: 1 },
  (request) =>
    guarded("getGeneration", () =>
      withCaller(callerOf(request, throttles.getGeneration, false), (d, c) => getGenerationCore(d, c, request.data))),
);

// SIWA_PRIVATE_KEY: getBalance revokes a stored Apple token once Apple is unlinked (core/appleToken.ts).
export const getBalance = onCall(
  // 30 s, not 10: after an unlink it may also call Cloud KMS and Apple (up to 10 s each), and a
  // timeout between removing the token and revoking it would leave Apple's link in place.
  { ...callable, secrets: [siwaPrivateKey], timeoutSeconds: 30, maxInstances: LIMITS.getBalance.maxInstances, concurrency: 1 },
  (request) =>
    guarded("getBalance", () => withCaller(callerOf(request, throttles.getBalance, false), (d, c) => balanceCore(d, c))),
);

// The taste quiz's definition (CONTRACT.md `getTasteQuiz`): a signed-in caller (anonymous
// included), App Check enforced but not consumed, like getBalance. Reads only config/runtime
// (cached); no account document, no reservation, no model, nothing charged.
export const getTasteQuiz = onCall(
  { ...callable, timeoutSeconds: 10, maxInstances: LIMITS.getTasteQuiz.maxInstances, concurrency: 1 },
  (request) =>
    guarded("getTasteQuiz", () => withCaller(callerOf(request, throttles.getTasteQuiz, false), (d) => tasteQuizCore(d, request.data))),
);

// The welcome grant's offer (CONTRACT.md `getWelcomeOffer`): the one call the apps make before any
// sign-in or purchase. App Check enforced with an ordinary token (not consumed) and NO sign-in;
// throttled by client address and the instance ceiling; takes no input, reads only config/runtime
// and today's grant count (both cached per instance), and ignores and stores nothing about the
// caller (any auth it carries is not looked at).
export const getWelcomeOffer = onCall(
  { ...callable, timeoutSeconds: 10, maxInstances: LIMITS.getWelcomeOffer.maxInstances, concurrency: 1 },
  (request) =>
    guarded("getWelcomeOffer", () => {
      if (!request.app) throw new HttpsError("unauthenticated", "App Check required");
      const raw = request.rawRequest;
      throttles.getWelcomeOffer.admitAddress(clientIp(raw.headers, raw.socket?.remoteAddress), Date.now());
      return welcomeOfferCore(getDeps());
    }),
);

// The welcome grant itself: raises a balance without a store receipt, so App Check is enforced
// AND consumed (limited-use tokens, replays refused), like verifyPurchase.
export const claimWelcome = onCall(
  {
    ...callable,
    consumeAppCheckToken: CONSUME_APP_CHECK_TOKENS,
    timeoutSeconds: 30,
    maxInstances: LIMITS.claimWelcome.maxInstances,
    concurrency: 1,
  },
  (request) =>
    guarded("claimWelcome", () =>
      withCaller(callerOf(request, throttles.claimWelcome, CONSUME_APP_CHECK_TOKENS), (d, c) => claimWelcomeCore(d, c))),
);

export const mergeBalance = onCall(
  {
    ...callable,
    consumeAppCheckToken: CONSUME_APP_CHECK_TOKENS,
    secrets: [siwaPrivateKey],
    timeoutSeconds: 30,
    maxInstances: LIMITS.mergeBalance.maxInstances,
    concurrency: 1,
  },
  (request) =>
    guarded("mergeBalance", () =>
      mergeCore(getDeps(), callerOf(request, throttles.mergeBalance, CONSUME_APP_CHECK_TOKENS), request.data)),
);

export const deleteAccount = onCall(
  {
    ...callable,
    consumeAppCheckToken: CONSUME_APP_CHECK_TOKENS,
    secrets: [siwaPrivateKey],
    timeoutSeconds: 120,
    maxInstances: LIMITS.deleteAccount.maxInstances,
    concurrency: 1,
  },
  (request) =>
    guarded("deleteAccount", () =>
      deleteCore(getDeps(), callerOf(request, throttles.deleteAccount, CONSUME_APP_CHECK_TOKENS), request.data)),
);

// The iOS app sends the authorization code of each Apple sign-in or link; the server keeps the
// refresh token (encrypted) so deleteAccount can revoke it from either platform. App Check
// consumed: the call takes a single-use credential.
export const registerAppleSignIn = onCall(
  {
    ...callable,
    consumeAppCheckToken: CONSUME_APP_CHECK_TOKENS,
    secrets: [siwaPrivateKey],
    timeoutSeconds: 30,
    maxInstances: LIMITS.registerAppleSignIn.maxInstances,
    concurrency: 1,
  },
  (request) =>
    guarded("registerAppleSignIn", () =>
      withCaller(callerOf(request, throttles.registerAppleSignIn, CONSUME_APP_CHECK_TOKENS), (d, c) => registerAppleCore(d, c, request.data))),
);

// "Report this recipe" (CONTRACT.md `reportRecipe`): only results the server wrote, so a signed-in
// caller (anonymous included) naming one of its generations. App Check enforced AND consumed
// (each report takes a fresh limited-use token, so a script cannot replay one to fill the
// database); throttled per uid and address, then the per-account and per-day ceilings of
// config/runtime.reports.
export const reportRecipe = onCall(
  {
    ...callable,
    consumeAppCheckToken: CONSUME_APP_CHECK_TOKENS,
    timeoutSeconds: 30,
    maxInstances: LIMITS.reportRecipe.maxInstances,
    concurrency: 1,
  },
  (request) =>
    guarded("reportRecipe", () => {
      const caller = callerOf(request, throttles.reportRecipe, CONSUME_APP_CHECK_TOKENS);
      return reportCore(getDeps(), { uid: caller.uid }, request.data);
    }),
);

// --- Background -------------------------------------------------------------------------------

export const sweepReservations = onSchedule(
  { schedule: "every 5 minutes", timeoutSeconds: 300, maxInstances: 1, concurrency: 1, secrets: [appStorePrivateKey] },
  async () => {
    const result = await sweep(getDeps());
    if (result.settled || result.consumed || result.consumptionAnswers || result.failures) logger.info("Sweep", { ...result });
  },
);

export const pollPlayVoidedPurchases = onSchedule(
  { schedule: "every day 04:00", timeZone: "Etc/UTC", timeoutSeconds: 540, maxInstances: 1, concurrency: 1 },
  async () => {
    const result = await pollVoidedPurchases(getDeps());
    logger.info("Voided purchases poll", { ...result });
  },
);

// Prepaid providers' balances (README "Provider balances"): only providers with a threshold in
// config/runtime.providerBalanceAlerts and a balance probe are asked; none by default.
export const checkProviderBalances = onSchedule(
  { schedule: "41 * * * *", timeZone: "Etc/UTC", timeoutSeconds: 120, retryCount: 0, maxInstances: 1, concurrency: 1 },
  async () => {
    const config = await loadRuntimeConfig(getFirestore(), logger, imageDefaults(), textDefaults());
    if (Object.keys(config.providerBalanceAlerts).length === 0) return;
    await checkProviderBalancesCore({ providers: allProviders(), thresholds: config.providerBalanceAlerts, context: providerContext, logger });
  },
);

// Model lifecycle (README "Model lifecycle"): daily, the retirement dates of the models in use and
// only offered by a switch, the table's age, and each provider's free metadata probe. Best effort:
// it never touches a request, and a probe that fails is only an info line.
export const checkModelLifecycle = onSchedule(
  { schedule: "23 6 * * *", timeZone: "Etc/UTC", timeoutSeconds: 120, retryCount: 0, maxInstances: 1, concurrency: 1 },
  async () => {
    const config = await loadRuntimeConfig(getFirestore(), logger, imageDefaults(), textDefaults());
    // The output judge is always Gemini on Vertex AI (deps above).
    const judge = { provider: "google", model: judgeModel.value() || providerParam("GEMINI_MODEL") };
    const review = { provider: "google", model: promptReviewModel.value() || providerParam("GEMINI_MODEL") };
    const reports = await assessModels({ models: modelsInUse(config, judge, undefined, review), context: providerContext, now: () => Date.now(), probe: true });
    await logModelLifecycle(reports, { db: getFirestore(), logger, now: () => Date.now() });
  },
);

// The weekly prompt review (README "Weekly prompt review"): Mondays at 03:41 UTC, a quiet hour, on
// the ISO week before. Advice only: it never changes a prompt. Idempotent per week
// (promptReviews/{YYYY-Www}); off with config/runtime.promptReviewEnabled. One model call at most,
// its worst case reserved against the day's spend ceiling. No retry: a failure is logged
// (prompt_review_failed, alerted on) and `npm run admin -- judge review --run --apply` runs it again.
// The same job makes the weekly app report (README "Weekly app report", core/weeklyReport.ts; no
// scheduler job of its own: the project pays per job), side by side with the review and
// independent of it; off with config/runtime.weeklyReportEnabled. No model call. When both are
// stored, each is emailed to the owner once (core/reportEmail.ts, README "Report emails"), through
// the owner's Gmail with the two secrets above; missing secrets: the reports are still stored and
// report_email_failed (NOT_CONFIGURED) is logged and alerted on.
export const weeklyPromptReview = onSchedule(
  { schedule: "41 3 * * 1", timeZone: "Etc/UTC", timeoutSeconds: 300, retryCount: 0, maxInstances: 1, concurrency: 1, secrets: [reportSmtpPassword, reportEmailAddress] },
  async () => {
    const db = getFirestore();
    const now = () => Date.now();
    const config = () => loadRuntimeConfig(db, logger, imageDefaults(), textDefaults());
    const project = projectId();
    const week = lastCompleteWeek(now());
    await runWeeklyJobs(
      {
        report: () => runWeeklyReport({ db, logger, now, config, project }, { trigger: "schedule", week }),
        review: () => {
          // Always Gemini on Vertex AI, like the judge (the emulators: a fixed answer, no call).
          const reviewer = emulatorFakesEnabled()
            ? new EmulatorPromptReviewer()
            : new GeminiPromptReviewer({
                project,
                location: providerParam("VERTEX_LOCATION"),
                model: promptReviewModel.value() || providerParam("GEMINI_MODEL"),
                jailbreakFilter: providerParam("GEMINI_JAILBREAK_FILTER") === "true",
              });
          return runPromptReview({ db, reviewer, logger, now, config }, { trigger: "schedule", week });
        },
        emails: async () => {
          // Under the emulators the messages are only recorded; nothing is sent.
          const mailer = emulatorFakesEnabled() ? new RecordingMailer() : gmailMailerOf(secretValue(reportEmailAddress), secretValue(reportSmtpPassword));
          let enabled = { weeklyReportEnabled: true, promptReviewEnabled: true };
          try {
            const c = await config();
            enabled = { weeklyReportEnabled: c.weeklyReportEnabled, promptReviewEnabled: c.promptReviewEnabled };
          } catch {
            // Without the config, whatever was stored this week is still emailed.
          }
          return sendWeeklyEmails({ db, logger, now, project, mailer }, { week, trigger: "schedule", ...enabled });
        },
      },
      logger,
      { week, project },
    );
  },
);

// The owner's board, redrawn hourly into a private bucket (README "The board", "Always on").
// Never the default Firebase Storage bucket; refuses to write unless the bucket is private.
export const redrawBoard = onSchedule(
  { schedule: "17 * * * *", timeZone: "Etc/UTC", timeoutSeconds: 120, retryCount: 0, maxInstances: 1, concurrency: 1 },
  async () => {
    const project = process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT ?? "";
    const name = boardBucket.value() || `${project}-owner-board`;
    await redrawBoardCore({
      db: getFirestore(),
      bucket: new CloudStorageBoardBucket(getStorage().bucket(name)),
      project,
      now: () => Date.now(),
      logger,
    });
  },
);

// --- The owner console (README "Provider switches", "The owner console") ----------------------
//
// Four callables for the owner's web page on Firebase Hosting, never for the apps: the four provider
// switches and the runtime settings (core/ownerSettings.ts). A caller must be
// a Google sign-in (verified email) listed in config/owner; the ID token is checked again with
// revocation, a switch or a save needs a sign-in from the last 30 minutes (a sensitive save 5, and
// only within the console's envelope, core/ownerSettings.ts WebRule), and every refusal answers the same
// few fixed words. App Check is NOT enforced here: a web App Check provider (reCAPTCHA Enterprise)
// would prove a browser, not who is in it, and the owner allowlist is the guard; OwnerCallGate
// (calls without a token refused uncounted, a per-address limit under an instance ceiling, a listed
// owner in a bucket of their own) and one instance bound what anyone else can cost. CORS admits
// only the project's own Hosting origins.

function projectId(): string {
  if (process.env.GCLOUD_PROJECT) return process.env.GCLOUD_PROJECT;
  if (process.env.GOOGLE_CLOUD_PROJECT) return process.env.GOOGLE_CLOUD_PROJECT;
  try {
    return String((JSON.parse(process.env.FIREBASE_CONFIG ?? "{}") as { projectId?: unknown }).projectId ?? "");
  } catch {
    return "";
  }
}

let ownerEnvCache: OwnerEnv | undefined;
function ownerEnv(): OwnerEnv {
  if (ownerEnvCache) return ownerEnvCache;
  ownerEnvCache = {
    db: getFirestore(),
    auth: getAuth(),
    now: () => Date.now(),
    logger,
    imageDefaults: imageDefaults(),
    textDefaults: textDefaults(),
    // Readiness only: the key is read (as the adapter would) and dropped, never returned.
    secretReadable,
    // The hosted board's authenticated address (redrawBoard's bucket), for the console's link.
    boardUrl: boardBrowserUrl(boardBucket.value() || `${projectId()}-owner-board`),
  };
  return ownerEnvCache;
}

let ownerGateCache: OwnerCallGate | undefined;
const ownerGate = () => (ownerGateCache ??= new OwnerCallGate({ db: getFirestore(), logger, now: () => Date.now() }));

const ownerCallable = {
  enforceAppCheck: false,
  invoker: "public",
  cors: ownerConsoleOrigins(projectId(), process.env.FUNCTIONS_EMULATOR === "true"),
  timeoutSeconds: 30,
  maxInstances: 1,
  concurrency: 1,
} as const;

function ownerCall<T>(name: string, request: CallableRequest, fn: (env: OwnerEnv, authorization: string | undefined) => Promise<T>): Promise<T> {
  return guarded(name, () => ownerGate().run(name, request, (authorization) => fn(ownerEnv(), authorization)));
}

export const getProviderSwitches = onCall(ownerCallable, (request) =>
  ownerCall("getProviderSwitches", request, (env, authorization) => providerSwitchesCore(env, authorization)));

export const setProviderSwitch = onCall(ownerCallable, (request) =>
  ownerCall("setProviderSwitch", request, (env, authorization) => setProviderSwitchCore(env, authorization, request.data)));

export const getOwnerSettings = onCall(ownerCallable, (request) =>
  ownerCall("getOwnerSettings", request, (env, authorization) => ownerSettingsCore(env, authorization)));

export const setOwnerSettings = onCall(ownerCallable, (request) =>
  ownerCall("setOwnerSettings", request, (env, authorization) => setOwnerSettingsCore(env, authorization, request.data)));

function decodePubSub(data: string | undefined): unknown {
  if (!data) return null;
  try {
    return JSON.parse(Buffer.from(data, "base64").toString("utf8"));
  } catch {
    return null;
  }
}

// Only Google Play publishes to this topic (Pub/Sub IAM), so its messages are not attacker input.
export const playNotifications = onMessagePublished(
  { topic: playRtdnTopic, retry: true, maxInstances: 1, concurrency: 1 },
  async (event) => {
    const outcome = await handlePlayNotification(getDeps(), decodePubSub(event.data.message.data));
    logger.info("Play RTDN", { outcome: String(outcome).slice(0, 80), messageId: event.data.message.messageId });
  },
);

export const budgetKillSwitch = onMessagePublished({ topic: budgetTopic, maxInstances: 1, concurrency: 1 }, async (event) => {
  const d = getDeps();
  // Read fresh, not from the cache: this is the one place a stale config could matter.
  const config = await loadRuntimeConfig(d.db, d.logger);
  await handleBudgetAlert(d, decodePubSub(event.data.message.data), config.budgetKillFraction);
});

/** Body cap: the signedPayload cap plus the JSON wrapper. */
const APPLE_BODY_MAX_BYTES = APPLE_SIGNED_PAYLOAD_MAX + 1024;
/** Every rejection on this public, unauthenticated endpoint is logged through these, never per request. */
const rejectedLog = new LogSampler(60_000);
const failedLog = new LogSampler(60_000);

function logRejected(outcome: string): void {
  const sample = rejectedLog.take(Date.now());
  if (sample.log) logger.warn("appstore_notification_rejected", { outcome, suppressedSinceLast: sample.suppressed });
}

export const appStoreNotifications = onRequest(
  { invoker: "public", maxInstances: 1, concurrency: 1, timeoutSeconds: 30, secrets: [appStorePrivateKey] },
  async (req, res) => {
    if (req.method !== "POST") {
      logRejected("METHOD");
      res.status(405).send("POST only");
      return;
    }
    if ((req.rawBody?.length ?? 0) > APPLE_BODY_MAX_BYTES || !req.is("application/json")) {
      logRejected("BODY");
      res.status(400).send("rejected");
      return;
    }
    try {
      const result = await handleAppleNotification(getDeps(), req.body);
      if (result.httpStatus === 200) {
        // Only a signature-verified notification from Apple gets here.
        logger.info("appstore_notification", { outcome: result.outcome });
      } else {
        logRejected(result.outcome);
      }
      res.status(result.httpStatus).send(result.httpStatus === 200 ? "OK" : "rejected");
    } catch (e) {
      const sample = failedLog.take(Date.now());
      if (sample.log) {
        logger.error("appstore_notification_failed", { error: String(e).slice(0, 200), suppressedSinceLast: sample.suppressed });
      }
      res.status(500).send("error");
    }
  },
);
