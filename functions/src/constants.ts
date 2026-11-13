/** Fixed facts the backend relies on. Runtime-tunable values live in `config/runtime` (see RuntimeConfig in model.ts). */

export const ANDROID_PACKAGE = "com.foodtracker.android";
export const IOS_BUNDLE_ID = "com.foodbest.ios";

/**
 * Firebase app ids (App Check `appId`) of the two clients, from the committed
 * androidApp/google-services.json and iosApp/FoodTracker/GoogleService-Info.plist. verifyPurchase
 * accepts Play purchases only from the Android app and App Store purchases only from the iOS app.
 */
export const ANDROID_FIREBASE_APP_ID = "1:1035101174561:android:8a5a6e0a9fca3573f0dc60";
export const IOS_FIREBASE_APP_ID = "1:1035101174561:ios:27d1027b9220f5d1f0dc60";

/**
 * Namespace of the purchase account token: UUIDv5(PURCHASE_TOKEN_NAMESPACE, uid). Clients set it as
 * Play's obfuscatedAccountId and Apple's appAccountToken (see CONTRACT.md). Never change it: every
 * purchase made with the old value would stop matching.
 */
export const PURCHASE_TOKEN_NAMESPACE = "6f2b3c1e-8d4a-4b7e-9c35-2a1f0e7d5b48";

/**
 * App Check replay protection on generateRecipe, verifyPurchase, mergeBalance and deleteAccount: each call must carry a fresh
 * limited-use token, which the server consumes (both clients request limited-use tokens for every
 * callable). The function's service account needs the "Firebase App Check Token Verifier" role
 * (roles/firebaseappcheck.tokenVerifier). A replayed token arrives with `alreadyConsumed: true`
 * and is refused in index.ts; the framework itself lets it through.
 */
export const CONSUME_APP_CHECK_TOKENS = true;

/** Region of every function. Clients must call the callables in this region. */
export const REGION = "europe-west1";

/** Credits per unit of each consumable product. The store's signed quantity multiplies these. */
export const PRODUCT_CREDITS: Readonly<Record<string, number>> = {
  recipes_10: 10,
  recipes_50: 50,
  recipes_100: 100,
};

export type GenerationKind = "recipe" | "menu";

/** The priced variants of a request: a recipe, a 2-course menu, a 3-course menu. */
export type CostKey = "recipe" | "menu2" | "menu3";
export const COST_KEYS: readonly CostKey[] = ["recipe", "menu2", "menu3"];
export type Costs = Readonly<Record<CostKey, number>>;

/**
 * Credits per request. The server is the only place that decides cost: `config/runtime.costs` may
 * override this table (validated in model.ts), every response that carries `balance` also carries
 * the table in force, and the reservation stores the cost it charged, which settle uses.
 */
export const DEFAULT_COSTS: Costs = { recipe: 1, menu2: 4, menu3: 5 };
/** An override is refused (defaults used) unless every cost is an integer in [1, COST_MAX]. */
export const COST_MAX = 100;

/**
 * Timings per kind. The reservation deadline is at least the longest a live invocation can spend on
 * the LLM, and the sweeper only touches a reservation SWEEP_GRACE_MS after its deadline.
 */
export const TIMINGS: Readonly<Record<GenerationKind, { llmAbortMs: number; reservationMs: number }>> = {
  recipe: { llmAbortMs: 40_000, reservationMs: 60_000 },
  menu: { llmAbortMs: 120_000, reservationMs: 180_000 },
};
/** generateRecipe's function timeout; covers the menu path, the longest one. */
export const GENERATE_TIMEOUT_SECONDS = 180;
export const SWEEP_GRACE_MS = 60_000;

/**
 * maxOutputTokens per request, sized from the schema and the validation limits: one dish at
 * "stepByStep" (up to 15 steps), 12 servings and a language that tokenises badly is about 1,800
 * output tokens of JSON; a recipe gets ~2x that. A menu is 3 days x courses dishes plus ~3,000 for
 * the day wrappers, `uses` lists and slack: 6 x 1,800 + 3,000 ~ 14,000 (2 courses) and
 * 9 x 1,800 + 3,000 ~ 20,000 (3 courses). Thinking may come on top (see THINKING_FACTOR).
 */
export const MAX_OUTPUT_TOKENS: Readonly<Record<CostKey, number>> = { recipe: 4096, menu2: 14_000, menu3: 20_000 };

/**
 * Thinking tokens are reported to be able to exceed maxOutputTokens, and Gemini 3+ offers no
 * enforceable thinking-token bound (thinking_level is a tier, thinking_budget is deprecated for 3+
 * and was a soft limit before). So the worst case of one call is assumed to be
 * THINKING_FACTOR x maxOutputTokens of output-priced tokens, and a stream observed past that
 * ceiling is aborted as an infrastructure failure.
 */
export const THINKING_FACTOR = 2;

/**
 * Vertex AI list price in USD per 1M tokens for the default model (gemini-3.8-flash, standard
 * tier, prompts <= 200k): 1.50 input, 7.50 output, thinking billed as output. This is the price
 * from 2027-01-01; the introductory 0.75 / 3.75 until then makes these estimates conservative.
 * `config/runtime.prices` overrides it. Update both when the model or Google's price changes.
 */
export const DEFAULT_PRICES = { inputUsdPerMTok: 1.5, outputUsdPerMTok: 7.5 } as const;

/**
 * How long a finished generation doc is kept for idempotent retries. Firestore TTL deletes it some
 * time after this (Google documents "typically within 24 hours" of expiry, not a guarantee), so the
 * idempotency window is at least 7 days after the generation finished, and no more is promised.
 */
export const GENERATION_TTL_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * Input-caused failures (safety block, MAX_TOKENS, invalid output) are refunded once per this many
 * credits the account has paid for over its lifetime (net of store refunds), minimum one once it has
 * paid for anything. Later ones are charged.
 */
export const CREDITS_PER_CONTENT_REFUND = 10;

/**
 * Infrastructure failures whose usage is unknown (timeouts, mid-stream errors, token-ceiling aborts,
 * invocations the sweeper settles) cost the worst case, so refunding them for ever would make timing
 * out on purpose free. They are refunded once per this many credits paid over the account's life
 * (net of store refunds), minimum one once it has paid for anything; later ones are charged.
 * Failures with known usage (an error before the model produced anything) are always refunded.
 */
export const CREDITS_PER_TIMEOUT_REFUND = 10;

export const MAX_INGREDIENTS = 30;
export const MAX_ALLERGENS = 20;
export const NAME_MAX_LENGTH = 40;
export const LANGUAGES = ["en", "nb", "ru", "de", "es", "fr", "it", "pl", "sv", "uk"] as const;

/** deleteAccount requires a sign-in at most this old (non-anonymous accounts). */
export const RECENT_SIGN_IN_SECONDS = 5 * 60;

/** How many `mergedInto` hops a refund follows to find the account that now holds a purchase's credits. */
export const MERGE_CHAIN_MAX = 5;

/** Play purchases granted more recently than this are left to the live verifyPurchase call to consume. */
export const CONSUME_RETRY_MIN_AGE_MS = 60_000;
/** After this many failed consumes the sweeper stops retrying and logs `play_consume_gave_up`. */
export const CONSUME_MAX_ATTEMPTS = 50;
/** Documents the sweeper reads per page, and the most pages per run, for each of its queues. */
export const SWEEP_PAGE = 100;
export const SWEEP_MAX_PAGES = 10;

/** Apple: "Respond within 12 hours of receiving the CONSUMPTION_REQUEST notification." */
export const CONSUMPTION_ANSWER_WINDOW_MS = 12 * 60 * 60 * 1000;

/** How long `config/runtime` is cached in memory per instance. */
export const CONFIG_CACHE_MS = 30_000;

/** Days of production credit grants (net of refunds) the revenue-aware daily spend ceiling looks at. */
export const REVENUE_WINDOW_DAYS = 7;

/** How far back the daily Voided Purchases poll looks (the API allows up to 30 days). */
export const VOIDED_POLL_WINDOW_MS = 3 * 24 * 60 * 60 * 1000;
