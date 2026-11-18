/**
 * The owner's runtime settings (README "The owner console", "config/runtime"): one declarative
 * registry of the config/runtime fields the owner may change from the console or the admin
 * command (npm run admin -- config list | set | reset), and the one core that changes them.
 *
 * The page renders only what listSettings returns (sections, labels, help, controls, bounds,
 * values, defaults), so a new setting is one entry in SETTINGS and no page change. Every field
 * parseRuntimeConfig reads is either an editable setting here, a read-only entry (READ_ONLY, shown
 * with its value and why it can't be edited here) or EXCLUDED with a reason;
 * test/ownerSettings.test.ts fails on a field that is none of these.
 *
 * applySettings: in one transaction, reads config/runtime, checks every change (known setting,
 * value within its bounds, the stored value the caller saw, its map not invalid as stored), builds
 * the document as it would be, refuses everything unless it parses with no newly invalid field
 * and every set setting reads back as asked, then writes with field paths only (a reset deletes
 * the override, or writes the default of a key the parser requires), and adds an audit record to
 * ownerSettingChanges/. A change to a "sensitive" setting (one that can pause the service, raise
 * a spending ceiling, change what users are charged or given, allow sandbox purchases, or weaken a
 * safety brake) needs the exact list of sensitive ids confirmed (the console also asks for a typed
 * word). Logs owner_settings_changed (warning when sensitive), alerted on for console changes.
 */
import { FieldPath, FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { COST_MAX, DEFAULT_COSTS, DEFAULT_IMAGE_SELECTION, DEFAULT_MENU_DAYS, DEFAULT_TEXT_SELECTION, MAX_STEP_IMAGES_LIMIT, MENU_COURSES, MENU_DAYS } from "../constants";
import {
  OUTPUT_JUDGE_MODES,
  WELCOME_PER_DAY_MAX,
  WELCOME_RECIPES_MAX,
  parseRuntimeConfig,
  uidHash,
  validCosts,
  type ImageSelection,
  type RuntimeConfig,
} from "../model";
import type { TextTarget } from "../ports";
import { allProviders } from "../providers/registry";
import { TASTE_CATALOGUE, type TasteCatalogue } from "../taste/catalogue";
import {
  RUNTIME_DOC,
  SwitchRefused,
  assertOwner,
  invalidFields,
  ownerCallerFromToken,
  scrubOwnerProfile,
  type Actor,
  type OwnerEnv,
  type SwitchEnv,
} from "./providerSwitch";

export const SETTING_CHANGES = "ownerSettingChanges";
/** The word the console asks the owner to type before a sensitive change. */
export const SENSITIVE_CONFIRM_WORD = "CHANGE";
/** Changes in one request at most. */
export const SETTING_CHANGES_MAX = 40;

// --- The registry ---------------------------------------------------------------------------

export type SectionId = "onoff" | "spending" | "perAccount" | "sandbox" | "costs" | "welcome" | "pictures" | "taste" | "reports" | "balances";

export const SECTIONS: readonly { id: SectionId; title: string; help: string }[] = [
  { id: "onoff", title: "On/off", help: "Switches that start or stop parts of the service at once." },
  { id: "spending", title: "Spending limits", help: "Estimated model spend per UTC day, across everyone. At a ceiling new recipes from packs are refused (nothing is charged)." },
  { id: "perAccount", title: "Limits per account", help: "Brakes on what one account can do; each refusal says no recipes were taken." },
  { id: "sandbox", title: "Test purchases", help: "Brakes on sandbox, TestFlight and licence-tester purchases while they are allowed." },
  { id: "costs", title: "What things cost users", help: "Recipes from a pack each request takes. The apps show the table the server sends; more days or courses are never cheaper, and no menu is cheaper than a recipe." },
  { id: "welcome", title: "Free recipes for signing in", help: "The welcome grant: free recipes once per Google or Apple account." },
  { id: "pictures", title: "Pictures", help: "Recipe pictures. The provider and model are switched with the provider profiles above." },
  { id: "taste", title: "Taste quiz", help: "Steps and options left out of the quiz and dropped from requests, without a deploy." },
  { id: "reports", title: "Reports", help: "\"Report this recipe\": how many, and how long they are kept." },
  { id: "balances", title: "Provider balances", help: "The hourly balance check logs provider_balance_low (alerted on) under these thresholds. Empty: not checked." },
];

export interface Option {
  value: string;
  label: string;
}

export type Control =
  | { kind: "toggle" }
  /** unit "usd": stored and sent as integer micro-USD (min, max and step too); the page shows USD. */
  | { kind: "number"; min: number; max: number; step: number; integer: boolean; unit: "count" | "usd" | "fraction" | "recipes" | "days" | "credits"; offAtZero?: string; emptyMeans?: string }
  | { kind: "select"; options: Option[] }
  | { kind: "multi"; options: Option[]; max: number }
  /** A days x courses table; its fixed cells are other settings' values, shown and not edited here. */
  | { kind: "table"; rows: Option[]; cols: Option[]; min: number; max: number; fixed: { row: string; col: string; from: string }[] };

type Data = Record<string, unknown>;

export interface SettingDef {
  id: string;
  section: SectionId;
  label: string;
  help: string;
  control: Control;
  /** Why the change is sensitive (absent: ordinary). */
  sensitive?: string;
  /** Where it is stored in config/runtime. */
  path: readonly string[];
  /** The value in force (the table as configured for costs). */
  read(cfg: RuntimeConfig, data: Data): unknown;
  /** Written by a reset instead of deleting the field (a key the parser requires). */
  resetValue?: unknown;
  /** The stored shape of a value (default: the value itself). */
  toStored?(value: unknown): unknown;
  /** Whether two values are the same setting (default: equal JSON). */
  same?(a: unknown, b: unknown): boolean;
}

/** The costs table as configured (before pictures-off removes its picture prices): what the costs settings show. */
export function configuredCosts(data: Data) {
  return (data.costs === undefined ? null : validCosts(data.costs)) ?? DEFAULT_COSTS;
}

const USD = 1_000_000;
const count = (min: number, max: number, unit: "count" | "recipes" | "days" | "credits" = "count", extra: { offAtZero?: string; emptyMeans?: string } = {}): Control =>
  ({ kind: "number", min, max, step: 1, integer: true, unit, ...extra });
const usd = (maxUsd: number, stepUsd = 0.01): Control => ({ kind: "number", min: 0, max: maxUsd * USD, step: Math.round(stepUsd * USD), integer: true, unit: "usd" });
const top = (key: keyof RuntimeConfig) => (cfg: RuntimeConfig) => cfg[key];

const SPEND = "it changes how much model spend a day can reach, or when recipes from packs pause";
const BRAKE = "it loosens or tightens a brake against abuse or runaway spend";
const PRICE = "it changes what users are charged";

const DAY_ROWS: Option[] = MENU_DAYS.map((d) => ({ value: `${d}`, label: d === 1 ? "1 day" : `${d} days` }));
const COURSE_COLS: Option[] = MENU_COURSES.map((c) => ({ value: `${c}`, label: c === 1 ? "1 course" : `${c} courses` }));
const BASE = `${DEFAULT_MENU_DAYS}`;

/** The quiz's steps and options as choices ("<step>" or "<step>:<option>"), with their English words. */
export function tasteChoices(catalogue: TasteCatalogue = TASTE_CATALOGUE): Option[] {
  const out: Option[] = [];
  for (const s of catalogue.steps) {
    out.push({ value: s.id, label: `Whole step: ${s.question.en}` });
    for (const g of s.groups) for (const o of g.options) out.push({ value: `${s.id}:${o.id}`, label: `${s.question.en} › ${o.label.en}` });
  }
  return out;
}

function balanceSettings(): SettingDef[] {
  const seen = new Set<string>();
  const out: SettingDef[] = [];
  for (const d of allProviders()) {
    if (!d.balance || seen.has(d.id)) continue;
    seen.add(d.id);
    out.push({
      id: `providerBalanceAlerts.${d.id}`,
      section: "balances",
      label: `${d.label} balance alert`,
      help: `Under this balance (in ${d.label}'s own unit) the hourly check logs provider_balance_low.`,
      control: count(0, 1_000_000_000, "credits", { emptyMeans: "not checked" }),
      path: ["providerBalanceAlerts", d.id],
      read: (cfg) => (Object.prototype.hasOwnProperty.call(cfg.providerBalanceAlerts, d.id) ? cfg.providerBalanceAlerts[d.id] : null),
    });
  }
  return out;
}

function tableSame(a: unknown, b: unknown): boolean {
  // The 3-day 2- and 3-course cells are menu2 / menu3 (their own settings): not compared here.
  const strip = (t: unknown) => {
    if (!isObj(t)) return t;
    const copy = structuredClone(t) as Record<string, Record<string, unknown>>;
    if (isObj(copy[BASE])) {
      delete copy[BASE]["2"];
      delete copy[BASE]["3"];
    }
    return copy;
  };
  return stable(strip(a)) === stable(strip(b));
}

export const SETTINGS: readonly SettingDef[] = [
  // On/off
  {
    id: "generationEnabled", section: "onoff", label: "Recipes from packs",
    help: "The kill switch. Off: every new recipe from a pack is refused as paused (nothing charged). The budget alert turns it off; only you turn it back on.",
    control: { kind: "toggle" }, sensitive: "it pauses or resumes every recipe from packs", path: ["generationEnabled"], read: top("generationEnabled"),
  },
  {
    id: "allowSandbox", section: "onoff", label: "Sandbox purchases grant recipes",
    help: "On for App Review and your own testing only: anyone with a TestFlight build can then get free recipes, up to the two test-purchase brakes.",
    control: { kind: "toggle" }, sensitive: "it lets test purchases grant recipes for nothing", path: ["allowSandbox"], read: top("allowSandbox"),
  },
  {
    id: "outputJudge", section: "onoff", label: "Second check of every answer (output judge)",
    help: "Off; shadow (run and log its verdict, never block); enforce (a verdict other than ok is a content failure). Each check is one more model call.",
    control: { kind: "select", options: OUTPUT_JUDGE_MODES.map((m) => ({ value: m, label: m === "off" ? "Off" : m === "shadow" ? "Shadow (log only)" : "Enforce" })) },
    sensitive: "it turns a safety check on or off", path: ["outputJudge"], read: top("outputJudge"),
  },
  // Spending limits
  {
    id: "dailySpendBaseMicroUsd", section: "spending", label: "Daily spend ceiling without sales",
    help: "The ceiling when nothing was sold in the last 7 days. Sales add to it (below), up to the absolute ceiling.",
    control: usd(20), sensitive: SPEND, path: ["dailySpendBaseMicroUsd"], read: top("dailySpendBaseMicroUsd"),
  },
  {
    id: "dailySpendMaxMicroUsd", section: "spending", label: "Absolute daily spend ceiling",
    help: "Never more than this a UTC day, whatever the sales. At it: paused for the rest of the day.",
    control: usd(50), sensitive: SPEND, path: ["dailySpendMaxMicroUsd"], read: top("dailySpendMaxMicroUsd"),
  },
  {
    id: "dailySpendRevenueFraction", section: "spending", label: "Share of revenue added to the ceiling",
    help: "This share of the last 7 days' estimated net revenue is added to the ceiling without sales (0 to 1).",
    control: { kind: "number", min: 0, max: 1, step: 0.05, integer: false, unit: "fraction" }, sensitive: SPEND, path: ["dailySpendRevenueFraction"], read: top("dailySpendRevenueFraction"),
  },
  {
    id: "netMicroUsdPerCredit", section: "spending", label: "Net revenue per recipe sold (estimate)",
    help: "What one production recipe nets after store fees and VAT, conservatively; the revenue part of the ceiling is worked out with it.",
    control: usd(1, 0.001), sensitive: SPEND, path: ["netMicroUsdPerCredit"], read: top("netMicroUsdPerCredit"),
  },
  {
    id: "perUserDailySpendMicroUsd", section: "spending", label: "Spend per account per day",
    help: "Estimated model spend one account may reach in a UTC day; at it: \"That's the limit for today\".",
    control: usd(5), sensitive: SPEND, path: ["perUserDailySpendMicroUsd"], read: top("perUserDailySpendMicroUsd"),
  },
  {
    id: "dailyCap", section: "spending", label: "Recipes a day, everyone together",
    help: "Recipes' worth of requests (each counts its cost) all accounts may start in a UTC day.",
    control: count(0, 100_000, "recipes"), sensitive: SPEND, path: ["dailyCap"], read: top("dailyCap"),
  },
  {
    id: "budgetKillFraction", section: "spending", label: "Budget alert pauses at",
    help: "The billing budget's alert turns recipes from packs off at cost ≥ budget × this (1 is the budget itself).",
    control: { kind: "number", min: 0.05, max: 2, step: 0.05, integer: false, unit: "fraction" }, sensitive: SPEND, path: ["budgetKillFraction"], read: top("budgetKillFraction"),
  },
  // Limits per account
  { id: "perUserPerMinute", section: "perAccount", label: "Recipes started per minute", help: "Per account; over it: too many requests.", control: count(0, 60), sensitive: BRAKE, path: ["perUserPerMinute"], read: top("perUserPerMinute") },
  { id: "perUserPerDay", section: "perAccount", label: "Recipes started per day", help: "Per account per UTC day; over it: \"That's the limit for today\".", control: count(0, 1000), sensitive: BRAKE, path: ["perUserPerDay"], read: top("perUserPerDay") },
  { id: "verifyPerMinute", section: "perAccount", label: "Purchase checks per minute", help: "verifyPurchase calls per account per minute.", control: count(0, 100), sensitive: BRAKE, path: ["verifyPerMinute"], read: top("verifyPerMinute") },
  { id: "infraRefundsPerDay", section: "perAccount", label: "Refunded failures per day", help: "Our failures refunded per account per UTC day; at it: \"We're having trouble writing recipes right now\".", control: count(0, 100), sensitive: BRAKE, path: ["infraRefundsPerDay"], read: top("infraRefundsPerDay") },
  { id: "rejectedPromptsPerDay", section: "perAccount", label: "Refused suspicious requests per day", help: "Requests refused as likely prompt injection per account per UTC day before the account is refused for the day.", control: count(0, 100), sensitive: BRAKE, path: ["rejectedPromptsPerDay"], read: top("rejectedPromptsPerDay") },
  { id: "refundAbuseThreshold", section: "perAccount", label: "Refunds after use that block a buyer", help: "Store refunds of recipes already used before buying is hidden for that buyer.", control: count(1, 100), sensitive: BRAKE, path: ["refundAbuseThreshold"], read: top("refundAbuseThreshold") },
  // Test purchases
  { id: "sandboxDailyCap", section: "sandbox", label: "Test purchases per day", help: "Sandbox purchases that may grant recipes per UTC day, everyone together.", control: count(0, 1000), sensitive: BRAKE, path: ["sandboxDailyCap"], read: top("sandboxDailyCap") },
  { id: "sandboxDailyCredits", section: "sandbox", label: "Recipes test purchases may grant per day", help: "Everyone together; one purchase worth more is refused.", control: count(0, 10_000, "recipes"), sensitive: BRAKE, path: ["sandboxDailyCredits"], read: top("sandboxDailyCredits") },
  // What things cost users
  {
    id: "costs.recipe", section: "costs", label: "A recipe", help: "Recipes a single recipe takes.",
    control: count(1, COST_MAX, "recipes"), sensitive: PRICE, path: ["costs", "recipe"], resetValue: DEFAULT_COSTS.recipe, read: (_c, d) => configuredCosts(d).recipe,
  },
  {
    id: "costs.menu2", section: "costs", label: "A 3-day menu, 2 courses", help: "Also the 3-day 2-course cell of the menu table.",
    control: count(1, COST_MAX, "recipes"), sensitive: PRICE, path: ["costs", "menu2"], resetValue: DEFAULT_COSTS.menu2, read: (_c, d) => configuredCosts(d).menu2,
  },
  {
    id: "costs.menu3", section: "costs", label: "A 3-day menu, 3 courses", help: "Also the 3-day 3-course cell of the menu table.",
    control: count(1, COST_MAX, "recipes"), sensitive: PRICE, path: ["costs", "menu3"], resetValue: DEFAULT_COSTS.menu3, read: (_c, d) => configuredCosts(d).menu3,
  },
  {
    id: "costs.recipeImage", section: "costs", label: "A recipe with a picture of the dish",
    help: "At least a recipe. 0 turns pictures off for the apps. Default: twice a recipe.",
    control: count(0, COST_MAX, "recipes", { offAtZero: "pictures off" }), sensitive: PRICE, path: ["costs", "recipeImage"], read: (_c, d) => configuredCosts(d).recipeImage ?? 0,
  },
  {
    id: "costs.recipeImageSteps", section: "costs", label: "A recipe with step pictures too",
    help: "At least the dish picture's price. 0 turns step pictures off. Default: three times a recipe.",
    control: count(0, COST_MAX, "recipes", { offAtZero: "step pictures off" }), sensitive: PRICE, path: ["costs", "recipeImageSteps"], read: (_c, d) => configuredCosts(d).recipeImageSteps ?? 0,
  },
  {
    id: "costs.menuSets", section: "costs", label: "Menu sets (days × courses)",
    help: "Each cell from 1 to 100. Default: one recipe more a day or a course. The 3-day 2- and 3-course cells are the two settings above.",
    control: { kind: "table", rows: DAY_ROWS, cols: COURSE_COLS, min: 1, max: COST_MAX, fixed: [{ row: BASE, col: "2", from: "costs.menu2" }, { row: BASE, col: "3", from: "costs.menu3" }] },
    sensitive: PRICE, path: ["costs", "menuSets"], read: (_c, d) => configuredCosts(d).menuSets,
    toStored: (v) => {
      const t = structuredClone(v) as Record<string, Record<string, number>>;
      delete t[BASE]["2"];
      delete t[BASE]["3"];
      return t;
    },
    same: tableSame,
  },
  // Free recipes for signing in
  {
    id: "welcome.recipes", section: "welcome", label: "Free recipes for signing in",
    help: "Once per Google or Apple account. 0 turns the offer off everywhere.",
    control: count(0, WELCOME_RECIPES_MAX, "recipes", { offAtZero: "offer off" }), sensitive: "it gives recipes away for nothing", path: ["welcome", "recipes"], read: (c) => c.welcome.recipes,
  },
  {
    id: "welcome.perDay", section: "welcome", label: "Grants a day at most",
    help: "Everyone together, per UTC day: the brake on farming. At it the offer reads 0 until the next day.",
    control: count(0, WELCOME_PER_DAY_MAX), sensitive: BRAKE, path: ["welcome", "perDay"], read: (c) => c.welcome.perDay,
  },
  // Pictures
  {
    id: "image.maxStepImages", section: "pictures", label: "Step pictures at most",
    help: "Step pictures beside the dish's for a recipe with step pictures. 0 turns that level off (the apps stop offering it).",
    control: count(0, MAX_STEP_IMAGES_LIMIT, "count", { offAtZero: "step pictures off" }), sensitive: "it changes what a recipe with step pictures gives and may spend", path: ["image", "maxStepImages"], read: (c) => c.image.maxStepImages,
  },
  // Taste quiz
  {
    id: "tasteQuiz.hidden", section: "taste", label: "Hidden steps and options",
    help: "Ticked ones are left out of the quiz and dropped from requests. A change gives the quiz a new version, which the apps fetch quietly.",
    control: { kind: "multi", options: tasteChoices(), max: 200 }, path: ["tasteQuiz", "hidden"], read: (c) => c.tasteQuiz.hidden,
  },
  // Reports
  { id: "reports.perUserPerDay", section: "reports", label: "Reports per account per day", help: "Per signed-in account per UTC day.", control: count(0, 1000), path: ["reports", "perUserPerDay"], read: (c) => c.reports.perUserPerDay },
  { id: "reports.perDay", section: "reports", label: "Reports a day, everyone together", help: "The first one refused at it logs report_day_limit (alerted on).", control: count(0, 100_000), path: ["reports", "perDay"], read: (c) => c.reports.perDay },
  { id: "reports.keepDays", section: "reports", label: "Days a report is kept", help: "Deleted by Firestore's TTL after this.", control: count(1, 400, "days"), path: ["reports", "keepDays"], read: (c) => c.reports.keepDays },
  // Provider balances
  ...balanceSettings(),
];

/** Fields shown but not editable here, with why. */
export interface ReadOnlyDef {
  id: string;
  label: string;
  reason: string;
  read(cfg: RuntimeConfig): unknown;
}

const RAW_PRICES = "Raw model prices: every reservation, the spend ceilings and the per-account brake are worked out from them, and a price below the provider's real one lets real spend pass every ceiling unnoticed. Change them in Firestore by hand after checking the provider's price page (README \"Price table\"); a provider profile switch raises a picture price it needs itself.";
const BY_PROFILE = "Set by the provider profile switch above, which checks the provider's key, its privacy approval and its price first; a model typed here could be unpriced (pictures off, or the text falling back) or a provider not yet approved.";

export const READ_ONLY: readonly ReadOnlyDef[] = [
  { id: "prices.inputUsdPerMTok", label: "Gemini text: USD per 1M input tokens", reason: RAW_PRICES, read: (c) => c.prices.inputUsdPerMTok },
  { id: "prices.outputUsdPerMTok", label: "Gemini text: USD per 1M output tokens", reason: RAW_PRICES, read: (c) => c.prices.outputUsdPerMTok },
  { id: "prices.imageUsdPerImage", label: "USD per picture, by provider/model", reason: RAW_PRICES, read: (c) => c.prices.imageUsdPerImage ?? {} },
  { id: "prices.textUsdPerMTok", label: "Other text providers' token prices", reason: RAW_PRICES, read: (c) => c.prices.textUsdPerMTok ?? {} },
  { id: "image.provider", label: "Picture provider", reason: BY_PROFILE, read: (c) => c.image.provider },
  { id: "image.model", label: "Picture model", reason: BY_PROFILE, read: (c) => c.image.model },
  { id: "image.fallbackModel", label: "Picture fallback model", reason: BY_PROFILE, read: (c) => c.image.fallbackModel },
  { id: "image.fallbackProvider", label: "Picture fallback provider", reason: BY_PROFILE, read: (c) => c.image.fallbackProvider ?? "" },
  { id: "text.provider", label: "Recipe text provider", reason: BY_PROFILE, read: (c) => c.text.provider },
  { id: "text.model", label: "Recipe text model", reason: BY_PROFILE, read: (c) => c.text.model },
  { id: "text.fallbackProvider", label: "Recipe text fallback provider", reason: BY_PROFILE, read: (c) => c.text.fallbackProvider },
  { id: "text.fallbackModel", label: "Recipe text fallback model", reason: BY_PROFILE, read: (c) => c.text.fallbackModel },
  {
    id: "consumptionSampleContentProvided", label: "Told to Apple: sample content provided",
    reason: "A statement to Apple in every refund consumption answer (the pack sheet explains what a recipe buys before purchase). It changes only if the app's pack sheet changes, with a code review, not as a setting.",
    read: (c) => c.consumptionSampleContentProvided,
  },
];

/** Fields parseRuntimeConfig knows that are neither: why they are left out. */
export const EXCLUDED: Readonly<Record<string, string>> = {
  providerDefaults: "Not stored in config/runtime: the deploy parameters' text and picture defaults (TEXT_PROVIDER, GEMINI_MODEL, IMAGE_*), changed only by a deploy.",
};

/** Maps keyed by an id, edited as one setting per known key ("<field>.<key>"); other keys are kept as stored. */
export const FAMILIES: Readonly<Record<string, string>> = {
  providerBalanceAlerts: "One setting per provider that has a balance probe; a threshold for another id does nothing (provider_balance_unprobed) and is kept as it is.",
};

const BY_ID = new Map(SETTINGS.map((s) => [s.id, s]));
export const settingById = (id: unknown): SettingDef | undefined => (typeof id === "string" ? BY_ID.get(id) : undefined);

/** Problems with the registry itself (run by the tests): duplicate ids, unknown sections, bad bounds. */
export function settingsRegistryProblems(settings: readonly SettingDef[] = SETTINGS): string[] {
  const out: string[] = [];
  const ids = new Set<string>();
  const sections = new Set(SECTIONS.map((s) => s.id));
  for (const s of settings) {
    if (ids.has(s.id)) out.push(`${s.id}: duplicate id`);
    ids.add(s.id);
    if (s.id !== s.path.join(".")) out.push(`${s.id}: the id must be its path`);
    if (!sections.has(s.section)) out.push(`${s.id}: unknown section`);
    if (!s.label || !s.help) out.push(`${s.id}: needs a label and help`);
    const c = s.control;
    if (c.kind === "number" && !(c.min <= c.max && c.step > 0)) out.push(`${s.id}: bad bounds`);
    if ((c.kind === "select" || c.kind === "multi") && c.options.length === 0) out.push(`${s.id}: no options`);
    if (c.kind === "table") for (const f of c.fixed) if (!settings.some((x) => x.id === f.from)) out.push(`${s.id}: fixed cell from unknown ${f.from}`);
  }
  for (const r of READ_ONLY) if (ids.has(r.id)) out.push(`${r.id}: both editable and read-only`);
  return out;
}

// --- Small helpers --------------------------------------------------------------------------

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

/** JSON with sorted keys: equal values give equal strings. */
export function stable(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(stable).join(",")}]`;
  if (isObj(v)) return `{${Object.keys(v).sort().map((k) => JSON.stringify(k) + ":" + stable(v[k])).join(",")}}`;
  return JSON.stringify(v ?? null);
}

function getAt(data: Data, path: readonly string[]): unknown {
  let at: unknown = data;
  for (const seg of path) {
    if (!isObj(at) || !Object.prototype.hasOwnProperty.call(at, seg)) return undefined;
    at = at[seg];
  }
  return at;
}

function setAt(data: Data, path: readonly string[], value: unknown): void {
  let at: Data = data;
  for (const seg of path.slice(0, -1)) {
    if (!isObj(at[seg])) at[seg] = {};
    at = at[seg] as Data;
  }
  at[path[path.length - 1]] = structuredClone(value);
}

function deleteAt(data: Data, path: readonly string[]): void {
  const parent = path.length === 1 ? data : getAt(data, path.slice(0, -1));
  if (isObj(parent)) delete parent[path[path.length - 1]];
}

const same = (def: SettingDef, a: unknown, b: unknown) => (def.same ? def.same(a, b) : stable(a) === stable(b));

// --- Checking a value -----------------------------------------------------------------------

function refuseValue(def: SettingDef, why: string): never {
  throw new SwitchRefused("invalid-argument", "INVALID_VALUE", `${def.label}: ${why}; nothing changed`);
}

const fmtBound = (c: Extract<Control, { kind: "number" }>, n: number) => (c.unit === "usd" ? `USD ${(n / USD).toFixed(c.step < 10_000 ? 3 : 2)}` : String(n));

/** The value, checked against the setting's control; throws INVALID_VALUE (fixed words, never the value itself). */
export function checkValue(def: SettingDef, value: unknown): unknown {
  const c = def.control;
  switch (c.kind) {
    case "toggle":
      if (typeof value !== "boolean") refuseValue(def, "must be on or off");
      return value;
    case "number": {
      if (typeof value !== "number" || !Number.isFinite(value)) refuseValue(def, "must be a number");
      if (c.integer && !Number.isSafeInteger(value)) refuseValue(def, c.unit === "usd" ? "must be whole micro-USD" : "must be a whole number");
      if (value < c.min || value > c.max) refuseValue(def, `must be from ${fmtBound(c, c.min)} to ${fmtBound(c, c.max)}`);
      return value;
    }
    case "select":
      if (typeof value !== "string" || !c.options.some((o) => o.value === value)) refuseValue(def, `must be one of ${c.options.map((o) => o.label).join(", ")}`);
      return value;
    case "multi": {
      if (!Array.isArray(value) || value.length > c.max) refuseValue(def, `must be a list of at most ${c.max}`);
      const list = value as unknown[];
      const known = new Set(c.options.map((o) => o.value));
      if (!list.every((v) => typeof v === "string" && known.has(v))) refuseValue(def, "has an entry that is not one of its choices");
      if (new Set(list).size !== list.length) refuseValue(def, "has an entry twice");
      // Kept in the choices' order, so the same set is always stored the same way.
      const order = new Map(c.options.map((o, i) => [o.value, i]));
      return [...(list as string[])].sort((a, b) => order.get(a)! - order.get(b)!);
    }
    case "table": {
      if (!isObj(value)) refuseValue(def, "must be a table");
      const t = value as Record<string, unknown>;
      const rows = c.rows.map((r) => r.value);
      const cols = c.cols.map((x) => x.value);
      if (Object.keys(t).length !== rows.length || !rows.every((r) => isObj(t[r]))) refuseValue(def, "must have every row");
      const out: Record<string, Record<string, number>> = {};
      for (const r of rows) {
        const row = t[r] as Record<string, unknown>;
        if (Object.keys(row).length !== cols.length) refuseValue(def, "must have every cell");
        out[r] = {};
        for (const k of cols) {
          const n = row[k];
          if (typeof n !== "number" || !Number.isInteger(n) || n < c.min || n > c.max) refuseValue(def, `every cell must be a whole number from ${c.min} to ${c.max}`);
          out[r][k] = n as number;
        }
      }
      return out;
    }
  }
}

// --- The view -------------------------------------------------------------------------------

export interface SettingView {
  id: string;
  label: string;
  help: string;
  control: Control;
  sensitive: boolean;
  sensitiveWhy: string;
  /** In force now. */
  value: unknown;
  /** What a reset would put in force now. */
  default: unknown;
  /** As stored in config/runtime (null: not set, the default applies); sent back as expect. */
  stored: unknown;
  /** The value in force is not the default. */
  changed: boolean;
  /** config/runtime holds a value of its own. */
  overridden: boolean;
  /** Why the value can't be changed now (its map is invalid as stored), or "". */
  blocked: string;
  lastChange: { at: string; via: string } | null;
}

export interface SettingsView {
  sections: { id: string; title: string; help: string; settings: SettingView[] }[];
  readOnly: { id: string; label: string; value: unknown; default: unknown; reason: string }[];
  confirmWord: string;
  /** Fields of config/runtime that are invalid as stored (their safe values are in force). */
  invalid: string[];
  lastChange: { at: string; via: string; settings: string[] } | null;
}

export interface SettingDefaults {
  imageDefaults: ImageSelection;
  textDefaults: TextTarget;
}

const parse = (data: Data, d: SettingDefaults) => parseRuntimeConfig(data, undefined, d.imageDefaults, d.textDefaults);

function resetInto(def: SettingDef, data: Data, next: Data): void {
  if (getAt(data, def.path) === undefined) return;
  if (def.resetValue !== undefined) setAt(next, def.path, def.resetValue);
  else deleteAt(next, def.path);
}

/** What def would read after a reset now (the default in force for this document). */
function defaultOf(def: SettingDef, data: Data, d: SettingDefaults): unknown {
  const next = structuredClone(data);
  resetInto(def, data, next);
  return def.read(parse(next, d), next);
}

function blockedOf(def: SettingDef, data: Data, invalid: string[]): string {
  const field = def.path[0];
  if (def.path.length > 1 && data[field] !== undefined && invalid.includes(field)) {
    return `config/runtime.${field} is invalid as stored, so its safe value is in force; fix or delete it in the Firestore console first`;
  }
  return "";
}

function settingView(def: SettingDef, data: Data, cfg: RuntimeConfig, d: SettingDefaults, invalid: string[], last: Map<string, { at: string; via: string }>): SettingView {
  const value = def.read(cfg, data);
  const dflt = defaultOf(def, data, d);
  const stored = getAt(data, def.path);
  return {
    id: def.id,
    label: def.label,
    help: def.help,
    control: def.control,
    sensitive: def.sensitive !== undefined,
    sensitiveWhy: def.sensitive ?? "",
    value: value ?? null,
    default: dflt ?? null,
    stored: stored === undefined ? null : stored,
    changed: !same(def, value, dflt),
    overridden: stored !== undefined,
    blocked: blockedOf(def, data, invalid),
    lastChange: last.get(def.id) ?? null,
  };
}

export const settingDefaultsOf = (env: Pick<SwitchEnv, "imageDefaults" | "textDefaults">): SettingDefaults => ({
  imageDefaults: env.imageDefaults ?? DEFAULT_IMAGE_SELECTION,
  textDefaults: env.textDefaults ?? DEFAULT_TEXT_SELECTION,
});

/** Pure: the view of a config/runtime document. */
export function settingsViewOf(
  data: Data,
  d: SettingDefaults,
  last = new Map<string, { at: string; via: string }>(),
  lastChange: SettingsView["lastChange"] = null,
): SettingsView {
  const cfg = parse(data, d);
  const empty = parse({}, d);
  const invalid = invalidFields(data, d.imageDefaults, d.textDefaults);
  return {
    sections: SECTIONS.map((s) => ({
      id: s.id,
      title: s.title,
      help: s.help,
      settings: SETTINGS.filter((x) => x.section === s.id).map((x) => settingView(x, data, cfg, d, invalid, last)),
    })).filter((s) => s.settings.length > 0),
    readOnly: READ_ONLY.map((r) => ({ id: r.id, label: r.label, value: r.read(cfg) ?? null, default: r.read(empty) ?? null, reason: r.reason })),
    confirmWord: SENSITIVE_CONFIRM_WORD,
    invalid,
    lastChange,
  };
}

const iso = (t: unknown) => (t instanceof Timestamp ? t.toDate().toISOString() : "");

async function lastChanges(db: Firestore): Promise<{ per: Map<string, { at: string; via: string }>; latest: SettingsView["lastChange"] }> {
  const snap = await db.collection(SETTING_CHANGES).orderBy("at", "desc").limit(100).get();
  const per = new Map<string, { at: string; via: string }>();
  let latest: SettingsView["lastChange"] = null;
  for (const doc of snap.docs) {
    const d = doc.data();
    const at = iso(d.at);
    const via = String(d.actor?.via ?? "");
    const ids: string[] = Array.isArray(d.changes) ? d.changes.map((c: { id?: unknown }) => String(c?.id ?? "")) : [];
    latest ??= { at, via, settings: ids };
    for (const id of ids) if (!per.has(id)) per.set(id, { at, via });
  }
  return { per, latest };
}

export async function listSettings(env: Pick<SwitchEnv, "db" | "imageDefaults" | "textDefaults">): Promise<SettingsView> {
  const [snap, last] = await Promise.all([env.db.doc(RUNTIME_DOC).get(), lastChanges(env.db)]);
  return settingsViewOf((snap.data() ?? {}) as Data, settingDefaultsOf(env), last.per, last.latest);
}

// --- Planning -------------------------------------------------------------------------------

export interface SettingChange {
  id: string;
  /** The new value (absent with reset). */
  value?: unknown;
  reset?: boolean;
  /** The stored value the caller saw (SettingView.stored); a different one now refuses with STALE. */
  expect?: unknown;
}

export interface PlannedSettingWrite {
  path: string[];
  value?: unknown;
  /** The field is deleted. */
  delete?: true;
}

export interface PlannedChange {
  id: string;
  label: string;
  sensitive: boolean;
  reset: boolean;
  /** In force before and after. */
  from: unknown;
  to: unknown;
  /** As stored before and after (null: not set). */
  storedFrom: unknown;
  storedTo: unknown;
}

export interface SettingsPlan {
  changes: PlannedChange[];
  /** Asked, but already so: nothing to write for them. */
  unchanged: string[];
  /** Other settings whose value in force changes with these (e.g. derived menu prices). */
  sideEffects: { id: string; label: string; from: unknown; to: unknown }[];
  writes: PlannedSettingWrite[];
  /** The ids of the sensitive settings among changes, in registry order. */
  sensitive: string[];
}

export interface ParsedChange {
  def: SettingDef;
  change: SettingChange;
  value: unknown;
}

const has = (o: Record<string, unknown>, k: string) => Object.prototype.hasOwnProperty.call(o, k);

/** The request's changes, checked for shape, known ids and values (no document needed). */
export function parseChanges(raw: unknown, opts: { requireExpect: boolean }): ParsedChange[] {
  const bad = (m: string): never => {
    throw new HttpsError("invalid-argument", m, { reason: "INVALID_REQUEST" });
  };
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > SETTING_CHANGES_MAX) bad(`Expected 1 to ${SETTING_CHANGES_MAX} changes`);
  const seen = new Set<string>();
  return (raw as unknown[]).map((c) => {
    if (!isObj(c)) return bad("Expected {id, value} or {id, reset: true}");
    const def = settingById(c.id);
    if (!def) throw new SwitchRefused("invalid-argument", "UNKNOWN_SETTING", "No such setting; reload the page");
    if (seen.has(def.id)) bad(`${def.label} is changed twice`);
    seen.add(def.id);
    const reset = c.reset === true;
    if (reset === has(c, "value")) bad(`${def.label}: give a value or reset: true`);
    if (opts.requireExpect && !has(c, "expect")) bad(`${def.label}: expect is required`);
    const value = reset ? undefined : checkValue(def, c.value);
    return { def, change: { id: def.id, ...(reset ? { reset: true } : { value }), ...(has(c, "expect") ? { expect: c.expect } : {}) }, value };
  });
}

const DEFAULT_COSTS_MAP = { recipe: DEFAULT_COSTS.recipe, menu2: DEFAULT_COSTS.menu2, menu3: DEFAULT_COSTS.menu3 };

function whyInvalid(fields: string[]): string {
  if (fields.includes("costs")) {
    return "a menu must cost at least a recipe, more days or courses never less, a dish picture at least a recipe and step pictures at least a dish picture";
  }
  return "a value the server does not accept";
}

/**
 * Pure: the writes that make the changes over the document data (config/runtime as stored),
 * checked by parsing the result. Throws STALE, STORED_INVALID or INVALID_RESULT.
 */
export function planSettings(data: Data, parsed: readonly ParsedChange[], d: SettingDefaults): SettingsPlan {
  const invalidBefore = invalidFields(data, d.imageDefaults, d.textDefaults);
  const next = structuredClone(data);
  const touched: { def: SettingDef; reset: boolean; value: unknown }[] = [];
  const unchanged: string[] = [];
  for (const { def, change, value } of parsed) {
    const stored = getAt(data, def.path);
    if (has(change as unknown as Record<string, unknown>, "expect") && stable(stored === undefined ? null : stored) !== stable(change.expect)) {
      throw new SwitchRefused("aborted", "STALE", `${def.label} was changed meanwhile; reload and try again`);
    }
    const blocked = blockedOf(def, data, invalidBefore);
    if (blocked) throw new SwitchRefused("failed-precondition", "STORED_INVALID", `${def.label}: ${blocked}; nothing changed`);
    if (change.reset) {
      if (stored === undefined || (def.resetValue !== undefined && stable(stored) === stable(def.resetValue))) {
        unchanged.push(def.id);
        continue;
      }
      resetInto(def, data, next);
    } else {
      const want = def.toStored ? def.toStored(value) : value;
      if (stored !== undefined && stable(stored) === stable(want)) {
        unchanged.push(def.id);
        continue;
      }
      // A costs map is all or nothing: without one stored, its required keys are written beside the change.
      if (def.path[0] === "costs" && !isObj(data.costs)) {
        for (const [k, v] of Object.entries(DEFAULT_COSTS_MAP)) if (getAt(next, ["costs", k]) === undefined) setAt(next, ["costs", k], v);
      }
      setAt(next, def.path, want);
    }
    touched.push({ def, reset: change.reset === true, value });
  }
  // A map left empty by resets goes too.
  for (const t of touched) {
    const field = t.def.path[0];
    if (t.def.path.length > 1 && isObj(next[field]) && Object.keys(next[field] as Data).length === 0) delete next[field];
  }

  // The writes: field paths into a map stored as a map, the whole field otherwise.
  const writes: PlannedSettingWrite[] = [];
  for (const field of [...new Set(touched.map((t) => t.def.path[0]))]) {
    const before = data[field];
    const after = next[field];
    if (stable(before) === stable(after)) continue;
    if (!isObj(before) || after === undefined || touched.some((t) => t.def.path[0] === field && t.def.path.length === 1)) {
      writes.push(after === undefined ? { path: [field], delete: true } : { path: [field], value: structuredClone(after) });
      continue;
    }
    for (const t of touched.filter((x) => x.def.path[0] === field)) {
      const v = getAt(next, t.def.path);
      writes.push(v === undefined ? { path: [...t.def.path], delete: true } : { path: [...t.def.path], value: structuredClone(v) });
    }
  }

  // The check: the document as it would be parses with no field newly invalid, and every setting
  // asked reads back as asked.
  const cfgBefore = parse(data, d);
  const cfgAfter = parse(next, d);
  const newlyInvalid = invalidFields(next, d.imageDefaults, d.textDefaults).filter((f) => !invalidBefore.includes(f));
  if (newlyInvalid.length) {
    throw new SwitchRefused("failed-precondition", "INVALID_RESULT",
      `config/runtime would not be valid with these changes (${newlyInvalid.join(", ")}: ${whyInvalid(newlyInvalid)}); nothing changed`);
  }
  for (const t of touched) {
    if (t.reset) continue;
    if (!same(t.def, t.def.read(cfgAfter, next), t.value)) {
      throw new SwitchRefused("failed-precondition", "INVALID_RESULT", `${t.def.label} would not be in force as asked with these changes; nothing changed`);
    }
  }
  const changes: PlannedChange[] = touched.map((t) => {
    const sf = getAt(data, t.def.path);
    const st = getAt(next, t.def.path);
    return {
      id: t.def.id,
      label: t.def.label,
      sensitive: t.def.sensitive !== undefined,
      reset: t.reset,
      from: t.def.read(cfgBefore, data) ?? null,
      to: t.def.read(cfgAfter, next) ?? null,
      storedFrom: sf === undefined ? null : sf,
      storedTo: st === undefined ? null : st,
    };
  });
  const asked = new Set(touched.map((t) => t.def.id));
  const sideEffects = SETTINGS.filter((s) => !asked.has(s.id))
    .map((s) => ({ s, from: s.read(cfgBefore, data) ?? null, to: s.read(cfgAfter, next) ?? null }))
    .filter((x) => !same(x.s, x.from, x.to))
    .map((x) => ({ id: x.s.id, label: x.s.label, from: x.from, to: x.to }));
  const sensitive = SETTINGS.filter((s) => s.sensitive !== undefined && asked.has(s.id)).map((s) => s.id);
  return { changes, unchanged, sideEffects, writes, sensitive };
}

// --- Applying -------------------------------------------------------------------------------

export interface ApplySettingsOptions {
  apply: boolean;
  actor: Actor;
  /** The sensitive ids the caller confirmed; must be exactly the plan's when it has any. */
  confirmSensitive?: readonly string[];
}

export interface ApplySettingsResult {
  applied: boolean;
  changed: boolean;
  plan: SettingsPlan;
  /** ownerSettingChanges/<id>, when written. */
  changeId?: string;
}

const scalar = (v: unknown) => (v === null || ["number", "boolean", "string"].includes(typeof v) ? v : "(in the audit record)");

/** Makes the changes (README "The owner console"). A dry run plans and checks everything, refusals included, and writes nothing. */
export async function applySettings(env: SwitchEnv, rawChanges: unknown, opts: ApplySettingsOptions): Promise<ApplySettingsResult> {
  const parsed = parseChanges(rawChanges, { requireExpect: opts.actor.via === "web" });
  const d = settingDefaultsOf(env);
  const ref = env.db.doc(RUNTIME_DOC);
  const auditRef = env.db.collection(SETTING_CHANGES).doc();
  const result = await env.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = (snap.data() ?? {}) as Data;
    const plan = planSettings(data, parsed, d);
    if (plan.writes.length === 0) return { plan, written: false };
    if (opts.apply && plan.sensitive.length > 0) {
      const confirmed = [...(opts.confirmSensitive ?? [])].sort();
      if (stable(confirmed) !== stable([...plan.sensitive].sort())) {
        throw new SwitchRefused("failed-precondition", "CONFIRM_SENSITIVE",
          `Sensitive settings need their own confirmation (${plan.changes.filter((c) => c.sensitive).map((c) => c.label).join(", ")}); nothing changed`);
      }
    }
    if (!opts.apply) return { plan, written: false };
    if (!snap.exists) {
      const doc: Data = {};
      for (const w of plan.writes) if (!w.delete) setAt(doc, w.path, w.value);
      tx.set(ref, doc);
    } else {
      const pairs = plan.writes.flatMap((w) => [new FieldPath(...w.path), w.delete ? FieldValue.delete() : w.value]);
      const [first, firstValue, ...rest] = pairs;
      tx.update(ref, first as FieldPath, firstValue, ...rest);
    }
    tx.create(auditRef, {
      at: Timestamp.fromMillis(env.now()),
      actor: opts.actor,
      sensitive: plan.sensitive.length > 0,
      sensitiveSettings: plan.sensitive,
      changes: plan.changes,
      sideEffects: plan.sideEffects,
      writes: plan.writes.map((w) => ({ path: w.path.join(" > "), ...(w.delete ? { deleted: true } : { value: w.value }) })),
    });
    return { plan, written: true };
  });
  if (result.written) {
    const line = {
      via: opts.actor.via,
      ...(opts.actor.via === "web" ? { uidHash: uidHash(opts.actor.uid) } : {}),
      settings: result.plan.changes.map((c) => c.id),
      sensitive: result.plan.sensitive,
      changes: result.plan.changes.map((c) => ({ id: c.id, from: scalar(c.from), to: scalar(c.to) })),
      change: auditRef.id,
    };
    if (result.plan.sensitive.length) env.logger.warn("owner_settings_changed", line);
    else env.logger.info("owner_settings_changed", line);
  }
  return {
    applied: result.written,
    changed: result.plan.writes.length > 0,
    plan: result.plan,
    ...(result.written ? { changeId: auditRef.id } : {}),
  };
}

// --- The two callables' bodies (src/index.ts binds them) --------------------------------------

/** getOwnerSettings: the owner's view. Takes no input. */
export async function getOwnerSettings(env: OwnerEnv, authorization: string | undefined): Promise<SettingsView> {
  const caller = await ownerCallerFromToken(env.auth, authorization);
  await assertOwner(env.db, caller, { write: false, nowMs: env.now(), auth: env.auth });
  await scrubOwnerProfile(env, caller);
  return listSettings(env);
}

/**
 * setOwnerSettings {changes: [{id, value | reset: true, expect}], dryRun: true} answers with the
 * plan (before and after, side effects, which are sensitive) and writes nothing; {changes,
 * confirm: true, sensitive: [ids], typed} makes them (typed must be SENSITIVE_CONFIRM_WORD when
 * any is sensitive), then answers with the new view.
 */
export async function setOwnerSettings(
  env: OwnerEnv,
  authorization: string | undefined,
  data: unknown,
): Promise<SettingsView & { changed: boolean; plan: SettingsPlan }> {
  const caller = await ownerCallerFromToken(env.auth, authorization);
  await assertOwner(env.db, caller, { write: true, nowMs: env.now(), auth: env.auth });
  await scrubOwnerProfile(env, caller);
  const d = isObj(data) ? data : {};
  const dryRun = d.dryRun === true;
  if (!dryRun && d.confirm !== true) {
    throw new HttpsError("invalid-argument", "Expected {changes, dryRun: true} or {changes, confirm: true}", { reason: "INVALID_REQUEST" });
  }
  const sensitive = Array.isArray(d.sensitive) && d.sensitive.every((s) => typeof s === "string") ? (d.sensitive as string[]) : [];
  if (!dryRun && sensitive.length > 0 && d.typed !== SENSITIVE_CONFIRM_WORD) {
    throw new SwitchRefused("failed-precondition", "CONFIRM_SENSITIVE", `Type ${SENSITIVE_CONFIRM_WORD} to confirm the sensitive settings; nothing changed`);
  }
  const out = await applySettings(env, d.changes, { apply: !dryRun, actor: { via: "web", uid: caller.uid }, confirmSensitive: sensitive });
  return { ...(await listSettings(env)), changed: out.applied, plan: out.plan };
}
