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
import { createHash } from "node:crypto";
import { FieldPath, FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { COST_MAX, DEFAULT_COSTS, DEFAULT_IMAGE_SELECTION, DEFAULT_MENU_DAYS, DEFAULT_MENU_SETS, DEFAULT_TEXT_SELECTION, MAX_STEP_IMAGES_LIMIT, MENU_COURSES, MENU_DAYS, type Costs } from "../constants";
import {
  DEFAULT_RUNTIME_CONFIG,
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
/** A sensitive change from the console needs a sign-in at most this old (ordinary ones: OWNER_RECENT_SIGN_IN_S). */
export const OWNER_SENSITIVE_SIGN_IN_S = 5 * 60;
/** disabledBy as the console and the command write it when they pause generation. */
export const PAUSED_BY = { web: "owner console", cli: "admin command" } as const;
/** disabledBy as the budget kill switch writes it (core/notifications.ts handleBudgetAlert). */
export const BUDGET_PAUSE_PREFIX = "budget alert";
/** A price may be raised on the console up to this many times its default, rounded up. */
export const WEB_PRICE_CEILING_FACTOR = 1.5;
/** A price may be raised on the console by at most this much per save. */
export const WEB_PRICE_STEP = 1;

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
  /** What the owner console may do with it (absent: the whole range above). The command keeps the whole range. */
  web?: WebRule;
}

/**
 * The console's narrower envelope (README "Settings", "The console's envelope"). The page is a
 * browser session that can be stolen; the command needs the owner's own cloud credentials, so it
 * keeps the whole range of each control. A move in a rule's safe direction is always allowed.
 * - range: from min to max (both relative to the code defaults, fixed); safe "lower" also allows
 *   any lowering (a value set higher with the command can be brought down), "higher" any raising;
 *   noClear: a set value can't be cleared.
 * - lowerOnly: never above the value in force.
 * - offOnly: a switch the page may only turn off.
 * - stricter: a choice the page may only move later in its options (off -> shadow -> enforce).
 * - pause: off at any time; on, except while the budget kill switch's pause stands (disabledBy).
 * - costs: the prices' own rule (costsWebRefusal), over the whole table, derived cells included.
 */
export type WebRule =
  | { kind: "range"; min?: number; max?: number; safe?: "lower" | "higher"; noClear?: boolean }
  | { kind: "lowerOnly" }
  | { kind: "offOnly" }
  | { kind: "stricter" }
  | { kind: "pause" }
  | { kind: "costs" };

/** The costs table as configured (before pictures-off removes its picture prices): what the costs settings show. */
export function configuredCosts(data: Data) {
  return (data.costs === undefined ? null : validCosts(data.costs)) ?? DEFAULT_COSTS;
}

const USD = 1_000_000;
const count = (min: number, max: number, unit: "count" | "recipes" | "days" | "credits" = "count", extra: { offAtZero?: string; emptyMeans?: string } = {}): Control =>
  ({ kind: "number", min, max, step: 1, integer: true, unit, ...extra });
const usd = (maxUsd: number, stepUsd = 0.01): Control => ({ kind: "number", min: 0, max: maxUsd * USD, step: Math.round(stepUsd * USD), integer: true, unit: "usd" });
const top = (key: keyof RuntimeConfig) => (cfg: RuntimeConfig) => cfg[key];
const DR = DEFAULT_RUNTIME_CONFIG;
/** Web: up to factor x the code default (rounded down to the control's step); any lowering. */
const upTo = (dflt: number, factor: number, step = 1): WebRule => ({ kind: "range", max: Math.floor((dflt * factor) / step) * step, safe: "lower" });
const LOWER_ONLY: WebRule = { kind: "lowerOnly" };
const COSTS_RULE: WebRule = { kind: "costs" };

const SPEND = "it changes how much model spend a day can reach, or when recipes from packs pause";
const REPORTS = "it changes how many recipes users can report, or how long reports are kept";
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
      sensitive: "it changes when you are told a provider's prepaid balance is running out",
      // Raised or lowered on the page, never cleared there or set to 0 (which never warns).
      web: { kind: "range", min: 1, noClear: true },
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
    id: "generationEnabled", web: { kind: "pause" }, section: "onoff", label: "Recipes from packs",
    help: "The kill switch. Off: every new recipe from a pack is refused as paused (nothing charged). The budget alert turns it off; after its pause only the command turns it back on (and clears disabledBy).",
    control: { kind: "toggle" }, sensitive: "it pauses or resumes every recipe from packs", path: ["generationEnabled"], read: top("generationEnabled"),
  },
  {
    id: "allowSandbox", web: { kind: "offOnly" }, section: "onoff", label: "Sandbox purchases grant recipes",
    help: "On for App Review and your own testing only: anyone with a TestFlight build can then get free recipes, up to the two test-purchase brakes.",
    control: { kind: "toggle" }, sensitive: "it lets test purchases grant recipes for nothing", path: ["allowSandbox"], read: top("allowSandbox"),
  },
  {
    id: "outputJudge", web: { kind: "stricter" }, section: "onoff", label: "Second check of every answer (output judge)",
    help: "Off; shadow (run and log its verdict, never block); enforce (a verdict other than ok is a content failure). Each check is one more model call.",
    control: { kind: "select", options: OUTPUT_JUDGE_MODES.map((m) => ({ value: m, label: m === "off" ? "Off" : m === "shadow" ? "Shadow (log only)" : "Enforce" })) },
    sensitive: "it turns a safety check on or off", path: ["outputJudge"], read: top("outputJudge"),
  },
  // Spending limits
  {
    id: "dailySpendBaseMicroUsd", web: upTo(DR.dailySpendBaseMicroUsd, 2.5, 10_000), section: "spending", label: "Daily spend ceiling without sales",
    help: "The ceiling when nothing was sold in the last 7 days. Sales add to it (below), up to the absolute ceiling.",
    control: usd(20), sensitive: SPEND, path: ["dailySpendBaseMicroUsd"], read: top("dailySpendBaseMicroUsd"),
  },
  {
    id: "dailySpendMaxMicroUsd", web: upTo(DR.dailySpendMaxMicroUsd, 2.5, 10_000), section: "spending", label: "Absolute daily spend ceiling",
    help: "Never more than this a UTC day, whatever the sales. At it: paused for the rest of the day.",
    control: usd(50), sensitive: SPEND, path: ["dailySpendMaxMicroUsd"], read: top("dailySpendMaxMicroUsd"),
  },
  {
    id: "dailySpendRevenueFraction", web: LOWER_ONLY, section: "spending", label: "Share of revenue added to the ceiling",
    help: "This share of the last 7 days' estimated net revenue is added to the ceiling without sales (0 to 1).",
    control: { kind: "number", min: 0, max: 1, step: 0.05, integer: false, unit: "fraction" }, sensitive: SPEND, path: ["dailySpendRevenueFraction"], read: top("dailySpendRevenueFraction"),
  },
  {
    id: "netMicroUsdPerCredit", web: LOWER_ONLY, section: "spending", label: "Net revenue per recipe sold (estimate)",
    help: "What one recipe bought in a store nets after store fees and VAT, conservatively; the revenue part of the ceiling is worked out with it (net recipes sold in the last 7 days × this × the share above). Only store-bought production recipes count: free recipes (the welcome grant) and test purchases add nothing to it, while the model spend of what free recipes pay for counts in the spend like everyone's.",
    control: usd(1, 0.001), sensitive: SPEND, path: ["netMicroUsdPerCredit"], read: top("netMicroUsdPerCredit"),
  },
  {
    id: "perUserDailySpendMicroUsd", web: upTo(DR.perUserDailySpendMicroUsd, 2, 10_000), section: "spending", label: "Spend per account per day",
    help: "Estimated model spend one account may reach in a UTC day; at it: \"That's the limit for today\".",
    control: usd(5), sensitive: SPEND, path: ["perUserDailySpendMicroUsd"], read: top("perUserDailySpendMicroUsd"),
  },
  {
    id: "dailyCap", web: upTo(DR.dailyCap, 2.5), section: "spending", label: "Recipes a day, everyone together",
    help: "Recipes' worth of requests (each counts its cost) all accounts may start in a UTC day.",
    control: count(0, 100_000, "recipes"), sensitive: SPEND, path: ["dailyCap"], read: top("dailyCap"),
  },
  {
    id: "budgetKillFraction", web: LOWER_ONLY, section: "spending", label: "Budget alert pauses at",
    help: "The billing budget's alert turns recipes from packs off at cost ≥ budget × this (1 is the budget itself).",
    control: { kind: "number", min: 0.05, max: 2, step: 0.05, integer: false, unit: "fraction" }, sensitive: SPEND, path: ["budgetKillFraction"], read: top("budgetKillFraction"),
  },
  // Limits per account
  { id: "perUserPerMinute", web: upTo(DR.perUserPerMinute, 2), section: "perAccount", label: "Recipes started per minute", help: "Per account; over it: too many requests.", control: count(0, 60), sensitive: BRAKE, path: ["perUserPerMinute"], read: top("perUserPerMinute") },
  { id: "perUserPerDay", web: upTo(DR.perUserPerDay, 2), section: "perAccount", label: "Recipes started per day", help: "Per account per UTC day; over it: \"That's the limit for today\".", control: count(0, 1000), sensitive: BRAKE, path: ["perUserPerDay"], read: top("perUserPerDay") },
  { id: "verifyPerMinute", web: upTo(DR.verifyPerMinute, 2), section: "perAccount", label: "Purchase checks per minute", help: "verifyPurchase calls per account per minute.", control: count(0, 100), sensitive: BRAKE, path: ["verifyPerMinute"], read: top("verifyPerMinute") },
  { id: "infraRefundsPerDay", web: upTo(DR.infraRefundsPerDay, 2), section: "perAccount", label: "Refunded failures per day", help: "Our failures refunded per account per UTC day; at it: \"We're having trouble writing recipes right now\".", control: count(0, 100), sensitive: BRAKE, path: ["infraRefundsPerDay"], read: top("infraRefundsPerDay") },
  { id: "rejectedPromptsPerDay", web: upTo(DR.rejectedPromptsPerDay, 2), section: "perAccount", label: "Refused suspicious requests per day", help: "Requests refused as likely prompt injection per account per UTC day before the account is refused for the day.", control: count(0, 100), sensitive: BRAKE, path: ["rejectedPromptsPerDay"], read: top("rejectedPromptsPerDay") },
  { id: "refundAbuseThreshold", web: LOWER_ONLY, section: "perAccount", label: "Refunds after use that block a buyer", help: "Store refunds of recipes already used before buying is hidden for that buyer.", control: count(1, 100), sensitive: BRAKE, path: ["refundAbuseThreshold"], read: top("refundAbuseThreshold") },
  // Test purchases
  { id: "sandboxDailyCap", web: upTo(DR.sandboxDailyCap, 1), section: "sandbox", label: "Test purchases per day", help: "Sandbox purchases that may grant recipes per UTC day, everyone together.", control: count(0, 1000), sensitive: BRAKE, path: ["sandboxDailyCap"], read: top("sandboxDailyCap") },
  { id: "sandboxDailyCredits", web: upTo(DR.sandboxDailyCredits, 1), section: "sandbox", label: "Recipes test purchases may grant per day", help: "Everyone together; one purchase worth more is refused.", control: count(0, 10_000, "recipes"), sensitive: BRAKE, path: ["sandboxDailyCredits"], read: top("sandboxDailyCredits") },
  // What things cost users
  {
    id: "costs.recipe", web: COSTS_RULE, section: "costs", label: "A recipe", help: "Recipes a single recipe takes.",
    control: count(1, COST_MAX, "recipes"), sensitive: PRICE, path: ["costs", "recipe"], resetValue: DEFAULT_COSTS.recipe, read: (_c, d) => configuredCosts(d).recipe,
  },
  {
    id: "costs.menu2", web: COSTS_RULE, section: "costs", label: "A 3-day menu, 2 courses", help: "Also the 3-day 2-course cell of the menu table.",
    control: count(1, COST_MAX, "recipes"), sensitive: PRICE, path: ["costs", "menu2"], resetValue: DEFAULT_COSTS.menu2, read: (_c, d) => configuredCosts(d).menu2,
  },
  {
    id: "costs.menu3", web: COSTS_RULE, section: "costs", label: "A 3-day menu, 3 courses", help: "Also the 3-day 3-course cell of the menu table.",
    control: count(1, COST_MAX, "recipes"), sensitive: PRICE, path: ["costs", "menu3"], resetValue: DEFAULT_COSTS.menu3, read: (_c, d) => configuredCosts(d).menu3,
  },
  {
    id: "costs.recipeImage", web: COSTS_RULE, section: "costs", label: "A recipe with a picture of the dish",
    help: "At least a recipe. 0 turns pictures off for the apps. Default: twice a recipe.",
    control: count(0, COST_MAX, "recipes", { offAtZero: "pictures off" }), sensitive: PRICE, path: ["costs", "recipeImage"], read: (_c, d) => configuredCosts(d).recipeImage ?? 0,
  },
  {
    id: "costs.recipeImageSteps", web: COSTS_RULE, section: "costs", label: "A recipe with step pictures too",
    help: "At least the dish picture's price. 0 turns step pictures off. Default: three times a recipe.",
    control: count(0, COST_MAX, "recipes", { offAtZero: "step pictures off" }), sensitive: PRICE, path: ["costs", "recipeImageSteps"], read: (_c, d) => configuredCosts(d).recipeImageSteps ?? 0,
  },
  {
    id: "costs.menuSets", web: COSTS_RULE, section: "costs", label: "Menu sets (days × courses)",
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
    id: "welcome.recipes", web: { kind: "range", max: 5, safe: "lower" }, section: "welcome", label: "Free recipes for signing in",
    help: "Once per Google or Apple account. 0 turns the offer off everywhere.",
    control: count(0, WELCOME_RECIPES_MAX, "recipes", { offAtZero: "offer off" }), sensitive: "it gives recipes away for nothing", path: ["welcome", "recipes"], read: (c) => c.welcome.recipes,
  },
  {
    id: "welcome.perDay", web: { kind: "range", max: 500, safe: "lower" }, section: "welcome", label: "Grants a day at most",
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
  { id: "reports.perUserPerDay", web: { kind: "range", min: 1, safe: "higher" }, section: "reports", label: "Reports per account per day", help: "Per signed-in account per UTC day.", control: count(0, 1000), sensitive: REPORTS, path: ["reports", "perUserPerDay"], read: (c) => c.reports.perUserPerDay },
  { id: "reports.perDay", web: { kind: "range", min: 10, safe: "higher" }, section: "reports", label: "Reports a day, everyone together", help: "The first one refused at it logs report_day_limit (alerted on).", control: count(0, 100_000), sensitive: REPORTS, path: ["reports", "perDay"], read: (c) => c.reports.perDay },
  { id: "reports.keepDays", web: { kind: "range", min: 30, safe: "higher" }, section: "reports", label: "Days a report is kept", help: "Deleted by Firestore's TTL after this.", control: count(1, 400, "days"), sensitive: REPORTS, path: ["reports", "keepDays"], read: (c) => c.reports.keepDays },
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

/** Settings the console may change within their whole range, and why that is safe. */
export const WEB_UNRESTRICTED: Readonly<Record<string, string>> = {
  "image.maxStepImages": "0 to 4 step pictures: each is priced into the recipe with step pictures and booked against the spend ceilings, which the console can't raise past its envelope.",
  "tasteQuiz.hidden": "Hiding quiz steps or options only leaves soft preferences out of the quiz and the prompt; it charges, gives or spends nothing.",
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
    // The console's envelope: every setting has one or says why it needs none, and fits its control.
    const w = s.web;
    if (!w && !(s.id in WEB_UNRESTRICTED)) out.push(`${s.id}: no web rule and not in WEB_UNRESTRICTED`);
    if (w && s.id in WEB_UNRESTRICTED) out.push(`${s.id}: both a web rule and WEB_UNRESTRICTED`);
    if (w) {
      const fits = { range: c.kind === "number", lowerOnly: c.kind === "number", offOnly: c.kind === "toggle", pause: c.kind === "toggle", stricter: c.kind === "select", costs: s.path[0] === "costs" }[w.kind];
      if (!fits) out.push(`${s.id}: web rule ${w.kind} does not fit its control`);
      if (w.kind === "range" && c.kind === "number") {
        if ((w.min !== undefined && (w.min < c.min || w.min > c.max)) || (w.max !== undefined && (w.max < c.min || w.max > c.max))) out.push(`${s.id}: web bounds outside the control's`);
      }
    }
    if (s.path[0] === "costs" && w?.kind !== "costs") out.push(`${s.id}: a price needs the costs web rule`);
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
  /** What the console may do with it now (null: the whole range of control); the server enforces it. */
  web: WebView | null;
  /** The command for any change within control's whole range (without --project). */
  command: string;
}

export interface SettingsView {
  sections: { id: string; title: string; help: string; settings: SettingView[] }[];
  readOnly: { id: string; label: string; value: unknown; default: unknown; reason: string }[];
  confirmWord: string;
  /** A save with a sensitive setting needs a sign-in at most this many minutes old (others: 30). */
  sensitiveSignInMinutes: number;
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
    web: webViewOf(def, value ?? null, data),
    command: commandFor(def),
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
    sensitiveSignInMinutes: OWNER_SENSITIVE_SIGN_IN_S / 60,
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
  /** The value is the time of the write (disabledAt). */
  now?: true;
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
  /**
   * A digest of everything above that a confirm writes or records (changes, side effects, the
   * writes, derived ones included, and the sensitive ids). The console sends the dry run's back
   * with its confirm; a different plan at confirm time is refused as STALE, so nothing is written
   * that the dialog did not show.
   */
  token: string;
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
/** What a disabledAt write holds in the plan; written as the time of the write. */
const NOW_MARK = "(the time of the change)";

function whyInvalid(fields: string[]): string {
  if (fields.includes("costs")) {
    return "a menu must cost at least a recipe, more days or courses never less, a dish picture at least a recipe and step pictures at least a dish picture";
  }
  return "a value the server does not accept";
}

/** A pause by the budget kill switch still stands: only the command turns generation back on. */
export function budgetPaused(data: Data): boolean {
  return typeof data.disabledBy === "string" && data.disabledBy.startsWith(BUDGET_PAUSE_PREFIX);
}

type Table = Record<string, Record<string, number>>;
const DEFAULT_TABLE = DEFAULT_MENU_SETS as unknown as Table;

/**
 * costs.menuSets as stored. Never the 3-day 2- and 3-course cells (menu2 / menu3 are their only
 * source, so a stored copy could only make a menu2 / menu3 change invalid). With minimal (a save
 * of the table itself): only the cells that differ from what validCosts would derive for them, so
 * editing one cell stores that cell alone and a later price change still moves every other one.
 * Without (any other costs save): cells stored by hand stay as they are. Works on a costs map in
 * place; a map that would not parse is left to the INVALID_RESULT check.
 */
export function normaliseMenuSets(costs: Data, minimal = true): void {
  if (!isObj(costs.menuSets)) return;
  const sets = costs.menuSets as Record<string, unknown>;
  if (isObj(sets[BASE])) {
    delete (sets[BASE] as Data)["2"];
    delete (sets[BASE] as Data)["3"];
    if (Object.keys(sets[BASE] as Data).length === 0) delete sets[BASE];
  }
  if (Object.keys(sets).length === 0) {
    delete costs.menuSets;
    return;
  }
  if (!minimal) return;
  const full = validCosts(costs)?.menuSets;
  if (!full) return;
  const recipe = costs.recipe as number;
  // validCosts's own derivation: one credit a day or a course from the 3-day row, within [recipe, COST_MAX].
  const derive = (from: number, d: string, c: string, fromD: string, fromC: string) =>
    Math.min(COST_MAX, Math.max(recipe, from + DEFAULT_TABLE[d][c] - DEFAULT_TABLE[fromD][fromC]));
  const t = full as unknown as Table;
  const out: Table = {};
  for (const d of DAY_ROWS.map((r) => r.value)) {
    for (const c of COURSE_COLS.map((x) => x.value)) {
      if (d === BASE && c !== "1") continue;
      const derived = d === BASE ? derive(t[BASE]["2"], BASE, "1", BASE, "2") : derive(t[BASE][c], d, c, BASE, c);
      if (t[d][c] !== derived) (out[d] ??= {})[c] = t[d][c];
    }
  }
  if (Object.keys(out).length) costs.menuSets = out;
  else delete costs.menuSets;
}

// --- The console's envelope -------------------------------------------------------------------

interface Price {
  /** The setting that holds it (a menu cell other than 3 days x 2 or 3 courses: costs.menuSets). */
  id: string;
  name: string;
  /** Dishes of a menu (days x courses); 0 for a recipe. */
  dishes: number;
  row?: string;
  col?: string;
  get(c: Costs): number;
  dflt: number;
}

const PRICES: readonly Price[] = [
  { id: "costs.recipe", name: "A recipe", dishes: 0, get: (c) => c.recipe, dflt: DEFAULT_COSTS.recipe },
  { id: "costs.recipeImage", name: "A recipe with a picture", dishes: 0, get: (c) => c.recipeImage ?? 0, dflt: DEFAULT_COSTS.recipeImage ?? 0 },
  { id: "costs.recipeImageSteps", name: "A recipe with step pictures", dishes: 0, get: (c) => c.recipeImageSteps ?? 0, dflt: DEFAULT_COSTS.recipeImageSteps ?? 0 },
  ...MENU_DAYS.flatMap((d) => MENU_COURSES.map((c): Price => ({
    id: d === DEFAULT_MENU_DAYS && c > 1 ? `costs.menu${c}` : "costs.menuSets",
    name: `A ${d}-day menu of ${c} course${c === 1 ? "" : "s"}`,
    dishes: d * c,
    row: `${d}`,
    col: `${c}`,
    get: (t) => t.menuSets[`${d}`][`${c}`],
    dflt: DEFAULT_MENU_SETS[`${d}`][`${c}`],
  }))),
];

/** The highest the console may raise a price to: 1.5 x its default, rounded up. */
export const webPriceCeiling = (dflt: number): number => Math.ceil(dflt * WEB_PRICE_CEILING_FACTOR);
/**
 * The lowest the console may lower a menu to: half a recipe a dish, rounded up, and never under
 * a recipe (the default table's cheapest per dish is 4 days x 3 courses for 6). The command may
 * go down to what the parser accepts (a recipe).
 */
export const webMenuFloor = (recipe: number, dishes: number): number => Math.max(recipe, Math.ceil((recipe * dishes) / 2));

/** The console's bounds of one price now: lower (a menu down to its floor), or up by WEB_PRICE_STEP, never above the ceiling. */
function priceBounds(p: Price, now: Costs): { min: number; max: number } {
  const b = p.get(now);
  const ceil = webPriceCeiling(p.dflt);
  const max = b === 0 ? ceil : b >= ceil ? b : Math.min(b + WEB_PRICE_STEP, ceil);
  const min = p.dishes > 0 ? Math.min(b, webMenuFloor(now.recipe, p.dishes)) : p.id === "costs.recipe" ? 1 : 0;
  return { min, max };
}

/**
 * The prices' envelope on the console, over the whole table as configured before and after (so
 * derived cells that move with a change count too): a price is lowered (a menu never below
 * webMenuFloor), or raised by at most WEB_PRICE_STEP a save and never above webPriceCeiling;
 * a picture level that was off (0) may come back at any price up to its ceiling, since no app
 * offers it at a price it could have cached. "" when within it.
 */
export function costsWebRefusal(before: Costs, after: Costs): string {
  for (const p of PRICES) {
    const b = p.get(before);
    const a = p.get(after);
    if (a > b) {
      const ceil = webPriceCeiling(p.dflt);
      if (b !== 0 && a > b + WEB_PRICE_STEP) return `${p.name}: ${b} to ${a}; at most ${WEB_PRICE_STEP} more a save here`;
      if (a > ceil) return `${p.name}: ${a}; at most ${ceil} here (1.5 x its default, ${p.dflt})`;
    } else if (a < b && p.dishes > 0) {
      const floor = webMenuFloor(after.recipe, p.dishes);
      if (a < floor) return `${p.name}: ${a}; at least ${floor} here (half a recipe a dish)`;
    }
  }
  return "";
}

const optionLabels = (c: Control) => (c.kind === "select" ? c.options.map((o) => o.label) : []);

function shown(def: SettingDef, v: unknown): string {
  const c = def.control;
  if (v === null || v === undefined) return "not set";
  if (c.kind === "number" && typeof v === "number") return fmtBound(c, v);
  if (c.kind === "toggle") return v ? "on" : "off";
  if (c.kind === "select") return c.options.find((o) => o.value === v)?.label ?? String(v);
  return "the value asked";
}

/** Why the console may not move def from before to after (values in force), or "" (costs: costsWebRefusal). */
export function webRefusal(def: SettingDef, before: unknown, after: unknown, data: Data): string {
  const r = def.web;
  if (!r || r.kind === "costs") return "";
  const c = def.control;
  switch (r.kind) {
    case "range": {
      if (after === null || after === undefined) return r.noClear && before !== null && before !== undefined ? "can't be cleared here" : "";
      if (typeof after !== "number" || c.kind !== "number") return "";
      const b = typeof before === "number" ? before : null;
      if (r.max !== undefined && after > r.max && !(r.safe === "lower" && b !== null && after <= b)) return `at most ${fmtBound(c, r.max)} here`;
      if (r.min !== undefined && after < r.min && !(r.safe === "higher" && b !== null && after >= b)) return `at least ${fmtBound(c, r.min)} here`;
      return "";
    }
    case "lowerOnly":
      return typeof before === "number" && typeof after === "number" && after > before ? `only lowered here (it is ${shown(def, before)})` : "";
    case "offOnly":
      return after === true && before !== true ? "only turned off here" : "";
    case "stricter": {
      const order = c.kind === "select" ? c.options.map((o) => o.value) : [];
      return order.indexOf(after as string) < order.indexOf(before as string) ? `only made stricter here (${optionLabels(c).join(" -> ")})` : "";
    }
    case "pause":
      return after === true && before !== true && budgetPaused(data) ? "was paused by the budget alert; only the command turns it back on (and clears disabledBy)" : "";
  }
}

/** What the page shows of the envelope and uses as its inputs' bounds (null: the whole range). */
export interface WebView {
  text: string;
  min?: number;
  max?: number;
  /** For the menu table: each cell's bounds, by days, then courses. */
  cells?: Record<string, Record<string, { min: number; max: number }>>;
}

function webViewOf(def: SettingDef, value: unknown, data: Data): WebView | null {
  const r = def.web;
  if (!r) return null;
  const c = def.control;
  switch (r.kind) {
    case "range": {
      if (c.kind !== "number") return null;
      const v = typeof value === "number" ? value : null;
      const parts: string[] = [];
      let min = c.min;
      let max = c.max;
      if (r.max !== undefined) {
        parts.push(`up to ${fmtBound(c, r.max)}` + (r.safe === "lower" ? " or any lower value" : ""));
        max = r.safe === "lower" && v !== null ? Math.max(r.max, v) : r.max;
      }
      if (r.min !== undefined) {
        parts.push(`at least ${fmtBound(c, r.min)}` + (r.safe === "higher" ? " or any higher value" : ""));
        min = r.safe === "higher" && v !== null ? Math.min(r.min, v) : r.min;
      }
      if (r.noClear) parts.push("never cleared");
      return { text: parts.join("; "), min, max };
    }
    case "lowerOnly":
      return { text: `only lower than the value in force (${shown(def, value)})`, ...(c.kind === "number" ? { min: c.min, max: typeof value === "number" ? value : c.max } : {}) };
    case "offOnly":
      return { text: "only off" };
    case "stricter":
      return { text: `only stricter: ${optionLabels(c).join(" -> ")}` };
    case "pause":
      return { text: budgetPaused(data) && value !== true ? "off at any time; this pause is the budget alert's, so only the command turns it back on" : "off at any time; on again, except after the budget alert's pause" };
    case "costs": {
      const now = configuredCosts(data);
      const rule = `lower, or at most ${WEB_PRICE_STEP} more a save, never above 1.5 x the default`;
      if (c.kind === "table") {
        const cells: Record<string, Record<string, { min: number; max: number }>> = {};
        for (const p of PRICES) if (p.row && p.col) (cells[p.row] ??= {})[p.col] = priceBounds(p, now);
        return { text: `each cell: ${rule}; a menu never below half a recipe a dish`, cells };
      }
      const p = PRICES.find((x) => x.id === def.id);
      if (!p) return null;
      const b = priceBounds(p, now);
      return { text: `${b.min} to ${b.max} (${rule}${p.dishes ? "; a menu never below half a recipe a dish" : ""})`, ...b };
    }
  }
}

/** The command that makes any change of def, whatever the console's envelope (the page adds --project). */
export function commandFor(def: SettingDef): string {
  const value = def.control.kind === "table" ? "'<table as JSON>'" : "<value>";
  return `npm run admin -- config set ${def.id} ${value} --apply${def.sensitive ? ` --confirm ${def.id}` : ""}`;
}

/** The plan's digest (SettingsPlan.token). */
function planToken(p: Omit<SettingsPlan, "token">): string {
  return createHash("sha256").update(stable({ changes: p.changes, sideEffects: p.sideEffects, writes: p.writes, sensitive: p.sensitive })).digest("base64url");
}

export interface PlanOptions {
  /** "web": the console's envelope applies (README "The console's envelope"); "cli" (default): the controls' whole range. */
  via?: "web" | "cli";
}

/**
 * Pure: the writes that make the changes over the document data (config/runtime as stored),
 * checked by parsing the result. Throws STALE, STORED_INVALID, INVALID_RESULT or (via web) WEB_LIMIT.
 */
export function planSettings(data: Data, parsed: readonly ParsedChange[], d: SettingDefaults, opts: PlanOptions = {}): SettingsPlan {
  const via = opts.via ?? "cli";
  const invalidBefore = invalidFields(data, d.imageDefaults, d.textDefaults);
  const cfgBefore = parse(data, d);
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
      // The menu table is compared in force: it is stored as the cells that differ from their derived value.
      if ((stored !== undefined && stable(stored) === stable(want)) || (def.id === "costs.menuSets" && same(def, def.read(cfgBefore, data), value))) {
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
  // The menu table as stored (normaliseMenuSets): its differing cells when it is saved, never the 3-day 2- and 3-course ones.
  if (touched.some((t) => t.def.path[0] === "costs") && isObj(next.costs)) normaliseMenuSets(next.costs as Data, touched.some((t) => t.def.id === "costs.menuSets"));
  // A map left empty by resets goes too.
  for (const t of touched) {
    const field = t.def.path[0];
    if (t.def.path.length > 1 && isObj(next[field]) && Object.keys(next[field] as Data).length === 0) delete next[field];
  }
  // Pausing records who paused (disabledBy, disabledAt, as the budget kill switch does); turning
  // generation back on clears both, so a later pause by the budget alert is told apart.
  const extra: string[] = [];
  const gen = touched.find((t) => t.def.id === "generationEnabled");
  if (gen) {
    const on = gen.reset ? DEFAULT_RUNTIME_CONFIG.generationEnabled : gen.value === true;
    if (on) {
      delete next.disabledBy;
      delete next.disabledAt;
    } else {
      next.disabledBy = PAUSED_BY[via];
      next.disabledAt = NOW_MARK;
    }
    extra.push("disabledBy", "disabledAt");
  }

  // The writes: field paths into a map stored as a map (each key that differs), the whole field otherwise.
  const writes: PlannedSettingWrite[] = [];
  const whole = (field: string) => touched.some((t) => t.def.path[0] === field && t.def.path.length === 1) || extra.includes(field);
  for (const field of [...new Set([...touched.map((t) => t.def.path[0]), ...extra])]) {
    const before = data[field];
    const after = next[field];
    if (stable(before) === stable(after) && has(data, field) === has(next, field)) continue;
    if (field === "disabledAt" && after !== undefined) {
      writes.push({ path: [field], value: NOW_MARK, now: true });
      continue;
    }
    if (!isObj(before) || !isObj(after) || whole(field)) {
      writes.push(after === undefined ? { path: [field], delete: true } : { path: [field], value: structuredClone(after) });
      continue;
    }
    for (const key of [...new Set([...Object.keys(before), ...Object.keys(after)])]) {
      if (stable(before[key]) === stable(after[key]) && has(before, key) === has(after, key)) continue;
      writes.push(has(after, key) ? { path: [field, key], value: structuredClone(after[key]) } : { path: [field, key], delete: true });
    }
  }

  // The check: the document as it would be parses with no field newly invalid, and every setting
  // asked reads back as asked.
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
  // The console's envelope, on the values in force before and after (README "The console's envelope").
  if (via === "web") {
    for (const t of touched) {
      const why = webRefusal(t.def, t.def.read(cfgBefore, data) ?? null, t.def.read(cfgAfter, next) ?? null, data);
      if (why) throw webLimit(t.def, why);
    }
    const costDef = touched.find((t) => t.def.web?.kind === "costs");
    if (costDef) {
      const why = costsWebRefusal(configuredCosts(data), configuredCosts(next));
      if (why) throw webLimit(costDef.def, why);
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
  const plan = { changes, unchanged, sideEffects, writes, sensitive };
  return { ...plan, token: planToken(plan) };
}

function webLimit(def: SettingDef, why: string): SwitchRefused {
  return new SwitchRefused("failed-precondition", "WEB_LIMIT",
    `${def.label}: ${why}; nothing changed. Larger changes: use the command, ${commandFor(def)}`);
}

// --- Applying -------------------------------------------------------------------------------

export interface ApplySettingsOptions {
  apply: boolean;
  actor: Actor;
  /** The sensitive ids the caller confirmed; must be exactly the plan's when it has any. */
  confirmSensitive?: readonly string[];
  /** The dry run's SettingsPlan.token the caller saw (required from the console): another plan now is STALE. */
  expectToken?: string;
  /** Seconds since the caller's sign-in (the console): a sensitive save needs at most OWNER_SENSITIVE_SIGN_IN_S. */
  signInAgeS?: number;
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
  const via = opts.actor.via;
  const parsed = parseChanges(rawChanges, { requireExpect: via === "web" });
  const d = settingDefaultsOf(env);
  const ref = env.db.doc(RUNTIME_DOC);
  const auditRef = env.db.collection(SETTING_CHANGES).doc();
  const result = await env.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = (snap.data() ?? {}) as Data;
    const plan = planSettings(data, parsed, d, { via });
    if (plan.writes.length === 0) return { plan, written: false };
    if (opts.apply && opts.expectToken !== undefined && opts.expectToken !== plan.token) {
      throw new SwitchRefused("aborted", "STALE", "What would be written is no longer what was checked (config/runtime changed meanwhile); check again");
    }
    if (opts.apply && plan.sensitive.length > 0) {
      const confirmed = [...(opts.confirmSensitive ?? [])].sort();
      if (stable(confirmed) !== stable([...plan.sensitive].sort())) {
        throw new SwitchRefused("failed-precondition", "CONFIRM_SENSITIVE",
          `Sensitive settings need their own confirmation (${plan.changes.filter((c) => c.sensitive).map((c) => c.label).join(", ")}); nothing changed`);
      }
    }
    if (opts.apply && plan.sensitive.length > 0 && opts.signInAgeS !== undefined && opts.signInAgeS > OWNER_SENSITIVE_SIGN_IN_S) {
      throw new SwitchRefused("failed-precondition", "RECENT_SIGN_IN",
        `Sign in again: a sensitive change needs a sign-in from the last ${OWNER_SENSITIVE_SIGN_IN_S / 60} minutes; nothing changed`);
    }
    if (!opts.apply) return { plan, written: false };
    const at = Timestamp.fromMillis(env.now());
    const valueOf = (w: PlannedSettingWrite) => (w.now ? at : w.value);
    if (!snap.exists) {
      const doc: Data = {};
      for (const w of plan.writes) if (!w.delete) setAt(doc, w.path, valueOf(w));
      tx.set(ref, doc);
    } else {
      const pairs = plan.writes.flatMap((w) => [new FieldPath(...w.path), w.delete ? FieldValue.delete() : valueOf(w)]);
      const [first, firstValue, ...rest] = pairs;
      tx.update(ref, first as FieldPath, firstValue, ...rest);
    }
    tx.create(auditRef, {
      at,
      actor: opts.actor,
      sensitive: plan.sensitive.length > 0,
      sensitiveSettings: plan.sensitive,
      changes: plan.changes,
      sideEffects: plan.sideEffects,
      writes: plan.writes.map((w) => ({ path: w.path.join(" > "), ...(w.delete ? { deleted: true } : { value: valueOf(w) }) })),
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
 * plan (before and after, side effects, which are sensitive, its token) and writes nothing;
 * {changes, confirm: true, token, sensitive: [ids], typed} makes them (token: the dry run's, else
 * STALE; typed must be SENSITIVE_CONFIRM_WORD when any is sensitive, and a sensitive save needs a
 * sign-in from the last OWNER_SENSITIVE_SIGN_IN_S), then answers with the new view. Everything
 * is checked against the console's envelope (WebRule), whatever the page sent.
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
  if (!dryRun && (typeof d.token !== "string" || d.token.length === 0 || d.token.length > 100)) {
    throw new HttpsError("invalid-argument", "Expected the token of the check (dry run) the confirmation shows", { reason: "INVALID_REQUEST" });
  }
  const sensitive = Array.isArray(d.sensitive) && d.sensitive.every((s) => typeof s === "string") ? (d.sensitive as string[]) : [];
  if (!dryRun && sensitive.length > 0 && d.typed !== SENSITIVE_CONFIRM_WORD) {
    throw new SwitchRefused("failed-precondition", "CONFIRM_SENSITIVE", `Type ${SENSITIVE_CONFIRM_WORD} to confirm the sensitive settings; nothing changed`);
  }
  const out = await applySettings(env, d.changes, {
    apply: !dryRun,
    actor: { via: "web", uid: caller.uid },
    confirmSensitive: sensitive,
    ...(dryRun ? {} : { expectToken: d.token as string }),
    signInAgeS: env.now() / 1000 - caller.authTime,
  });
  return { ...(await listSettings(env)), changed: out.applied, plan: out.plan };
}
