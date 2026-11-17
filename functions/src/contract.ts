/**
 * The wire contract shared with the Android and iOS clients (see CONTRACT.md), and its validation.
 * Everything a client sends is validated here before it reaches Firestore or the LLM.
 */
import { HttpsError } from "firebase-functions/v2/https";
import { createHash } from "node:crypto";
import {
  type Costs,
  DEFAULT_MAX_STEP_IMAGES,
  DEFAULT_MENU_DAYS,
  GenerationKind,
  IMAGE_LEVELS,
  type ImageLevel,
  LANGUAGES,
  MAX_ALLERGENS,
  MAX_INGREDIENTS,
  MAX_OPTIONAL_NAME_CHARS,
  MAX_STAPLE_NAME_CHARS,
  MAX_STAPLES,
  MAX_TOTAL_INGREDIENTS,
  MENU_COURSES,
  MENU_DAYS,
  type MenuCourses,
  type MenuDays,
} from "./constants";
import { SUSPICIOUS_INPUT_RULES, normalizeName, type InputRule } from "./core/inputSafety";
import { cleanTaste, tasteQuizVersion } from "./taste/quiz";

export const DIFFICULTIES = ["easy", "medium", "ambitious"] as const;
export const DETAILS = ["brief", "standard", "stepByStep"] as const;
/** The legacy `maxMinutes` values (a config without `mealTypes` / `diets`). */
export const MAX_MINUTES = [15, 30, 60] as const;
/** The legacy single `mealType` and `diet`. */
export const MEAL_TYPES = ["any", "breakfast", "lunch", "dinner", "snack", "dessert"] as const;
export const DIETS = ["none", "vegetarian", "vegan", "pescatarian", "glutenFree", "dairyFree"] as const;
/** The choices of `mealTypes` and `diets` (CONTRACT.md "RecipeConfig"); "any meal" and "no diet" are the empty list. */
export const MEAL_CHOICES = ["breakfast", "lunch", "dinner", "snack", "dessert"] as const;
export const DIET_CHOICES = ["vegetarian", "vegan", "pescatarian", "glutenFree", "dairyFree"] as const;
/** `minMinutes` and `maxMinutes` of a multi-choice config: a multiple of `step` in [min, max]. */
export const MINUTES_RANGE = { min: 10, max: 120, step: 5 } as const;
export const COURSES = ["starter", "main", "dessert"] as const;

/** `taste` shape bounds (CONTRACT.md "Taste profile"): beyond any of them the request is INVALID_REQUEST. */
export const TASTE_MAX_KEYS = 16;
export const TASTE_MAX_ENTRIES = 32;
export const TASTE_MAX_ID_CHARS = 40;

export type Difficulty = (typeof DIFFICULTIES)[number];
export type Language = (typeof LANGUAGES)[number];
export type Course = (typeof COURSES)[number];

export type MealChoice = (typeof MEAL_CHOICES)[number];
/**
 * A cleaned, non-empty taste profile (taste/quiz.ts cleanTaste): the visible catalogue steps that
 * have an answer, in catalogue order, each with its kept option ids in catalogue order. Soft
 * preferences for the prompt only; never an allergen or a diet.
 */
export type TasteProfile = { step: string; options: string[] }[];

/** One option of a quiz step, as getTasteQuiz sends it. */
export interface TasteQuizOption {
  id: string;
  label: string;
  /** A second line, on `single` cards. */
  sub?: string;
  /** 1-3 flames on a `single` card. */
  level?: number;
}

export interface TasteQuizStep {
  id: string;
  kind: "multi" | "single";
  tone: "like" | "avoid";
  /** A Lucide icon name from taste/catalogue.ts TASTE_ICONS. */
  icon: string;
  question: string;
  note: string | null;
  max: number;
  groups: { title: string | null; options: TasteQuizOption[] }[];
}

/** getTasteQuiz's response (CONTRACT.md `getTasteQuiz`). */
export interface TasteQuizResponse {
  version: string;
  language: string;
  steps: TasteQuizStep[];
  conflicts: [string, string][];
  summary: string[];
}
export type DietChoice = (typeof DIET_CHOICES)[number];

/**
 * A validated config, the same whichever shape the client sent (CONTRACT.md "RecipeConfig"): a
 * legacy config's single `mealType` and `diet` become a list of at most one, "any" and "none"
 * the empty list.
 */
export interface RecipeConfig {
  difficulty: Difficulty;
  detail: (typeof DETAILS)[number];
  servings: number;
  /** A guide only ("about this long or more"), never enforced; null without one. */
  minMinutes: number | null;
  /** A hard limit: the schema's `minutes.maximum` and the output check. Null without one. */
  maxMinutes: number | null;
  /** The dish suits any one of these; empty is any meal. In MEAL_CHOICES order, no repeats. */
  mealTypes: MealChoice[];
  /** Every one of these holds at once; empty is no diet. In DIET_CHOICES order, no repeats. */
  diets: DietChoice[];
  language: Language;
}

export interface IngredientInput {
  name: string;
  /** YYYY-MM-DD, or null for an item without an expiry date. */
  expiresOn: string | null;
  /** Item count in the pantry, or null when unknown. */
  quantity: number | null;
  /** Kept optional items only: the entry's index in `optionalIngredients` as the client sent it. */
  sentIndex?: number;
}

/** A kept entry of `staples` (CONTRACT.md "Staples"): a basic the person always has at home. */
export interface StapleInput {
  name: string;
  /** The entry's 0-based index in `staples` as the client sent it (entries left out still count). */
  sentIndex: number;
}

export interface GenerateRequest {
  requestId: string;
  kind: GenerationKind;
  courses?: MenuCourses;
  /**
   * A menu's number of days, set on every validated menu (DEFAULT_MENU_DAYS when the client sent
   * none, as every client before menu sets); absent on a recipe. Read it with menuDaysOf.
   */
  days?: MenuDays;
  config: RecipeConfig;
  ingredients: IngredientInput[];
  /**
   * The rest of the pantry, which the recipe MAY use as well ("Send all products" in the apps):
   * validated like `ingredients`, but a name the rules refuse is left out rather than refusing the
   * request, and what is kept is capped together with `ingredients` (CONTRACT.md "Optional
   * ingredients"). Empty when the client sent none.
   */
  optionalIngredients: IngredientInput[];
  /**
   * The names of the basics the person always has at home, which a recipe may use and never has
   * to: a name the rules refuse, or one already among the ingredients, the kept optional items or
   * an earlier staple, is left out rather than refusing the request, and what is kept is capped on
   * its own (CONTRACT.md "Staples"). Never pantry use, never past their date. Empty when the
   * client sent none. The prompt and the answer check see only usableStaples (core/outputSafety.ts).
   */
  staples: StapleInput[];
  allergens: string[];
  today: string;
  /**
   * The cleaned taste profile, present only when what was sent is not empty after cleaning
   * against the visible catalogue; without it the prompt is exactly that of a request without
   * `taste`.
   */
  taste?: TasteProfile;
  /**
   * `taste` as sent, canonical (keys sorted, each list without repeats and sorted, empty lists
   * left out), present only when not empty: what inputHashOf binds, so a retry with the same
   * answers is the same request whatever the catalogue or the hidden list became meanwhile.
   */
  tasteSent?: [string, string[]][];
  /**
   * A single recipe asked with generated pictures (CONTRACT.md "Capability `acceptsRecipeImage`"):
   * "dish" (one picture of the finished dish) or "steps" (that and up to `stepImagesMax` pictures
   * of steps hard to follow from words alone); absent otherwise, so a request without it is
   * exactly what it was before pictures existed.
   */
  image?: ImageLevel;
  /** Level "steps": the step pictures asked at most (config/runtime `image.maxStepImages`). */
  stepImagesMax?: number;
}

/** A step's picture in a response: `step` is the 0-based index into `recipe.steps`. */
export interface StepImageWire {
  step: number;
  image: RecipeImageWire;
}

/** A delivered picture (CONTRACT.md "Recipe picture (response)"): a JPEG, base64. */
export interface RecipeImageWire {
  mimeType: "image/jpeg";
  /** Standard base64 (RFC 4648 section 4, with padding) of the JPEG's bytes. */
  data: string;
  width: number;
  height: number;
}

export interface RecipeIngredient {
  amount: string;
  name: string;
  fromPantry: boolean;
  /**
   * Present exactly when this is one of the request's `ingredients`: its 0-based index there, so
   * a client can find that item and its date. Absent in answers saved before it.
   */
  pantryIndex?: number;
  /**
   * Present exactly when this is one of the request's `optionalIngredients`: its 0-based index in
   * that list as sent (entries the server left out still count), so a client can find its date.
   */
  optionalIndex?: number;
  /** Present exactly when this ingredient is one of the request's `staples`: its 0-based index in
   *  that list as sent (entries the server left out still count). Such an ingredient always has
   *  fromPantry: false. Absent otherwise and in answers from before it. */
  stapleIndex?: number;
  /**
   * Present and true only on an ingredient that is not from the pantry and whose name is only a
   * staple (water, ice, oil, salt, pepper, sugar, vinegar, spices: core/foodTerms.ts STAPLE_WORDS).
   * Absent otherwise, and in answers saved before it.
   */
  staple?: true;
}

export interface Recipe {
  title: string;
  summary: string;
  minutes: number;
  servings: number;
  difficulty: Difficulty;
  ingredients: RecipeIngredient[];
  steps: string[];
  tip?: string;
}

export interface MenuCourse {
  course: Course;
  recipe: Recipe;
}

export interface MenuDay {
  date: string;
  uses: string[];
  courses: MenuCourse[];
}

export interface Menu {
  days: MenuDay[];
}

export type GenerateStatus = "DONE" | "IN_PROGRESS" | "FAILED";

/** Credits per request kind, in every response that carries `balance`. */
export type CostsWire = Costs;

/**
 * Present and true on every response that carries `balance` when the account's buyer is blocked for
 * refunds after use: the client hides buying. Absent otherwise.
 */
export interface BlockFlag {
  purchasesBlocked?: true;
  /**
   * Present and true on every response that carries `balance` from a server that reads
   * `optionalIngredients`. A client sends optional ingredients only after a response said so, so a
   * server deployed before the field existed (which ignores it) is never sent pantry data it would
   * not use.
   */
  acceptsOptionalIngredients?: true;
  /**
   * Present and true on every response that carries `balance` from a server that reads the
   * multi-choice config (`minMinutes`, `mealTypes`, `diets`). A client sends that shape only after
   * a response said so; to any other server it sends the legacy single values.
   */
  acceptsMultiChoiceConfig?: true;
  /**
   * Present and true on every response that carries `balance` from a server that writes recipes in
   * Danish (`config.language: "da"`). A client sends `da` only after a response said so; a server
   * from before it refuses `da` as INVALID_REQUEST, so the client asks for another language instead
   * of sending a different one.
   */
  acceptsDanish?: true;
  /**
   * Present and true on every response that carries `balance` from a server that reads `days` on a
   * menu (menu sets of 1 to 4 days, CONTRACT.md "Capability `acceptsMenuDays`"). A client sends
   * `days` only after a response said so, and only when it is not 3; a server from before it
   * ignores the field and would write a 3-day menu at the 3-day price.
   */
  acceptsMenuDays?: true;
  /**
   * Present and true on every response that carries `balance` from a server that reads `taste` as
   * a map of quiz step ids to option ids and has `getTasteQuiz` (CONTRACT.md "Capability
   * `acceptsTaste`"). A client fetches the quiz and sends a taste profile only after a response
   * said so; a server from before it ignores the field and would write without it.
   */
  acceptsTaste?: true;
  /**
   * Present beside `acceptsTaste` on every response that carries `balance`: the taste quiz's
   * current `version` (the value getTasteQuiz returns, so it reflects the catalogue and
   * `config/runtime.tasteQuiz.hidden`). The apps compare it with the quiz they cached to show
   * that there are new questions without fetching the quiz.
   */
  tasteQuizVersion?: string;
  /**
   * Present and true on every response that carries `balance` from a server that has
   * `claimWelcome` (CONTRACT.md "Capability `acceptsWelcome`").
   */
  acceptsWelcome?: true;
  /**
   * Beside `acceptsWelcome` on every response that carries `balance`: how many free recipes a
   * claim grants now (`config/runtime.welcome.recipes`), 0 when the grant is off, generation is
   * paused or today's grants are used up. The same for every account (it does not say whether
   * this account has claimed).
   */
  welcomeRecipes?: number;
  /**
   * Present and true on every response that carries `balance` from a server that reads
   * `staples` (CONTRACT.md "Capability `acceptsStaples`"). A client sends the names of the
   * person's staples only after a response said so; a server from before it ignores the field, so
   * it is never sent names it would not use.
   */
  acceptsStaples?: true;
  /**
   * Present and true on every response that carries `balance` from a server that reads `image`
   * on a recipe request (CONTRACT.md "Capability `acceptsRecipeImage`"). A client offers a
   * picture level, and sends `image: "dish"` or `"steps"`, only after a response said so AND its
   * `costs` carried that level's key (`recipeImage`, `recipeImageSteps`); a server from before it ignores the field and would write the recipe without
   * a picture at the plain price.
   */
  acceptsRecipeImage?: true;
  /**
   * Present and true on every response that carries `balance` from a server that has `reportRecipe` (CONTRACT.md "Capability `acceptsReports`"). The apps show
   * "Report this recipe" only after a response said so.
   */
  acceptsReports?: true;
}

/** Spread into every response that carries `balance` (see BlockFlag). */
export const CAPABILITIES = {
  acceptsOptionalIngredients: true,
  acceptsMultiChoiceConfig: true,
  acceptsDanish: true,
  acceptsMenuDays: true,
  acceptsTaste: true,
  acceptsWelcome: true,
  acceptsStaples: true,
  acceptsRecipeImage: true,
  acceptsReports: true,
} as const;

/**
 * What to spread into every response that carries `balance`: the constant capabilities plus the
 * taste quiz's current version, which depends on the runtime config's hidden list.
 */
export function capabilitiesOf(config: { tasteQuiz: { hidden: readonly string[] } }): typeof CAPABILITIES & { tasteQuizVersion: string } {
  return { ...CAPABILITIES, tasteQuizVersion: tasteQuizVersion(config.tasteQuiz.hidden) };
}

/** generateRecipe and getGeneration. */
export interface GenerateResponse extends BlockFlag {
  status: GenerateStatus;
  recipe?: Recipe;
  menu?: Menu;
  /** DONE, for a request with `image` whose dish picture was made: the picture. */
  image?: RecipeImageWire;
  /**
   * DONE, for a request with `image` delivered without the dish's picture: true. The dish
   * picture's share of the cost was not kept (`balance` shows it).
   */
  imageFailed?: true;
  /**
   * DONE, level "steps", when the model marked at least one step: the step pictures made, by step
   * index (possibly fewer than marked, or none). Absent when no step was marked.
   */
  stepImages?: StepImageWire[];
  /**
   * DONE, level "steps": true when at least one marked step's picture was not delivered (partial
   * or total). Absent when every marked one was delivered, and when none was marked. The steps
   * share is given back when not one step picture was delivered, whether or not this is set.
   */
  stepImagesFailed?: true;
  balance: number;
  charged: boolean;
  costs: CostsWire;
}

/**
 * getWelcomeOffer: how many free recipes a sign-in gets now (0: off, generation paused, or today's
 * grants are used up). The one call the apps make before any sign-in or purchase; it carries
 * nothing about the caller.
 */
export interface WelcomeOfferResponse {
  recipes: number;
}

export type WelcomeStatus = "GRANTED" | "ALREADY" | "OFF";

/** claimWelcome. `granted` is 0 unless `status` is GRANTED. */
export interface WelcomeClaimResponse extends BlockFlag {
  status: WelcomeStatus;
  granted: number;
  balance: number;
  costs: CostsWire;
}

export type VerifyStatus = "GRANTED" | "ALREADY_GRANTED" | "REJECTED_PERMANENT" | "RETRY";

export interface VerifyResponse extends BlockFlag {
  status: VerifyStatus;
  balance: number;
  costs: CostsWire;
}

export type VerifyRequest = (
  | { store: "play"; purchaseToken: string }
  | { store: "appstore"; signedTransaction: string }
) & {
  /** iOS: the buyer saw and accepted the consumption-data line before buying. null when not sent. */
  consumptionConsent: boolean | null;
};

// ---------------------------------------------------------------------------------------------
// Validation helpers

/** Machine-readable reasons carried in HttpsError.details.reason, for the clients to branch on. */
export type ErrorReason =
  | "INVALID_REQUEST"
  | "INSUFFICIENT_CREDITS"
  | "BUSY"
  | "GENERATION_DISABLED"
  | "ACCOUNT_CLOSED"
  | "RATE_LIMITED"
  | "USER_DAILY_LIMIT"
  | "INFRA_FAILURE_LIMIT"
  | "DAILY_CAP"
  | "REQUEST_ID_REUSED"
  | "APP_CHECK_REPLAYED"
  | "RECENT_SIGN_IN_REQUIRED"
  | "MERGE_NOT_ALLOWED"
  | "UNKNOWN_REQUEST"
  | "STORE_MISMATCH"
  | "PURCHASE_ACCOUNT_MISMATCH"
  | "ALLERGEN_CONSENT_REQUIRED"
  | "APPLE_NOT_LINKED"
  | "APPLE_CODE_REJECTED"
  | "APPLE_UNAVAILABLE"
  | "NOT_ENOUGH_ITEMS"
  | "SIGN_IN_REQUIRED"
  | "IMAGE_UNAVAILABLE"
  | "REPORT_LIMIT";

export function fail(
  code: ConstructorParameters<typeof HttpsError>[0],
  reason: ErrorReason,
  message: string,
): never {
  throw new HttpsError(code, message, { reason });
}

function invalid(message: string): never {
  fail("invalid-argument", "INVALID_REQUEST", message);
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function oneOf<T extends string | number>(values: readonly T[], v: unknown, field: string): T {
  if (!(values as readonly unknown[]).includes(v)) invalid(`${field} must be one of ${values.join(", ")}`);
  return v as T;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** A UUID of versions 1 to 8 (the shape of every request and report id). */
export function isUuid(v: unknown): v is string {
  return typeof v === "string" && UUID.test(v);
}

export function isIsoDate(v: unknown): v is string {
  if (typeof v !== "string" || !DATE.test(v)) return false;
  const d = new Date(`${v}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === v;
}

/**
 * HttpsError.details of a refused name: `field` (e.g. "ingredients[3].name", "allergens[0]") and
 * `rule` (an InputRule). `suspicious` rules count against the daily prompt-rejection limit.
 */
export interface NameRejection {
  reason: "INVALID_REQUEST";
  field: string;
  rule: InputRule;
}

export function isNameRejection(details: unknown): details is NameRejection {
  return typeof details === "object" && details !== null && typeof (details as NameRejection).rule === "string"
    && typeof (details as NameRejection).field === "string";
}

export function isSuspiciousRejection(details: unknown): boolean {
  return isNameRejection(details) && SUSPICIOUS_INPUT_RULES.has(details.rule);
}

/** A name normalised and checked by core/inputSafety.ts (CONTRACT.md "Ingredient (request)"). */
function validName(v: unknown, field: string): string {
  const r = normalizeName(v);
  if (!r.ok) {
    const details: NameRejection = { reason: "INVALID_REQUEST", field, rule: r.rule };
    throw new HttpsError("invalid-argument", `${field} is not accepted as a food name (${r.rule})`, details);
  }
  return r.name;
}

const present = (v: unknown): boolean => v !== undefined && v !== null;

/**
 * Either shape of CONTRACT.md "RecipeConfig". A config with `mealTypes` or `diets` is the
 * multi-choice shape: then both lists are required, `minMinutes` is optional, `maxMinutes` is any
 * step of MINUTES_RANGE, and the legacy `mealType` and `diet` must be absent or null (so an old
 * server, which requires them, refuses such a config instead of reading only part of it).
 * Anything else is the legacy shape, validated exactly as before.
 */
export function validateConfig(v: unknown): RecipeConfig {
  if (!isObject(v)) invalid("config must be an object");
  const servings = v.servings;
  if (typeof servings !== "number" || !Number.isInteger(servings) || servings < 1 || servings > 12) {
    invalid("config.servings must be an integer from 1 to 12");
  }
  const common = {
    difficulty: oneOf(DIFFICULTIES, v.difficulty, "config.difficulty"),
    detail: oneOf(DETAILS, v.detail, "config.detail"),
    servings,
  };
  const language = oneOf(LANGUAGES, v.language, "config.language");
  if (present(v.mealTypes) || present(v.diets)) {
    if (present(v.mealType) || present(v.diet)) invalid("config.mealType and config.diet are not allowed with mealTypes and diets");
    const minMinutes = rangeMinutes(v.minMinutes, "config.minMinutes");
    const maxMinutes = rangeMinutes(v.maxMinutes, "config.maxMinutes");
    if (minMinutes !== null && maxMinutes !== null && minMinutes > maxMinutes) invalid("config.minMinutes must not be above config.maxMinutes");
    return {
      ...common,
      minMinutes,
      maxMinutes,
      mealTypes: choices(MEAL_CHOICES, v.mealTypes, "config.mealTypes"),
      diets: choices(DIET_CHOICES, v.diets, "config.diets"),
      language,
    };
  }
  if (present(v.minMinutes)) invalid("config.minMinutes is only allowed with mealTypes and diets");
  const maxMinutes = present(v.maxMinutes) ? oneOf(MAX_MINUTES, v.maxMinutes, "config.maxMinutes") : null;
  const mealType = oneOf(MEAL_TYPES, v.mealType, "config.mealType");
  const diet = oneOf(DIETS, v.diet, "config.diet");
  return {
    ...common,
    minMinutes: null,
    maxMinutes,
    mealTypes: mealType === "any" ? [] : [mealType],
    diets: diet === "none" ? [] : [diet],
    language,
  };
}

/** null, or a multiple of MINUTES_RANGE.step within it. */
function rangeMinutes(v: unknown, field: string): number | null {
  if (!present(v)) return null;
  const { min, max, step } = MINUTES_RANGE;
  if (typeof v !== "number" || !Number.isInteger(v) || v < min || v > max || v % step !== 0) {
    invalid(`${field} must be null or a multiple of ${step} from ${min} to ${max}`);
  }
  return v as number;
}

/** A list of distinct values of `values` (so at most all of them), returned in `values`' order. */
function choices<T extends string>(values: readonly T[], v: unknown, field: string): T[] {
  if (!Array.isArray(v) || v.length > values.length) invalid(`${field} must be a list of at most ${values.length} values`);
  const picked = (v as unknown[]).map((x) => oneOf(values, x, field));
  if (new Set(picked).size !== picked.length) invalid(`${field} must not repeat a value`);
  return values.filter((x) => picked.includes(x));
}

/**
 * The legacy config values a config is the same as (`maxMinutes`, `mealType`, `diet`), or null
 * when only the multi-choice shape can say it. A request is bound to its meaning, not its shape:
 * a legacy request hashes exactly as before the multi-choice shape existed, and so does a
 * multi-choice one that means the same.
 */
export function legacyConfigOf(c: RecipeConfig): [number | null, string, string] | null {
  if (c.minMinutes !== null || c.mealTypes.length > 1 || c.diets.length > 1) return null;
  if (c.maxMinutes !== null && !(MAX_MINUTES as readonly number[]).includes(c.maxMinutes)) return null;
  return [c.maxMinutes, c.mealTypes[0] ?? "any", c.diets[0] ?? "none"];
}

/** A requestId (any UUID version 1-8), lower-cased. */
export function validateRequestId(v: unknown): string {
  if (typeof v !== "string" || !UUID.test(v)) invalid("requestId must be a UUID");
  return v.toLowerCase();
}

/**
 * `nowMs` is the server clock; `today` must be within a day of the server's UTC date either way.
 * `tasteHidden` is `config/runtime.tasteQuiz.hidden`: those steps and options are dropped from
 * `taste`.
 */
export function validateGenerateRequest(
  v: unknown,
  nowMs: number,
  tasteHidden: readonly string[] = [],
  stepImagesMax: number = DEFAULT_MAX_STEP_IMAGES,
): GenerateRequest {
  if (!isObject(v)) invalid("request must be an object");
  const requestId = validateRequestId(v.requestId);
  const kind = oneOf(["recipe", "menu"] as const, v.kind, "kind");
  let courses: MenuCourses | undefined;
  let days: MenuDays | undefined;
  if (kind === "menu") {
    courses = oneOf(MENU_COURSES, v.courses, "courses");
    // Absent (every client before menu sets) or null is the 3-day menu those clients asked for.
    days = present(v.days) ? oneOf(MENU_DAYS, v.days, "days") : DEFAULT_MENU_DAYS;
  } else {
    if (present(v.courses)) invalid("courses is only allowed for a menu");
    if (present(v.days)) invalid("days is only allowed for a menu");
  }
  // `image`: absent or null is no picture; "dish" or "steps" only on a recipe (no menu has pictures).
  const image = present(v.image) && v.image !== false ? oneOf(IMAGE_LEVELS, v.image, "image") : undefined;
  if (image && kind === "menu") invalid("image is only allowed for a recipe");

  if (!isIsoDate(v.today)) invalid("today must be a YYYY-MM-DD date");
  const todayMs = Date.parse(`${v.today}T00:00:00Z`);
  const serverDayMs = Date.parse(`${new Date(nowMs).toISOString().slice(0, 10)}T00:00:00Z`);
  if (Math.abs(todayMs - serverDayMs) > 24 * 60 * 60 * 1000) invalid("today is too far from the server date");

  if (!Array.isArray(v.ingredients) || v.ingredients.length === 0 || v.ingredients.length > MAX_INGREDIENTS) {
    invalid(`ingredients must hold 1 to ${MAX_INGREDIENTS} items`);
  }
  const ingredients = v.ingredients.map((raw: unknown, i: number): IngredientInput => {
    const shape = ingredientShape(raw, `ingredients[${i}]`);
    return { name: validName(raw && (raw as Record<string, unknown>).name, `ingredients[${i}].name`), ...shape };
  });
  const optionalIngredients = validateOptionalIngredients(v.optionalIngredients, ingredients);
  const staples = validateStaples(v.staples, ingredients, optionalIngredients);

  const allergensRaw = v.allergens === undefined ? [] : v.allergens;
  if (!Array.isArray(allergensRaw) || allergensRaw.length > MAX_ALLERGENS) {
    invalid(`allergens must be a list of at most ${MAX_ALLERGENS} names`);
  }
  // An allergen list is data concerning health (GDPR Art. 9): sent only after the person's
  // explicit consent in the app. The server cannot see that consent, but a client that skipped
  // the step (an old or modified build) is refused here, before any name is read or anything is
  // stored or charged.
  if (allergensRaw.length > 0 && v.allergenConsent !== true) {
    fail("failed-precondition", "ALLERGEN_CONSENT_REQUIRED", "allergens are sent only with allergenConsent: true");
  }
  const allergens = allergensRaw.map((a: unknown, i: number) => validName(a, `allergens[${i}]`));
  const tasteSent = parseTaste(v.taste);
  const taste = tasteSent ? cleanTaste(tasteSent, tasteHidden) : undefined;
  const tasteHash = tasteSent ? canonicalTaste(tasteSent) : undefined;

  return {
    requestId,
    kind,
    ...(courses ? { courses } : {}),
    ...(days ? { days } : {}),
    config: validateConfig(v.config),
    ingredients,
    optionalIngredients,
    staples,
    allergens,
    today: v.today,
    ...(taste ? { taste } : {}),
    ...(tasteHash ? { tasteSent: tasteHash } : {}),
    ...(image ? { image } : {}),
    ...(image === "steps" ? { stepImagesMax } : {}),
  };
}

/**
 * `taste`'s shape (CONTRACT.md "Taste profile"): absent or null is none; otherwise an object of at
 * most TASTE_MAX_KEYS keys, each a string of at most TASTE_MAX_ID_CHARS characters whose value is
 * null (nothing chosen), one string (read as a list of one) or an array of at most
 * TASTE_MAX_ENTRIES strings, every string at most TASTE_MAX_ID_CHARS characters; anything else is
 * INVALID_REQUEST. Nothing is checked against the catalogue here: an unknown step or option is
 * never an error (cleanTaste drops it). Returns step → ids, repeats removed, in the order sent.
 */
export function parseTaste(v: unknown): Map<string, string[]> | undefined {
  if (!present(v)) return undefined;
  if (!isObject(v)) invalid("taste must be an object");
  const entries = Object.entries(v);
  if (entries.length > TASTE_MAX_KEYS) invalid(`taste must have at most ${TASTE_MAX_KEYS} steps`);
  const out = new Map<string, string[]>();
  for (const [key, value] of entries) {
    if (key.length > TASTE_MAX_ID_CHARS) invalid(`taste step ids must be at most ${TASTE_MAX_ID_CHARS} characters`);
    if (!present(value)) continue;
    const list = typeof value === "string" ? [value] : value;
    if (!Array.isArray(list) || list.length > TASTE_MAX_ENTRIES) invalid(`taste values must be an id or a list of at most ${TASTE_MAX_ENTRIES} ids`);
    for (const x of list as unknown[]) {
      if (typeof x !== "string" || x.length > TASTE_MAX_ID_CHARS) invalid(`taste ids must be strings of at most ${TASTE_MAX_ID_CHARS} characters`);
    }
    out.set(key, [...new Set(list as string[])]);
  }
  return out;
}

/** `taste` as sent, canonical for inputHashOf: keys sorted, lists sorted, empty lists left out; undefined when nothing is left. */
function canonicalTaste(sent: ReadonlyMap<string, readonly string[]>): [string, string[]][] | undefined {
  const pairs = [...sent.entries()]
    .filter(([, ids]) => ids.length > 0)
    .map(([k, ids]): [string, string[]] => [k, [...ids].sort()])
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  return pairs.length ? pairs : undefined;
}

/** An ingredient's date and quantity, checked; a malformed one refuses the request. */
function ingredientShape(raw: unknown, field: string): { expiresOn: string | null; quantity: number | null } {
  if (!isObject(raw)) invalid(`${field} must be an object`);
  const expiresOn = raw.expiresOn === null || raw.expiresOn === undefined ? null : raw.expiresOn;
  if (expiresOn !== null && !isIsoDate(expiresOn)) invalid(`${field}.expiresOn must be YYYY-MM-DD`);
  const q = raw.quantity === undefined ? null : raw.quantity;
  if (q !== null && (typeof q !== "number" || !Number.isInteger(q) || q < 0 || q > 9999)) {
    invalid(`${field}.quantity must be an integer from 0 to 9999`);
  }
  return { expiresOn: expiresOn as string | null, quantity: q as number | null };
}

/**
 * `optionalIngredients`: absent or null is none. The list itself and each entry's shape are
 * checked as strictly as `ingredients` (an honest client never gets them wrong), but a name the
 * rules refuse, or one already in the request, is only left out: an optional item never stops a
 * recipe, and leaving it out keeps it away from the model just as well. What is kept, in the order
 * sent (the clients send soonest date first), is capped so that `ingredients` and the kept
 * optional items together hold at most MAX_TOTAL_INGREDIENTS names and MAX_OPTIONAL_NAME_CHARS
 * code points of names; an entry that does not fit is skipped. The caps keep the worst-case input
 * of a request with optional items below that of the largest request without them (README "Spend
 * ceilings").
 */
export function validateOptionalIngredients(v: unknown, ingredients: readonly IngredientInput[]): IngredientInput[] {
  if (v === undefined || v === null) return [];
  if (!Array.isArray(v) || v.length > MAX_INGREDIENTS) invalid(`optionalIngredients must be a list of at most ${MAX_INGREDIENTS} items`);
  const seen = new Set(ingredients.map((i) => i.name.toLowerCase()));
  let names = ingredients.length;
  let chars = ingredients.reduce((sum, i) => sum + Array.from(i.name).length, 0);
  const kept: IngredientInput[] = [];
  v.forEach((raw: unknown, i: number) => {
    const shape = ingredientShape(raw, `optionalIngredients[${i}]`);
    const checked = normalizeName((raw as Record<string, unknown>).name);
    if (!checked.ok) return;
    const key = checked.name.toLowerCase();
    const length = Array.from(checked.name).length;
    if (seen.has(key) || names + 1 > MAX_TOTAL_INGREDIENTS || chars + length > MAX_OPTIONAL_NAME_CHARS) return;
    seen.add(key);
    names++;
    chars += length;
    kept.push({ name: checked.name, ...shape, sentIndex: i });
  });
  return kept;
}

/**
 * `staples`: absent or null is none. The list must be an array of at most MAX_STAPLES strings
 * (INVALID_REQUEST otherwise, with no `field` in the details), but a name the rules refuse, or one
 * equal (ignoring case, after normalising) to a ticked ingredient, a kept optional item or an
 * earlier kept staple, is only left out: a staple never stops a recipe and never counts towards
 * the prompt-rejection limit. What is kept, in the order sent, holds at most MAX_STAPLE_NAME_CHARS
 * code points of names (and so at most MAX_STAPLES names, the list's own bound); an entry that does
 * not fit is skipped. This budget is
 * separate from that of the ticked and optional items (README "Spend ceilings").
 */
export function validateStaples(
  v: unknown,
  ingredients: readonly IngredientInput[],
  optionalIngredients: readonly IngredientInput[],
): StapleInput[] {
  if (v === undefined || v === null) return [];
  if (!Array.isArray(v) || v.length > MAX_STAPLES) invalid(`staples must be a list of at most ${MAX_STAPLES} names`);
  if (v.some((s: unknown) => typeof s !== "string")) invalid("staples must be a list of names");
  const seen = new Set([...ingredients, ...optionalIngredients].map((i) => i.name.toLowerCase()));
  let chars = 0;
  const kept: StapleInput[] = [];
  (v as string[]).forEach((raw, i) => {
    const checked = normalizeName(raw);
    if (!checked.ok) return;
    const key = checked.name.toLowerCase();
    const length = Array.from(checked.name).length;
    // At most MAX_STAPLES are kept by construction: a longer list was refused above.
    if (seen.has(key) || chars + length > MAX_STAPLE_NAME_CHARS) return;
    seen.add(key);
    chars += length;
    kept.push({ name: checked.name, sentIndex: i });
  });
  return kept;
}

/**
 * Binds a requestId to its inputs: sha256 of the validated request without the id. A stored
 * generation is returned for a retry only when the hash matches.
 */
export function inputHashOf(req: GenerateRequest): string {
  const inputs = req;
  const canonical = JSON.stringify({
    kind: inputs.kind,
    courses: inputs.courses ?? null,
    // Only when not the default, so a 3-day menu (every request from before menu sets) hashes as
    // it did before the field existed and a retry of a generation stored then still matches.
    ...(inputs.kind === "menu" && menuDaysOf(inputs) !== DEFAULT_MENU_DAYS ? { days: menuDaysOf(inputs) } : {}),
    config: configHashPart(inputs.config),
    ingredients: inputs.ingredients.map((i) => [i.name, i.expiresOn, i.quantity]),
    // Only when there are some, so a request without them hashes as it did before the field
    // existed (a retry of a generation stored by the earlier deploy still matches).
    ...(inputs.optionalIngredients.length > 0
      ? { optionalIngredients: inputs.optionalIngredients.map((i) => [i.name, i.expiresOn, i.quantity]) }
      : {}),
    // Only when some were kept, so a request without staples hashes exactly as before they existed.
    // The names as kept, in order; the allergen and diet filter (usableStaples) is not part of it.
    ...(inputs.staples.length > 0 ? { staples: inputs.staples.map((s) => s.name) } : {}),
    allergens: inputs.allergens,
    today: inputs.today,
    // `taste` as sent (canonical), not the cleaned profile, so a retry with the same answers
    // matches after the catalogue or the hidden list changed; absent when nothing was sent, so a
    // request without it hashes exactly as before taste profiles existed.
    ...(inputs.tasteSent ? { taste: inputs.tasteSent } : {}),
    // Only when asked, so a request without a picture hashes exactly as before pictures existed,
    // and the same requestId with and without a picture is two different requests.
    ...(inputs.image ? { image: inputs.image } : {}),
  });
  return createHash("sha256").update(canonical).digest("hex");
}

/** The config's part of inputHashOf: the legacy values when there are some (see legacyConfigOf). */
function configHashPart(c: RecipeConfig): unknown[] {
  const legacy = legacyConfigOf(c);
  if (legacy) return [c.difficulty, c.detail, c.servings, legacy[0], legacy[1], legacy[2], c.language];
  return [c.difficulty, c.detail, c.servings, { minMinutes: c.minMinutes, maxMinutes: c.maxMinutes, mealTypes: c.mealTypes, diets: c.diets }, c.language];
}

const PLAY_TOKEN =/^[A-Za-z0-9._:-]{20,2048}$/;
const JWS = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;

export function validateVerifyRequest(v: unknown): VerifyRequest {
  if (!isObject(v)) invalid("request must be an object");
  const c = v.consumptionConsent;
  if (c !== undefined && c !== null && typeof c !== "boolean") invalid("consumptionConsent must be a boolean");
  const consumptionConsent = typeof c === "boolean" ? c : null;
  if (v.store === "play") {
    if (typeof v.purchaseToken !== "string" || !PLAY_TOKEN.test(v.purchaseToken)) {
      invalid("purchaseToken is malformed");
    }
    return { store: "play", purchaseToken: v.purchaseToken, consumptionConsent };
  }
  if (v.store === "appstore") {
    if (typeof v.signedTransaction !== "string" || v.signedTransaction.length > 32_768 || !JWS.test(v.signedTransaction)) {
      invalid("signedTransaction must be a compact JWS");
    }
    return { store: "appstore", signedTransaction: v.signedTransaction, consumptionConsent };
  }
  invalid("store must be play or appstore");
}

/** The courses of each menu day, in order: 1 is the main course alone. */
export function coursesFor(courses: MenuCourses): Course[] {
  return courses === 1 ? ["main"] : courses === 2 ? ["starter", "main"] : ["starter", "main", "dessert"];
}

/** A menu request's number of days: its `days`, or DEFAULT_MENU_DAYS for one built without it. */
export function menuDaysOf(req: Pick<GenerateRequest, "days">): MenuDays {
  return req.days ?? DEFAULT_MENU_DAYS;
}

/** The dates of a menu of `days` days starting `today`: today .. today + days - 1. */
export function menuDates(today: string, days: MenuDays): string[] {
  const start = Date.parse(`${today}T00:00:00Z`);
  return Array.from({ length: days }, (_, d) => new Date(start + d * 86_400_000).toISOString().slice(0, 10));
}
