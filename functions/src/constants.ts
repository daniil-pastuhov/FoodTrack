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
 * App Check replay protection on generateRecipe, verifyPurchase, mergeBalance, deleteAccount and
 * registerAppleSignIn: each call must carry a fresh
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

/** Days of a menu set (`generateRecipe` `days`); a request without `days` is a DEFAULT_MENU_DAYS menu. */
export const MENU_DAYS = [1, 2, 3, 4] as const;
export type MenuDays = (typeof MENU_DAYS)[number];
export const DEFAULT_MENU_DAYS: MenuDays = 3;
/** Courses of a menu set (`generateRecipe` `courses`): 1 is the main course alone (coursesFor). */
export const MENU_COURSES = [1, 2, 3] as const;
export type MenuCourses = (typeof MENU_COURSES)[number];

/**
 * The priced variants of a request: a recipe, or a menu of D days and C courses (`menu3x2` is a
 * 3-day 2-course menu, which the wire table also calls `menu2`): 4 x 3 = 12 menu variants.
 */
export type CostKey = "recipe" | `menu${MenuDays}x${MenuCourses}`;
export const COST_KEYS: readonly CostKey[] = [
  "recipe",
  ...MENU_DAYS.flatMap((d) => MENU_COURSES.map((c): CostKey => `menu${d}x${c}`)),
];

/** Credits of a menu set, keyed by days, then courses, as strings (the wire's `costs.menuSets`). */
export type MenuSetCosts = Readonly<Record<`${MenuDays}`, Readonly<Record<`${MenuCourses}`, number>>>>;

/**
 * The table in force, exactly as sent to clients (CONTRACT.md "Costs"). `menu2` and `menu3` are
 * the 3-day row of `menuSets`, kept for clients from before menu sets; build a table with
 * costsOf so they can never disagree.
 */
export interface Costs {
  readonly recipe: number;
  readonly menu2: number;
  readonly menu3: number;
  readonly menuSets: MenuSetCosts;
  /**
   * A single recipe with a generated picture of the dish (`generateRecipe` `image: "dish"`,
   * CONTRACT.md "Capability `acceptsRecipeImage`"). Absent when pictures are off: turned off in
   * `config/runtime.costs` (`recipeImage: 0`), the provider is "off", or the selected provider and
   * model have no per-image price (model.ts parseRuntimeConfig). The apps offer a picture only
   * while the table has this key.
   */
  readonly recipeImage?: number;
  /**
   * A single recipe with a picture of the dish and pictures of up to `image.maxStepImages` of its
   * steps (`image: "steps"`), the whole price. Absent when that level is off: whenever
   * `recipeImage` is absent, `recipeImageSteps: 0`, or `maxStepImages` is 0.
   */
  readonly recipeImageSteps?: number;
}

export function costsOf(recipe: number, menuSets: MenuSetCosts, recipeImage?: number, recipeImageSteps?: number): Costs {
  const row = menuSets[`${DEFAULT_MENU_DAYS}`];
  return {
    recipe, menu2: row["2"], menu3: row["3"], menuSets,
    ...(recipeImage !== undefined ? { recipeImage } : {}),
    ...(recipeImage !== undefined && recipeImageSteps !== undefined ? { recipeImageSteps } : {}),
  };
}

/** Credits of a request's variant in a table. */
export function costOf(costs: Costs, key: CostKey): number {
  if (key === "recipe") return costs.recipe;
  const [d, c] = key.slice(4).split("x") as [`${MenuDays}`, `${MenuCourses}`];
  return costs.menuSets[d][c];
}

/**
 * Credits per request. The server is the only place that decides cost: `config/runtime.costs` may
 * override this table (validated in model.ts), every response that carries `balance` also carries
 * the table in force, and the reservation stores the cost it charged, which settle uses. A menu
 * set's default is days + courses - 1 credits (one credit more per day or course added).
 */
export const DEFAULT_MENU_SETS: MenuSetCosts = {
  "1": { "1": 1, "2": 2, "3": 3 },
  "2": { "1": 2, "2": 3, "3": 4 },
  "3": { "1": 3, "2": 4, "3": 5 },
  "4": { "1": 4, "2": 5, "3": 6 },
};
/**
 * A recipe with a picture: twice a recipe by default (README "How the money works", "Recipe
 * pictures"). A `config/runtime.costs` override without `recipeImage` gets twice its own
 * `recipe` (at most COST_MAX); `recipeImage: 0` turns pictures off.
 */
export const DEFAULT_RECIPE_IMAGE_COST = 2;
/**
 * A recipe with the dish's picture and up to DEFAULT_MAX_STEP_IMAGES step pictures: three times a
 * recipe by default (at most three pictures of about USD 0.034 plus the text, against at least
 * 3 x USD 0.106 net). An override without `recipeImageSteps` gets three times its own `recipe`;
 * `recipeImageSteps: 0` turns the level off.
 */
export const DEFAULT_RECIPE_IMAGE_STEPS_COST = 3;
export const DEFAULT_COSTS: Costs = costsOf(1, DEFAULT_MENU_SETS, DEFAULT_RECIPE_IMAGE_COST, DEFAULT_RECIPE_IMAGE_STEPS_COST);

/** The picture levels of `generateRecipe` `image` (CONTRACT.md "Capability `acceptsRecipeImage`"). */
export const IMAGE_LEVELS = ["dish", "steps"] as const;
export type ImageLevel = (typeof IMAGE_LEVELS)[number];
/** Step pictures beside the dish's at most, by default (`config/runtime.image.maxStepImages`, 0 to MAX_STEP_IMAGES_LIMIT). */
export const DEFAULT_MAX_STEP_IMAGES = 2;
export const MAX_STEP_IMAGES_LIMIT = 4;
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
/**
 * The optional output judge (config/runtime `outputJudge`, README "Prompt injection defences"):
 * its answer is one enum value, so 32 visible tokens; output plus thinking observed above
 * `tokenCeiling` aborts it. It runs after the generator, within the same reservation:
 * llmAbortMs + abortMs stays below reservationMs for both kinds.
 */
export const JUDGE = {
  maxOutputTokens: 32,
  tokenCeiling: 2048,
  abortMs: { recipe: 12_000, menu: 25_000 } as Readonly<Record<GenerationKind, number>>,
} as const;

/**
 * A recipe's picture (README "Recipe pictures"): made after the text passed every check, within
 * the same reservation, so a recipe with pictures reserves `reservationExtraMs[level]` more.
 *
 * Shape: every provider is asked for `aspectRatio` (4:3, the apps' picture frame) at its 1K size
 * (Vertex: 1200 x 896; OpenAI: its nearest landscape size, adapters/image.ts).
 *
 * Calls and their time (core/recipeImage.ts): the dish's picture first, then, only when it was
 * made, the step pictures, at most `maxParallel` at once (four simultaneous calls to the Vertex
 * model gave three 429s; one recipe never sends more than two). Each attempt is aborted after
 * `abortMs`; a retryable failure (429, 5xx) is retried after a jittered backoff
 * (`retryBaseMs` x 2^(n-1) x 0.5..1.5) while at most `maxAttempts` attempts are made and at least
 * `minAttemptMs` is left of the picture's budget: `budgetMs.dish` from the start of the pictures for
 * the dish's, `budgetMs.steps` from the same start for the step pictures. The arithmetic (text 40 s +
 * judge 12 s before any picture): dish 40 + 12 + 50 = 102 s inside the 115 s reservation
 * (60 + 55); steps 40 + 12 + 100 = 152 s inside the 165 s reservation (60 + 105) and the 180 s
 * function timeout. The apps wait 130 s (dish) and 190 s (steps).
 *
 * Delivery (adapters/imageNormalize.ts): a provider's JPEG of at most `passThroughMaxEdge` pixels on
 * its longer side and `passThroughMaxBytes` bytes is delivered as it came (its header and size read
 * without decoding); anything else is decoded and re-encoded to a JPEG of at most `maxEdge` at
 * `jpegQuality`, no metadata. A provider's answer larger than `maxInputBytes` is refused without
 * decoding, and a re-encoded picture is at most `maxOutputBytes`. Either way one picture per
 * Firestore document (1 MiB limit), and three in one response are at most 3 x 800,000 base64 bytes.
 */
export const IMAGE = {
  aspectRatio: "4:3",
  abortMs: 35_000,
  budgetMs: { dish: 50_000, steps: 100_000 } as Readonly<Record<ImageLevel, number>>,
  reservationExtraMs: { dish: 55_000, steps: 105_000 } as Readonly<Record<ImageLevel, number>>,
  minAttemptMs: 20_000,
  maxAttempts: 4,
  retryBaseMs: 2_000,
  maxParallel: 2,
  /** Vertex's 1K 4:3 picture (1200 x 896, about 200-260 KB at its quality 85) passes through. */
  passThroughMaxEdge: 1280,
  passThroughMaxBytes: 400_000,
  maxEdge: 1200,
  jpegQuality: 80,
  /** A provider's answer: a 1K picture is well under both (1,536 px on the long side at most). */
  maxInputBytes: 8 * 1024 * 1024,
  maxInputPixels: 1600 * 1600,
  maxOutputBytes: 600_000,
  /** The text model's `imageBrief`: plain English, these many characters at most. */
  briefMaxChars: 200,
  briefMinChars: 10,
} as const;

/**
 * The recipe text's default provider and model, when neither the TEXT_PROVIDER / GEMINI_MODEL
 * parameters nor `config/runtime.text` say otherwise: Gemini on Vertex AI (adapters/gemini.ts),
 * no fallback. An out-of-credit failure of another provider falls back to these defaults.
 */
export const DEFAULT_TEXT_SELECTION = {
  provider: "google",
  model: "gemini-3.8-flash",
  fallbackProvider: "",
  fallbackModel: "",
} as const;

/**
 * A picture provider's id: one of the adapters in providers/registry.ts (vertex, openai), or "off"
 * (no pictures). Checked at run time against the registry (model.ts validImageSelection).
 */
export type ImageProvider = string;

/**
 * The picture's default provider, model and fallback model, when neither the IMAGE_PROVIDER /
 * IMAGE_MODEL / IMAGE_FALLBACK_MODEL parameters nor `config/runtime.image` say otherwise
 * (owner's decision 2026-11-17): Vertex AI's Gemini 3.1 Flash-Lite Image (`gemini-3.1-flash-lite-image`:
 * 1K only, 4:3 accepted, `global` only; about 3 s a picture, no thinking), and Nano Banana 2.1
 * (`gemini-nano-banana-2.1`: 1K/2K/4K, `global` only; about 15 s, thinks) as the fallback, at the
 * same provider (model pages on docs.cloud.google.com, read 2026-11-17). An empty
 * `fallbackModel` means no fallback.
 */
export const DEFAULT_IMAGE_SELECTION = {
  provider: "vertex",
  model: "gemini-3.1-flash-lite-image",
  fallbackModel: "gemini-nano-banana-2.1",
  maxStepImages: 2,
} as const;

// The pictures' prices (USD per delivered picture, and the token rates of the models that report
// token counts) are in each provider's adapter (adapters/image.ts vertexImage / openaiImage),
// collected by providers/registry.ts defaultImagePrices / imageTokenPricesOf;
// `config/runtime.prices.imageUsdPerImage` adds to or overrides them, and a provider and model with
// no price make no pictures (the costs table then has no `recipeImage`), so switching to an
// unpriced model can never spend unbooked money.

/** generateRecipe's function timeout; covers the menu path, the longest one. */
export const GENERATE_TIMEOUT_SECONDS = 180;
export const SWEEP_GRACE_MS = 60_000;

/**
 * maxOutputTokens per request, sized from the schema and the validation limits: one dish at
 * "stepByStep" (up to 15 steps), 12 servings and a language that tokenises badly is about 1,800
 * output tokens of JSON; a recipe gets ~2x that. A menu is days x courses dishes plus ~1,000 a day
 * for the day wrappers, `uses` lists and slack, rounded up (the 3-day figures are those from
 * before menu sets: 6 x 1,800 + 3,000 ~ 14,000 and 9 x 1,800 + 3,000 ~ 20,000). The exception is
 * 4 days of 3 courses, 12 x 1,800 + 2,400 = 24,000 (600 a day): held there so that its worst case
 * with the output judge on still fits the per-user daily spend ceiling (README "Spend
 * ceilings"). Thinking may come on top (see THINKING_FACTOR).
 *
 * | days | 1 course | 2 courses | 3 courses |
 * |---|---|---|---|
 * | 1 | 3,000 | 5,000 | 7,000 |
 * | 2 | 5,500 | 9,500 | 13,000 |
 * | 3 | 8,500 | 14,000 | 20,000 |
 * | 4 | 11,500 | 19,000 | 24,000 |
 */
export const MAX_OUTPUT_TOKENS: Readonly<Record<CostKey, number>> = {
  recipe: 4096,
  menu1x1: 3_000,
  menu1x2: 5_000,
  menu1x3: 7_000,
  menu2x1: 5_500,
  menu2x2: 9_500,
  menu2x3: 13_000,
  menu3x1: 8_500,
  menu3x2: 14_000,
  menu3x3: 20_000,
  menu4x1: 11_500,
  menu4x2: 19_000,
  menu4x3: 24_000,
};

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
 * These are the "google" text provider's prices (adapters/gemini.ts googleText) and the output
 * judge's, whichever provider writes the recipe; another text provider's prices are in its adapter.
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
/**
 * Names in one request when it carries `optionalIngredients`: the ticked ingredients and the kept
 * optional ones together. The same as MAX_INGREDIENTS, so the model never sees more names than in
 * the largest request without optional items.
 */
export const MAX_TOTAL_INGREDIENTS = 30;
/**
 * Code points of names (ticked and kept optional together) in a request that carries optional
 * items. Chosen so that its worst-case input bound, with the extra instruction lines and the
 * second data block, stays below the worst case of the largest request without them
 * (30 names of 40 code points): README "Spend ceilings" shows the arithmetic, and
 * test/optionalIngredients.test.ts checks it.
 */
export const MAX_OPTIONAL_NAME_CHARS = 1050;
/**
 * Entries of `staples` in one request (the names of the basics a person always has at home,
 * CONTRACT.md "Staples"): a longer list is INVALID_REQUEST, and at most this many are kept. Its own
 * budget, apart from MAX_TOTAL_INGREDIENTS: staples never use up room meant for pantry items.
 */
export const MAX_STAPLES = 20;
/**
 * Code points of the kept staples' names together; an entry that does not fit is skipped and a
 * later, shorter one may still be kept. Chosen so that the largest request of every kind, with
 * every staple at its longest in 4-byte characters, still fits the per-user daily spend ceiling
 * (README "Spend ceilings"; test/staples.test.ts checks it). The apps cap at 400 as well.
 */
export const MAX_STAPLE_NAME_CHARS = 400;
export const MAX_ALLERGENS = 20;
export const NAME_MAX_LENGTH = 40;
export const LANGUAGES = ["en", "nb", "ru", "de", "es", "fr", "it", "pl", "sv", "uk", "da"] as const;

/** deleteAccount requires a sign-in at most this old (non-anonymous accounts). */
export const RECENT_SIGN_IN_SECONDS = 5 * 60;

/**
 * The Cloud KMS key that wraps the data keys of stored Sign in with Apple refresh tokens
 * (adapters/envelope.ts): projects/<project>/locations/<KMS_LOCATION>/keyRings/<KMS_KEY_RING>/cryptoKeys/<KMS_APPLE_TOKEN_KEY>.
 * Created before deploy (docs/recipe-setup.md); only the functions' service account may use it.
 */
export const KMS_LOCATION = "europe-west1";
export const KMS_KEY_RING = "recipe";
export const KMS_APPLE_TOKEN_KEY = "apple-refresh-token";

/** Bound to each stored Apple token as additional authenticated data, with the uid. */
export const appleTokenContext = (uid: string) => `foodtracker/apple-refresh-token/v1/${uid}`;

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
