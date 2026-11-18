/**
 * The owner's runtime settings (src/core/ownerSettings.ts): the registry (every field
 * parseRuntimeConfig reads is editable, read-only or excluded with a reason), every setting's
 * bounds, the planned writes (field paths only, resets, a complete costs map or none), the
 * transaction against the emulators (other fields untouched, an audit record, the log line),
 * stale views, the sensitive confirmation enforced by the server, the owner gate with Auth
 * emulator tokens, the admin command's dry run and --apply, and the console page holding no data.
 */
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { HttpsError } from "firebase-functions/v2/https";
import { AdminRefusal } from "../src/admin/accountRequests";
import { cliValue, parseConfigArgs, runConfig, type ImageEnv } from "../src/admin/cli";
import { DEFAULT_MENU_SETS, DEFAULT_IMAGE_SELECTION, DEFAULT_TEXT_SELECTION } from "../src/constants";
import { DEFAULT_RUNTIME_CONFIG, loadRuntimeConfig, parseRuntimeConfig, uidHash } from "../src/model";
import type { Logger } from "../src/ports";
import { OWNER_DOC, PROFILE_CHANGES, RUNTIME_DOC, SwitchRefused, activateProfile, type OwnerEnv } from "../src/core/providerSwitch";
import {
  EXCLUDED,
  FAMILIES,
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

  test("the sensitive ones are those that pause, spend, charge, give away or loosen a brake", () => {
    const sensitive = SETTINGS.filter((s) => s.sensitive).map((s) => s.id);
    for (const id of ["generationEnabled", "allowSandbox", "outputJudge", "dailySpendBaseMicroUsd", "dailySpendMaxMicroUsd", "dailySpendRevenueFraction",
      "netMicroUsdPerCredit", "perUserDailySpendMicroUsd", "dailyCap", "budgetKillFraction", "perUserPerMinute", "perUserPerDay", "verifyPerMinute",
      "infraRefundsPerDay", "rejectedPromptsPerDay", "refundAbuseThreshold", "sandboxDailyCap", "sandboxDailyCredits", "costs.recipe", "costs.menu2",
      "costs.menu3", "costs.recipeImage", "costs.recipeImageSteps", "costs.menuSets", "welcome.recipes", "welcome.perDay", "image.maxStepImages"]) {
      expect(sensitive, id).toContain(id);
    }
    for (const id of ["tasteQuiz.hidden", "reports.perUserPerDay", "reports.perDay", "reports.keepDays", "providerBalanceAlerts.bfl"]) expect(sensitive, id).not.toContain(id);
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
    for (const value of values) {
      let p;
      try {
        p = plan({}, [{ id, value }]);
      } catch (e) {
        if (id.startsWith("costs.") && e instanceof SwitchRefused && e.reason === "INVALID_RESULT") continue;
        throw e;
      }
      const next: Record<string, unknown> = {};
      for (const w of p.writes) {
        let at = next;
        for (const seg of w.path.slice(0, -1)) at = (at[seg] ??= {}) as Record<string, unknown>;
        at[w.path[w.path.length - 1]] = w.value;
      }
      expect(warnings(next), `${id}=${JSON.stringify(value)}`).toEqual([]);
      expect(p.changes[0].to).toEqual(s.toStored ? s.read(parseRuntimeConfig(next), next) : value);
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
    expect(stored.menuSets["3"]).toEqual({ "1": 3 });
    expect(stored.menuSets["4"]).toEqual({ "1": 4, "2": 5, "3": 8 });
    expect(parseRuntimeConfig({ costs: stored }).costs.menuSets["4"]["3"]).toBe(8);
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
    const out = await applySettings(env, [{ id: "reports.keepDays", value: 30 }, { id: "tasteQuiz.hidden", value: [tasteChoices()[0].value] }], { apply: true, actor: { via: "cli" } });
    expect(out).toMatchObject({ applied: true, changed: true });
    expect(await runtime()).toEqual({ ...FULL_CONFIG, reports: { keepDays: 30 }, tasteQuiz: { hidden: [tasteChoices()[0].value] } });
    const [audit, ...more] = await audits();
    expect(more).toEqual([]);
    expect(audit).toMatchObject({
      actor: { via: "cli" },
      sensitive: false,
      sensitiveSettings: [],
      changes: [
        { id: "reports.keepDays", from: 90, to: 30, storedFrom: null, storedTo: 30, reset: false, sensitive: false },
        { id: "tasteQuiz.hidden", from: ["units"], to: [tasteChoices()[0].value], storedFrom: ["units"] },
      ],
      writes: [{ path: "reports", value: { keepDays: 30 } }, { path: "tasteQuiz > hidden", value: [tasteChoices()[0].value] }],
    });
    expect(audit.at.toMillis()).toBeGreaterThan(Date.now() - 60_000);
    expect(env.logs.filter((l) => l.message === "owner_settings_changed")).toEqual([{
      level: "info", message: "owner_settings_changed",
      data: { via: "cli", settings: ["reports.keepDays", "tasteQuiz.hidden"], sensitive: [], changes: [{ id: "reports.keepDays", from: 90, to: 30 }, { id: "tasteQuiz.hidden", from: "(in the audit record)", to: "(in the audit record)" }], change: out.changeId },
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
    await applySettings(makeEnv(), [{ id: "reports.perDay", value: 50 }], { apply: true, actor: { via: "cli" } });
    expect(await runtime()).toEqual({ reports: { perDay: 50 } });
  });

  test("sensitive changes need their exact confirmation; ordinary ones don't; a dry run needs none and writes nothing", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    const ask = [{ id: "generationEnabled", value: false }, { id: "reports.perDay", value: 9 }];
    const dry = await applySettings(env, ask, { apply: false, actor: { via: "cli" } });
    expect(dry).toMatchObject({ applied: false, changed: true, plan: { sensitive: ["generationEnabled"] } });
    expect(await reason(applySettings(env, ask, { apply: true, actor: { via: "cli" } }))).toBe("CONFIRM_SENSITIVE");
    expect(await reason(applySettings(env, ask, { apply: true, actor: { via: "cli" }, confirmSensitive: ["reports.perDay"] }))).toBe("CONFIRM_SENSITIVE");
    expect(await reason(applySettings(env, ask, { apply: true, actor: { via: "cli" }, confirmSensitive: ["generationEnabled", "dailyCap"] }))).toBe("CONFIRM_SENSITIVE");
    expect(await runtime()).toEqual(FULL_CONFIG);
    expect(await audits()).toEqual([]);
    const out = await applySettings(env, ask, { apply: true, actor: { via: "cli" }, confirmSensitive: ["generationEnabled"] });
    expect(out.applied).toBe(true);
    expect((await runtime())!.generationEnabled).toBe(false);
    const [audit] = await audits();
    expect(audit).toMatchObject({ sensitive: true, sensitiveSettings: ["generationEnabled"] });
    const line = env.logs.find((l) => l.message === "owner_settings_changed")!;
    expect(line).toMatchObject({ level: "warn", data: { sensitive: ["generationEnabled"], changes: [{ id: "generationEnabled", from: true, to: false }, { id: "reports.perDay", from: 500, to: 9 }] } });
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

  test("the profile switch still works beside it, and neither touches the other's fields", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    await applySettings(env, [{ id: "image.maxStepImages", value: 1 }], { apply: true, actor: { via: "cli" }, confirmSensitive: ["image.maxStepImages"] });
    await activateProfile(env, "nano-banana", { apply: true, actor: { via: "cli" } });
    const image = (await runtime())!.image as Record<string, unknown>;
    expect(image).toEqual({ provider: "vertex", model: "gemini-nano-banana-2.1", fallbackModel: "gemini-3.1-flash-lite-image", maxStepImages: 1 });
    expect((await db.collection(PROFILE_CHANGES).get()).size).toBe(1);
    expect((await audits()).length).toBe(1);
  });

  test("listSettings: values, defaults, markers and the last change per setting", async () => {
    await db.doc(RUNTIME_DOC).set(FULL_CONFIG);
    const env = makeEnv();
    await applySettings(env, [{ id: "reports.keepDays", value: 30 }], { apply: true, actor: { via: "cli" } });
    const view = await listSettings(env);
    const v = (id: string) => view.sections.flatMap((s) => s.settings).find((s) => s.id === id)!;
    expect(v("dailyCap")).toMatchObject({ value: 77, default: 1000, stored: 77, overridden: true, changed: true, lastChange: null });
    expect(v("reports.keepDays")).toMatchObject({ value: 30, default: 90, overridden: true, lastChange: { via: "cli" } });
    expect(v("costs.recipeImage")).toMatchObject({ value: 3, default: 2, changed: true });
    expect(v("costs.recipe")).toMatchObject({ value: 1, default: 1, overridden: true, changed: false });
    expect(view.lastChange).toMatchObject({ via: "cli", settings: ["reports.keepDays"] });
    expect(view.readOnly.find((r) => r.id === "prices.inputUsdPerMTok")).toMatchObject({ value: 2, default: 1.5 });
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
    await applySettings(env, [{ id: "reports.perDay", value: 7 }, { id: "providerBalanceAlerts.bfl", value: 10 }], { apply: true, actor: { via: "cli" } });
    await applySettings(env, [{ id: "reports.perDay", reset: true }, { id: "providerBalanceAlerts.bfl", reset: true }], { apply: true, actor: { via: "cli" } });
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

    const changes = [{ id: "welcome.recipes", value: 5, expect: null }, { id: "reports.perDay", value: 9, expect: null }];
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes }))).toBe("invalid-argument:INVALID_REQUEST");
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes: [{ id: "reports.perDay", value: 9 }], confirm: true }))).toBe("invalid-argument:INVALID_REQUEST");
    const dry = await setOwnerSettings(env, bearer(owner.idToken), { changes, dryRun: true });
    expect(dry).toMatchObject({ changed: false, plan: { sensitive: ["welcome.recipes"], changes: [{ id: "welcome.recipes", from: 3, to: 5, sensitive: true }, { id: "reports.perDay", from: 500, to: 9 }] } });
    expect(await runtime()).toBeUndefined();
    // Server-side, whatever the page did: the typed word, and exactly the sensitive ids.
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes, confirm: true }))).toBe("CONFIRM_SENSITIVE");
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes, confirm: true, sensitive: ["welcome.recipes"], typed: "change" }))).toBe("CONFIRM_SENSITIVE");
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes, confirm: true, sensitive: ["reports.perDay"], typed: SENSITIVE_CONFIRM_WORD }))).toBe("CONFIRM_SENSITIVE");
    expect(await runtime()).toBeUndefined();
    const done = await setOwnerSettings(env, bearer(owner.idToken), { changes, confirm: true, sensitive: ["welcome.recipes"], typed: SENSITIVE_CONFIRM_WORD });
    expect(done.changed).toBe(true);
    expect(await runtime()).toEqual({ welcome: { recipes: 5 }, reports: { perDay: 9 } });
    const v = done.sections.flatMap((s) => s.settings).find((s) => s.id === "welcome.recipes")!;
    expect(v).toMatchObject({ value: 5, stored: 5, overridden: true, lastChange: { via: "web" } });
    const [audit] = await audits();
    expect(audit.actor).toEqual({ via: "web", uid: owner.uid });
    // An ordinary change alone needs no word.
    await setOwnerSettings(env, bearer(owner.idToken), { changes: [{ id: "reports.keepDays", value: 9, expect: null }], confirm: true });
    // The stale view: what the page saw is no longer stored.
    expect(await reason(setOwnerSettings(env, bearer(owner.idToken), { changes: [{ id: "reports.perDay", value: 10, expect: null }], confirm: true }))).toBe("STALE");
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

  test("src/index.ts binds both through the owner gate, like the profile callables", () => {
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
    expect(text).toContain('To write: npm run admin -- config set dailyCap "5" reports.perDay "9" --apply --confirm dailyCap');
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
});
