/** Firestore document shapes, paths and small helpers. Only the Admin SDK touches these documents. */
import { createHash } from "node:crypto";
import type { DocumentReference, Firestore, Timestamp, Transaction } from "firebase-admin/firestore";
import { fail } from "./contract";
import {
  CONFIG_CACHE_MS,
  COST_MAX,
  DEFAULT_COSTS,
  DEFAULT_IMAGE_SELECTION,
  DEFAULT_MENU_DAYS,
  MAX_STEP_IMAGES_LIMIT,
  DEFAULT_MENU_SETS,
  DEFAULT_PRICES,
  DEFAULT_TEXT_SELECTION,
  HOME_WORKER,
  JUDGE_PRICES,
  MENU_COURSES,
  MENU_DAYS,
  PURCHASE_TOKEN_NAMESPACE,
  costsOf,
  type CostKey,
  type Costs,
  type GenerationKind,
  type ImageLevel,
  type ImageProvider,
  type MenuCourses,
  type MenuDays,
  type MenuSetCosts,
} from "./constants";
import type { FailureClass, ImageTarget, LlmUsage, Logger, SealedSecret, TextTarget } from "./ports";
import { defaultImagePrices, imageProviderIds, textDefaultPrices, textProviderById } from "./providers/registry";
import type { TokenPrices } from "./providers/types";
import type { Menu, Recipe } from "./contract";

export interface UserDoc {
  balance: number;
  inFlight: number;
  purchasedCredits: number;
  consumedCredits: number;
  refundedCredits: number;
  /** Rate-limit windows: generations started in the current minute and UTC day. */
  minuteWindow?: { start: number; count: number };
  dayWindow?: { day: string; count: number };
  /** verifyPurchase calls in the current minute. */
  verifyWindow?: { start: number; count: number };
  /** Input-caused failures refunded over the account's lifetime (see paidAllowance). */
  contentRefundsUsed?: number;
  /** Unknown-usage infrastructure failures (timeouts) refunded over the account's lifetime (see paidAllowance). */
  timeoutRefundsUsed?: number;
  /**
   * Buyer keys (`buyers/{key}`) of every purchase granted to this account or to accounts merged into
   * it; a blocked one sets `purchasesBlocked` in responses.
   */
  buyerKeys?: string[];
  /** Infrastructure failures refunded in the current UTC day (ceiling: infraRefundsPerDay). */
  infraRefunds?: { day: string; count: number };
  /**
   * Requests refused in the current UTC day as likely prompt injection: suspicious input names,
   * answers that failed a suspicious output rule, model safety blocks, judge rejections. At
   * `rejectedPromptsPerDay`, generation is refused (USER_DAILY_LIMIT) until the next UTC day.
   */
  promptRejections?: { day: string; count: number };
  /** Estimated LLM spend in micro-USD in the current UTC day: worst case while in flight, actual after. */
  spendWindow?: { day: string; microUsd: number };
  mergedInto?: string;
  mergedAt?: Timestamp;
  deletedAt?: Timestamp;
  /**
   * Free recipes this account (and every account merged into it) was given by the welcome grant
   * (claimWelcome), lifetime. Never part of `purchasedCredits`: they are not purchases, so they
   * raise no refund allowance (paidAllowance); the FIFO ledger leaves out those charged
   * (`welcomeSpent`), which the charges after the grant spend first.
   */
  welcomeCredits?: number;
  /** When this account's welcome grant was made (claimWelcome); set once, kept on the tombstone. */
  welcomeAt?: Timestamp;
  /**
   * Free recipes granted and not yet charged. Every charge after the grant (generation settle)
   * takes from here first and moves it to `welcomeSpent`; credits charged before the grant never
   * do. Both follow merges (summed).
   */
  welcomeUnspent?: number;
  /** Free recipes charged so far (lifetime, following merges): left out of the FIFO ledger. */
  welcomeSpent?: number;
  /**
   * The sign-in providers ("google.com", "apple.com") whose identities this claimed account has
   * recorded in `welcomes/`: when an ID token names another, getBalance records it.
   */
  welcomeProviders?: string[];
  /** Reports (reportRecipe) sent by this account in the current UTC day (ceiling: reports.perUserPerDay). */
  reportWindow?: { day: string; count: number };
  /** Balance that was lost when the account was deleted. */
  forfeitedBalance?: number;
  /** When the Auth user's displayName and photoURL were last cleared (scrubProfile). */
  profileScrubbedAt?: Timestamp;
  /** Set when this account's negative balance was moved to another account of the same buyer. */
  debtMovedTo?: string;
  debtMovedAt?: Timestamp;
  createdAt?: Timestamp;
  /**
   * The Sign in with Apple refresh token from the account's last Apple sign-in on an iPhone
   * (registerAppleSignIn), envelope-encrypted (adapters/envelope.ts) with the uid bound as
   * additional authenticated data. Kept only to revoke Apple's link when the account is deleted
   * from either platform; removed (and revoked) on deletion, when Apple is unlinked, and from
   * every tombstone. Never returned to a client, never logged.
   */
  appleToken?: StoredAppleToken;
}

export interface StoredAppleToken {
  sealed: SealedSecret;
  storedAt: Timestamp;
}

export type PurchaseState = "granted" | "consumed" | "refunded";

export interface PurchaseDoc {
  store: "play" | "appstore";
  /** Owner. null for a purchase never submitted (refund seen first) or whose account was deleted. */
  uid: string | null;
  /** The owner before deleteAccount nulled `uid`; keeps the FIFO ledger and buyer attribution. */
  formerUid?: string;
  env: "production" | "sandbox";
  productId: string | null;
  quantity: number;
  /** Credits the purchase is worth: PRODUCT_CREDITS[productId] * quantity. */
  credits: number;
  /** Credits taken back by refunds so far (partial refunds make this less than `credits`). */
  refundedCredits: number;
  state: PurchaseState;
  /** Set when the credits were added to a balance. A doc without it was never redeemed. */
  grantedAt: Timestamp | null;
  /** Play only: set once the server consumed the purchase. */
  consumedAt?: Timestamp | null;
  /** Play only, kept until consumed so the sweeper can retry the consume. */
  purchaseToken?: string;
  orderId?: string | null;
  transactionId?: string;
  /** Store event time (ms) of the last refund/reversal applied; older events are ignored. */
  refundEventAt?: number;
  consumptionRequestedAt?: Timestamp;
  consumeAttempts?: number;
  /** Play only: true while a server-side consume is still owed (the sweeper's work list). */
  consumePending?: boolean;
  /** Set when the sweeper stopped retrying the consume after CONSUME_MAX_ATTEMPTS. */
  consumeGaveUpAt?: Timestamp;
  /** The purchase account token the store reported (obfuscatedExternalAccountId / appAccountToken). */
  accountToken?: string;
  /** buyers/{key} documents this purchase counts under (see buyerKeysFor in core/buyers.ts). */
  buyerKeys?: string[];
  /** iOS: the buyer agreed that consumption data may be sent to Apple if they ask for a refund. */
  consumptionConsent?: boolean;
  consumptionConsentAt?: Timestamp;
  /** The CONSUMPTION_REQUEST being answered (or last answered); see core/consumption.ts. */
  consumption?: ConsumptionState;
  /** True while a consumption answer is owed (the sweeper retries until `consumption.deadlineAt`). */
  consumptionPending?: boolean;
  /** Set while a refund of this purchase takes credits that had been spent (FIFO, core/buyers.ts). */
  refundAfterUse?: boolean;
}

export interface ConsumptionState {
  notificationUUID: string;
  requestedAt: Timestamp;
  deadlineAt: Timestamp;
  attempts: number;
  answeredAt?: Timestamp;
  /** What was (or will be) sent, for the record. */
  consumptionPercentage?: number;
  /** Fixed reason code of the last failure. */
  lastError?: string;
  gaveUp?: boolean;
}

/**
 * buyers/{key}: refund-after-use events of one buyer. Never deleted. Holds no personal data: the key
 * is an Apple appTransactionId, a Firebase uid, or a hash of a sign-in provider's user id.
 */
export interface BuyerDoc {
  /** Active refund-after-use events, by purchase id. */
  events: Record<string, { at: Timestamp; store: "play" | "appstore"; spentCreditsTaken: number; active: boolean }>;
  refundAfterUseCount: number;
  /** Written by the policy, unless `blockedOverride` is true: then it is the owner's decision. */
  blocked: boolean;
  blockedOverride?: boolean;
  blockedAt?: Timestamp;
  createdAt: Timestamp;
}

/**
 * welcomes/{key}: one welcome grant per sign-in identity, ever (claimWelcome). Never deleted (not
 * by deleteAccount, not by mergeBalance, not by the owner's deletion command): it is the only
 * guard against granting twice. The key is the identity's buyer key (`providerBuyerKey`, a hash
 * of the provider's own user id); no email, name or token is kept.
 */
export interface WelcomeDoc {
  /** The account that claimed (or, for `credits: 0`, the account that already had). */
  uid: string;
  /** "google.com" or "apple.com". */
  provider: string;
  /** Free recipes granted by this claim; 0 on an identity recorded because its account had already claimed. */
  credits: number;
  at: Timestamp;
}

export type GenerationState = "pending" | "done" | "failed";

export interface GenerationDoc {
  state: GenerationState;
  kind: GenerationKind;
  courses?: MenuCourses;
  /**
   * A menu's days, on every menu reserved since menu sets; absent on a recipe and on a menu from
   * before them, which was a DEFAULT_MENU_DAYS (3-day) menu.
   */
  days?: MenuDays;
  /** Credits charged by the reservation; settle and refund use this, never the current table. */
  cost: number;
  /** sha256 of the validated request (inputHashOf): a requestId is bound to its inputs. */
  inputHash?: string;
  /** Worst-case estimated cost reserved in stats/{statsDay} and in the user's spend window. */
  reservedMicroUsd?: number;
  /** The UTC day (YYYY-MM-DD) the reservation was counted in, so settle corrects that same day. */
  statsDay?: string;
  /** Usage of the LLM call, recorded at settle (absent: unknown, e.g. settled by the sweeper). */
  usage?: LlmUsage;
  /** Estimated cost recorded at settle (the reserved worst case when usage is unknown). */
  actualMicroUsd?: number;
  createdAt: Timestamp;
  deadlineAt: Timestamp;
  /** Whether the reserved credits stayed spent. */
  charged?: boolean;
  failureClass?: FailureClass;
  failureReason?: string;
  recipe?: Recipe;
  menu?: Menu;
  /** Set on terminal states only; Firestore TTL deletes the doc after it. */
  expiresAt?: Timestamp;
  /** A recipe asked with pictures: the level asked (`image`); absent on every other generation. */
  imageLevel?: ImageLevel;
  /**
   * The dish picture's share of `cost` (recipeImage minus recipe, from the table at reservation
   * time): given back when the recipe is delivered without the dish's picture. Never read from
   * the current table.
   */
  imageCost?: number;
  /**
   * Level "steps": the step pictures' share of `cost` (recipeImageSteps minus recipeImage, at
   * reservation): given back when not one step picture is delivered.
   */
  stepImagesCost?: number;
  /** The step pictures asked at most (`image.maxStepImages` at reservation); level "steps" only. */
  stepImagesMax?: number;
  /** The pictures' worst-case spend (1 + stepImagesMax pictures), part of `reservedMicroUsd`. */
  imageReservedMicroUsd?: number;
  /** The provider and model the picture was asked of (no prompt, no brief). */
  imageProvider?: string;
  imageModel?: string;
  /** The text provider that answered, when it was not the deploy default or a fallback was asked. */
  textProvider?: string;
  /** Set when the recipe was delivered with its picture, which is in imageRef. */
  imageStored?: true;
  /** Set when the recipe was delivered without its picture (and imageCost given back). */
  imageFailed?: true;
  /** Fixed reason code of a picture that failed (never provider text). */
  imageFailure?: string;
  /** Level "steps": how many steps the model marked (after the brief checks, at most stepImagesMax). */
  stepImagesMarked?: number;
  /** Level "steps": the 0-based step indices whose pictures were delivered (in recipeImages/step-<i>). */
  stepImagesStored?: number[];
  /** Level "steps": set when some marked step's picture was not delivered (stepImagesCost is given back only when none was). */
  stepImagesFailed?: true;
}

/** The document id of a generation's picture: the dish's, or a step's (0-based index). */
export const imageDocId = (step?: number) => (step === undefined ? "main" : `step-${step}`);

/**
 * The delivered picture of a generation, in its own document so that the generation document,
 * the judge's input and every read of a generation stay small: users/{uid}/generations/{id}/
 * recipeImages/main. Same `expiresAt` as its generation (the TTL policy on `recipeImages`
 * deletes it); deleteAccount's recursive delete of `generations` removes it too.
 */
export interface GenerationImageDoc {
  mimeType: "image/jpeg";
  data: Buffer;
  width: number;
  height: number;
  expiresAt: Timestamp;
}

export interface Prices {
  inputUsdPerMTok: number;
  outputUsdPerMTok: number;
  /**
   * USD per delivered picture, by "provider/model" (the adapters' prices, providers/registry.ts
   * defaultImagePrices, plus `config/runtime.prices.imageUsdPerImage`, which adds to or overrides them).
   */
  imageUsdPerImage?: Readonly<Record<string, number>>;
  /**
   * Text prices by "provider/model" for providers other than "google" (whose prices are the two
   * fields above), over the adapters' own tables. Present only when config/runtime sets it.
   */
  textUsdPerMTok?: Readonly<Record<string, TokenPrices>>;
}

/**
 * Which provider and model write the recipe text (README "Adding a provider"):
 * `config/runtime.text` over the TEXT_PROVIDER / GEMINI_MODEL parameters over
 * DEFAULT_TEXT_SELECTION. `fallbackProvider` / `fallbackModel` ("" for none) are asked once
 * when the model failed before writing anything for a reason about the provider (capacity, a
 * refused credential, out of credit), never after a safety refusal or a content failure.
 */
export interface TextSelection {
  provider: string;
  model: string;
  fallbackProvider: string;
  fallbackModel: string;
}

/** Which provider and model make a recipe's picture (README "Recipe pictures", "Switching"). */
export interface ImageSelection {
  provider: ImageProvider;
  model: string;
  /**
   * The fallback's provider, when it is not `provider` (absent: the same provider). Lets a
   * picture fall back across providers, e.g. FLUX to Vertex AI (the picture fallback switch).
   */
  fallbackProvider?: string;
  /**
   * The model at the same provider asked once when `model` could not make a picture for a reason
   * about the model or capacity (core/recipeImage.ts makeRecipeImage); "" for none. A fallback
   * without a price is dropped (parseRuntimeConfig).
   */
  fallbackModel: string;
  /** Step pictures beside the dish's at most (`image: "steps"`); 0 turns that level off. */
  maxStepImages: number;
}

export interface RuntimeConfig {
  generationEnabled: boolean;
  allowSandbox: boolean;
  /** Credits' worth of generations (each counts its cost) allowed per UTC day across all users. */
  dailyCap: number;
  /** Sandbox / TestFlight / licence-tester purchases that may grant credits per UTC day. */
  sandboxDailyCap: number;
  /** Credits sandbox purchases may grant per UTC day across all users (one recipes_100 x 10 is 1000). */
  sandboxDailyCredits: number;
  perUserPerMinute: number;
  perUserPerDay: number;
  verifyPerMinute: number;
  /** Refunded infrastructure failures per user per UTC day; at the ceiling, generation is refused. */
  infraRefundsPerDay: number;
  /**
   * The daily spend ceiling (all users, micro-USD; at it generation is paused) is
   * min(dailySpendMaxMicroUsd, dailySpendBaseMicroUsd + dailySpendRevenueFraction x
   * netMicroUsdPerCredit x production credits granted, net of refunds, over the last 7 UTC days).
   */
  dailySpendBaseMicroUsd: number;
  dailySpendMaxMicroUsd: number;
  dailySpendRevenueFraction: number;
  /** Conservative estimate of what one production credit nets after store fees and VAT, in micro-USD. */
  netMicroUsdPerCredit: number;
  /** Estimated LLM spend per user per UTC day, in micro-USD. */
  perUserDailySpendMicroUsd: number;
  /** Refund-after-use events that block a buyer (purchasesBlocked). */
  refundAbuseThreshold: number;
  /** Sent to Apple as sampleContentProvided: the pack sheet explains what a credit buys before purchase. */
  consumptionSampleContentProvided: boolean;
  /** The kill switch flips when a budget alert reports cost >= budget * this. */
  budgetKillFraction: number;
  /** Prompt-injection rejections per user per UTC day after which generation is refused. */
  rejectedPromptsPerDay: number;
  /**
   * The second check on every validated answer: "off" (default), "shadow" (run and log the
   * verdict, never block) or "enforce" (a verdict other than ok is a content failure).
   */
  outputJudge: OutputJudgeMode;
  /**
   * Keep the judged text (adapters/gemini.ts judgeText: the generated fields only) with each judge
   * answer in `judgeAnswers` (core/judgeLog.ts). Off by default: the privacy policy says a result
   * is kept 7 days, and these are kept 90; turn it on only with a policy line saying so.
   */
  outputJudgeKeepText: boolean;
  /**
   * The weekly prompt review (core/promptReview.ts): on by default. Off, the Monday run reads and
   * spends nothing. Invalid reads as `false` (nothing spent).
   */
  promptReviewEnabled: boolean;
  /**
   * The weekly app report (core/weeklyReport.ts): on by default. Off, the Monday run reads and
   * writes nothing for it and no report email is sent. Invalid reads as `false`.
   */
  weeklyReportEnabled: boolean;
  /**
   * The table in force. Its `recipeImage` is left out when pictures cannot be made:
   * `recipeImage: 0`, the provider "off", or no price for the selected provider and model.
   */
  costs: Costs;
  prices: Prices;
  /**
   * The picture's provider and model: `config/runtime.image` over the IMAGE_PROVIDER and
   * IMAGE_MODEL parameters over DEFAULT_IMAGE_SELECTION. An invalid `image` turns pictures off.
   */
  image: ImageSelection;
  /** The recipe text's provider and model (TextSelection). */
  text: TextSelection;
  /**
   * The deploy defaults (parameters over DEFAULT_TEXT_SELECTION / DEFAULT_IMAGE_SELECTION), where an
   * out-of-credit failure goes when no fallback is named; `image` null when pictures are off by default.
   */
  providerDefaults: { text: TextTarget; image: ImageTarget | null };
  /**
   * Balance thresholds by provider id, in the provider's own unit (`config/runtime.providerBalanceAlerts`):
   * the scheduled checkProviderBalances logs `provider_balance_low` under them. Empty: no probe runs.
   */
  providerBalanceAlerts: Readonly<Record<string, number>>;
  /**
   * "Report this recipe" (reportRecipe, README "Reports"): at most `perUserPerDay` reports per
   * signed-in account and `perDay` across the service per UTC day; each report is kept
   * `keepDays` days (TTL).
   */
  reports: ReportsConfig;
  /**
   * The taste quiz's tuning without a deploy (README "Taste profile quiz"): `hidden` is a list of
   * "<step>" or "<step>:<option>" ids left out of getTasteQuiz and dropped from requests. Nothing
   * else of the quiz is configurable here.
   */
  tasteQuiz: { hidden: string[] };
  /**
   * The welcome grant (claimWelcome, README "Welcome grant"): `recipes` free recipes once per
   * sign-in identity (0 turns the feature off), at most `perDay` grants per UTC day across all
   * accounts (the brake on farming).
   */
  welcome: WelcomeConfig;
  /**
   * The home worker (README "Home worker"; constants.ts HOME_WORKER): `testers` (uids) and
   * `emails` (verified emails) are the owner's own accounts whose recipe text is asked of the
   * server on the owner's computer at `url` first,
   * while `enabled`; `waitMs` how long a call waits for it before the normal provider writes the
   * text. Missing or invalid, or no `url`: off (no uid is ever routed there).
   */
  homeWorker: HomeWorkerConfig;
}

export type HomeWaitKind = "recipe" | "menu" | "recipeImage";
export const HOME_WAIT_KINDS: readonly HomeWaitKind[] = ["recipe", "menu", "recipeImage"];

export interface HomeWorkerConfig {
  enabled: boolean;
  /** The owner's own accounts by uid (an anonymous account has no email). */
  testers: string[];
  /** The owner's own accounts by verified email: lower-cased, trimmed. Never logged. */
  emails: string[];
  /** How long a request waits for the server: a recipe, a menu, a recipe with pictures. */
  waitMs: Readonly<Record<HomeWaitKind, number>>;
  /** The server's https base address (the owner's tunnel), "" for none. Never logged. */
  url: string;
}

/**
 * An https base address the home worker may be called at: no credentials, query or fragment, at
 * most HOME_WORKER.maxUrlChars; normalised without a trailing slash. null otherwise.
 */
export function homeWorkerUrlOf(v: unknown): string | null {
  if (typeof v !== "string" || v.length === 0 || v.length > HOME_WORKER.maxUrlChars) return null;
  let u: URL;
  try {
    u = new URL(v);
  } catch {
    return null;
  }
  if (u.protocol !== "https:" || u.username || u.password || u.search || u.hash || !u.hostname) return null;
  return `${u.origin}${u.pathname.replace(/\/+$/, "")}`;
}

/** A Firebase Auth uid as the tester list may hold it. */
export const TESTER_UID = /^[A-Za-z0-9_-]{1,128}$/;

/**
 * A plausible email address as the tester list holds it: trimmed and lower-cased, at most 254
 * characters, one @ with up to 64 characters before it and a dotted domain after it, no spaces.
 * null otherwise. (Plausible only: a match also needs the ID token to say the address is verified.)
 */
export function testerEmailOf(v: unknown): string | null {
  if (typeof v !== "string") return null;
  const e = v.trim().toLowerCase();
  if (e.length > 254 || !/^[^\s@]{1,64}@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/.test(e)) return null;
  return e;
}

/** What a missing or invalid `homeWorker` reads as: off, nobody listed. */
export const HOME_WORKER_OFF: Readonly<HomeWorkerConfig> = { enabled: false, testers: [], emails: [], waitMs: HOME_WORKER.waitMs, url: "" };

/**
 * `homeWorker`: `enabled` a boolean (default false), `testers` at most HOME_WORKER.maxTesters
 * distinct uids, `emails` at most HOME_WORKER.maxEmails distinct plausible addresses
 * (testerEmailOf; read trimmed and lower-cased), `waitMs` an optional map whose `recipe` / `menu`
 * / `recipeImage` (each optional) are integers in HOME_WORKER.minWaitMs to maxWaitMs, `url`
 * absent, "" or an https address (homeWorkerUrlOf). Anything else is refused as a whole (the
 * caller turns it off).
 */
export function validHomeWorker(v: unknown): HomeWorkerConfig | null {
  if (!isObj(v)) return null;
  const enabled = v.enabled === undefined ? false : v.enabled;
  if (typeof enabled !== "boolean") return null;
  const testers = v.testers === undefined ? [] : v.testers;
  if (!Array.isArray(testers) || testers.length > HOME_WORKER.maxTesters) return null;
  if (!testers.every((t) => typeof t === "string" && TESTER_UID.test(t)) || new Set(testers).size !== testers.length) return null;
  const rawEmails = v.emails === undefined ? [] : v.emails;
  if (!Array.isArray(rawEmails) || rawEmails.length > HOME_WORKER.maxEmails) return null;
  const emails = rawEmails.map(testerEmailOf);
  if (emails.some((e) => e === null) || new Set(emails).size !== emails.length) return null;
  const waits = v.waitMs === undefined ? {} : v.waitMs;
  if (!isObj(waits)) return null;
  const waitMs: Record<HomeWaitKind, number> = { ...HOME_WORKER.waitMs };
  for (const kind of HOME_WAIT_KINDS) {
    const n = waits[kind];
    if (n === undefined) continue;
    if (!isCount(n) || n < HOME_WORKER.minWaitMs || n > HOME_WORKER.maxWaitMs[kind]) return null;
    waitMs[kind] = n;
  }
  let url = "";
  if (v.url !== undefined && v.url !== "") {
    const u = homeWorkerUrlOf(v.url);
    if (u === null) return null;
    url = u;
  }
  return { enabled, testers: [...(testers as string[])], emails: emails as string[], waitMs, url };
}

export interface ReportsConfig {
  perUserPerDay: number;
  perDay: number;
  keepDays: number;
}

/** `reports`: each field optional (its default); out of range refuses the whole map (defaults). */
export function validReports(v: unknown): ReportsConfig | null {
  if (!isObj(v)) return null;
  const d = DEFAULT_RUNTIME_CONFIG.reports;
  const get = (k: keyof ReportsConfig, min: number, max: number) => {
    const n = v[k] === undefined ? d[k] : v[k];
    return isCount(n) && n >= min && n <= max ? n : null;
  };
  const perUserPerDay = get("perUserPerDay", 0, 1000);
  const perDay = get("perDay", 0, 100_000);
  const keepDays = get("keepDays", 1, 400);
  if (perUserPerDay === null || perDay === null || keepDays === null) return null;
  return { perUserPerDay, perDay, keepDays };
}

export interface WelcomeConfig {
  recipes: number;
  perDay: number;
}

/** Bounds of `config/runtime.welcome`. */
export const WELCOME_RECIPES_MAX = 20;
export const WELCOME_PER_DAY_MAX = 10_000;
/** What an invalid `welcome` reads as: off (never fail open). */
export const WELCOME_OFF: Readonly<WelcomeConfig> = { recipes: 0, perDay: 0 };

export type OutputJudgeMode = "off" | "shadow" | "enforce";
export const OUTPUT_JUDGE_MODES: readonly OutputJudgeMode[] = ["off", "shadow", "enforce"];

export const DEFAULT_RUNTIME_CONFIG: Readonly<RuntimeConfig> = {
  generationEnabled: true,
  allowSandbox: false,
  dailyCap: 1000,
  sandboxDailyCap: 20,
  sandboxDailyCredits: 200,
  perUserPerMinute: 3,
  perUserPerDay: 40,
  verifyPerMinute: 10,
  infraRefundsPerDay: 3,
  // See README "Spend ceilings" for the arithmetic: at most USD 2.00 a day (USD 60 a month) of
  // estimated LLM spend, USD 1.20 a day without sales, USD 0.45 per user (2 three-course menus).
  dailySpendBaseMicroUsd: 1_200_000,
  dailySpendMaxMicroUsd: 2_000_000,
  dailySpendRevenueFraction: 0.25,
  netMicroUsdPerCredit: 120_000,
  perUserDailySpendMicroUsd: 450_000,
  refundAbuseThreshold: 2,
  consumptionSampleContentProvided: true,
  budgetKillFraction: 1,
  rejectedPromptsPerDay: 5,
  outputJudge: "off",
  outputJudgeKeepText: false,
  promptReviewEnabled: true,
  weeklyReportEnabled: true,
  costs: DEFAULT_COSTS,
  prices: { ...DEFAULT_PRICES, imageUsdPerImage: defaultImagePrices() },
  image: { ...DEFAULT_IMAGE_SELECTION },
  text: { ...DEFAULT_TEXT_SELECTION },
  providerDefaults: {
    text: { provider: DEFAULT_TEXT_SELECTION.provider, model: DEFAULT_TEXT_SELECTION.model },
    image: { provider: DEFAULT_IMAGE_SELECTION.provider, model: DEFAULT_IMAGE_SELECTION.model },
  },
  providerBalanceAlerts: {},
  reports: { perUserPerDay: 10, perDay: 500, keepDays: 90 },
  tasteQuiz: { hidden: [] },
  welcome: { recipes: 3, perDay: 200 },
  homeWorker: HOME_WORKER_OFF,
};

const COUNT_FIELDS = [
  "dailyCap",
  "sandboxDailyCap",
  "sandboxDailyCredits",
  "perUserPerMinute",
  "perUserPerDay",
  "verifyPerMinute",
  "infraRefundsPerDay",
  "dailySpendBaseMicroUsd",
  "dailySpendMaxMicroUsd",
  "netMicroUsdPerCredit",
  "perUserDailySpendMicroUsd",
  "rejectedPromptsPerDay",
] as const;

const isCount = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v) && v >= 0;
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

const isCost = (n: unknown): n is number => typeof n === "number" && Number.isInteger(n) && n >= 1 && n <= COST_MAX;

/**
 * A costs override, or null when any part of it is invalid (then the whole default table applies).
 * `recipe`, `menu2` and `menu3` are required, as before menu sets: `menu2` / `menu3` are the
 * 3-day 2- and 3-course prices and their only source. `menuSets` is optional; each row ("1" to
 * "4" days) may give any of its cells ("1" to "3" courses), except that the 3-day row's "2" and
 * "3" are accepted only when they repeat `menu2` / `menu3` exactly, so a stored table cannot say
 * two prices for one menu. A missing cell keeps the default table's step from the 3-day row (one
 * credit a day or a course), held within [recipe, COST_MAX]; so does the 3-day 1-course cell,
 * from `menu2`. Whatever the source, more days or more courses are never cheaper, and no menu is
 * cheaper than a recipe.
 */
export function validCosts(v: unknown): Costs | null {
  if (!isObj(v)) return null;
  if (!isCost(v.recipe) || !isCost(v.menu2) || !isCost(v.menu3)) return null;
  const recipe = v.recipe;
  // `recipeImage` (optional): absent or null is twice `recipe` (at most COST_MAX), 0 turns
  // pictures off, anything else must be a cost of at least `recipe`: a picture never makes a
  // recipe cheaper. `recipeImageSteps` likewise: absent or null is three times `recipe` (at
  // least `recipeImage`, at most COST_MAX), 0 turns that level off, anything else must be at least
  // `recipeImage`; without `recipeImage` there is no steps level either.
  let recipeImage: number | undefined;
  if (v.recipeImage === undefined || v.recipeImage === null) recipeImage = Math.min(COST_MAX, recipe * 2);
  else if (v.recipeImage === 0) recipeImage = undefined;
  else if (isCost(v.recipeImage) && v.recipeImage >= recipe) recipeImage = v.recipeImage;
  else return null;
  let recipeImageSteps: number | undefined;
  if (v.recipeImageSteps === undefined || v.recipeImageSteps === null) {
    recipeImageSteps = recipeImage === undefined ? undefined : Math.min(COST_MAX, Math.max(recipeImage, recipe * 3));
  } else if (v.recipeImageSteps === 0) recipeImageSteps = undefined;
  else if (isCost(v.recipeImageSteps) && v.recipeImageSteps >= (recipeImage ?? recipe)) recipeImageSteps = v.recipeImageSteps;
  else return null;
  const sets = v.menuSets === undefined || v.menuSets === null ? {} : v.menuSets;
  if (!isObj(sets)) return null;
  const dayKeys = MENU_DAYS.map((d) => `${d}`);
  const courseKeys = MENU_COURSES.map((c) => `${c}`);
  if (Object.keys(sets).some((k) => !dayKeys.includes(k))) return null;
  const given: Record<string, Record<string, number>> = {};
  for (const d of dayKeys) {
    const row = sets[d];
    if (row === undefined || row === null) continue;
    if (!isObj(row)) return null;
    for (const [c, n] of Object.entries(row)) {
      if (!courseKeys.includes(c)) return null;
      if (n === undefined || n === null) continue;
      if (!isCost(n)) return null;
      (given[d] ??= {})[c] = n;
    }
  }
  const base = `${DEFAULT_MENU_DAYS}` as const;
  const derive = (from: number, d: `${MenuDays}`, c: `${MenuCourses}`, fromD: `${MenuDays}`, fromC: `${MenuCourses}`) =>
    Math.min(COST_MAX, Math.max(recipe, from + DEFAULT_MENU_SETS[d][c] - DEFAULT_MENU_SETS[fromD][fromC]));
  const g3 = given[base] ?? {};
  if ((g3["2"] !== undefined && g3["2"] !== v.menu2) || (g3["3"] !== undefined && g3["3"] !== v.menu3)) return null;
  const three: Record<`${MenuCourses}`, number> = {
    "1": g3["1"] ?? derive(v.menu2, base, "1", base, "2"),
    "2": v.menu2,
    "3": v.menu3,
  };
  const menuSets = Object.fromEntries(MENU_DAYS.map((d) => {
    const dk = `${d}` as `${MenuDays}`;
    if (dk === base) return [dk, three];
    return [dk, Object.fromEntries(MENU_COURSES.map((c) => {
      const ck = `${c}` as `${MenuCourses}`;
      return [ck, given[dk]?.[ck] ?? derive(three[ck], dk, ck, base, ck)];
    }))];
  })) as MenuSetCosts;
  for (let i = 0; i < MENU_DAYS.length; i++) {
    const row = menuSets[`${MENU_DAYS[i]}`];
    for (let j = 0; j < MENU_COURSES.length; j++) {
      const ck = `${MENU_COURSES[j]}` as `${MenuCourses}`;
      if (row[ck] < (j === 0 ? recipe : row[`${MENU_COURSES[j - 1]}` as `${MenuCourses}`])) return null;
      if (i > 0 && row[ck] < menuSets[`${MENU_DAYS[i - 1]}`][ck]) return null;
    }
  }
  return costsOf(recipe, menuSets, recipeImage, recipeImageSteps);
}

/** A table without `recipeImage` and `recipeImageSteps` (pictures off). */
export function withoutImage(costs: Costs): Costs {
  if (costs.recipeImage === undefined && costs.recipeImageSteps === undefined) return costs;
  return costsOf(costs.recipe, costs.menuSets);
}

/** A table without `recipeImageSteps` (step pictures off). */
export function withoutStepImages(costs: Costs): Costs {
  if (costs.recipeImageSteps === undefined) return costs;
  return costsOf(costs.recipe, costs.menuSets, costs.recipeImage);
}

const IMAGE_KEY = /^[a-z][a-z0-9]{1,19}\/[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/;
const IMAGE_MODEL = /^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/;

/** `imageUsdPerImage`: "provider/model" keys, each a price from 0.001 to 2 USD; null when invalid. */
function validImagePrices(v: unknown): Record<string, number> | null {
  if (!isObj(v) || Object.keys(v).length > 50) return null;
  const out: Record<string, number> = {};
  for (const [k, n] of Object.entries(v)) {
    if (!IMAGE_KEY.test(k) || typeof n !== "number" || !Number.isFinite(n) || n < 0.001 || n > 2) return null;
    out[k] = n;
  }
  return out;
}

/**
 * `config/runtime.image`: { provider?, model?, fallbackModel?, maxStepImages? }, each optional
 * over `base` (`fallbackModel` "" or null for none); null when invalid.
 */
export function validImageSelection(v: unknown, base: ImageSelection): ImageSelection | null {
  if (!isObj(v)) return null;
  const provider = v.provider === undefined ? base.provider : v.provider;
  const model = v.model === undefined ? base.model : v.model;
  const fallback = v.fallbackModel === undefined ? base.fallbackModel : v.fallbackModel === null ? "" : v.fallbackModel;
  const max = v.maxStepImages === undefined ? base.maxStepImages : v.maxStepImages;
  const fbProvider = v.fallbackProvider === undefined ? (base.fallbackProvider ?? "") : v.fallbackProvider === null ? "" : v.fallbackProvider;
  if (!imageProviderIds().includes(provider as string)) return null;
  if (typeof model !== "string" || !IMAGE_MODEL.test(model)) return null;
  if (typeof fallback !== "string" || (fallback !== "" && !IMAGE_MODEL.test(fallback))) return null;
  if (!isCount(max) || max > MAX_STEP_IMAGES_LIMIT) return null;
  if (typeof fbProvider !== "string" || (fbProvider !== "" && (fbProvider === "off" || !imageProviderIds().includes(fbProvider)))) return null;
  // The fallback's provider is kept only when it is another one (absent: the same provider).
  const across = fbProvider !== "" && fbProvider !== provider;
  const same = !across && fallback === model;
  return {
    provider: provider as ImageProvider,
    model,
    ...(across ? { fallbackProvider: fbProvider } : {}),
    fallbackModel: same ? "" : fallback,
    maxStepImages: max,
  };
}

/** The picture's fallback target, when one is named (at `fallbackProvider`, else the same provider). */
export function imageFallbackOf(config: Pick<RuntimeConfig, "image">): ImageTarget | null {
  const img = config.image;
  if (img.provider === "off" || !img.fallbackModel) return null;
  return { provider: img.fallbackProvider || img.provider, model: img.fallbackModel };
}

/**
 * Where a picture goes when its provider is out of credit: the named fallback, else the deploy
 * default when that is another provider (owner's rule, README "Adding a provider"); null for none.
 */
export function imageOutOfCreditTargetOf(config: Pick<RuntimeConfig, "image" | "providerDefaults">): ImageTarget | null {
  const named = imageFallbackOf(config);
  if (named) return named;
  const d = config.providerDefaults?.image;
  if (!d || config.image.provider === "off" || d.provider === config.image.provider) return null;
  return { ...d };
}

/** The flat micro-USD price of one picture of `provider/model`, or null without a price (or provider "off"). */
export function imagePriceMicroUsd(config: Pick<RuntimeConfig, "prices">, provider: string, model: string): number | null {
  if (provider === "off") return null;
  const key = `${provider}/${model}`;
  const has = (t: Readonly<Record<string, number>>) => Object.prototype.hasOwnProperty.call(t, key);
  const prices = config.prices.imageUsdPerImage ?? defaultImagePrices();
  // The adapters' own table holds every default; config/runtime's map was merged over it at parse.
  const defaults = defaultImagePrices();
  const usd = has(prices) ? prices[key] : has(defaults) ? defaults[key] : undefined;
  return usd === undefined ? null : Math.ceil(usd * 1_000_000);
}

/**
 * The flat micro-USD price of one picture of the selected provider and model, or null when
 * pictures cannot be made (provider "off", or no price for this provider and model).
 */
export function imageMicroUsdOf(config: Pick<RuntimeConfig, "image" | "prices">): number | null {
  return imagePriceMicroUsd(config, config.image.provider, config.image.model);
}

/**
 * What every picture a request may get is reserved at: the dearer of the selected model and its
 * fallback (either may make it); null when pictures cannot be made.
 */
export function imageReservedMicroUsdOf(config: Pick<RuntimeConfig, "image" | "prices"> & Partial<Pick<RuntimeConfig, "providerDefaults">>): number | null {
  const primary = imageMicroUsdOf(config);
  if (primary === null) return null;
  const named = imageFallbackOf(config);
  const fallback = named ? imagePriceMicroUsd(config, named.provider, named.model) : null;
  // The out-of-credit default (only when no fallback is named and it is another provider).
  const d = config.providerDefaults ? imageOutOfCreditTargetOf(config as Pick<RuntimeConfig, "image" | "providerDefaults">) : null;
  const viaDefault = !named && d ? imagePriceMicroUsd(config, d.provider, d.model) : null;
  return Math.max(primary, fallback ?? 0, viaDefault ?? 0);
}

// --- The recipe text's provider ------------------------------------------------------------------

/** The text selection in force (a hand-built config without one reads as the deploy default). */
export function textSelectionOf(config: Partial<Pick<RuntimeConfig, "text">>): TextSelection {
  return config.text ?? { ...DEFAULT_TEXT_SELECTION };
}

/**
 * USD per 1M tokens of a text target: "google" (runtimeTokenPrices) is priced by
 * `config/runtime.prices.inputUsdPerMTok` / `outputUsdPerMTok`, as before; any other provider by
 * `prices.textUsdPerMTok["<provider>/<model>"]` over its adapter's table. null: unpriced (never used).
 */
export function textPricesOf(config: Pick<RuntimeConfig, "prices">, target: TextTarget): TokenPrices | null {
  const d = textProviderById(target.provider);
  if (!d) return null;
  if (d.runtimeTokenPrices) return { inputUsdPerMTok: config.prices.inputUsdPerMTok, outputUsdPerMTok: config.prices.outputUsdPerMTok };
  const key = `${target.provider}/${target.model}`;
  const over = config.prices.textUsdPerMTok;
  if (over && Object.prototype.hasOwnProperty.call(over, key)) return over[key];
  return textDefaultPrices(target.provider, target.model) ?? null;
}

/**
 * USD per 1M tokens of the output judge's model (always Gemini on Vertex AI, whichever provider
 * writes the recipe): `prices.textUsdPerMTok["google/<model>"]` when config/runtime sets it, else
 * its JUDGE_PRICES entry, else the generator's `prices` (a model with no known price, e.g. the
 * generator's own when GEMINI_JUDGE_MODEL is empty, is booked as before the judge had prices).
 */
export function judgePricesOf(config: Pick<RuntimeConfig, "prices">, model: string): TokenPrices {
  const over = config.prices.textUsdPerMTok;
  const key = `google/${model}`;
  if (over && Object.prototype.hasOwnProperty.call(over, key)) return over[key];
  if (Object.prototype.hasOwnProperty.call(JUDGE_PRICES, model)) return JUDGE_PRICES[model];
  return { inputUsdPerMTok: config.prices.inputUsdPerMTok, outputUsdPerMTok: config.prices.outputUsdPerMTok };
}

/** The text's named fallback target, or null. */
export function textFallbackOf(config: Partial<Pick<RuntimeConfig, "text">>): TextTarget | null {
  const t = textSelectionOf(config);
  return t.fallbackModel ? { provider: t.fallbackProvider || t.provider, model: t.fallbackModel } : null;
}

/** Where the text goes when its provider is out of credit: the named fallback, else the deploy default when that is another provider. */
export function textOutOfCreditTargetOf(config: Partial<Pick<RuntimeConfig, "text" | "providerDefaults">>): TextTarget | null {
  const named = textFallbackOf(config);
  if (named) return named;
  const d = config.providerDefaults?.text;
  const t = textSelectionOf(config);
  if (!d || d.provider === t.provider) return null;
  return { ...d };
}

/**
 * The token prices a request's worst case is reserved at: the dearer, input and output each, of
 * the model and every provider it may fall back to (the named fallback, the out-of-credit default).
 * With the deploy default and no fallback this is exactly `config.prices`'s token prices.
 */
export function textReservePricesOf(config: Pick<RuntimeConfig, "prices"> & Partial<Pick<RuntimeConfig, "text" | "providerDefaults">>): TokenPrices {
  const t = textSelectionOf(config);
  const targets = [{ provider: t.provider, model: t.model }, textFallbackOf(config), textOutOfCreditTargetOf(config)];
  let input = 0;
  let output = 0;
  let any = false;
  for (const target of targets) {
    if (!target) continue;
    const p = textPricesOf(config, target);
    if (!p) continue;
    any = true;
    input = Math.max(input, p.inputUsdPerMTok);
    output = Math.max(output, p.outputUsdPerMTok);
  }
  return any ? { inputUsdPerMTok: input, outputUsdPerMTok: output } : { inputUsdPerMTok: config.prices.inputUsdPerMTok, outputUsdPerMTok: config.prices.outputUsdPerMTok };
}

const TEXT_MODEL = /^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/;

/**
 * `config/runtime.text`: { provider?, model?, fallbackProvider?, fallbackModel? }, each optional
 * over `base`; null when invalid or when the model has no price. A fallbackModel without a
 * fallbackProvider is at the same provider; a fallback without a price, or equal to the model, is
 * dropped.
 */
export function validTextSelection(v: unknown, base: TextSelection, prices: Pick<RuntimeConfig, "prices">): TextSelection | null {
  if (!isObj(v)) return null;
  const provider = v.provider === undefined ? base.provider : v.provider;
  const providerChanged = v.provider !== undefined && v.provider !== base.provider;
  // A new provider without a model takes its adapter's default model.
  const model = v.model === undefined ? (providerChanged ? textProviderById(provider)?.defaultModel : base.model) : v.model;
  const fbModel = v.fallbackModel === undefined ? base.fallbackModel : v.fallbackModel === null ? "" : v.fallbackModel;
  const fbProvider = v.fallbackProvider === undefined ? base.fallbackProvider : v.fallbackProvider === null ? "" : v.fallbackProvider;
  if (!textProviderById(provider)) return null;
  if (typeof model !== "string" || !TEXT_MODEL.test(model)) return null;
  if (typeof fbModel !== "string" || (fbModel !== "" && !TEXT_MODEL.test(fbModel))) return null;
  if (typeof fbProvider !== "string" || (fbProvider !== "" && !textProviderById(fbProvider))) return null;
  const p = provider as string;
  if (!textPricesOf(prices, { provider: p, model })) return null;
  const fallbackAt = fbModel ? fbProvider || p : "";
  const keepFallback = fbModel !== "" && !(fallbackAt === p && fbModel === model) && textPricesOf(prices, { provider: fallbackAt, model: fbModel }) !== null;
  return { provider: p, model, fallbackProvider: keepFallback ? fallbackAt : "", fallbackModel: keepFallback ? fbModel : "" };
}

/** `providerBalanceAlerts`: provider id -> a threshold of 0 to 1e9 in its own unit; null when invalid. */
function validBalanceAlerts(v: unknown): Record<string, number> | null {
  if (!isObj(v) || Object.keys(v).length > 20) return null;
  const out: Record<string, number> = {};
  for (const [k, n] of Object.entries(v)) {
    if (!/^[a-z][a-z0-9]{1,19}$/.test(k) || typeof n !== "number" || !Number.isFinite(n) || n < 0 || n > 1e9) return null;
    out[k] = n;
  }
  return out;
}

/**
 * The token prices are required; `imageUsdPerImage` is optional and adds to or overrides
 * the adapters' picture prices entry by entry. Any invalid part refuses the whole override.
 */
function validPrices(v: unknown): Prices | null {
  // (`textUsdPerMTok`, optional: "provider/model" -> {inputUsdPerMTok, outputUsdPerMTok}, the same bounds.)
  if (!isObj(v)) return null;
  const ok = (n: unknown) => typeof n === "number" && Number.isFinite(n) && n >= 0.01 && n <= 1000;
  if (!ok(v.inputUsdPerMTok) || !ok(v.outputUsdPerMTok)) return null;
  let image: Record<string, number> = {};
  if (v.imageUsdPerImage !== undefined && v.imageUsdPerImage !== null) {
    const parsed = validImagePrices(v.imageUsdPerImage);
    if (!parsed) return null;
    image = parsed;
  }
  let text: Record<string, TokenPrices> | undefined;
  if (v.textUsdPerMTok !== undefined && v.textUsdPerMTok !== null) {
    const t = v.textUsdPerMTok;
    if (!isObj(t) || Object.keys(t).length > 50) return null;
    text = {};
    for (const [k, p] of Object.entries(t)) {
      if (!IMAGE_KEY.test(k) || !isObj(p) || !ok(p.inputUsdPerMTok) || !ok(p.outputUsdPerMTok)) return null;
      text[k] = { inputUsdPerMTok: p.inputUsdPerMTok as number, outputUsdPerMTok: p.outputUsdPerMTok as number };
    }
  }
  return {
    inputUsdPerMTok: v.inputUsdPerMTok as number,
    outputUsdPerMTok: v.outputUsdPerMTok as number,
    imageUsdPerImage: { ...defaultImagePrices(), ...image },
    ...(text ? { textUsdPerMTok: text } : {}),
  };
}

/**
 * Parses `config/runtime`. A missing field takes its default. A present but invalid field never
 * fails open: `generationEnabled` and `allowSandbox` read as false, limits and tables take their
 * defaults (the safe values), and a warning names the fields.
 */
export function parseRuntimeConfig(
  data: Record<string, unknown>,
  logger?: Logger,
  imageDefaults: ImageSelection = DEFAULT_IMAGE_SELECTION,
  textDefaults: TextTarget = DEFAULT_TEXT_SELECTION,
): RuntimeConfig {
  const out: RuntimeConfig = {
    ...DEFAULT_RUNTIME_CONFIG,
    image: { ...imageDefaults },
    text: { provider: textDefaults.provider, model: textDefaults.model, fallbackProvider: "", fallbackModel: "" },
    providerDefaults: {
      text: { provider: textDefaults.provider, model: textDefaults.model },
      image: imageDefaults.provider === "off" ? null : { provider: imageDefaults.provider, model: imageDefaults.model },
    },
  };
  const bad: string[] = [];
  for (const key of ["generationEnabled", "allowSandbox", "consumptionSampleContentProvided", "outputJudgeKeepText", "promptReviewEnabled", "weeklyReportEnabled"] as const) {
    if (data[key] === undefined) continue;
    if (typeof data[key] === "boolean") {
      out[key] = data[key] as boolean;
    } else {
      out[key] = false;
      bad.push(key);
    }
  }
  for (const key of COUNT_FIELDS) {
    if (data[key] === undefined) continue;
    if (isCount(data[key])) out[key] = data[key] as number;
    else bad.push(key);
  }
  if (data.dailySpendRevenueFraction !== undefined) {
    const f = data.dailySpendRevenueFraction;
    if (typeof f === "number" && Number.isFinite(f) && f >= 0 && f <= 1) out.dailySpendRevenueFraction = f;
    else bad.push("dailySpendRevenueFraction");
  }
  if (data.refundAbuseThreshold !== undefined) {
    const t = data.refundAbuseThreshold;
    if (isCount(t) && t >= 1) out.refundAbuseThreshold = t;
    else bad.push("refundAbuseThreshold");
  }
  if (data.budgetKillFraction !== undefined) {
    const f = data.budgetKillFraction;
    if (typeof f === "number" && Number.isFinite(f) && f > 0 && f <= 2) out.budgetKillFraction = f;
    else bad.push("budgetKillFraction");
  }
  if (data.outputJudge !== undefined) {
    // An invalid value turns the judge on in its strictest mode: never fail open.
    if ((OUTPUT_JUDGE_MODES as readonly unknown[]).includes(data.outputJudge)) out.outputJudge = data.outputJudge as OutputJudgeMode;
    else {
      out.outputJudge = "enforce";
      bad.push("outputJudge");
    }
  }
  if (data.costs !== undefined) {
    const c = validCosts(data.costs);
    if (c) out.costs = c;
    else bad.push("costs");
  }
  if (data.prices !== undefined) {
    const p = validPrices(data.prices);
    if (p) out.prices = p;
    else bad.push("prices");
  }
  if (data.tasteQuiz !== undefined) {
    const t = validTasteQuiz(data.tasteQuiz);
    if (t) out.tasteQuiz = t;
    else bad.push("tasteQuiz");
  }
  if (data.welcome !== undefined) {
    const w = validWelcome(data.welcome);
    if (w) out.welcome = w;
    else {
      // Never fail open: an invalid welcome setting turns the grant off.
      out.welcome = { ...WELCOME_OFF };
      bad.push("welcome");
    }
  }
  if (data.homeWorker !== undefined) {
    const h = validHomeWorker(data.homeWorker);
    if (h) out.homeWorker = h;
    else {
      // Never fail open: an invalid home worker setting routes nobody there.
      out.homeWorker = { ...HOME_WORKER_OFF };
      bad.push("homeWorker");
    }
  }
  if (data.reports !== undefined) {
    const r = validReports(data.reports);
    if (r) out.reports = r;
    else bad.push("reports");
  }
  if (data.image !== undefined) {
    const s = validImageSelection(data.image, out.image);
    if (s) out.image = s;
    else {
      // Never fail open: an invalid image setting turns pictures off.
      out.image = { ...out.image, provider: "off" };
      bad.push("image");
    }
  }
  // A fallback model without a price is never asked (it could spend unbooked money).
  // (Pictures off clear it too, as before: no fallback is asked then.)
  const fb = imageFallbackOf(out);
  if (out.image.fallbackModel && (!fb || imagePriceMicroUsd(out, fb.provider, fb.model) === null)) {
    const { fallbackProvider: _dropped, ...rest } = out.image;
    void _dropped;
    out.image = { ...rest, fallbackModel: "" };
  }
  if (data.text !== undefined) {
    const t = validTextSelection(data.text, out.text, out);
    if (t) out.text = t;
    else {
      // Never fail open, and never stop the service: an invalid text setting (an unknown provider,
      // an unpriced model) is the deploy default, the provider that was in force before switching.
      bad.push("text");
    }
  }
  if (data.providerBalanceAlerts !== undefined) {
    const b = validBalanceAlerts(data.providerBalanceAlerts);
    if (b) out.providerBalanceAlerts = b;
    else bad.push("providerBalanceAlerts");
  }
  // The table says a picture's price only while one can be made at a booked price.
  if (imageMicroUsdOf(out) === null) out.costs = withoutImage(out.costs);
  else if (out.image.maxStepImages === 0) out.costs = withoutStepImages(out.costs);
  if (bad.length) logger?.warn("config/runtime has invalid fields; safe values used", { fields: bad });
  return out;
}

/**
 * `tasteQuiz`: an object whose `hidden` is absent or a list of at most 200 strings of at most 81
 * characters ("<step>:<option>"); anything else is refused as a whole (nothing hidden).
 */
export function validTasteQuiz(v: unknown): RuntimeConfig["tasteQuiz"] | null {
  if (typeof v !== "object" || v === null || Array.isArray(v)) return null;
  const hidden = (v as Record<string, unknown>).hidden;
  if (hidden === undefined) return { hidden: [] };
  if (!Array.isArray(hidden) || hidden.length > 200) return null;
  if (!hidden.every((h) => typeof h === "string" && h.length <= 81)) return null;
  return { hidden: [...hidden] as string[] };
}

/**
 * `welcome`: an object whose `recipes` (integer 0 to WELCOME_RECIPES_MAX) and `perDay` (integer 0
 * to WELCOME_PER_DAY_MAX) are each optional (a missing one takes its default); anything else is
 * refused as a whole, and the caller then turns the grant off.
 */
export function validWelcome(v: unknown): WelcomeConfig | null {
  if (!isObj(v)) return null;
  const inRange = (n: unknown, max: number) => isCount(n) && n <= max;
  const recipes = v.recipes === undefined ? DEFAULT_RUNTIME_CONFIG.welcome.recipes : v.recipes;
  const perDay = v.perDay === undefined ? DEFAULT_RUNTIME_CONFIG.welcome.perDay : v.perDay;
  if (!inRange(recipes, WELCOME_RECIPES_MAX) || !inRange(perDay, WELCOME_PER_DAY_MAX)) return null;
  return { recipes: recipes as number, perDay: perDay as number };
}

export async function loadRuntimeConfig(db: Firestore, logger?: Logger, imageDefaults?: ImageSelection, textDefaults?: TextTarget): Promise<RuntimeConfig> {
  const snap = await db.doc("config/runtime").get();
  return parseRuntimeConfig((snap.data() ?? {}) as Record<string, unknown>, logger, imageDefaults, textDefaults);
}

/**
 * `config/runtime` cached in memory for `ttlMs`, so a flood of cheap calls is not also a flood of
 * config reads. Concurrent loads share one read. A failed read is never papered over with a stale
 * copy: the call fails (fail closed) and the next call reads again.
 */
export class RuntimeConfigCache {
  private value: RuntimeConfig | undefined;
  private loadedAt = Number.NEGATIVE_INFINITY;
  private inFlight: Promise<RuntimeConfig> | undefined;

  constructor(
    private readonly load: () => Promise<RuntimeConfig>,
    private readonly now: () => number,
    private readonly ttlMs = CONFIG_CACHE_MS,
  ) {}

  get(): Promise<RuntimeConfig> {
    if (this.value && this.now() - this.loadedAt < this.ttlMs) return Promise.resolve(this.value);
    this.inFlight ??= this.load().then(
      (v) => {
        this.value = v;
        this.loadedAt = this.now();
        this.inFlight = undefined;
        return v;
      },
      (e: unknown) => {
        this.value = undefined;
        this.inFlight = undefined;
        throw e;
      },
    );
    return this.inFlight;
  }
}

/** The revenue-aware daily spend ceiling (see RuntimeConfig.dailySpendBaseMicroUsd). */
export function dailySpendCeiling(config: RuntimeConfig, netCredits7d: number): number {
  const fromRevenue = Math.floor(
    config.dailySpendRevenueFraction * Math.max(0, netCredits7d) * config.netMicroUsdPerCredit,
  );
  return Math.min(config.dailySpendMaxMicroUsd, config.dailySpendBaseMicroUsd + fromRevenue);
}

/**
 * The purchase account token of a uid: UUIDv5 (RFC 9562) of the uid's UTF-8 bytes in
 * PURCHASE_TOKEN_NAMESPACE, lower case. Clients set it as Play's obfuscatedAccountId and Apple's
 * appAccountToken at purchase.
 */
export function purchaseAccountToken(uid: string): string {
  return uuidV5(PURCHASE_TOKEN_NAMESPACE, uid);
}

/** RFC 9562 UUID version 5 (SHA-1) of `name`'s UTF-8 bytes in `namespace`, lower case. */
export function uuidV5(namespace: string, name: string): string {
  const ns = Buffer.from(namespace.replace(/-/g, ""), "hex");
  const h = createHash("sha1").update(ns).update(Buffer.from(name, "utf8")).digest().subarray(0, 16);
  h[6] = (h[6] & 0x0f) | 0x50;
  h[8] = (h[8] & 0x3f) | 0x80;
  const hex = h.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** The priced variant of a request (a menu without `days` or `courses` reads as 3 days, 3 courses). */
export function costKeyOf(req: { kind: GenerationKind; courses?: MenuCourses; days?: MenuDays }): CostKey {
  if (req.kind === "recipe") return "recipe";
  return `menu${req.days ?? DEFAULT_MENU_DAYS}x${req.courses ?? 3}`;
}

/** Estimated cost in micro-USD, rounded up. Thinking tokens are billed as output. */
export function microUsd(prices: Prices, inputTokens: number, outputTokens: number): number {
  return Math.ceil(inputTokens * prices.inputUsdPerMTok + outputTokens * prices.outputUsdPerMTok);
}

/** A stable pseudonym for a uid in logs, so log lines correlate without holding the uid. */
export function uidHash(uid: string): string {
  return createHash("sha256").update(`foodtracker-log:${uid}`).digest("hex").slice(0, 16);
}

/**
 * Lifetime refundable failures of a kind: one per `perRefund` paid credits (net of store refunds),
 * min 1 once paid. Only store purchases count: free recipes from the welcome grant
 * (`welcomeCredits`) are never in `purchasedCredits`, so an account with only those has none.
 */
export function paidAllowance(user: UserDoc, perRefund: number): number {
  const paid = (user.purchasedCredits ?? 0) - (user.refundedCredits ?? 0);
  return paid > 0 ? Math.max(1, Math.floor(paid / perRefund)) : 0;
}

export const userRef = (db: Firestore, uid: string) => db.collection("users").doc(uid) as DocumentReference<UserDoc>;
export const generationRef = (db: Firestore, uid: string, requestId: string) =>
  userRef(db, uid).collection("generations").doc(requestId) as DocumentReference<GenerationDoc>;
/**
 * users/{uid}/reportable/{requestId}: that a generation of this account was delivered (DONE), kept
 * REPORTABLE_DAYS after it, long after the generation itself (7 days), so reportRecipe can tell
 * a saved result of the caller's from a made-up id. Holds no recipe text.
 */
export interface ReportableDoc {
  kind: GenerationKind;
  at: Timestamp;
  expiresAt: Timestamp;
}
export const REPORTABLE_DAYS = 365;
export const reportableRef = (db: Firestore, uid: string, requestId: string) =>
  userRef(db, uid).collection("reportable").doc(requestId) as DocumentReference<ReportableDoc>;

export const generationImageRef = (db: Firestore, uid: string, requestId: string, step?: number) =>
  generationRef(db, uid, requestId).collection("recipeImages").doc(imageDocId(step)) as DocumentReference<GenerationImageDoc>;
export const purchaseRef = (db: Firestore, id: string) =>
  db.collection("purchases").doc(id) as DocumentReference<PurchaseDoc>;
export const statsRef = (db: Firestore, day: string) => db.collection("stats").doc(day.replace(/-/g, ""));
export const buyerRef = (db: Firestore, key: string) => db.collection("buyers").doc(key) as DocumentReference<BuyerDoc>;
export const welcomeRef = (db: Firestore, key: string) => db.collection("welcomes").doc(key) as DocumentReference<WelcomeDoc>;

export function utcDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

export function emptyUser(): UserDoc {
  return {
    balance: 0, inFlight: 0, purchasedCredits: 0, consumedCredits: 0, refundedCredits: 0,
    contentRefundsUsed: 0, timeoutRefundsUsed: 0,
  };
}

export function isClosed(user: UserDoc | undefined): boolean {
  return !!user && (user.deletedAt !== undefined || user.mergedInto !== undefined);
}

/** Every mutating transaction refuses to act for a deleted or merged-away account (its ID token stays valid up to an hour). */
export function assertOpen(user: UserDoc | undefined): void {
  if (isClosed(user)) fail("failed-precondition", "ACCOUNT_CLOSED", "This account was deleted or merged");
}

/** Follows `mergedInto` from `uid` to the account that holds its credits now. Reads inside `tx`. */
export async function resolveHolder(
  tx: Transaction,
  db: Firestore,
  uid: string,
  maxHops: number,
): Promise<{ uid: string; user: UserDoc | undefined }> {
  let current = uid;
  for (let hop = 0; ; hop++) {
    const user = (await tx.get(userRef(db, current))).data();
    if (!user?.mergedInto || hop >= maxHops) return { uid: current, user };
    current = user.mergedInto;
  }
}
