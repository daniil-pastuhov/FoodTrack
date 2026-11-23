/**
 * The owner's runtime settings (src/core/ownerSettings.ts): the registry (every field
 * parseRuntimeConfig reads is editable, read-only or excluded with a reason), every setting's
 * bounds, the planned writes (field paths only, resets, a complete costs map or none), the
 * transaction against the emulators (other fields untouched, an audit record, the log line),
 * stale views, the sensitive confirmation enforced by the server, the owner gate with Auth
 * emulator tokens, the admin command's dry run and --apply, and the console page holding no data.
 */
import { randomUUID } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { HttpsError } from "firebase-functions/v2/https";
import { AdminRefusal } from "../src/admin/accountRequests";
import { cliValue, parseConfigArgs, runConfig, type ImageEnv } from "../src/admin/cli";
import { COST_MAX, DEFAULT_COSTS, DEFAULT_MENU_SETS, DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION } from "../src/constants";
import { Timestamp } from "firebase-admin/firestore";
import { DEFAULT_RUNTIME_CONFIG, dailySpendCeiling, loadRuntimeConfig, parseRuntimeConfig, statsRef, uidHash, utcDay, validCosts } from "../src/model";
import { OwnerCallGate, refusalReason, type OwnerCallRequest } from "../src/core/ownerCall";
import type { Logger } from "../src/ports";
import { OWNER_DOC, RUNTIME_DOC, SWITCH_CHANGES, SwitchRefused, switchProvider, type OwnerEnv } from "../src/core/providerSwitch";
import {
  EXCLUDED,
  FAMILIES,
  OWNER_SENSITIVE_SIGN_IN_S,
  PAUSED_BY,
  WEB_UNRESTRICTED,
  commandFor,
  costsWebRefusal,
  normaliseMenuSets,
  webMenuFloor,
  webPriceCeiling,
  READ_ONLY,
  SECTIONS,
  SENSITIVE_CONFIRM_WORD,
  SETTINGS,
  SETTING_CHANGES,
  applySettings,
  checkValue,
  getOwnerSettings,
  listSettings,
  parseChanges,
  planSettings,
  setOwnerSettings,
  settingById,
  settingsRegistryProblems,
  settingsViewOf,
  tasteChoices,
  todayFiguresOf,
  type SettingDef,
} from "../src/core/ownerSettings";
import { auth, db, resetEmulators } from "./helpers";

interface Line { level: string; message: string; data?: Record<string, unknown> }

function makeEnv(over: Partial<OwnerEnv> = {}): OwnerEnv & { logs: Line[] } {
  const logs: Line[] = [];
  const rec = (level: string) => (message: string, data?: Record<string, unknown>) => void logs.push({ level, message, data });
  const logger: Logger = { info: rec("info"), warn: rec("warn"), error: rec("error") };
  return { db, auth, now: () => Date.now(), logger, secretReadable: async () => false, logs, ...over };
}

async function reason(p: Promise<unknown> | (() => unknown)): Promise<string> {
  try {
    await (typeof p === "function" ? p() : p);
  } catch (e) {
    if (e instanceof SwitchRefused) return e.reason;
    if (e instanceof HttpsError) return `${e.code}:${(e.details as { reason?: string } | undefined)?.reason ?? ""}`;
    if (e instanceof AdminRefusal) return `admin:${e.message}`;
    throw e;
  }
  return "no refusal";
}

const D = { imageDefaults: DEFAULT_IMAGE_SELECTION, textDefaults: DEFAULT_TEXT_SELECTION };
const stableJson = (v: unknown): string => JSON.stringify(v, (_k, x) => (x && typeof x === "object" && !Array.isArray(x) ? Object.fromEntries(Object.keys(x).sort().map((k) => [k, x[k]])) : x));
const def = (id: string) => settingById(id)!;
const runtime = async () => (await db.doc(RUNTIME_DOC).get()).data() as Record<string, unknown> | undefined;
const audits = async () => (await db.collection(SETTING_CHANGES).orderBy("at").get()).docs.map((d) => d.data());
const plan = (data: Record<string, unknown>, changes: unknown[]) => planSettings(data, parseChanges(changes, { requireExpect: false }), D);
const writes = (data: Record<string, unknown>, changes: unknown[]) => plan(data, changes).writes.map((w) => [w.path.join(">"), w.delete ? "(deleted)" : w.value]);
/** Warnings parseRuntimeConfig gives for a document. */
function warnings(data: Record<string, unknown>): string[] {
  let fields: string[] = [];
  parseRuntimeConfig(data, { info: () => undefined, error: () => undefined, warn: (_m, d) => void (fields = (d?.fields as string[]) ?? []) });
  return fields;
}

/** The document a plan's writes make over data (field paths as Firestore applies them). */
function applyWrites(data: Record<string, unknown>, ws: { path: string[]; value?: unknown; delete?: true }[]): Record<string, unknown> {
  const next = structuredClone(data);
  for (const w of ws) {
    let at = next;
    for (const seg of w.path.slice(0, -1)) at = (at[seg] ??= {}) as Record<string, unknown>;
    if (w.delete) delete at[w.path[w.path.length - 1]];
    else at[w.path[w.path.length - 1]] = structuredClone(w.value);
  }
  return next;
}
const webPlan = (data: Record<string, unknown>, changes: unknown[]) => planSettings(data, parseChanges(changes, { requireExpect: false }), D, { via: "web" });

/** A config/runtime with many fields, to show a change leaves every other one as it was. */
const FULL_CONFIG = {
  generationEnabled: true,
  allowSandbox: false,
  dailyCap: 77,
  costs: { recipe: 1, menu2: 4, menu3: 5, recipeImage: 3 },
  prices: { inputUsdPerMTok: 2, outputUsdPerMTok: 9, imageUsdPerImage: { "vertex/gemini-3.1-flash-image": 0.08 } },
  welcome: { recipes: 2, perDay: 50 },
  tasteQuiz: { hidden: ["units"] },
  image: { provider: "vertex", model: "gemini-3.1-flash-lite-image", fallbackModel: "gemini-nano-banana-2.1", maxStepImages: 3 },
  providerBalanceAlerts: { someother: 5 },
  futureField: { kept: true },
};

beforeEach(async () => {
  await resetEmulators();
});

// --- The registry ---------------------------------------------------------------------------

describe("the registry", () => {
  test("is sound: unique ids that are their paths, known sections, labels, bounds", () => {
    expect(settingsRegistryProblems()).toEqual([]);
    const bad: SettingDef[] = [def("dailyCap"), { ...def("dailyCap") }, { ...def("welcome.recipes"), id: "welcome.other" }];
    expect(settingsRegistryProblems(bad).join("\n")).toMatch(/duplicate id[\s\S]*the id must be its path/);
    for (const s of SECTIONS) expect(SETTINGS.some((x) => x.section === s.id), s.id).toBe(true);
  });

  /**
   * Every field parseRuntimeConfig reads, found in its source (so a new one can't be missed):
   * the top-level keys of the document and the keys each map's validator reads.
   */
  function parsedFields(): string[] {
    const model = readFileSync(join(__dirname, "../src/model.ts"), "utf8");
    const body = (name: string) => {
      const start = model.indexOf(`function ${name}(`);
      expect(start, name).toBeGreaterThan(-1);
      return model.slice(start, model.indexOf("\n}\n", start));
    };
    const parse = body("parseRuntimeConfig");
    const top = new Set<string>([...parse.matchAll(/data\.(\w+)/g)].map((m) => m[1]));
    for (const m of parse.matchAll(/for \(const key of \[([^\]]*)\]/g)) for (const k of m[1].matchAll(/"(\w+)"/g)) top.add(k[1]);
    const countFields = model.slice(model.indexOf("const COUNT_FIELDS"), model.indexOf("] as const;", model.indexOf("const COUNT_FIELDS")));
    for (const k of countFields.matchAll(/"(\w+)"/g)) top.add(k[1]);
    for (const k of Object.keys(DEFAULT_RUNTIME_CONFIG)) top.add(k);
    const nested: Record<string, string> = {
      costs: "validCosts", prices: "validPrices", image: "validImageSelection", text: "validTextSelection",
      welcome: "validWelcome", reports: "validReports", tasteQuiz: "validTasteQuiz",
    };
    const out: string[] = [];
    for (const k of top) {
      if (!nested[k]) {
        out.push(k);
        continue;
      }
      const b = body(nested[k]);
      const keys = new Set([
        ...[...b.matchAll(/\bv\.(\w+)/g)].map((m) => m[1]),
        ...[...b.matchAll(/get\("(\w+)"/g)].map((m) => m[1]),
        ...[...b.matchAll(/as Record<string, unknown>\)\.(\w+)/g)].map((m) => m[1]),
      ]);
      expect(keys.size, nested[k]).toBeGreaterThan(0);
      for (const n of keys) out.push(`${k}.${n}`);
    }
    return out.sort();
  }

  test("every field parseRuntimeConfig reads is editable, read-only or excluded with a reason", () => {
    const fields = parsedFields();
    expect(fields).toEqual(expect.arrayContaining(["generationEnabled", "costs.menuSets", "costs.recipeImageSteps", "image.maxStepImages", "prices.textUsdPerMTok", "welcome.perDay", "reports.keepDays", "tasteQuiz.hidden", "outputJudge", "providerBalanceAlerts", "providerDefaults"]));
    const editable = new Set(SETTINGS.map((s) => s.id));
    const readOnly = new Set(READ_ONLY.map((r) => r.id));
    const uncovered = fields.filter((f) => !editable.has(f) && !readOnly.has(f) && !(f in EXCLUDED) && !(f in FAMILIES));
    expect(uncovered).toEqual([]);
    for (const reasonText of [...Object.values(EXCLUDED), ...Object.values(FAMILIES), ...READ_ONLY.map((r) => r.reason)]) expect(reasonText.length).toBeGreaterThan(40);
    // And nothing in the registry names a field the parser does not read.
    for (const s of SETTINGS) expect(fields.includes(s.id) || s.path[0] in FAMILIES, s.id).toBe(true);
    for (const r of READ_ONLY) expect(fields, r.id).toContain(r.id);
    // Every probe-able provider has its balance setting.
    expect(SETTINGS.filter((s) => s.path[0] === "providerBalanceAlerts").map((s) => s.id)).toContain("providerBalanceAlerts.bfl");
  });

  /**
   * Not from the source: every leaf of DEFAULT_RUNTIME_CONFIG and of the parse of a document that
   * sets every field (MAXIMAL, which must parse with no warning and move every editable setting
   * off its default), recursively, is covered. A new field reaches one of the two (the type needs a
   * default, or the fixture an entry), so it can't slip past both this and the source test.
   */
  const TERMINAL = new Set(["costs.menuSets", "prices.imageUsdPerImage", "prices.textUsdPerMTok", "providerBalanceAlerts", "tasteQuiz.hidden", "providerDefaults", "homeWorker"]);
  const isPlain = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
  const leaves = (v: unknown, at = ""): string[] =>
    !isPlain(v) || TERMINAL.has(at) ? [at] : Object.keys(v).flatMap((k) => leaves(v[k], at ? `${at}.${k}` : k));
  const MAXIMAL = {
    generationEnabled: false, allowSandbox: true, consumptionSampleContentProvided: false,
    dailyCap: 10, sandboxDailyCap: 1, sandboxDailyCredits: 2, perUserPerMinute: 1, perUserPerDay: 2, verifyPerMinute: 1, infraRefundsPerDay: 1,
    dailySpendBaseMicroUsd: 1, dailySpendMaxMicroUsd: 2, dailySpendRevenueFraction: 0.5, netMicroUsdPerCredit: 1, perUserDailySpendMicroUsd: 1,
    refundAbuseThreshold: 3, budgetKillFraction: 0.5, rejectedPromptsPerDay: 1, outputJudge: "enforce", outputJudgeKeepText: true, promptReviewEnabled: false, weeklyReportEnabled: false,
    costs: { recipe: 2, menu2: 5, menu3: 7, recipeImage: 5, recipeImageSteps: 6, menuSets: { "4": { "3": 9 } } },
    prices: { inputUsdPerMTok: 2, outputUsdPerMTok: 8, imageUsdPerImage: { "vertex/gemini-3.1-flash-image": 0.08 }, textUsdPerMTok: { "mistral/mistral-medium-latest": { inputUsdPerMTok: 1, outputUsdPerMTok: 3 } } },
    image: { provider: "vertex", model: "gemini-3.1-flash-lite-image", fallbackModel: "gemini-nano-banana-2.1", maxStepImages: 1 },
    providerBalanceAlerts: { bfl: 5 },
    reports: { perUserPerDay: 1, perDay: 2, keepDays: 3 },
    tasteQuiz: { hidden: ["units"] },
    welcome: { recipes: 1, perDay: 2 },
  };

  test("every leaf of the defaults and of a parsed maximal document is editable, read-only or excluded", () => {
    const editable = new Set(SETTINGS.map((s) => s.id));
    const readOnly = new Set(READ_ONLY.map((r) => r.id));
    const covered = (p: string) => editable.has(p) || readOnly.has(p) || p in EXCLUDED || p.split(".")[0] in FAMILIES;
    expect(warnings(MAXIMAL)).toEqual([]);
    const parsed = parseRuntimeConfig(MAXIMAL);
    const all = [...new Set([...leaves(DEFAULT_RUNTIME_CONFIG), ...leaves(parsed), ...leaves(MAXIMAL)])].sort();
    expect(all).toEqual(expect.arrayContaining(["costs.recipeImageSteps", "prices.textUsdPerMTok", "image.fallbackModel", "welcome.perDay", "providerDefaults"]));
    expect(all.filter((p) => !covered(p))).toEqual([]);
    // The fixture really moves every editable setting (so a field it left out would show here).
    const empty = parseRuntimeConfig({});
    const unmoved = SETTINGS.filter((s) => stableJson(s.read(parsed, MAXIMAL)) === stableJson(s.read(empty, {}))).map((s) => s.id);
    expect(unmoved).toEqual([]);
    // Every top-level key of RuntimeConfig is in the fixture or excluded (no field without an entry).
    for (const k of Object.keys(DEFAULT_RUNTIME_CONFIG)) expect(k in MAXIMAL || k in EXCLUDED || k === "text", k).toBe(true);
  });

  test("every setting has a web rule or says why it needs none", () => {
    for (const s of SETTINGS) expect(Boolean(s.web) !== (s.id in WEB_UNRESTRICTED), s.id).toBe(true);
    expect(Object.keys(WEB_UNRESTRICTED).sort()).toEqual(["image.maxStepImages", "promptReviewEnabled", "tasteQuiz.hidden", "weeklyReportEnabled"]);
    const bad: SettingDef[] = [{ ...def("dailyCap"), web: { kind: "offOnly" } }, { ...def("costs.recipe"), web: undefined }];
    expect(settingsRegistryProblems(bad).join("\n")).toMatch(/does not fit its control[\s\S]*no web rule/);
  });

  test("the sensitive ones are those that pause, spend, charge, give away or loosen a brake", () => {
    const sensitive = SETTINGS.filter((s) => s.sensitive).map((s) => s.id);
    for (const id of ["generationEnabled", "allowSandbox", "outputJudge", "dailySpendBaseMicroUsd", "dailySpendMaxMicroUsd", "dailySpendRevenueFraction",
      "netMicroUsdPerCredit", "perUserDailySpendMicroUsd", "dailyCap", "budgetKillFraction", "perUserPerMinute", "perUserPerDay", "verifyPerMinute",
      "infraRefundsPerDay", "rejectedPromptsPerDay", "refundAbuseThreshold", "sandboxDailyCap", "sandboxDailyCredits", "costs.recipe", "costs.menu2",
      "costs.menu3", "costs.recipeImage", "costs.recipeImageSteps", "costs.menuSets", "welcome.recipes", "welcome.perDay", "image.maxStepImages",
      "reports.perUserPerDay", "reports.perDay", "reports.keepDays", "providerBalanceAlerts.bfl"]) {
      expect(sensitive, id).toContain(id);
    }
    // The weekly prompt review: at most its small worst case a week, inside the spend ceiling, and no prompt change.
    // The weekly report: reads only, no model call, changes nothing.
    expect(SETTINGS.filter((s) => !s.sensitive).map((s) => s.id)).toEqual(["tasteQuiz.hidden", "promptReviewEnabled", "weeklyReportEnabled"]);
    // Raw prices and the provider setup are not editable here at all.
    for (const id of ["prices.inputUsdPerMTok", "prices.imageUsdPerImage", "image.provider", "text.model"]) {
      expect(settingById(id)).toBeUndefined();
      expect(READ_ONLY.map((r) => r.id)).toContain(id);
    }
  });

  test("the view of an empty document is the defaults, nothing set, nothing changed", () => {
    const view = settingsViewOf({}, D);
    const all = view.sections.flatMap((s) => s.settings);
    expect(all.map((s) => s.id)).toEqual(SETTINGS.map((s) => s.id));
    for (const s of all) expect([s.id, s.overridden, s.changed, s.blocked]).toEqual([s.id, false, false, ""]);
    const v = (id: string) => all.find((s) => s.id === id)!;
    expect(v("generationEnabled").value).toBe(true);
    expect(v("dailySpendMaxMicroUsd").value).toBe(2_000_000);
    expect(v("costs.recipeImage").value).toBe(2);
    expect(v("costs.menuSets").value).toEqual(DEFAULT_MENU_SETS);
    expect(v("welcome.recipes").value).toBe(3);
    expect(v("providerBalanceAlerts.bfl").value).toBeNull();
    expect(view.readOnly.find((r) => r.id === "prices.inputUsdPerMTok")).toMatchObject({ value: 1.5, default: 1.5 });
    expect(view.confirmWord).toBe(SENSITIVE_CONFIRM_WORD);
  });

  test("the console's groups: every section, wide cards, read-only fields placed in a card, no figures without today's", () => {
    const view = settingsViewOf({}, D);
    expect(view.sections.map((s) => s.id)).toEqual(SECTIONS.map((s) => s.id));
    expect(view.sections.filter((s) => s.wide).map((s) => s.id)).toEqual(["costs", "taste", "promptReview", "weeklyReport"]);
    const cards = new Set<string>([...SECTIONS.map((s) => s.id), "providers", "other"]);
    for (const r of view.readOnly) expect(cards.has(r.section), r.id).toBe(true);
    expect(view.readOnly.filter((r) => r.section === "providers").map((r) => r.id)).toEqual(READ_ONLY.filter((r) => /^(image|text)\./.test(r.id)).map((r) => r.id));
    expect(view.today).toBeNull();
    expect(view.boardUrl).toBeNull();
    expect(view.status.map((s) => s.label)).toEqual(["Recipes from packs", "Second check of every answer", "Free recipes for signing in", "Sandbox purchases"]);
    expect(view.sections.find((s) => s.id === "spending")!.facts).toEqual([expect.objectContaining({ label: "Today's ceiling", value: "unknown" })]);
  });

  test("the judge's settings sit in the On/off card, and its status tile says whether the judged text is kept", () => {
    const onoff = settingsViewOf({}, D).sections.find((s) => s.id === "onoff")!.settings.map((s) => s.id);
    expect(onoff).toEqual(expect.arrayContaining(["outputJudge", "outputJudgeKeepText"]));
    expect(onoff.indexOf("outputJudgeKeepText")).toBe(onoff.indexOf("outputJudge") + 1);
    const tile = (data: Record<string, unknown>) => settingsViewOf(data, D).status.find((s) => s.label === "Second check of every answer")!;
    expect(tile({ outputJudge: "shadow" })).toMatchObject({ value: "Shadow (log only), text not kept", tone: "neutral" });
    expect(tile({ outputJudge: "shadow" }).note).toBe("Runs, logs and keeps every answer; never blocks. Each answer is kept 90 days without the judged text.");
    expect(tile({ outputJudge: "shadow", outputJudgeKeepText: true })).toMatchObject({ value: "Shadow (log only), text kept" });
    expect(tile({ outputJudge: "shadow", outputJudgeKeepText: true }).note).toContain("kept 90 days with the judged recipe text");
    expect(tile({ outputJudge: "enforce", outputJudgeKeepText: true })).toMatchObject({ value: "Enforce, text kept", tone: "good" });
    expect(tile({ outputJudge: "off" })).toMatchObject({ value: "Off", note: "Not run." });
  });

  test("today's figures: the ceiling worked out from the values in force, the status strip, the free recipes", () => {
    const cfg = parseRuntimeConfig({});
    const today = todayFiguresOf(cfg, "2026-11-18", [
      { spentMicroUsd: 300_000, reservedMicroUsd: 100_000, netCreditsGranted: 30, generationUnits: 12, welcomeGrants: 2, welcomeCredits: 6, welcomeUsedCredits: 4, welcomeUsedMicroUsd: 40_000 },
      { netCreditsGranted: 10, spentMicroUsd: 999_999 },
      undefined,
    ]);
    expect(today).toEqual({
      day: "2026-11-18", spentMicroUsd: 300_000, reservedMicroUsd: 100_000, ceilingMicroUsd: dailySpendCeiling(cfg, 40), netCredits7d: 40,
      generationUnits: 12, ceilingReached: false, dailyCapReached: false, welcomeGrants: 2, welcomeCredits: 6, welcomeUsedCredits: 4, welcomeUsedMicroUsd: 40_000,
    });
    const view = settingsViewOf({}, D, undefined, null, { today, boardUrl: "https://storage.cloud.google.com/b/board.html" });
    expect(view.boardUrl).toBe("https://storage.cloud.google.com/b/board.html");
    const spending = view.sections.find((s) => s.id === "spending")!.facts;
    expect(spending[0]).toMatchObject({ label: "Today's ceiling", value: "USD 2.00" });
    expect(spending[0].note).toContain("min(USD 2.00 absolute, USD 1.20 + 0.25 × USD 0.120 × 40 net recipes sold in 7 days = USD 2.40) = USD 2.00");
    expect(spending[0].note).toContain("free recipes and test purchases add nothing");
    expect(spending[1]).toMatchObject({ label: "Booked today", value: "USD 0.40" });
    expect(view.sections.find((s) => s.id === "welcome")!.facts[0].note).toContain("4 free recipes used today, estimated model cost USD 0.040");
    expect(view.status.map((s) => s.label)).toEqual(["Recipes from packs", "Spend today", "Recipes used today", "Second check of every answer", "Free recipes for signing in", "Sandbox purchases"]);
    expect(view.status[1]).toMatchObject({ value: "USD 0.40 of USD 2.00", tone: "neutral", meter: { value: 400_000, max: 2_000_000 } });
    expect(view.status[2]).toMatchObject({ value: "12 of 1,000", meter: { value: 12, max: 1000 } });

    const paused = settingsViewOf({ generationEnabled: false, disabledBy: "budget alert B: 10 of 10", disabledAt: Timestamp.fromMillis(Date.parse("2026-11-18T09:30:00Z")) }, D, undefined, null, { today });
    expect(paused.status[0]).toEqual({ label: "Recipes from packs", value: "Paused", tone: "crit", note: "Paused by budget alert B: 10 of 10 since 2026-11-18 09:30 UTC. Only the admin command turns it back on." });
    const reached = todayFiguresOf(cfg, "2026-11-18", [{ spentMicroUsd: 2_000_000, spendAlerts: { "100": 1 } }]);
    expect(settingsViewOf({ allowSandbox: true, outputJudge: "enforce" }, D, undefined, null, { today: reached }).status.map((s) => [s.value, s.tone])).toEqual([
      ["On, ceiling reached", "warn"], ["USD 2.00 of USD 1.20", "crit"], ["0 of 1,000", "neutral"], ["Enforce, text not kept", "good"], ["3 per sign-in", "neutral"], ["Grant recipes", "warn"],
    ]);
  });
});

// --- Values and bounds ----------------------------------------------------------------------

describe("every setting's validation and bounds", () => {
  const refused = (id: string, value: unknown) => reason(() => checkValue(def(id), value));

  test.each(SETTINGS.map((s) => [s.id, s] as const))("%s", async (id, s) => {
    const c = s.control;
    if (c.kind === "toggle") {
      expect(checkValue(s, true)).toBe(true);
      expect(checkValue(s, false)).toBe(false);
      for (const bad of ["true", 1, null, undefined]) await expect(refused(id, bad)).resolves.toBe("INVALID_VALUE");
    } else if (c.kind === "number") {
      expect(checkValue(s, c.min)).toBe(c.min);
      expect(checkValue(s, c.max)).toBe(c.max);
      const below = c.min - (c.integer ? 1 : 0.01);
      for (const bad of [below, c.max + (c.integer ? 1 : 0.01), String(c.min), null, Number.NaN, Number.POSITIVE_INFINITY]) {
        await expect(refused(id, bad), String(bad)).resolves.toBe("INVALID_VALUE");
      }
      if (c.integer) await expect(refused(id, c.min + 0.5)).resolves.toBe("INVALID_VALUE");
    } else if (c.kind === "select") {
      for (const o of c.options) expect(checkValue(s, o.value)).toBe(o.value);
      await expect(refused(id, "everything")).resolves.toBe("INVALID_VALUE");
    } else if (c.kind === "multi") {
      const [a, b] = c.options;
      expect(checkValue(s, [b.value, a.value])).toEqual([a.value, b.value]);
      expect(checkValue(s, [])).toEqual([]);
      for (const bad of [[a.value, a.value], ["nope"], "x", [1]]) await expect(refused(id, bad)).resolves.toBe("INVALID_VALUE");
    } else if (c.kind === "table") {
      expect(checkValue(s, DEFAULT_MENU_SETS)).toEqual(DEFAULT_MENU_SETS);
      const missing = structuredClone(DEFAULT_MENU_SETS) as Record<string, Record<string, number>>;
      delete missing["4"]["3"];
      const zero = structuredClone(DEFAULT_MENU_SETS) as Record<string, Record<string, number>>;
      zero["1"]["1"] = 0;
      for (const bad of [missing, zero, { ...DEFAULT_MENU_SETS, "5": DEFAULT_MENU_SETS["4"] }, [], "x"]) await expect(refused(id, bad)).resolves.toBe("INVALID_VALUE");
    }
    // At its bounds over an empty document the setting reads back as set, with no warning (a
    // price the rest of the default table can't take excepted: the parser's own rule, tested below).
    const values = c.kind === "number" ? [c.min, c.max] : c.kind === "toggle" ? [!s.read(parseRuntimeConfig({}), {})] : c.kind === "select" ? [c.options[c.options.length - 1].value]
      : c.kind === "multi" ? [[c.options[0].value]] : [{ ...DEFAULT_MENU_SETS, "4": { "1": 4, "2": 5, "3": 7 } }];
    // The prices only hold together with each other: read back at their extremes, all at once, below.
    if (id.startsWith("costs.")) return;
    for (const value of values) {
      const p = plan({}, [{ id, value }]);
      const next = applyWrites({}, p.writes);
      expect(warnings(next), `${id}=${JSON.stringify(value)}`).toEqual([]);
      expect(p.changes[0].to).toEqual(s.toStored ? s.read(parseRuntimeConfig(next), next) : value);
    }
  });

  test("the prices read back at their extremes, all together (the parser's rule needs them to fit each other)", () => {
    const table = (n: number) => Object.fromEntries(Object.entries(DEFAULT_MENU_SETS).map(([d, row]) => [d, Object.fromEntries(Object.keys(row).map((c) => [c, n]))]));
    const all = (n: number, image: number) => [
      { id: "costs.recipe", value: n }, { id: "costs.menu2", value: n }, { id: "costs.menu3", value: n },
      { id: "costs.menuSets", value: table(n) }, { id: "costs.recipeImage", value: image }, { id: "costs.recipeImageSteps", value: image },
    ];
    for (const [n, image] of [[1, 1], [COST_MAX, COST_MAX], [1, 0], [COST_MAX, 0]]) {
      // The bounds these are: a price 1 to COST_MAX, a picture price 0 (off) to COST_MAX.
      for (const id of ["costs.recipe", "costs.menu2", "costs.menu3"]) expect(def(id).control, id).toMatchObject({ min: 1, max: COST_MAX });
      for (const id of ["costs.recipeImage", "costs.recipeImageSteps"]) expect(def(id).control, id).toMatchObject({ min: 0, max: COST_MAX });
      expect(def("costs.menuSets").control).toMatchObject({ min: 1, max: COST_MAX });
      const p = plan({}, all(n, image));
      const next = applyWrites({}, p.writes);
      expect(warnings(next), `${n}/${image}`).toEqual([]);
      const costs = parseRuntimeConfig(next).costs;
      expect(costs.recipe).toBe(n);
      expect([costs.menu2, costs.menu3]).toEqual([n, n]);
      expect(costs.menuSets).toEqual(table(n));
      if (image === 0) {
        expect(costs.recipeImage).toBeUndefined();
        expect(costs.recipeImageSteps).toBeUndefined();
      } else {
        expect([costs.recipeImage, costs.recipeImageSteps]).toEqual([image, image]);
      }
      // And each setting reads back as asked, in the plan and from the document as written.
      for (const ch of all(n, image)) {
        const s = def(ch.id);
        expect(s.read(parseRuntimeConfig(next), next), ch.id).toEqual(ch.value);
        expect(p.changes.find((c) => c.id === ch.id)?.to, ch.id).toEqual(ch.value);
      }
    }
  });

  test("unknown ids, two changes of one setting, and no value refuse the request", async () => {
    expect(await reason(() => parseChanges([{ id: "dailySpendCapMicroUsd", value: 1 }], { requireExpect: false }))).toBe("UNKNOWN_SETTING");
    expect(await reason(() => parseChanges([{ id: "prices.inputUsdPerMTok", value: 1 }], { requireExpect: false }))).toBe("UNKNOWN_SETTING");
    expect(await reason(() => parseChanges([{ id: "dailyCap", value: 1 }, { id: "dailyCap", value: 2 }], { requireExpect: false }))).toBe("invalid-argument:INVALID_REQUEST");
    expect(await reason(() => parseChanges([{ id: "dailyCap" }], { requireExpect: false }))).toBe("invalid-argument:INVALID_REQUEST");
    expect(await reason(() => parseChanges([{ id: "dailyCap", value: 1, reset: true }], { requireExpect: false }))).toBe("invalid-argument:INVALID_REQUEST");
    expect(await reason(() => parseChanges([{ id: "dailyCap", value: 1 }], { requireExpect: true }))).toBe("invalid-argument:INVALID_REQUEST");
    expect(await reason(() => parseChanges([], { requireExpect: false }))).toBe("invalid-argument:INVALID_REQUEST");
    expect(await reason(() => parseChanges("dailyCap", { requireExpect: false }))).toBe("invalid-argument:INVALID_REQUEST");
  });
});

// --- Planning (pure) ------------------------------------------------------------------------

describe("planSettings", () => {
  test("field paths into stored maps, the whole field only where none is stored", () => {
    expect(writes(FULL_CONFIG, [{ id: "welcome.perDay", value: 80 }, { id: "dailyCap", value: 90 }])).toEqual([
      ["welcome>perDay", 80],
      ["dailyCap", 90],
    ]);
    expect(writes({}, [{ id: "reports.keepDays", value: 30 }])).toEqual([["reports", { keepDays: 30 }]]);
    expect(writes(FULL_CONFIG, [{ id: "providerBalanceAlerts.bfl", value: 500 }])).toEqual([["providerBalanceAlerts>bfl", 500]]);
    expect(writes(FULL_CONFIG, [{ id: "image.maxStepImages", value: 1 }])).toEqual([["image>maxStepImages", 1]]);
  });

  test("a reset deletes the override; a key the parser requires is set to its default; an emptied map goes", () => {
    expect(writes(FULL_CONFIG, [{ id: "dailyCap", reset: true }])).toEqual([["dailyCap", "(deleted)"]]);
    expect(writes(FULL_CONFIG, [{ id: "welcome.recipes", reset: true }])).toEqual([["welcome>recipes", "(deleted)"]]);
    expect(writes(FULL_CONFIG, [{ id: "welcome.recipes", reset: true }, { id: "welcome.perDay", reset: true }])).toEqual([["welcome", "(deleted)"]]);
    expect(writes({ costs: { recipe: 2, menu2: 4, menu3: 5 } }, [{ id: "costs.recipe", reset: true }])).toEqual([["costs>recipe", 1]]);
    expect(writes(FULL_CONFIG, [{ id: "costs.recipeImage", reset: true }])).toEqual([["costs>recipeImage", "(deleted)"]]);
    // Nothing stored: nothing to reset.
    const p = plan({}, [{ id: "dailyCap", reset: true }, { id: "costs.recipe", reset: true }]);
    expect(p).toMatchObject({ writes: [], unchanged: ["dailyCap", "costs.recipe"] });
  });

  test("the costs map is written complete or not at all", () => {
    // No costs stored: the required keys are written beside the change.
    expect(writes({}, [{ id: "costs.recipeImage", value: 4 }])).toEqual([["costs", { recipe: 1, menu2: 4, menu3: 5, recipeImage: 4 }]]);
    expect(writes({}, [{ id: "costs.recipe", value: 2 }, { id: "costs.menu2", value: 5 }])).toEqual([["costs", { recipe: 2, menu2: 5, menu3: 5 }]]);
    // A partial map as stored is refused as a whole by the parser (the default table is in force):
    // it is not edited here, field by field, into something else.
    const partial = { costs: { recipe: 2 } };
    expect(warnings(partial)).toEqual(["costs"]);
    expect(() => plan(partial, [{ id: "costs.menu2", value: 4 }])).toThrow(/costs is invalid as stored/);
    expect(settingsViewOf(partial, D).sections.find((s) => s.id === "costs")!.settings.every((s) => s.blocked !== "")).toBe(true);
    // A change that would leave the table invalid refuses everything in the request.
    expect(() => plan({}, [{ id: "costs.recipe", value: 5 }, { id: "dailyCap", value: 5 }])).toThrow(/would not be valid with these changes \(costs/);
    expect(() => plan({}, [{ id: "costs.recipeImageSteps", value: 1 }])).toThrow(/would not be valid/);
    // Step pictures priced while the dish picture is off: valid as stored but not in force as asked.
    expect(() => plan({ costs: { recipe: 1, menu2: 4, menu3: 5, recipeImage: 0 } }, [{ id: "costs.recipeImageSteps", value: 4 }])).toThrow(/would not be in force as asked/);
  });

  test("the menu table: stored without the 3-day cells menu2 / menu3 own; derived cells are side effects", () => {
    const table = structuredClone(DEFAULT_MENU_SETS) as Record<string, Record<string, number>>;
    table["4"]["3"] = 8;
    const p = plan({}, [{ id: "costs.menuSets", value: table }]);
    const stored = p.writes[0].value as { menuSets: Record<string, Record<string, number>> };
    // Only the cell that differs from its derived value is stored.
    expect(stored.menuSets).toEqual({ "4": { "3": 8 } });
    expect(parseRuntimeConfig({ costs: stored }).costs.menuSets).toEqual(table);
    // Raising a recipe's price moves the derived menu cells with it, shown as a side effect.
    const raise = plan({ costs: { recipe: 1, menu2: 4, menu3: 5 } }, [{ id: "costs.recipe", value: 2 }]);
    expect(raise.sideEffects.map((s) => s.id)).toEqual(["costs.recipeImage", "costs.recipeImageSteps", "costs.menuSets"]);
    expect(raise.sideEffects[0]).toMatchObject({ from: 2, to: 4 });
  });

  test("stale: the stored value the caller saw must still be stored", () => {
    const stale = parseChanges([{ id: "dailyCap", value: 5, expect: 70 }], { requireExpect: true });
    expect(() => planSettings(FULL_CONFIG, stale, D)).toThrow(/changed meanwhile/);
    const fresh = parseChanges([{ id: "dailyCap", value: 5, expect: 77 }, { id: "reports.keepDays", value: 9, expect: null }], { requireExpect: true });
    expect(planSettings(FULL_CONFIG, fresh, D).writes).toHaveLength(2);
  });

  test("a top-level value stored invalid can be fixed; a map stored invalid can't be edited into something else", () => {
    expect(warnings({ dailyCap: "lots" })).toEqual(["dailyCap"]);
    expect(writes({ dailyCap: "lots" }, [{ id: "dailyCap", value: 5 }])).toEqual([["dailyCap", 5]]);
    expect(() => plan({ welcome: "three" }, [{ id: "welcome.recipes", value: 3 }])).toThrow(/welcome is invalid as stored/);
    expect(() => plan({ image: { provider: "nope" } }, [{ id: "image.maxStepImages", value: 1 }])).toThrow(/image is invalid as stored/);
  });
});

// --- Against the emulators ------------------------------------------------------------------

describe("applySettings", () => {
  test("apply: only the fields asked change, one audit record, one log line", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    const out = await applySettings(env, [{ id: "reports.keepDays", value: 30 }, { id: "tasteQuiz.hidden", value: [tasteChoices()[0].value] }], { apply: true, actor: { via: "cli" }, confirmSensitive: ["reports.keepDays"] });
    expect(out).toMatchObject({ applied: true, changed: true });
    expect(await runtime()).toEqual({ ...FULL_CONFIG, reports: { keepDays: 30 }, tasteQuiz: { hidden: [tasteChoices()[0].value] } });
    const [audit, ...more] = await audits();
    expect(more).toEqual([]);
    expect(audit).toMatchObject({
      actor: { via: "cli" },
      sensitive: true,
      sensitiveSettings: ["reports.keepDays"],
      changes: [
        { id: "reports.keepDays", from: 90, to: 30, storedFrom: null, storedTo: 30, reset: false, sensitive: true },
        { id: "tasteQuiz.hidden", from: ["units"], to: [tasteChoices()[0].value], storedFrom: ["units"] },
      ],
      writes: [{ path: "reports", value: { keepDays: 30 } }, { path: "tasteQuiz > hidden", value: [tasteChoices()[0].value] }],
    });
    expect(audit.at.toMillis()).toBeGreaterThan(Date.now() - 60_000);
    expect(env.logs.filter((l) => l.message === "owner_settings_changed")).toEqual([{
      level: "warn", message: "owner_settings_changed",
      data: { via: "cli", settings: ["reports.keepDays", "tasteQuiz.hidden"], sensitive: ["reports.keepDays"], changes: [{ id: "reports.keepDays", from: 90, to: 30 }, { id: "tasteQuiz.hidden", from: "(in the audit record)", to: "(in the audit record)" }], change: out.changeId },
    }]);
    // The instances read it as asked.
    const cfg = await loadRuntimeConfig(db);
    expect(cfg.reports.keepDays).toBe(30);
    expect(cfg.dailyCap).toBe(77);
  });

  test("a reset deletes the field and keeps the rest; a missing document is created with only the change", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    await applySettings(makeEnv(), [{ id: "dailyCap", reset: true }, { id: "welcome.recipes", reset: true }], { apply: true, actor: { via: "cli" }, confirmSensitive: ["dailyCap", "welcome.recipes"] });
    const { dailyCap: _gone, ...rest } = FULL_CONFIG;
    void _gone;
    expect(await runtime()).toEqual({ ...rest, welcome: { perDay: 50 } });
    await resetEmulators();
    await applySettings(makeEnv(), [{ id: "reports.perDay", value: 50 }], { apply: true, actor: { via: "cli" }, confirmSensitive: ["reports.perDay"] });
    expect(await runtime()).toEqual({ reports: { perDay: 50 } });
  });

  test("sensitive changes need their exact confirmation; ordinary ones don't; a dry run needs none and writes nothing", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    const ask = [{ id: "generationEnabled", value: false }, { id: "tasteQuiz.hidden", value: [] }];
    const dry = await applySettings(env, ask, { apply: false, actor: { via: "cli" } });
    expect(dry).toMatchObject({ applied: false, changed: true, plan: { sensitive: ["generationEnabled"] } });
    expect(await reason(applySettings(env, ask, { apply: true, actor: { via: "cli" } }))).toBe("CONFIRM_SENSITIVE");
    expect(await reason(applySettings(env, ask, { apply: true, actor: { via: "cli" }, confirmSensitive: ["tasteQuiz.hidden"] }))).toBe("CONFIRM_SENSITIVE");
    expect(await reason(applySettings(env, ask, { apply: true, actor: { via: "cli" }, confirmSensitive: ["generationEnabled", "dailyCap"] }))).toBe("CONFIRM_SENSITIVE");
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await audits()).toEqual([]);
    const out = await applySettings(env, ask, { apply: true, actor: { via: "cli" }, confirmSensitive: ["generationEnabled"] });
    expect(out.applied).toBe(true);
    expect((await runtime())!.generationEnabled).toBe(false);
    const [audit] = await audits();
    expect(audit).toMatchObject({ sensitive: true, sensitiveSettings: ["generationEnabled"] });
    const line = env.logs.find((l) => l.message === "owner_settings_changed")!;
    expect(line).toMatchObject({ level: "warn", data: { sensitive: ["generationEnabled"], changes: [{ id: "generationEnabled", from: true, to: false }, { id: "tasteQuiz.hidden", from: "(in the audit record)", to: "(in the audit record)" }] } });
  });

  test("a refusal anywhere refuses everything; a value already so writes nothing and records nothing", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    expect(await reason(applySettings(env, [{ id: "reports.perDay", value: 9 }, { id: "costs.recipe", value: 50 }], { apply: true, actor: { via: "cli" }, confirmSensitive: ["costs.recipe"] }))).toBe("INVALID_RESULT");
    expect(await reason(applySettings(env, [{ id: "reports.perDay", value: 9 }, { id: "welcome.recipes", value: 999 }], { apply: true, actor: { via: "cli" } }))).toBe("INVALID_VALUE");
    const same = await applySettings(env, [{ id: "dailyCap", value: 77 }, { id: "welcome.perDay", value: 50 }], { apply: true, actor: { via: "cli" } });
    expect(same).toMatchObject({ applied: false, changed: false, plan: { unchanged: ["dailyCap", "welcome.perDay"] } });
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await audits()).toEqual([]);
    expect(env.logs.filter((l) => l.message === "owner_settings_changed")).toEqual([]);
  });

  test("the provider switches still work beside it, and neither touches the other's fields", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    await applySettings(env, [{ id: "image.maxStepImages", value: 1 }], { apply: true, actor: { via: "cli" }, confirmSensitive: ["image.maxStepImages"] });
    await switchProvider(env, "image-fallback", "none", { apply: true, actor: { via: "cli" } });
    await switchProvider(env, "image", "vertex/gemini-nano-banana-2.1", { apply: true, actor: { via: "cli" } });
    const image = (await runtime())!.image as Record<string, unknown>;
    expect(image).toEqual({ provider: "vertex", model: "gemini-nano-banana-2.1", fallbackModel: "", maxStepImages: 1 });
    expect((await db.collection(SWITCH_CHANGES).get()).size).toBe(2);
    expect((await audits()).length).toBe(1);
  });

  test("listSettings: values, defaults, markers and the last change per setting", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    await applySettings(env, [{ id: "reports.keepDays", value: 30 }], { apply: true, actor: { via: "cli" }, confirmSensitive: ["reports.keepDays"] });
    const view = await listSettings(env);
    const v = (id: string) => view.sections.flatMap((s) => s.settings).find((s) => s.id === id)!;
    expect(v("dailyCap")).toMatchObject({ value: 77, default: 1000, stored: 77, overridden: true, changed: true, lastChange: null });
    expect(v("reports.keepDays")).toMatchObject({ value: 30, default: 90, overridden: true, lastChange: { via: "cli" } });
    expect(v("costs.recipeImage")).toMatchObject({ value: 3, default: 2, changed: true });
    expect(v("costs.recipe")).toMatchObject({ value: 1, default: 1, overridden: true, changed: false });
    expect(view.lastChange).toMatchObject({ via: "cli", settings: ["reports.keepDays"] });
    expect(view.readOnly.find((r) => r.id === "prices.inputUsdPerMTok")).toMatchObject({ value: 2, default: 1.5 });
  });

  test("listSettings reads today's figures from the last 7 days of stats, read-only, and passes the board's address", async () => {
    const now = Date.now();
    await statsRef(db, utcDay(now)).set({ spentMicroUsd: 100_000, netCreditsGranted: 5, welcomeUsedCredits: 2 });
    await statsRef(db, utcDay(now - 6 * 86_400_000)).set({ netCreditsGranted: 7 });
    await statsRef(db, utcDay(now - 7 * 86_400_000)).set({ netCreditsGranted: 1000 });
    const view = await listSettings({ ...makeEnv(), boardUrl: "https://storage.cloud.google.com/x/board.html" });
    expect(view.today).toMatchObject({ day: utcDay(now), spentMicroUsd: 100_000, netCredits7d: 12, welcomeUsedCredits: 2 });
    expect(view.boardUrl).toBe("https://storage.cloud.google.com/x/board.html");
    expect((await statsRef(db, utcDay(now)).get()).data()).toEqual({ spentMicroUsd: 100_000, netCreditsGranted: 5, welcomeUsedCredits: 2 });
  });
});

// --- The console's envelope (web) against the command's whole range ------------------------

describe("the console's envelope", () => {
  const webReason = (data: Record<string, unknown>, changes: unknown[]) => reason(() => webPlan(data, changes));
  const ok = async (data: Record<string, unknown>, changes: unknown[]) => expect(await webReason(data, changes), JSON.stringify(changes)).toBe("no refusal");
  const no = async (data: Record<string, unknown>, changes: unknown[]) => expect(await webReason(data, changes), JSON.stringify(changes)).toBe("WEB_LIMIT");
  const set = (id: string, value: unknown) => [{ id, value }];

  test.each([
    // id, the most the page may set over the defaults, the least it refuses
    ["dailySpendBaseMicroUsd", 3_000_000, 3_010_000],
    ["dailySpendMaxMicroUsd", 5_000_000, 5_010_000],
    ["perUserDailySpendMicroUsd", 900_000, 910_000],
    ["dailyCap", 2500, 2501],
    ["perUserPerMinute", 6, 7],
    ["perUserPerDay", 80, 81],
    ["verifyPerMinute", 20, 21],
    ["infraRefundsPerDay", 6, 7],
    ["rejectedPromptsPerDay", 10, 11],
    ["sandboxDailyCap", 20, 21],
    ["sandboxDailyCredits", 200, 201],
    ["welcome.recipes", 5, 6],
    ["welcome.perDay", 500, 501],
  ] as const)("%s: up to %d on the page, any lower value, the whole range with the command", async (id, most, refused) => {
    await ok({}, set(id, most));
    await no({}, set(id, refused));
    await ok({}, set(id, 0));
    const c = def(id).control as { max: number };
    expect(plan({}, set(id, c.max)).changes[0].to).toBe(c.max);
    // A value set higher with the command can be lowered on the page, not raised further.
    const [field, key] = def(id).path;
    const stored = (n: number) => (key ? { [field]: { [key]: n } } : { [field]: n });
    await ok(stored(c.max), set(id, c.max - 1));
    await no(stored(c.max - 1), set(id, c.max));
  });

  test("lowering only: the revenue share, the net per recipe, the budget alert's fraction, the refund threshold", async () => {
    for (const [id, lower, higher] of [
      ["dailySpendRevenueFraction", 0.2, 0.3], ["netMicroUsdPerCredit", 100_000, 130_000], ["budgetKillFraction", 0.5, 1.05], ["refundAbuseThreshold", 1, 3],
    ] as const) {
      await ok({}, set(id, lower));
      await no({}, set(id, higher));
      // Relative to the value in force: once lowered, the page can't raise it back, not even by a reset.
      await no({ [id]: lower }, set(id, (DEFAULT_RUNTIME_CONFIG as unknown as Record<string, number>)[id]));
      await no({ [id]: lower }, [{ id, reset: true }]);
      expect(plan({ [id]: lower }, [{ id, reset: true }]).writes).toEqual([{ path: [id], delete: true }]);
      expect(plan({}, set(id, higher)).changes[0].to).toBe(higher);
    }
  });

  test("switches and the judge: sandbox only off, the judge only stricter", async () => {
    await no({}, set("allowSandbox", true));
    await ok({ allowSandbox: true }, set("allowSandbox", false));
    expect(plan({}, set("allowSandbox", true)).changes[0].to).toBe(true);
    await ok({}, set("outputJudge", "shadow"));
    await ok({ outputJudge: "shadow" }, set("outputJudge", "enforce"));
    await no({ outputJudge: "enforce" }, set("outputJudge", "shadow"));
    await no({ outputJudge: "shadow" }, set("outputJudge", "off"));
    await no({ outputJudge: "enforce" }, [{ id: "outputJudge", reset: true }]);
    // Stored invalid reads as enforce: the page can't loosen it either.
    await no({ outputJudge: "lax" }, set("outputJudge", "off"));
    expect(plan({ outputJudge: "enforce" }, set("outputJudge", "off")).changes[0].to).toBe("off");
  });

  test("reports: floors on the page, any raise; balances: never cleared or 0 on the page", async () => {
    for (const [id, floor] of [["reports.perUserPerDay", 1], ["reports.perDay", 10], ["reports.keepDays", 30]] as const) {
      await ok({}, set(id, floor));
      await no({}, set(id, floor - 1));
      expect(plan({}, set(id, floor - 1)).changes[0].to).toBe(floor - 1);
    }
    // Set lower with the command: the page may raise it, even while still under the floor.
    await ok({ reports: { perDay: 5 } }, set("reports.perDay", 8));
    await no({ reports: { perDay: 5 } }, set("reports.perDay", 4));
    await ok({}, set("providerBalanceAlerts.bfl", 500));
    await no({}, set("providerBalanceAlerts.bfl", 0));
    await ok({ providerBalanceAlerts: { bfl: 500 } }, set("providerBalanceAlerts.bfl", 1));
    await ok({ providerBalanceAlerts: { bfl: 500 } }, set("providerBalanceAlerts.bfl", 5000));
    await no({ providerBalanceAlerts: { bfl: 500 } }, [{ id: "providerBalanceAlerts.bfl", reset: true }]);
    expect(plan({ providerBalanceAlerts: { bfl: 500 } }, [{ id: "providerBalanceAlerts.bfl", reset: true }]).writes).toEqual([{ path: ["providerBalanceAlerts"], delete: true }]);
  });

  test("unrestricted: step pictures and the quiz's hidden choices", async () => {
    await ok({}, set("image.maxStepImages", 4));
    await ok({}, set("image.maxStepImages", 0));
    await ok({}, set("tasteQuiz.hidden", tasteChoices().slice(0, 5).map((o) => o.value)));
  });

  test("prices: lower (a menu down to half a recipe a dish), or 1 more a save up to 1.5 x the default", async () => {
    const stored = { costs: { recipe: 1, menu2: 4, menu3: 5, recipeImage: 2, recipeImageSteps: 3 } };
    expect([webPriceCeiling(1), webPriceCeiling(4), webPriceCeiling(5), webPriceCeiling(6)]).toEqual([2, 6, 8, 9]);
    expect([webMenuFloor(1, 1), webMenuFloor(1, 6), webMenuFloor(1, 9), webMenuFloor(1, 12), webMenuFloor(2, 9)]).toEqual([1, 3, 5, 6, 9]);
    // Every default menu is at or above its floor (so the floor never blocks the defaults).
    for (const [d, row] of Object.entries(DEFAULT_MENU_SETS)) for (const [c, n] of Object.entries(row)) expect(n, `${d}x${c}`).toBeGreaterThanOrEqual(webMenuFloor(1, Number(d) * Number(c)));
    await ok({}, set("costs.menu2", 5));
    await no({ costs: { recipe: 1, menu2: 4, menu3: 8 } }, set("costs.menu2", 6));
    // Derived cells count: at menu2 6 the 1-day 1-course menu (derived, unstored) would reach 3, above its own ceiling.
    expect(() => webPlan({ costs: { recipe: 1, menu2: 5, menu3: 6 } }, set("costs.menu2", 6))).toThrow(/A 1-day menu of 1 course: 3; at most 2 here/);
    await ok({ costs: { recipe: 1, menu2: 5, menu3: 6, menuSets: { "1": { "1": 2, "2": 3 }, "2": { "1": 3 } } } }, set("costs.menu2", 6));
    await no({ costs: { recipe: 1, menu2: 6, menu3: 8 } }, set("costs.menu2", 7));
    await ok({}, set("costs.menu2", 3));
    await no({}, set("costs.menu2", 2));
    await no({}, set("costs.menu3", 4));
    // A recipe's price raised alone moves the unstored picture prices by 2 (twice and three times a recipe): refused.
    expect(await reason(() => webPlan({}, set("costs.recipe", 2)))).toBe("WEB_LIMIT");
    expect(() => webPlan({}, set("costs.recipe", 2))).toThrow(/A recipe with a picture: 2 to 4; at most 1 more a save here[\s\S]*npm run admin -- config set costs.recipe <value> --apply --confirm costs.recipe/);
    await ok(stored, set("costs.recipe", 2));
    await no({ costs: { recipe: 2, menu2: 4, menu3: 5, recipeImage: 3, recipeImageSteps: 3 } }, set("costs.recipe", 3));
    // Pictures: off and on again up to the ceiling.
    await ok(stored, set("costs.recipeImage", 0));
    await ok({ costs: { ...stored.costs, recipeImage: 0, recipeImageSteps: 0 } }, [{ id: "costs.recipeImage", value: 3 }, { id: "costs.recipeImageSteps", value: 5 }]);
    await no({ costs: { ...stored.costs, recipeImage: 0, recipeImageSteps: 0 } }, set("costs.recipeImage", 4));
    // The table, cell by cell.
    const table = (cell: [string, string, number]) => {
      const t2 = structuredClone(DEFAULT_MENU_SETS) as Record<string, Record<string, number>>;
      t2[cell[0]][cell[1]] = cell[2];
      return t2;
    };
    await ok({}, set("costs.menuSets", table(["4", "3", 7])));
    await no({}, set("costs.menuSets", table(["4", "3", 8])));
    await no({}, set("costs.menuSets", table(["4", "3", 5])));
    await ok({}, set("costs.menuSets", table(["1", "3", 2])));
    // The whole parser range stays with the command.
    expect(plan({}, set("costs.menuSets", table(["4", "3", COST_MAX]))).changes[0].to).toEqual(table(["4", "3", COST_MAX]));
    expect(costsWebRefusal(DEFAULT_COSTS, DEFAULT_COSTS)).toBe("");
  });

  test("the view shows the page's range and the command for anything larger", () => {
    const v = (data: Record<string, unknown>, id: string) => settingsViewOf(data, D).sections.flatMap((s) => s.settings).find((s) => s.id === id)!;
    expect(v({}, "dailyCap")).toMatchObject({ web: { text: "up to 2500 or any lower value", max: 2500 }, command: "npm run admin -- config set dailyCap <value> --apply --confirm dailyCap" });
    expect(v({ dailyCap: 9000 }, "dailyCap").web).toMatchObject({ max: 9000 });
    expect(v({}, "dailySpendMaxMicroUsd").web?.text).toBe("up to USD 5.00 or any lower value");
    expect(v({}, "budgetKillFraction").web).toMatchObject({ text: "only lower than the value in force (1)", max: 1 });
    expect(v({}, "allowSandbox").web?.text).toBe("only off");
    expect(v({}, "outputJudge").web?.text).toBe("only stricter: Off -> Shadow (log only) -> Enforce");
    expect(v({}, "reports.keepDays").web).toMatchObject({ min: 30 });
    expect(v({}, "providerBalanceAlerts.bfl").web?.text).toBe("at least 1; never cleared");
    expect(v({}, "costs.menu2").web).toMatchObject({ min: 3, max: 5 });
    expect(v({}, "costs.menuSets").web?.cells?.["4"]["3"]).toEqual({ min: 6, max: 7 });
    expect(v({}, "costs.menuSets").command).toBe("npm run admin -- config set costs.menuSets '<table as JSON>' --apply --confirm costs.menuSets");
    expect(v({ generationEnabled: false, disabledBy: "budget alert b: 10 of 10" }, "generationEnabled").web?.text).toMatch(/budget alert's, so only the command/);
    expect(v({}, "tasteQuiz.hidden")).toMatchObject({ web: null, command: commandFor(def("tasteQuiz.hidden")) });
    for (const s of SETTINGS) expect(commandFor(s)).toContain(`config set ${s.id} `);
  });
});

describe("generationEnabled and the budget kill switch's pause", () => {
  const killed = { generationEnabled: false, disabledBy: "budget alert Recipe budget: 120 of 100", disabledAt: "then" };

  test("the page pauses at any time and records it; it turns on a pause of its own or by hand, clearing the marks", async () => {
    const off = webPlan({}, [{ id: "generationEnabled", value: false }]);
    expect(off.writes).toEqual([
      { path: ["generationEnabled"], value: false },
      { path: ["disabledBy"], value: PAUSED_BY.web },
      { path: ["disabledAt"], value: "(the time of the change)", now: true },
    ]);
    for (const paused of [{ generationEnabled: false, disabledBy: PAUSED_BY.web, disabledAt: "then" }, { generationEnabled: false }, { generationEnabled: false, disabledBy: "by hand" }]) {
      const on = webPlan(paused, [{ id: "generationEnabled", value: true }]);
      expect(applyWrites(paused, on.writes)).toEqual({ generationEnabled: true });
    }
  });

  test("after the budget alert's pause only the command turns it on, and clears disabledBy / disabledAt", async () => {
    expect(await reason(() => webPlan(killed, [{ id: "generationEnabled", value: true }]))).toBe("WEB_LIMIT");
    expect(await reason(() => webPlan(killed, [{ id: "generationEnabled", reset: true }]))).toBe("WEB_LIMIT");
    const on = plan(killed, [{ id: "generationEnabled", value: true }]);
    expect(applyWrites(killed, on.writes)).toEqual({ generationEnabled: true });
    // Against the emulators, through the command.
    await db.doc(RUNTIME_DOC).set({ ...killed, dailyCap: 77 });
    const lines: string[] = [];
    await runConfig(parseConfigArgs(["config", "set", "generationEnabled", "on", "--apply", "--confirm", "generationEnabled"]), { ...makeEnv(), print: (l: string) => void lines.push(l) });
    expect(await runtime()).toEqual({ generationEnabled: true, dailyCap: 77 });
    expect(lines.join("\n")).toContain("  - disabledBy deleted");
    // The command's own pause says so, with the time.
    await runConfig(parseConfigArgs(["config", "set", "generationEnabled", "off", "--apply", "--confirm", "generationEnabled"]), { ...makeEnv(), print: () => undefined });
    const after = (await runtime())!;
    expect(after).toMatchObject({ generationEnabled: false, disabledBy: PAUSED_BY.cli });
    expect((after.disabledAt as { toMillis(): number }).toMillis()).toBeGreaterThan(Date.now() - 60_000);
  });
});

// --- The menu table as stored ---------------------------------------------------------------

describe("the menu table as stored", () => {
  test("a stored table holding the 3-day cells does not stop menu2 / menu3 being changed", async () => {
    const data = { costs: { recipe: 1, menu2: 4, menu3: 5, menuSets: { "3": { "1": 3, "2": 4, "3": 5 }, "4": { "3": 7 } } } };
    expect(warnings(data)).toEqual([]);
    for (const via of ["cli", "web"] as const) {
      const p = planSettings(data, parseChanges([{ id: "costs.menu2", value: 5 }], { requireExpect: false }), D, { via });
      const next = applyWrites(data, p.writes);
      expect(warnings(next)).toEqual([]);
      // The copies of menu2 / menu3 go; the cells set by hand stay as stored.
      expect((next.costs as { menuSets: unknown }).menuSets).toEqual({ "3": { "1": 3 }, "4": { "3": 7 } });
      expect(parseRuntimeConfig(next).costs).toMatchObject({ menu2: 5, menu3: 5 });
    }
  });

  test("editing one cell freezes no other: a later price change still moves every derived cell", () => {
    const table = structuredClone(DEFAULT_MENU_SETS) as Record<string, Record<string, number>>;
    table["4"]["3"] = 8;
    const first = applyWrites({}, plan({}, [{ id: "costs.menuSets", value: table }]).writes);
    expect((first.costs as { menuSets: unknown }).menuSets).toEqual({ "4": { "3": 8 } });
    const second = applyWrites(first, plan(first, [{ id: "costs.menu3", value: 6 }]).writes);
    const t2 = parseRuntimeConfig(second).costs.menuSets;
    expect([t2["1"]["3"], t2["2"]["3"], t2["3"]["3"], t2["4"]["3"]]).toEqual([4, 5, 6, 8]);
    // A table equal to the derived one stores nothing; asking for what is in force writes nothing.
    expect(plan(first, [{ id: "costs.menuSets", value: parseRuntimeConfig(first).costs.menuSets }]).writes).toEqual([]);
    const back = applyWrites(first, plan(first, [{ id: "costs.menuSets", value: DEFAULT_MENU_SETS }]).writes);
    expect(back).toEqual({ costs: { recipe: 1, menu2: 4, menu3: 5 } });
  });

  test("round trip: any valid table stored as its differing cells parses back to itself", () => {
    let seed = 7;
    const rnd = (n: number) => (seed = (seed * 1103515245 + 12345) % 2 ** 31) % n;
    let checked = 0;
    for (let i = 0; i < 400; i++) {
      const recipe = 1 + rnd(3);
      const raw: Record<string, Record<string, number>> = {};
      for (const d of ["1", "2", "3", "4"]) for (const c of ["1", "2", "3"]) if (rnd(3) === 0) (raw[d] ??= {})[c] = recipe + rnd(8);
      const menu2 = recipe + rnd(6);
      const menu3 = menu2 + rnd(4);
      const costs = { recipe, menu2, menu3, menuSets: { ...raw, "3": { ...(raw["3"] ?? {}), "2": menu2, "3": menu3 } } };
      const want = validCosts(costs);
      if (!want) continue;
      checked++;
      const stored = structuredClone(costs) as Record<string, unknown>;
      normaliseMenuSets(stored);
      expect(stored.menuSets === undefined || !(stored.menuSets as Record<string, Record<string, number>>)["3"]?.["2"]).toBe(true);
      expect(validCosts(stored)?.menuSets, JSON.stringify(costs)).toEqual(want.menuSets);
      expect(parseRuntimeConfig({ costs: stored }).costs.menuSets).toEqual(want.menuSets);
      // And the editor's plan for that table writes the same.
      const p = plan({ costs: { recipe, menu2, menu3 } }, [{ id: "costs.menuSets", value: want.menuSets }]);
      expect(parseRuntimeConfig(applyWrites({ costs: { recipe, menu2, menu3 } }, p.writes)).costs.menuSets).toEqual(want.menuSets);
    }
    expect(checked).toBeGreaterThan(50);
  });
});

// --- The confirm is bound to the dry run -----------------------------------------------------

describe("the confirm is bound to the dry run", () => {
  test("the token is the plan's digest: the same plan the same token, a different one (derived cells included) another", () => {
    const a = plan({ costs: { recipe: 1, menu2: 4, menu3: 5 } }, [{ id: "costs.recipeImage", value: 3 }]);
    expect(plan({ costs: { recipe: 1, menu2: 4, menu3: 5 } }, [{ id: "costs.recipeImage", value: 3 }]).token).toBe(a.token);
    // A stored value that changes neither what is written nor what is shown leaves it as it was.
    const b = plan({ costs: { recipe: 1, menu2: 5, menu3: 5 } }, [{ id: "costs.recipeImage", value: 3 }]);
    expect(b.changes).toEqual(a.changes);
    expect(b.token).toBe(a.token);
    // The same change with other things moving with it (here the step pictures' derived price): another token.
    const c = plan({ costs: { recipe: 1, menu2: 4, menu3: 5 } }, [{ id: "costs.recipe", value: 2 }]);
    const c2 = plan({ costs: { recipe: 1, menu2: 4, menu3: 5, recipeImage: 2 } }, [{ id: "costs.recipe", value: 2 }]);
    expect(c2.token).not.toBe(c.token);
  });

  test("a confirm whose plan is no longer the dry run's is refused as STALE and writes nothing", async () => {
    await db.doc(RUNTIME_DOC).set({ costs: { recipe: 1, menu2: 4, menu3: 5, recipeImage: 2, recipeImageSteps: 3 } });
    const env = makeEnv();
    const actor = { via: "web" as const, uid: "owner" };
    const ask = [{ id: "costs.recipe", value: 2, expect: 1 }];
    const dry = await applySettings(env, ask, { apply: false, actor });
    // Meanwhile the command changes the 3-course menu: costs.recipe's own value is untouched, but
    // what the save would move with it (the menu table) is not what the dialog showed.
    await applySettings(env, [{ id: "costs.menu3", value: 6 }], { apply: true, actor: { via: "cli" }, confirmSensitive: ["costs.menu3"] });
    expect(await reason(applySettings(env, ask, { apply: true, actor, confirmSensitive: ["costs.recipe"], expectToken: dry.plan.token }))).toBe("STALE");
    expect(((await runtime())!.costs as { recipe: number }).recipe).toBe(1);
    const fresh = await applySettings(env, ask, { apply: false, actor });
    expect(fresh.plan.token).not.toBe(dry.plan.token);
    const done = await applySettings(env, ask, { apply: true, actor, confirmSensitive: ["costs.recipe"], expectToken: fresh.plan.token });
    expect(done.applied).toBe(true);
  });
});

// --- A sensitive save needs a sign-in from the last 5 minutes -------------------------------

describe("sign-in age", () => {
  test("a sensitive save needs a sign-in from the last 5 minutes; an ordinary one 30; a check (dry run) 30", async () => {
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ uids: [owner.uid] });
    const later = makeEnv({ now: () => Date.now() + (OWNER_SENSITIVE_SIGN_IN_S + 60) * 1000 });
    const sensitive = [{ id: "welcome.recipes", value: 4, expect: null }];
    const dry = await setOwnerSettings(later, bearer(owner.idToken), { changes: sensitive, dryRun: true });
    expect(await reason(setOwnerSettings(later, bearer(owner.idToken), { changes: sensitive, confirm: true, token: dry.plan.token, sensitive: ["welcome.recipes"], typed: SENSITIVE_CONFIRM_WORD }))).toBe("RECENT_SIGN_IN");
    expect(await runtime()).toBeUndefined();
    const ordinary = [{ id: "tasteQuiz.hidden", value: [tasteChoices()[0].value], expect: null }];
    const dry2 = await setOwnerSettings(later, bearer(owner.idToken), { changes: ordinary, dryRun: true });
    expect((await setOwnerSettings(later, bearer(owner.idToken), { changes: ordinary, confirm: true, token: dry2.plan.token })).changed).toBe(true);
    // Right after the sign-in, the sensitive one goes through.
    const now = makeEnv();
    const dry3 = await setOwnerSettings(now, bearer(owner.idToken), { changes: sensitive, dryRun: true });
    expect((await setOwnerSettings(now, bearer(owner.idToken), { changes: sensitive, confirm: true, token: dry3.plan.token, sensitive: ["welcome.recipes"], typed: SENSITIVE_CONFIRM_WORD })).changed).toBe(true);
    // The command has no sign-in: no age.
    expect((await applySettings(makeEnv(), [{ id: "welcome.recipes", value: 3 }], { apply: true, actor: { via: "cli" }, confirmSensitive: ["welcome.recipes"] })).applied).toBe(true);
  });

  test("the server's envelope holds whatever the page sends", async () => {
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ uids: [owner.uid] });
    const env = makeEnv();
    for (const [id, value] of [["dailyCap", 100_000], ["allowSandbox", true], ["welcome.recipes", 20], ["budgetKillFraction", 2], ["costs.menu2", 1]] as const) {
      expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes: [{ id, value, expect: null }], dryRun: true })), id).toBe("WEB_LIMIT");
    }
    expect(await runtime()).toBeUndefined();
  });
});

// --- Refusals are logged and alerted on ------------------------------------------------------

describe("refusals of the owner callables", () => {
  test("every refusal past the throttle is logged with a fixed reason and the uid hash; INVALID_REQUEST and WEB_LIMIT too", async () => {
    const env = makeEnv();
    const gate = new OwnerCallGate(env);
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ uids: [owner.uid] });
    const req = (): OwnerCallRequest => ({
      auth: { uid: owner.uid, token: { email: owner.email, firebase: { sign_in_provider: "google.com" } } },
      rawRequest: { headers: { "x-forwarded-for": "192.0.2.10", authorization: bearer(owner.idToken) } },
    });
    const call = (data: unknown) => gate.run("setOwnerSettings", req(), (authorization) => setOwnerSettings(env, authorization, data));
    expect(await reason(call({ changes: [] }))).toBe("invalid-argument:INVALID_REQUEST");
    expect(await reason(call({ changes: [{ id: "dailyCap", value: 100_000, expect: null }], dryRun: true }))).toBe("WEB_LIMIT");
    expect(await reason(call({ changes: [{ id: "nope", value: 1, expect: null }], dryRun: true }))).toBe("UNKNOWN_SETTING");
    const refused = env.logs.filter((l) => l.message === "owner_call_refused").map((l) => l.data);
    expect(refused).toEqual(["INVALID_REQUEST", "WEB_LIMIT", "UNKNOWN_SETTING"].map((r) => ({ function: "setOwnerSettings", reason: r, uidHash: uidHash(owner.uid) })));
    const logged = JSON.stringify(env.logs);
    expect(logged).not.toContain(owner.uid);
    expect(logged).not.toContain(owner.email);
    // Fixed codes only.
    expect(refusalReason(new HttpsError("invalid-argument", "x <script>"))).toBe("INVALID_ARGUMENT");
    expect(refusalReason(new HttpsError("invalid-argument", "x", { reason: "lower case <b>" }))).toBe("INVALID_ARGUMENT");
    expect(refusalReason(new Error("boom"))).toBeNull();
  });

  test("an alert policy covers every reason but NOT_OWNER, SIGN_IN and RATE_LIMITED", () => {
    const policy = JSON.parse(readFileSync(join(__dirname, "../alerts/policies/owner-refusals.json"), "utf8"));
    const filter: string = policy.conditions[0].conditionMatchedLog.filter;
    expect(filter).toBe('resource.type="cloud_run_revision" AND jsonPayload.message="owner_call_refused" AND NOT jsonPayload.reason=("NOT_OWNER" OR "SIGN_IN" OR "RATE_LIMITED")');
    const readme = readFileSync(join(__dirname, "../README.md"), "utf8");
    const count = readdirSync(join(__dirname, "../alerts/policies")).filter((f) => f.endsWith(".json")).length;
    expect(count).toBe(23);
    expect(readme).toContain("and twenty-three policies (");
    expect(readme).toContain("(`owner-refusals`)");
  });
});

// --- Pin: nothing changes for the apps unless a setting is changed --------------------------

describe("pin", () => {
  test("listing and dry runs write nothing; a change and its reset give back exactly the parse of before", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    const before = await loadRuntimeConfig(db);
    await listSettings(env);
    await applySettings(env, [{ id: "dailyCap", value: 5 }], { apply: false, actor: { via: "cli" } });
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await loadRuntimeConfig(db)).toEqual(before);
    const both = ["reports.perDay", "providerBalanceAlerts.bfl"];
    await applySettings(env, [{ id: "reports.perDay", value: 7 }, { id: "providerBalanceAlerts.bfl", value: 10 }], { apply: true, actor: { via: "cli" }, confirmSensitive: both });
    await applySettings(env, [{ id: "reports.perDay", reset: true }, { id: "providerBalanceAlerts.bfl", reset: true }], { apply: true, actor: { via: "cli" }, confirmSensitive: both });
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await loadRuntimeConfig(db)).toEqual(before);
    // An empty document reads as the defaults, exactly as before the settings existed.
    expect(parseRuntimeConfig({})).toEqual(DEFAULT_RUNTIME_CONFIG);
  });
});

// --- The callables with Auth emulator tokens ------------------------------------------------

async function googleUser(email = `owner-${randomUUID()}@example.com`): Promise<{ uid: string; idToken: string; email: string }> {
  const res = await fetch(
    `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=fake-api-key`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        postBody: `id_token=${encodeURIComponent(JSON.stringify({ sub: randomUUID(), email, email_verified: true, name: "Some Name" }))}&providerId=google.com`,
        requestUri: "http://localhost",
        returnSecureToken: true,
      }),
    },
  );
  const json = (await res.json()) as { localId: string; idToken: string };
  if (!res.ok) throw new Error(`signInWithIdp failed: ${JSON.stringify(json)}`);
  return { uid: json.localId, idToken: json.idToken, email };
}

const bearer = (t: string) => `Bearer ${t}`;

describe("the owner callables", () => {
  test("unsigned, garbage, not listed, and no allowlist are refused, writing nothing", async () => {
    const env = makeEnv();
    const owner = await googleUser();
    const body = { changes: [{ id: "reports.perDay", value: 9, expect: null }], confirm: true };
    expect(await reason(getOwnerSettings(env, undefined))).toBe("SIGN_IN");
    expect(await reason(setOwnerSettings(env, "Bearer nope", body))).toBe("SIGN_IN");
    expect(await reason(getOwnerSettings(env, bearer(owner.idToken)))).toBe("NOT_OWNER");
    await db.doc(OWNER_DOC).set({ emails: [owner.email] });
    const other = await googleUser();
    expect(await reason(getOwnerSettings(env, bearer(other.idToken)))).toBe("NOT_OWNER");
    expect(await reason(setOwnerSettings(env, bearer(other.idToken), body))).toBe("NOT_OWNER");
    expect(await runtime()).toBeUndefined();
    expect(await audits()).toEqual([]);
  });

  test("the owner sees the settings, checks a change, and saves it; sensitive ones need the typed word and ids", async () => {
    const env = makeEnv();
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ uids: [owner.uid] });
    const view = await getOwnerSettings(env, bearer(owner.idToken));
    expect(view.sections.map((s) => s.id)).toEqual(SECTIONS.map((s) => s.id));
    expect((await auth.getUser(owner.uid)).displayName).toBeUndefined();

    const hide = tasteChoices()[0].value;
    const changes = [{ id: "welcome.recipes", value: 5, expect: null }, { id: "tasteQuiz.hidden", value: [hide], expect: null }];
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes }))).toBe("invalid-argument:INVALID_REQUEST");
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes: [{ id: "tasteQuiz.hidden", value: [hide] }], dryRun: true }))).toBe("invalid-argument:INVALID_REQUEST");
    const dry = await setOwnerSettings(env, bearer(owner.idToken), { changes, dryRun: true });
    expect(dry).toMatchObject({ changed: false, plan: { sensitive: ["welcome.recipes"], changes: [{ id: "welcome.recipes", from: 3, to: 5, sensitive: true }, { id: "tasteQuiz.hidden", from: [], to: [hide] }] } });
    const token = dry.plan.token;
    expect(token).toMatch(/^[\w-]{20,}$/);
    expect(await runtime()).toBeUndefined();
    // A confirm without the dry run's token is refused before anything else.
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes, confirm: true, sensitive: ["welcome.recipes"], typed: SENSITIVE_CONFIRM_WORD }))).toBe("invalid-argument:INVALID_REQUEST");
    // Server-side, whatever the page did: the typed word, and exactly the sensitive ids.
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes, confirm: true, token }))).toBe("CONFIRM_SENSITIVE");
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes, confirm: true, token, sensitive: ["welcome.recipes"], typed: "change" }))).toBe("CONFIRM_SENSITIVE");
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes, confirm: true, token, sensitive: ["tasteQuiz.hidden"], typed: SENSITIVE_CONFIRM_WORD }))).toBe("CONFIRM_SENSITIVE");
    // Another token (another dry run's) is stale.
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes, confirm: true, token: token.slice(0, -1) + (token.endsWith("A") ? "B" : "A"), sensitive: ["welcome.recipes"], typed: SENSITIVE_CONFIRM_WORD }))).toBe("STALE");
    expect(await runtime()).toBeUndefined();
    const done = await setOwnerSettings(env, bearer(owner.idToken), { changes, confirm: true, token, sensitive: ["welcome.recipes"], typed: SENSITIVE_CONFIRM_WORD });
    expect(done.changed).toBe(true);
    expect(await runtime()).toEqual({ welcome: { recipes: 5 }, tasteQuiz: { hidden: [hide] } });
    const v = done.sections.flatMap((s) => s.settings).find((s) => s.id === "welcome.recipes")!;
    expect(v).toMatchObject({ value: 5, stored: 5, overridden: true, lastChange: { via: "web" } });
    const [audit] = await audits();
    expect(audit.actor).toEqual({ via: "web", uid: owner.uid });
    // An ordinary change alone needs no word.
    const ordinary = [{ id: "tasteQuiz.hidden", value: [], expect: [hide] }];
    const dry2 = await setOwnerSettings(env, bearer(owner.idToken), { changes: ordinary, dryRun: true });
    await setOwnerSettings(env, bearer(owner.idToken), { changes: ordinary, confirm: true, token: dry2.plan.token });
    // The stale view: what the page saw is no longer stored.
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes: [{ id: "welcome.recipes", value: 4, expect: null }], confirm: true, token, sensitive: ["welcome.recipes"], typed: SENSITIVE_CONFIRM_WORD }))).toBe("STALE");
    // Logs carry a uid hash at most.
    const logged = JSON.stringify(env.logs);
    expect(logged).not.toContain(owner.uid);
    expect(logged).not.toContain(owner.email);
    expect(env.logs.find((l) => l.message === "owner_settings_changed")?.data).toMatchObject({ via: "web", uidHash: uidHash(owner.uid) });
  });

  test("a change needs a recent sign-in; reading does not", async () => {
    const owner = await googleUser();
    await db.doc(OWNER_DOC).set({ emails: [owner.email] });
    const later = makeEnv({ now: () => Date.now() + 31 * 60_000 });
    expect((await getOwnerSettings(later, bearer(owner.idToken))).sections.length).toBeGreaterThan(0);
    expect(await reason(setOwnerSettings(later, bearer(owner.idToken), { changes: [{ id: "reports.perDay", value: 9, expect: null }], dryRun: true }))).toBe("RECENT_SIGN_IN");
    expect(await runtime()).toBeUndefined();
  });

  test("src/index.ts binds both through the owner gate, like the provider switch callables", () => {
    const index = readFileSync(join(__dirname, "../src/index.ts"), "utf8");
    expect(index).toMatch(/export const getOwnerSettings = onCall\(ownerCallable, \(request\) =>\s*ownerCall\("getOwnerSettings", request, \(env, authorization\) => ownerSettingsCore\(env, authorization\)\)\);/);
    expect(index).toMatch(/export const setOwnerSettings = onCall\(ownerCallable, \(request\) =>\s*ownerCall\("setOwnerSettings", request, \(env, authorization\) => setOwnerSettingsCore\(env, authorization, request\.data\)\)\);/);
  });
});

// --- The admin command ----------------------------------------------------------------------

describe("npm run admin -- config", () => {
  function cliEnv(): ImageEnv & { lines: string[]; logs: Line[] } {
    const lines: string[] = [];
    return { ...makeEnv(), print: (l: string) => void lines.push(l), lines };
  }

  test("parses list, set and reset, refuses the rest", () => {
    expect(parseConfigArgs(["config", "list"])).toEqual({ command: "list", project: "demo-foodtracker" });
    expect(parseConfigArgs(["config", "set", "dailyCap", "5", "reports.perDay", "9", "--apply", "--confirm", "dailyCap", "--project", "p"]))
      .toEqual({ command: "set", pairs: [["dailyCap", "5"], ["reports.perDay", "9"]], apply: true, confirm: ["dailyCap"], project: "p" });
    expect(parseConfigArgs(["config", "reset", "dailyCap"])).toMatchObject({ command: "reset", ids: ["dailyCap"], apply: false });
    for (const bad of [["config"], ["config", "set", "dailyCap"], ["config", "list", "--apply"], ["config", "set", "dailyCap", "5", "--confirm", "dailyCap"], ["config", "drop", "x"], ["config", "set", "a", "1", "--force"]]) {
      expect(() => parseConfigArgs(bad), bad.join(" ")).toThrow(AdminRefusal);
    }
    expect(cliValue(def("generationEnabled"), "off")).toBe(false);
    expect(cliValue(def("dailySpendMaxMicroUsd"), "3000000")).toBe(3_000_000);
    expect(cliValue(def("tasteQuiz.hidden"), "")).toEqual([]);
    expect(() => cliValue(def("dailyCap"), "many")).toThrow(AdminRefusal);
  });

  test("list shows every setting with its default and the read-only ones", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = cliEnv();
    await runConfig(parseConfigArgs(["config", "list"]), env);
    const text = env.lines.join("\n");
    for (const s of SETTINGS) expect(text).toContain(s.id);
    expect(text).toMatch(/dailyCap\s+77\s+\(default 1000\)\s+\[set, changed from default, sensitive\]/);
    expect(text).toContain("dailySpendMaxMicroUsd");
    expect(text).toContain("USD 2.00 (2000000)");
    expect(text).toMatch(/## Read-only here[\s\S]*prices\.inputUsdPerMTok\s+2/);
  });

  test("set and reset: a dry run writes nothing, --apply writes, a sensitive one needs --confirm", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const dry = cliEnv();
    await runConfig(parseConfigArgs(["config", "set", "dailyCap", "5", "reports.perDay", "9"]), dry);
    const text = dry.lines.join("\n");
    expect(text).toContain("dailyCap: 77 -> 5  [sensitive]");
    expect(text).toContain("Dry run, nothing changed. --apply would write to config/runtime:");
    expect(text).toContain("  - dailyCap = 5");
    expect(text).toContain('  - reports = {"perDay":9}');
    expect(text).toContain('To write: npm run admin -- config set dailyCap "5" reports.perDay "9" --apply --confirm dailyCap,reports.perDay');
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await audits()).toEqual([]);

    expect(await reason(runConfig(parseConfigArgs(["config", "set", "dailyCap", "5", "--apply"]), cliEnv()))).toMatch(/^admin:Refused \(CONFIRM_SENSITIVE\)/);
    expect(await reason(runConfig(parseConfigArgs(["config", "set", "dailyCap", "5x"]), cliEnv()))).toMatch(/^admin:/);
    expect(await reason(runConfig(parseConfigArgs(["config", "set", "welcome.recipes", "99"]), cliEnv()))).toMatch(/^admin:Refused \(INVALID_VALUE\)/);
    expect(await reason(runConfig(parseConfigArgs(["config", "reset", "nope"]), cliEnv()))).toMatch(/^admin:No setting nope/);

    const wet = cliEnv();
    await runConfig(parseConfigArgs(["config", "set", "dailyCap", "5", "--apply", "--confirm", "dailyCap"]), wet);
    expect(wet.lines.join("\n")).toMatch(/Done, written to config\/runtime:[\s\S]*Audit record ownerSettingChanges\//);
    expect((await runtime())!.dailyCap).toBe(5);
    const reset = cliEnv();
    await runConfig(parseConfigArgs(["config", "reset", "dailyCap", "--apply", "--confirm", "dailyCap"]), reset);
    expect((await runtime())!.dailyCap).toBeUndefined();
    const again = cliEnv();
    await runConfig(parseConfigArgs(["config", "reset", "dailyCap"]), again);
    expect(again.lines).toContain("Nothing to write.");
    expect((await audits()).map((a) => a.actor)).toEqual([{ via: "cli" }, { via: "cli" }]);
  });
});

// --- The console page -----------------------------------------------------------------------

describe("the console page", () => {
  const dir = join(__dirname, "../../owner-console");
  const html = readFileSync(join(dir, "index.html"), "utf8");
  const js = readFileSync(join(dir, "console.js"), "utf8");

  test("holds no setting, section, value or bound: it draws what the server sends", () => {
    for (const s of SETTINGS) {
      expect(html + js, s.id).not.toContain(s.id);
      expect(html + js, s.label).not.toContain(s.label);
      expect(html + js, s.help).not.toContain(s.help);
    }
    for (const s of SECTIONS) expect(html + js, s.title).not.toContain(`"${s.title}"`);
    for (const r of READ_ONLY) expect(html + js, r.id).not.toContain(r.id);
    expect(html + js).not.toContain(SENSITIVE_CONFIRM_WORD);
    expect(html + js).not.toContain("@");
    expect(js).not.toMatch(/innerHTML|outerHTML|insertAdjacentHTML|document\.write|eval\(|new Function|localStorage|sessionStorage|indexedDB|document\.cookie/);
    expect(html).not.toMatch(/<script(?![^>]*src=)/);
    expect(html).not.toMatch(/ on[a-z]+=/);
    expect(html).not.toMatch(/ style=/);
    expect(js).toContain("inMemoryPersistence");
    expect(js).toContain('call("getOwnerSettings")');
    expect(js).toContain('"setOwnerSettings", { changes, dryRun: true }');
  });

  test("is a dashboard drawn from the view: status strip, a card per group with its figures and read-only fields, the board's link", () => {
    // Groups, figures, status and the board come from getOwnerSettings, never from the page.
    for (const k of ["v.sections", "sec.facts", "sec.wide", "settingsView?.status", "r.section", "v.boardUrl"]) expect(js, k).toContain(k);
    for (const s of SECTIONS) expect(html + js, s.id).not.toMatch(new RegExp(`["'\`]${s.id}["'\`]`));
    // The board's address is the server's, not the page's.
    expect(html + js).not.toContain("storage.cloud.google.com");
    expect(html).toContain('id="board-link"');
    expect(html).toMatch(/<a id="board-link"[^>]*rel="noopener noreferrer"/);
    // One save path: the dry run, the confirmation bound to its token, the typed word, the fresh sign-in.
    expect(js).toContain("token: p.plan.token");
    expect(js).toContain('e.reason !== "RECENT_SIGN_IN"');
    expect((js.match(/call\("setOwnerSettings"/g) ?? []).length).toBe(2);
    expect(js).toContain("$(\"settings-confirm-ok\").disabled = sensitive");
    // Unsaved changes: per card and in the dock, with a review.
    for (const id of ["dock", "pending", "pending-list", "save-all", "discard-all", "review-all"]) expect(html, id).toContain(`id="${id}"`);
    // Labels bound to their controls; no inline style or script; light and dark.
    expect(js).toContain("htmlFor: fid");
    expect(html).not.toMatch(/<style|style=/);
    expect(readFileSync(join(dir, "console.css"), "utf8")).toContain("prefers-color-scheme:dark");
  });

  test("every group has a title and a line of help, and ids are unique", () => {
    expect(new Set(SECTIONS.map((s) => s.id)).size).toBe(SECTIONS.length);
    for (const s of SECTIONS) expect(s.title && s.help, s.id).toBeTruthy();
    for (const r of READ_ONLY) expect([...SECTIONS.map((s) => s.id), "providers", "other"], r.id).toContain(r.section);
  });
});
