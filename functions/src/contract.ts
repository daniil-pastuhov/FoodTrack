/**
 * The wire contract shared with the Android and iOS clients (see CONTRACT.md), and its validation.
 * Everything a client sends is validated here before it reaches Firestore or the LLM.
 */
import { HttpsError } from "firebase-functions/v2/https";
import { createHash } from "node:crypto";
import {
  type Costs,
  GenerationKind,
  LANGUAGES,
  MAX_ALLERGENS,
  MAX_INGREDIENTS,
  MAX_OPTIONAL_NAME_CHARS,
  MAX_TOTAL_INGREDIENTS,
} from "./constants";
import { SUSPICIOUS_INPUT_RULES, normalizeName, type InputRule } from "./core/inputSafety";

export const DIFFICULTIES = ["easy", "medium", "ambitious"] as const;
export const DETAILS = ["brief", "standard", "stepByStep"] as const;
export const MAX_MINUTES = [15, 30, 60] as const;
export const MEAL_TYPES = ["any", "breakfast", "lunch", "dinner", "snack", "dessert"] as const;
export const DIETS = ["none", "vegetarian", "vegan", "pescatarian", "glutenFree", "dairyFree"] as const;
export const COURSES = ["starter", "main", "dessert"] as const;

export type Difficulty = (typeof DIFFICULTIES)[number];
export type Language = (typeof LANGUAGES)[number];
export type Course = (typeof COURSES)[number];

export interface RecipeConfig {
  difficulty: Difficulty;
  detail: (typeof DETAILS)[number];
  servings: number;
  maxMinutes: (typeof MAX_MINUTES)[number] | null;
  mealType: (typeof MEAL_TYPES)[number];
  diet: (typeof DIETS)[number];
  language: Language;
}

export interface IngredientInput {
  name: string;
  /** YYYY-MM-DD, or null for an item without an expiry date. */
  expiresOn: string | null;
  /** Item count in the pantry, or null when unknown. */
  quantity: number | null;
}

export interface GenerateRequest {
  requestId: string;
  kind: GenerationKind;
  courses?: 2 | 3;
  config: RecipeConfig;
  ingredients: IngredientInput[];
  /**
   * The rest of the pantry, which the recipe MAY use as well ("Send all products" in the apps):
   * validated like `ingredients`, but a name the rules refuse is left out rather than refusing the
   * request, and what is kept is capped together with `ingredients` (CONTRACT.md "Optional
   * ingredients"). Empty when the client sent none.
   */
  optionalIngredients: IngredientInput[];
  allergens: string[];
  today: string;
}

export interface RecipeIngredient {
  amount: string;
  name: string;
  fromPantry: boolean;
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
}

/** Spread into every response that carries `balance` (see BlockFlag). */
export const CAPABILITIES = { acceptsOptionalIngredients: true } as const;

/** generateRecipe and getGeneration. */
export interface GenerateResponse extends BlockFlag {
  status: GenerateStatus;
  recipe?: Recipe;
  menu?: Menu;
  balance: number;
  charged: boolean;
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
  | "APPLE_UNAVAILABLE";

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

export function validateConfig(v: unknown): RecipeConfig {
  if (!isObject(v)) invalid("config must be an object");
  const servings = v.servings;
  if (typeof servings !== "number" || !Number.isInteger(servings) || servings < 1 || servings > 12) {
    invalid("config.servings must be an integer from 1 to 12");
  }
  const maxMinutes = v.maxMinutes === null || v.maxMinutes === undefined
    ? null
    : oneOf(MAX_MINUTES, v.maxMinutes, "config.maxMinutes");
  return {
    difficulty: oneOf(DIFFICULTIES, v.difficulty, "config.difficulty"),
    detail: oneOf(DETAILS, v.detail, "config.detail"),
    servings,
    maxMinutes,
    mealType: oneOf(MEAL_TYPES, v.mealType, "config.mealType"),
    diet: oneOf(DIETS, v.diet, "config.diet"),
    language: oneOf(LANGUAGES, v.language, "config.language"),
  };
}

/** A requestId (any UUID version 1-8), lower-cased. */
export function validateRequestId(v: unknown): string {
  if (typeof v !== "string" || !UUID.test(v)) invalid("requestId must be a UUID");
  return v.toLowerCase();
}

/** `nowMs` is the server clock; `today` must be within a day of the server's UTC date either way. */
export function validateGenerateRequest(v: unknown, nowMs: number): GenerateRequest {
  if (!isObject(v)) invalid("request must be an object");
  const requestId = validateRequestId(v.requestId);
  const kind = oneOf(["recipe", "menu"] as const, v.kind, "kind");
  let courses: 2 | 3 | undefined;
  if (kind === "menu") courses = oneOf([2, 3] as const, v.courses, "courses");
  else if (v.courses !== undefined && v.courses !== null) invalid("courses is only allowed for a menu");

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

  return {
    requestId,
    kind,
    ...(courses ? { courses } : {}),
    config: validateConfig(v.config),
    ingredients,
    optionalIngredients,
    allergens,
    today: v.today,
  };
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
    kept.push({ name: checked.name, ...shape });
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
    config: [
      inputs.config.difficulty, inputs.config.detail, inputs.config.servings, inputs.config.maxMinutes,
      inputs.config.mealType, inputs.config.diet, inputs.config.language,
    ],
    ingredients: inputs.ingredients.map((i) => [i.name, i.expiresOn, i.quantity]),
    // Only when there are some, so a request without them hashes as it did before the field
    // existed (a retry of a generation stored by the earlier deploy still matches).
    ...(inputs.optionalIngredients.length > 0
      ? { optionalIngredients: inputs.optionalIngredients.map((i) => [i.name, i.expiresOn, i.quantity]) }
      : {}),
    allergens: inputs.allergens,
    today: inputs.today,
  });
  return createHash("sha256").update(canonical).digest("hex");
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

export function coursesFor(courses: 2 | 3): Course[] {
  return courses === 2 ? ["starter", "main"] : ["starter", "main", "dessert"];
}

export function menuDates(today: string): string[] {
  const start = Date.parse(`${today}T00:00:00Z`);
  return [0, 1, 2].map((d) => new Date(start + d * 86_400_000).toISOString().slice(0, 10));
}
