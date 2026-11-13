/** Fixed facts the backend relies on. Runtime-tunable values live in `config/runtime` (see RuntimeConfig in model.ts). */

export const ANDROID_PACKAGE = "com.foodtracker.android";
export const IOS_BUNDLE_ID = "com.foodbest.ios";

/**
 * App Check replay protection (limited-use tokens, consumed on verification) on generateRecipe and
 * verifyPurchase. Off until both clients request limited-use tokens for those two callables; then
 * flip it and grant the service account "Firebase App Check Token Verifier".
 */
export const CONSUME_APP_CHECK_TOKENS = false;

/** Region of every function. Clients must call the callables in this region. */
export const REGION = "europe-west1";

/** Credits per unit of each consumable product. The store's signed quantity multiplies these. */
export const PRODUCT_CREDITS: Readonly<Record<string, number>> = {
  recipes_10: 10,
  recipes_50: 50,
  recipes_100: 100,
};

export type GenerationKind = "recipe" | "menu";

/** What a request costs, by kind. The reservation stores the cost, and settle/refund use the stored value. */
export const COST: Readonly<Record<GenerationKind, number>> = { recipe: 1, menu: 3 };

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

/** Max output tokens per kind (thinking tokens count against this too). */
export const MAX_OUTPUT_TOKENS: Readonly<Record<GenerationKind, number>> = { recipe: 4096, menu: 20_000 };

/** How long a finished generation doc is kept for idempotent retries before Firestore TTL deletes it. */
export const GENERATION_TTL_MS = 7 * 24 * 60 * 60 * 1000;

/** Input-caused failures (safety block, invalid output) refunded per user per UTC day; later ones are charged. */
export const CONTENT_REFUNDS_PER_DAY = 3;

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

/** How far back the daily Voided Purchases poll looks (the API allows up to 30 days). */
export const VOIDED_POLL_WINDOW_MS = 3 * 24 * 60 * 60 * 1000;
