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
  NAME_MAX_LENGTH,
} from "./constants";

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

export interface GenerateResponse {
  status: GenerateStatus;
  recipe?: Recipe;
  menu?: Menu;
  balance: number;
  charged: boolean;
  costs: CostsWire;
}

export type VerifyStatus = "GRANTED" | "ALREADY_GRANTED" | "REJECTED_PERMANENT" | "RETRY";

export interface VerifyResponse {
  status: VerifyStatus;
  balance: number;
  costs: CostsWire;
}

export type VerifyRequest =
  | { store: "play"; purchaseToken: string }
  | { store: "appstore"; signedTransaction: string };

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
  | "MERGE_NOT_ALLOWED";

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
/** Letters (any script, with combining marks), digits, space and hyphen. */
const NAME = /^[\p{L}\p{M}\p{N} -]+$/u;

export function isIsoDate(v: unknown): v is string {
  if (typeof v !== "string" || !DATE.test(v)) return false;
  const d = new Date(`${v}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === v;
}

function validName(v: unknown, field: string): string {
  if (typeof v !== "string") invalid(`${field} must be a string`);
  const name = v.normalize("NFC").trim().replace(/ {2,}/g, " ");
  if (name.length === 0 || name.length > NAME_MAX_LENGTH || !NAME.test(name)) {
    invalid(`${field} must be 1-${NAME_MAX_LENGTH} letters, digits, spaces or hyphens`);
  }
  return name;
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

/** `nowMs` is the server clock; `today` must be within a day of the server's UTC date either way. */
export function validateGenerateRequest(v: unknown, nowMs: number): GenerateRequest {
  if (!isObject(v)) invalid("request must be an object");
  if (typeof v.requestId !== "string" || !UUID.test(v.requestId)) invalid("requestId must be a UUID");
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
    if (!isObject(raw)) invalid(`ingredients[${i}] must be an object`);
    const expiresOn = raw.expiresOn === null || raw.expiresOn === undefined ? null : raw.expiresOn;
    if (expiresOn !== null && !isIsoDate(expiresOn)) invalid(`ingredients[${i}].expiresOn must be YYYY-MM-DD`);
    const q = raw.quantity === undefined ? null : raw.quantity;
    if (q !== null && (typeof q !== "number" || !Number.isInteger(q) || q < 0 || q > 9999)) {
      invalid(`ingredients[${i}].quantity must be an integer from 0 to 9999`);
    }
    return { name: validName(raw.name, `ingredients[${i}].name`), expiresOn, quantity: q };
  });

  const allergensRaw = v.allergens === undefined ? [] : v.allergens;
  if (!Array.isArray(allergensRaw) || allergensRaw.length > MAX_ALLERGENS) {
    invalid(`allergens must be a list of at most ${MAX_ALLERGENS} names`);
  }
  const allergens = allergensRaw.map((a: unknown, i: number) => validName(a, `allergens[${i}]`));

  return {
    requestId: v.requestId.toLowerCase(),
    kind,
    ...(courses ? { courses } : {}),
    config: validateConfig(v.config),
    ingredients,
    allergens,
    today: v.today,
  };
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
    allergens: inputs.allergens,
    today: inputs.today,
  });
  return createHash("sha256").update(canonical).digest("hex");
}

const PLAY_TOKEN =/^[A-Za-z0-9._:-]{20,2048}$/;
const JWS = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;

export function validateVerifyRequest(v: unknown): VerifyRequest {
  if (!isObject(v)) invalid("request must be an object");
  if (v.store === "play") {
    if (typeof v.purchaseToken !== "string" || !PLAY_TOKEN.test(v.purchaseToken)) {
      invalid("purchaseToken is malformed");
    }
    return { store: "play", purchaseToken: v.purchaseToken };
  }
  if (v.store === "appstore") {
    if (typeof v.signedTransaction !== "string" || v.signedTransaction.length > 32_768 || !JWS.test(v.signedTransaction)) {
      invalid("signedTransaction must be a compact JWS");
    }
    return { store: "appstore", signedTransaction: v.signedTransaction };
  }
  invalid("store must be play or appstore");
}

// ---------------------------------------------------------------------------------------------
// LLM output validation. Anything that fails here is an "invalid output" content failure.

function str(v: unknown, max: number, min = 1): string | null {
  if (typeof v !== "string") return null;
  const s = v.trim();
  return s.length >= min && s.length <= max ? s : null;
}

function int(v: unknown, min: number, max: number): number | null {
  return typeof v === "number" && Number.isInteger(v) && v >= min && v <= max ? v : null;
}

export function parseRecipe(v: unknown): Recipe | null {
  if (!isObject(v)) return null;
  const title = str(v.title, 120);
  const summary = str(v.summary, 600);
  const minutes = int(v.minutes, 1, 24 * 60);
  const servings = int(v.servings, 1, 24);
  const difficulty = (DIFFICULTIES as readonly unknown[]).includes(v.difficulty) ? (v.difficulty as Difficulty) : null;
  if (!title || !summary || minutes === null || servings === null || !difficulty) return null;
  if (!Array.isArray(v.ingredients) || v.ingredients.length === 0 || v.ingredients.length > 40) return null;
  const ingredients: RecipeIngredient[] = [];
  for (const raw of v.ingredients) {
    if (!isObject(raw)) return null;
    const amount = str(raw.amount, 60, 0);
    const name = str(raw.name, 80);
    if (amount === null || !name || typeof raw.fromPantry !== "boolean") return null;
    ingredients.push({ amount, name, fromPantry: raw.fromPantry });
  }
  if (!Array.isArray(v.steps) || v.steps.length === 0 || v.steps.length > 30) return null;
  const steps: string[] = [];
  for (const raw of v.steps) {
    const step = str(raw, 800);
    if (!step) return null;
    steps.push(step);
  }
  let tip: string | undefined;
  if (v.tip !== undefined && v.tip !== null && v.tip !== "") {
    const t = str(v.tip, 600);
    if (!t) return null;
    tip = t;
  }
  return { title, summary, minutes, servings, difficulty, ingredients, steps, ...(tip ? { tip } : {}) };
}

export function coursesFor(courses: 2 | 3): Course[] {
  return courses === 2 ? ["starter", "main"] : ["starter", "main", "dessert"];
}

export function menuDates(today: string): string[] {
  const start = Date.parse(`${today}T00:00:00Z`);
  return [0, 1, 2].map((d) => new Date(start + d * 86_400_000).toISOString().slice(0, 10));
}

/** A menu must have exactly the 3 expected days, each with exactly the requested courses; else it is invalid. */
export function parseMenu(v: unknown, today: string, courses: 2 | 3): Menu | null {
  if (!isObject(v) || !Array.isArray(v.days) || v.days.length !== 3) return null;
  const dates = menuDates(today);
  const wanted = coursesFor(courses);
  const days: MenuDay[] = [];
  for (let i = 0; i < 3; i++) {
    const raw = v.days[i];
    if (!isObject(raw) || raw.date !== dates[i]) return null;
    if (!Array.isArray(raw.uses) || raw.uses.length > MAX_INGREDIENTS) return null;
    const uses: string[] = [];
    for (const u of raw.uses) {
      const s = str(u, 80);
      if (!s) return null;
      uses.push(s);
    }
    if (!Array.isArray(raw.courses) || raw.courses.length !== wanted.length) return null;
    const byCourse = new Map<Course, Recipe>();
    for (const c of raw.courses) {
      if (!isObject(c) || !(wanted as unknown[]).includes(c.course) || byCourse.has(c.course as Course)) return null;
      const recipe = parseRecipe(c.recipe);
      if (!recipe) return null;
      byCourse.set(c.course as Course, recipe);
    }
    days.push({ date: dates[i], uses, courses: wanted.map((course) => ({ course, recipe: byCourse.get(course)! })) });
  }
  return { days };
}
