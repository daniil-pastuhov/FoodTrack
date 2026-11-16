/**
 * The taste quiz's catalogue (src/taste/catalogue.ts) and `getTasteQuiz` (CONTRACT.md
 * `getTasteQuiz`): every text in every app language, prompt words for every option, ids unique
 * and never reused with another meaning (test/fixtures/taste-ids.json), structure sane, the
 * response per language, `config/runtime.tasteQuiz.hidden`, and the version.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { parseRuntimeConfig, validTasteQuiz } from "../src/model";
import { TASTE_CATALOGUE, TASTE_ICONS, TASTE_LANGUAGES, type TasteCatalogue, type Texts } from "../src/taste/catalogue";
import { effectiveHidden, getTasteQuiz, tasteLanguageOf, tasteQuizFor, tasteQuizVersion } from "../src/taste/quiz";

const C = TASTE_CATALOGUE;
const options = (stepId: string) => C.steps.find((s) => s.id === stepId)!.groups.flatMap((g) => g.options);
const LEDGER = JSON.parse(readFileSync(join(__dirname, "fixtures/taste-ids.json"), "utf8")) as {
  steps: Record<string, string>;
  options: Record<string, string>;
};
const ID = /^[a-z][a-z0-9_]{0,39}$/;

function texts(): [string, Texts][] {
  const out: [string, Texts][] = [];
  for (const s of C.steps) {
    out.push([`${s.id}.question`, s.question]);
    if (s.note) out.push([`${s.id}.note`, s.note]);
    s.groups.forEach((g, i) => g.title && out.push([`${s.id}.groups[${i}].title`, g.title]));
    for (const o of s.groups.flatMap((g) => g.options)) {
      out.push([`${s.id}:${o.id}.label`, o.label]);
      if (o.sub) out.push([`${s.id}:${o.id}.sub`, o.sub]);
    }
  }
  return out;
}

describe("catalogue completeness", () => {
  test("every text is in all eight app languages, non-empty, trimmed, without Android escapes", () => {
    expect(TASTE_LANGUAGES).toEqual(["en", "ru", "nb", "es", "de", "fr", "sv", "da"]);
    for (const [where, t] of texts()) {
      expect(Object.keys(t).sort(), where).toEqual([...TASTE_LANGUAGES].sort());
      for (const l of TASTE_LANGUAGES) {
        expect(t[l], `${where}.${l}`).toBeTruthy();
        expect(t[l], `${where}.${l}`).toBe(t[l].trim());
        expect(t[l], `${where}.${l}`).not.toMatch(/\\|&[a-z]+;|%\d?\$?[sd]/);
      }
    }
  });

  test("every option has prompt words: plain English, the only form a choice reaches the model in", () => {
    for (const s of C.steps) {
      expect(s.prompt.label, s.id).toMatch(/^- [A-Z][a-z ]+:$/);
      if (s.prompt.suffix) expect(s.prompt.suffix).toMatch(/^, [a-z ]+$/);
      for (const o of options(s.id)) expect(o.prompt, `${s.id}:${o.id}`).toMatch(/^[A-Za-z][A-Za-z ,()]*[a-z)]$/);
    }
    // The leak check skips text after a label, so labels must not start one another.
    const labels = C.steps.map((s) => s.prompt.label);
    expect(new Set(labels).size).toBe(labels.length);
  });

  test("ids: well formed and unique (steps, and options within a step)", () => {
    const steps = C.steps.map((s) => s.id);
    expect(new Set(steps).size).toBe(steps.length);
    for (const s of C.steps) {
      expect(s.id).toMatch(ID);
      const ids = options(s.id).map((o) => o.id);
      expect(new Set(ids).size, s.id).toBe(ids.length);
      for (const id of ids) expect(id).toMatch(ID);
    }
  });

  test("an id is never reused with another meaning: every id and its prompt words are in the ledger", () => {
    for (const s of C.steps) {
      expect(LEDGER.steps[s.id], `add step ${s.id} to test/fixtures/taste-ids.json`).toBe(s.prompt.label + (s.prompt.suffix ?? ""));
      for (const o of options(s.id)) {
        expect(LEDGER.options[`${s.id}:${o.id}`], `${s.id}:${o.id}: a new meaning needs a new id; a new id needs a ledger entry`).toBe(o.prompt);
      }
    }
  });

  test("structure: kinds, tones, caps, levels and subtitles are sane; icons from the allow-list", () => {
    expect(TASTE_ICONS).toEqual([
      "globe", "flame", "citrus", "heart", "heart-off", "cooking-pot",
      "utensils", "leaf", "salad", "fish", "wheat", "clock", "sparkles", "list-checks",
    ]);
    expect(C.steps.length).toBeLessThanOrEqual(16); // the request's key bound
    for (const s of C.steps) {
      expect(["multi", "single"]).toContain(s.kind);
      expect(["like", "avoid"]).toContain(s.tone);
      expect(TASTE_ICONS as readonly string[]).toContain(s.icon);
      const opts = options(s.id);
      expect(opts.length).toBeGreaterThan(0);
      expect(opts.length).toBeLessThanOrEqual(32); // the request's entry bound
      expect(s.max).toBeGreaterThanOrEqual(1);
      expect(s.max).toBeLessThanOrEqual(opts.length);
      if (s.kind === "single") expect(s.max).toBe(1);
      for (const g of s.groups) expect(g.options.length).toBeGreaterThan(0);
      if (s.groups.length > 1) for (const g of s.groups) expect(g.title).not.toBeNull();
      for (const o of opts) {
        if (o.level !== undefined) {
          expect(s.kind).toBe("single");
          expect([1, 2, 3]).toContain(o.level);
        }
        if (o.sub) expect(s.kind).toBe("single");
      }
      if (s.kind === "single") expect(new Set(opts.map((o) => o.sub === undefined)).size).toBe(1);
    }
  });

  test("conflicts and the summary name existing steps and options; every conflict has an avoid side", () => {
    const tone = (key: string) => C.steps.find((s) => s.id === key.split(":")[0])?.tone;
    for (const pair of C.conflicts) {
      for (const key of pair) {
        const [step, option] = key.split(":");
        expect(options(step).some((o) => o.id === option), key).toBe(true);
      }
      expect(pair[0].split(":")[0]).not.toBe(pair[1].split(":")[0]);
      expect(pair.map(tone)).toContain("avoid");
    }
    for (const s of C.summary) expect(C.steps.find((x) => x.id === s)?.kind, s).toBe("multi");
  });

  test("the approved design: six steps, caps 6 / 1 / 6 / 8 / 8 / 5, icons, favourites in three groups, heat cards, the skip note", () => {
    expect(C.steps.map((s) => [s.id, s.kind, s.tone, s.icon, s.max])).toEqual([
      ["cuisines", "multi", "like", "globe", 6],
      ["heat", "single", "like", "flame", 1],
      ["flavours", "multi", "like", "citrus", 6],
      ["favourites", "multi", "like", "heart", 8],
      ["avoid", "multi", "avoid", "heart-off", 8],
      ["methods", "multi", "like", "cooking-pot", 5],
    ]);
    expect(C.steps.map((s) => s.question.en)).toEqual([
      "Which cuisines do you like?", "How hot do you like it?", "Which flavours do you reach for?",
      "What do you love to eat?", "What would you rather skip?", "How do you like to cook?",
    ]);
    const fav = C.steps.find((s) => s.id === "favourites")!;
    expect(fav.groups.map((g) => [g.title?.en, g.options.length])).toEqual([["Vegetables and beans", 7], ["Protein and dairy", 5], ["Fruit", 6]]);
    expect(options("heat").map((o) => [o.id, o.label.en, o.sub?.en, o.level])).toEqual([
      ["mild", "Mild", "Little or no chilli", 1], ["medium", "Medium", "A gentle warmth", 2], ["hot", "Hot", "Bring on the chilli", 3],
    ]);
    expect(C.steps.find((s) => s.id === "avoid")!.note?.en).toBe("This is a preference, not an allergy setting. Allergies are set under Allergen warnings in Settings.");
    expect(C.steps.filter((s) => s.id !== "avoid").every((s) => s.note === null)).toBe(true);
    expect(C.conflicts).toEqual([["favourites:mushrooms", "avoid:mushrooms"], ["favourites:aubergine", "avoid:aubergine"], ["flavours:garlic", "avoid:garlic"]]);
    expect(C.summary).toEqual(["cuisines", "flavours", "favourites", "methods"]);
  });
});

describe("tasteQuizFor / getTasteQuiz", () => {
  test("English: the catalogue's structure with English text, nothing of the prompt", () => {
    const q = tasteQuizFor("en", []);
    expect(q.language).toBe("en");
    expect(q.version).toMatch(/^[0-9a-f]{16}$/);
    expect(q.steps).toHaveLength(6);
    expect(q.steps[1]).toEqual({
      id: "heat", kind: "single", tone: "like", icon: "flame", question: "How hot do you like it?", note: null, max: 1,
      groups: [{ title: null, options: [
        { id: "mild", label: "Mild", sub: "Little or no chilli", level: 1 },
        { id: "medium", label: "Medium", sub: "A gentle warmth", level: 2 },
        { id: "hot", label: "Hot", sub: "Bring on the chilli", level: 3 },
      ] }],
    });
    expect(q.steps[0].groups[0].options[0]).toEqual({ id: "italian", label: "Italian" });
    expect(q.conflicts).toEqual(C.conflicts);
    expect(q.summary).toEqual(C.summary);
    expect(JSON.stringify(q)).not.toMatch(/prompt|they like|chilli heat|savoury dishes/);
  });

  test("every language answers in that language with the same structure and version", () => {
    const en = tasteQuizFor("en", []);
    const shape = (q: typeof en) => q.steps.map((s) => [s.id, s.kind, s.tone, s.icon, s.max, s.groups.map((g) => g.options.map((o) => [o.id, o.level ?? null]))]);
    for (const l of TASTE_LANGUAGES) {
      const q = tasteQuizFor(l, []);
      expect(q.language).toBe(l);
      expect(q.version).toBe(en.version);
      expect(shape(q)).toEqual(shape(en));
    }
    expect(tasteQuizFor("de", []).steps[0].question).toBe("Welche Küchen magst du?");
    expect(tasteQuizFor("ru", []).steps[3].groups.map((g) => g.title)).toEqual(["Овощи и бобовые", "Белок и молочное", "Фрукты"]);
    expect(tasteQuizFor("fr", []).steps[1].groups[0].options[2].sub).toBe("Du piment, et pas qu'un peu");
  });

  test.each([
    [undefined, "en"], [null, "en"], [5, "en"], ["", "en"], ["xx", "en"], ["pl", "en"], ["it", "en"],
    ["de", "de"], ["DE", "de"], ["de-DE", "de"], ["fr_CA", "fr"], ["es-419", "es"], ["no", "nb"], ["nn", "nb"], ["nb-NO", "nb"], ["da", "da"],
  ])("language %j answers in %s", (sent, lang) => {
    expect(tasteLanguageOf(sent)).toBe(lang);
  });

  test("getTasteQuiz reads the language from the request and the hidden list from config/runtime", async () => {
    const deps = (hidden: unknown) => ({ config: async () => parseRuntimeConfig({ tasteQuiz: { hidden } }) });
    expect((await getTasteQuiz(deps([]), { language: "sv" })).language).toBe("sv");
    for (const data of [undefined, null, "de", 3, [], { language: 7 }]) expect((await getTasteQuiz(deps([]), data)).language).toBe("en");
    const q = await getTasteQuiz(deps(["heat"]), { language: "en" });
    expect(q.steps.map((s) => s.id)).toEqual(["cuisines", "flavours", "favourites", "avoid", "methods"]);
    expect(q.version).not.toBe(tasteQuizFor("en", []).version);
  });

  test("hidden steps and options are left out; empty groups and steps vanish; conflicts and summary follow", () => {
    const q = tasteQuizFor("en", [
      "methods",
      "cuisines:italian",
      "avoid:mushrooms",
      ...options("favourites").filter((_, i) => i >= 7 && i < 12).map((o) => `favourites:${o.id}`), // the whole protein group
      ...options("flavours").map((o) => `flavours:${o.id}`), // every flavour: the step goes
    ]);
    expect(q.steps.map((s) => s.id)).toEqual(["cuisines", "heat", "favourites", "avoid"]);
    expect(q.steps[0].groups[0].options.map((o) => o.id)).not.toContain("italian");
    expect(q.steps[2].groups.map((g) => g.title)).toEqual(["Vegetables and beans", "Fruit"]);
    expect(q.steps[3].groups[0].options.map((o) => o.id)).not.toContain("mushrooms");
    expect(q.conflicts).toEqual([["favourites:aubergine", "avoid:aubergine"]]);
    expect(q.summary).toEqual(["cuisines", "favourites"]);
  });

  test("version: changes with the catalogue and with what the hidden list really hides, and only then", () => {
    const base = tasteQuizVersion([]);
    expect(tasteQuizVersion([])).toBe(base);
    expect(tasteQuizVersion(["nonsense", "heat:volcanic", "martian"])).toBe(base);
    expect(tasteQuizVersion(["heat"])).not.toBe(base);
    expect(tasteQuizVersion(["heat", "heat", "cuisines:thai"])).toBe(tasteQuizVersion(["cuisines:thai", "heat"]));
    expect(tasteQuizVersion(["cuisines:thai"])).not.toBe(tasteQuizVersion(["heat"]));
    expect(effectiveHidden(["heat", "x", "heat", "avoid:olives"])).toEqual(["avoid:olives", "heat"]);
    const relabelled: TasteCatalogue = {
      ...C,
      steps: C.steps.map((s, i) => (i === 0 ? { ...s, question: { ...s.question, de: "Welche Küchen magst du gern?" } } : s)),
    };
    expect(tasteQuizVersion([], relabelled)).not.toBe(base);
    const recapped: TasteCatalogue = { ...C, steps: C.steps.map((s, i) => (i === 0 ? { ...s, max: 5 } : s)) };
    expect(tasteQuizVersion([], recapped)).not.toBe(base);
    const reworded: TasteCatalogue = { ...C, conflicts: C.conflicts.slice(1) };
    expect(tasteQuizVersion([], reworded)).not.toBe(base);
  });

  test("config/runtime.tasteQuiz: a list of strings, else nothing hidden", () => {
    expect(parseRuntimeConfig({}).tasteQuiz).toEqual({ hidden: [] });
    expect(parseRuntimeConfig({ tasteQuiz: { hidden: ["heat", "cuisines:thai"] } }).tasteQuiz).toEqual({ hidden: ["heat", "cuisines:thai"] });
    expect(validTasteQuiz({})).toEqual({ hidden: [] });
    for (const bad of [null, [], "heat", { hidden: "heat" }, { hidden: [1] }, { hidden: Array(201).fill("heat") }, { hidden: ["x".repeat(82)] }]) {
      expect(validTasteQuiz(bad)).toBeNull();
      expect(parseRuntimeConfig({ tasteQuiz: bad }).tasteQuiz).toEqual({ hidden: [] });
    }
  });
});

describe("the callable's wiring (src/index.ts)", () => {
  // The callable wrappers are not run in tests; this pins what they are built from.
  const index = readFileSync(join(__dirname, "../src/index.ts"), "utf8");
  test("getTasteQuiz: App Check enforced (`callable`), token not consumed, signed-in caller, throttled like getBalance", () => {
    expect(index).toMatch(/const callable = \{ enforceAppCheck: true,/);
    expect(index).toMatch(/export const getTasteQuiz = onCall\(\s*\{ \.\.\.callable, timeoutSeconds: 10, maxInstances: LIMITS\.getTasteQuiz\.maxInstances, concurrency: 1 \}/);
    expect(index).toContain("callerOf(request, throttles.getTasteQuiz, false)");
    expect(index).toContain("getTasteQuiz: { maxInstances: 1, perMinute: 120 },");
    expect(index).toContain('if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required");');
    expect(index).not.toMatch(/getTasteQuiz = onCall\(\s*\{[^}]*consumeAppCheckToken/);
  });
});
