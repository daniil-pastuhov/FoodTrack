/**
 * The multi-choice config (CONTRACT.md "RecipeConfig"): a time range, several meals (any one of
 * them) and several diets (all at once). Validation of both shapes, the idempotency binding (a
 * legacy request hashes exactly as before), the prompt, the answer checks for every selected diet
 * and the time limit, the NOT_ENOUGH_ITEMS pre-check, the capability flag, the replay fixtures and
 * the worst-case cost.
 */
import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { HttpsError } from "firebase-functions/v2/https";
import { buildPrompt, inputTokenBound, promptDiets, responseSchema } from "../src/adapters/prompt";
import { inputHashOf, validateGenerateRequest, type GenerateRequest } from "../src/contract";
import { getBalance } from "../src/core/account";
import { generateRecipe, planFor } from "../src/core/generation";
import { checkMenuOutput, checkRecipeOutput, hasEnoughItems, type OutputRule } from "../src/core/outputSafety";
import { emptyUser, parseRuntimeConfig } from "../src/model";
import { callerFor, db, makeDeps, resetEmulators, sampleRecipe, type TestDeps } from "./helpers";

const today = new Date().toISOString().slice(0, 10);
const CANARY = "LRD-0123456789ABCDEF";
const LEGACY = { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30, mealType: "dinner", diet: "none", language: "en" };
const CHOICES = { difficulty: "easy", detail: "standard", servings: 2, minMinutes: null, maxMinutes: 30, mealTypes: ["dinner"], diets: [], language: "en" };

function raw(config: Record<string, unknown> = CHOICES, over: Record<string, unknown> = {}) {
  return {
    requestId: randomUUID(),
    kind: "recipe",
    config,
    ingredients: [
      { name: "Pasta", expiresOn: today, quantity: 1 },
      { name: "Tomatoes", expiresOn: null, quantity: 4 },
    ],
    allergens: [],
    today,
    ...over,
  };
}

const parse = (config: Record<string, unknown>, over: Record<string, unknown> = {}): GenerateRequest =>
  validateGenerateRequest(raw(config, over), Date.now());
const choices = (over: Record<string, unknown>) => ({ ...CHOICES, ...over });

function reasonOf(f: () => unknown): string {
  try {
    f();
  } catch (e) {
    if (e instanceof HttpsError) return (e.details as { reason: string }).reason;
    throw e;
  }
  return "no error";
}

describe("validation", () => {
  test("a legacy config reads into the same model: one meal and one diet, or none", () => {
    expect(parse(LEGACY).config).toEqual({
      difficulty: "easy", detail: "standard", servings: 2, minMinutes: null, maxMinutes: 30, mealTypes: ["dinner"], diets: [], language: "en",
    });
    expect(parse({ ...LEGACY, mealType: "any", diet: "vegan", maxMinutes: null }).config).toMatchObject({ maxMinutes: null, mealTypes: [], diets: ["vegan"] });
    // The client encodes absent new fields as null.
    expect(parse({ ...LEGACY, minMinutes: null, mealTypes: null, diets: null }).config).toMatchObject({ mealTypes: ["dinner"], diets: [] });
  });

  test("a multi-choice config: any step of the range, lists in a fixed order", () => {
    const c = parse(choices({ minMinutes: 20, maxMinutes: 45, mealTypes: ["snack", "breakfast"], diets: ["glutenFree", "vegetarian"] })).config;
    expect(c).toMatchObject({ minMinutes: 20, maxMinutes: 45, mealTypes: ["breakfast", "snack"], diets: ["vegetarian", "glutenFree"] });
    expect(parse(choices({ minMinutes: 30, maxMinutes: 30 })).config).toMatchObject({ minMinutes: 30, maxMinutes: 30 });
    expect(parse(choices({ minMinutes: 120, maxMinutes: null, mealTypes: [], diets: [] })).config).toMatchObject({ minMinutes: 120, maxMinutes: null, mealTypes: [], diets: [] });
    expect(parse(choices({ minMinutes: undefined, mealTypes: ["lunch"], diets: ["vegan", "vegetarian", "pescatarian", "glutenFree", "dairyFree"] })).config.diets).toHaveLength(5);
  });

  test.each<[string, Record<string, unknown>]>([
    ["a legacy maxMinutes off the list", { ...LEGACY, maxMinutes: 45 }],
    ["minMinutes in a legacy config", { ...LEGACY, minMinutes: 20 }],
    ["legacy fields beside the lists", { ...CHOICES, diet: "none" }],
    ["legacy meal beside the lists", { ...CHOICES, mealType: "dinner" }],
    ["only one of the lists", { ...LEGACY, mealType: undefined, diet: undefined, mealTypes: ["dinner"] }],
    ["only diets", { ...LEGACY, mealType: undefined, diet: undefined, diets: [] }],
    ["min above max", choices({ minMinutes: 50, maxMinutes: 45 })],
    ["off the step", choices({ maxMinutes: 42 })],
    ["below the range", choices({ maxMinutes: 5 })],
    ["above the range", choices({ minMinutes: 125, maxMinutes: null })],
    ["not an integer", choices({ maxMinutes: 30.5 })],
    ["a string", choices({ minMinutes: "20" })],
    ["a repeated meal", choices({ mealTypes: ["dinner", "dinner"] })],
    ["any as a meal", choices({ mealTypes: ["any"] })],
    ["none as a diet", choices({ diets: ["none"] })],
    ["an unknown diet", choices({ diets: ["keto"] })],
    ["a repeated diet", choices({ diets: ["vegan", "vegan"] })],
    ["too many meals", choices({ mealTypes: ["breakfast", "lunch", "dinner", "snack", "dessert", "breakfast"] })],
    ["not a list", choices({ diets: "vegan" })],
  ])("refused: %s", (_label, config) => {
    expect(reasonOf(() => parse(config))).toBe("INVALID_REQUEST");
  });
});

describe("idempotency hash", () => {
  /** inputHashOf as it was before the multi-choice config (a request with no optional items). */
  function hashBefore(body: ReturnType<typeof raw>): string {
    const c = body.config as typeof LEGACY;
    return createHash("sha256").update(JSON.stringify({
      kind: body.kind,
      courses: null,
      config: [c.difficulty, c.detail, c.servings, c.maxMinutes, c.mealType, c.diet, c.language],
      ingredients: body.ingredients.map((i) => [i.name, i.expiresOn, i.quantity]),
      allergens: body.allergens,
      today: body.today,
    })).digest("hex");
  }

  test("a legacy request hashes exactly as before", () => {
    for (const config of [LEGACY, { ...LEGACY, maxMinutes: null, mealType: "any", diet: "pescatarian" }]) {
      const body = raw(config);
      expect(inputHashOf(validateGenerateRequest(body, Date.now()))).toBe(hashBefore(body));
    }
  });

  test("a multi-choice request that means a legacy one hashes as it; anything more is bound to every choice", () => {
    const legacy = inputHashOf(parse(LEGACY));
    expect(inputHashOf(parse(CHOICES))).toBe(legacy);
    const seen = new Set([legacy]);
    for (const over of [
      { minMinutes: 20 },
      { maxMinutes: 45 },
      { maxMinutes: null },
      { mealTypes: ["dinner", "lunch"] },
      { mealTypes: [] },
      { diets: ["vegan"] },
      { diets: ["vegan", "glutenFree"] },
      { diets: ["vegetarian", "glutenFree"] },
    ]) {
      const h = inputHashOf(parse(choices(over)));
      expect(seen.has(h)).toBe(false);
      seen.add(h);
    }
    // Order as sent does not matter: the lists are kept in a fixed order.
    expect(inputHashOf(parse(choices({ diets: ["glutenFree", "vegan"] })))).toBe(inputHashOf(parse(choices({ diets: ["vegan", "glutenFree"] }))));
  });
});

describe("prompt", () => {
  const instruction = (config: Record<string, unknown>, over: Record<string, unknown> = {}) => buildPrompt(parse(config, over), CANARY).systemInstruction;

  test("a legacy request's settings read as before", () => {
    const s = instruction(LEGACY);
    expect(s).toContain("- Total time: at most 30 minutes.\n");
    expect(s).toContain("- Meal type: dinner.\n");
    expect(s).toContain("- Diet: no dietary restriction. Never use an ingredient that breaks it, even if it is in the pantry.");
  });

  test("the lower bound is a guide, the upper one a limit", () => {
    expect(instruction(choices({ minMinutes: 20, maxMinutes: 45 }))).toContain("- Total time: at most 45 minutes; aim for about 20 minutes or more (a guide, not a rule).");
    expect(instruction(choices({ minMinutes: 40, maxMinutes: null }))).toContain("- Total time: no limit; aim for about 40 minutes or more (a guide, not a rule).");
    expect(instruction(choices({ minMinutes: 30, maxMinutes: 30 }))).toContain("- Total time: at most 30 minutes; aim for about 30 minutes (a guide, not a rule).");
    expect(instruction(choices({ maxMinutes: null }))).toContain("- Total time: no limit.\n");
  });

  test("several meals: any one of them", () => {
    expect(instruction(choices({ mealTypes: ["breakfast", "lunch"] }))).toContain("- Meal type: any one of breakfast, lunch: the dish must suit at least one of them.");
    expect(instruction(choices({ mealTypes: [] }))).toContain("- Meal type: any.");
  });

  test("several diets: all at once, implied ones left out of the wording", () => {
    const s = instruction(choices({ diets: ["vegetarian", "glutenFree"] }));
    expect(s).toContain("- Diet: all of these at once: vegetarian (no meat, poultry, fish or seafood); gluten-free (no wheat, barley, rye, spelt or products made from them). Never use an ingredient that breaks any of them");
    expect(promptDiets(["vegetarian", "pescatarian"])).toEqual(["vegetarian"]);
    expect(promptDiets(["vegetarian", "vegan", "pescatarian", "dairyFree", "glutenFree"])).toEqual(["vegan", "glutenFree"]);
    expect(promptDiets(["pescatarian", "dairyFree"])).toEqual(["pescatarian", "dairyFree"]);
    expect(instruction(choices({ diets: ["pescatarian", "vegetarian"] }))).toContain("- Diet: vegetarian (no meat, poultry, fish or seafood). Never use an ingredient that breaks it");
    expect(instruction(choices({ diets: ["pescatarian", "vegetarian"] }))).not.toContain("fish and seafood allowed");
  });

  test("a menu: the meal is for the main course; time and diet for every course", () => {
    expect(instruction(choices({ mealTypes: ["lunch", "dinner"] }), { kind: "menu", courses: 3 }))
      .toContain("The meal type is for the main course only: ignore it for starters and desserts. The time and the diet hold for every course.");
  });

  test("the schema's only change is the value of minutes.maximum; minMinutes is not in it", () => {
    const schema = (config: Record<string, unknown>, over: Record<string, unknown> = {}) => JSON.stringify(responseSchema(parse(config, over)));
    expect(schema(choices({ minMinutes: 20, maxMinutes: 45 }))).toBe(schema(LEGACY).replace('"maximum":30', '"maximum":45'));
    expect(schema(choices({ minMinutes: 20, maxMinutes: 45, diets: ["vegan", "glutenFree"], mealTypes: ["lunch", "dinner"] }), { kind: "menu", courses: 3 }))
      .toBe(schema(LEGACY, { kind: "menu", courses: 3 }).replaceAll('"maximum":30', '"maximum":45'));
    expect(schema(choices({ minMinutes: 20, maxMinutes: null }))).toContain('"minimum":1,"maximum":1440');
  });
});

describe("answer checks", () => {
  type Ing = { amount: string; name: string; pantryId: string };
  const dish = (ingredients: Ing[], minutes = 25) => ({
    title: "Tomato pasta",
    summary: "A quick pasta with fresh tomatoes.",
    minutes,
    ingredients,
    steps: ["Boil the pasta in salted water.", "Chop the tomatoes and warm them in a pan with oil.", "Toss and serve."],
  });
  const base: Ing[] = [{ amount: "200 g", name: "pasta", pantryId: "p1" }, { amount: "4", name: "tomatoes", pantryId: "p2" }];
  const check = (r: GenerateRequest, answer: unknown): "ok" | OutputRule => {
    const res = checkRecipeOutput(answer, { req: r, canary: CANARY, systemInstruction: buildPrompt(r, CANARY).systemInstruction });
    return res.ok ? "ok" : res.rule;
  };
  // Gluten-free dishes for the diet checks: pasta is gluten.
  const rice: Ing[] = [{ amount: "4", name: "tomatoes", pantryId: "p2" }, { amount: "200 g", name: "rice", pantryId: "none" }];

  test("every selected diet's words are refused", () => {
    const vegGf = parse(choices({ diets: ["vegetarian", "glutenFree"] }));
    expect(check(vegGf, dish(rice))).toBe("ok");
    expect(check(vegGf, dish([...rice, { amount: "100 g", name: "bacon", pantryId: "none" }]))).toBe("DIET");
    expect(check(vegGf, dish(base))).toBe("DIET");
    expect(check(vegGf, dish([...rice, { amount: "1", name: "wheat flour tortilla", pantryId: "none" }]))).toBe("DIET");
    // Each alone lets the other's word through.
    expect(check(parse(choices({ diets: ["vegetarian"] })), dish(base))).toBe("ok");
    expect(check(parse(choices({ diets: ["glutenFree"] })), dish([...rice, { amount: "100 g", name: "bacon", pantryId: "none" }]))).toBe("ok");
  });

  test("pescatarian with vegetarian: fish is refused (the strictest wins)", () => {
    const salmon = dish([...base, { amount: "200 g", name: "salmon", pantryId: "none" }]);
    expect(check(parse(choices({ diets: ["pescatarian"] })), salmon)).toBe("ok");
    expect(check(parse(choices({ diets: ["pescatarian", "vegetarian"] })), salmon)).toBe("DIET");
  });

  test("dairy-free with pescatarian: cheese and chicken are both refused", () => {
    const r = parse(choices({ diets: ["pescatarian", "dairyFree"] }));
    expect(check(r, dish([...base, { amount: "50 g", name: "parmesan cheese", pantryId: "none" }]))).toBe("DIET");
    expect(check(r, dish([...base, { amount: "1", name: "chicken breast", pantryId: "none" }]))).toBe("DIET");
    expect(check(r, dish([...base, { amount: "1", name: "cod", pantryId: "none" }]))).toBe("ok");
  });

  test("a menu course breaking the second diet is refused", () => {
    const r = parse(choices({ diets: ["vegan", "glutenFree"] }), { kind: "menu", courses: 2, ingredients: [{ name: "Tomatoes", expiresOn: null, quantity: 4 }] });
    const ok: Ing[] = [{ amount: "4", name: "tomatoes", pantryId: "p1" }, { amount: "200 g", name: "rice", pantryId: "none" }];
    const menu = (main: Ing[]) => ({
      days: [0, 1, 2].map((d) => ({
        date: new Date(Date.parse(`${today}T00:00:00Z`) + d * 86_400_000).toISOString().slice(0, 10),
        uses: ["p1"],
        courses: [
          { course: "starter", recipe: { ...dish(ok), title: `Tomato salad ${d}` } },
          { course: "main", recipe: { ...dish(d === 2 ? main : ok), title: `Tomato rice ${d}` } },
        ],
      })),
    });
    const res = (m: unknown) => checkMenuOutput(m, { req: r, canary: CANARY, systemInstruction: buildPrompt(r, CANARY).systemInstruction });
    expect(res(menu(ok)).ok).toBe(true);
    expect(res(menu([...ok, { amount: "2", name: "bread rolls", pantryId: "none" }]))).toMatchObject({ ok: false, rule: "DIET" });
  });

  test("the upper time bound is checked as well as constrained; the lower one never refuses", () => {
    const r = parse(choices({ minMinutes: 40, maxMinutes: 45 }));
    expect(check(r, dish(base, 45))).toBe("ok");
    expect(check(r, dish(base, 10))).toBe("ok");
    expect(check(r, dish(base, 50))).toBe("SHAPE");
    expect(check(parse(choices({ minMinutes: 40, maxMinutes: null })), dish(base, 300))).toBe("ok");
    expect(check(parse(LEGACY), dish(base, 31))).toBe("SHAPE");
  });

  test("staples the cook supplies are marked; pantry items and other extras are not", () => {
    const res = checkRecipeOutput(dish([
      ...base,
      { amount: "", name: "salt and freshly ground black pepper", pantryId: "none" },
      { amount: "2 dl", name: "water", pantryId: "none" },
      { amount: "1", name: "onion", pantryId: "none" },
    ]), { req: parse(CHOICES), canary: CANARY, systemInstruction: "" });
    expect(res.ok).toBe(true);
    if (!res.ok) return;
    expect(res.value.ingredients.map((i) => i.staple ?? false)).toEqual([false, false, true, true, false]);
    expect(res.value.ingredients[0]).not.toHaveProperty("staple");
  });

  test("NOT_ENOUGH_ITEMS applies every selected diet", () => {
    const items = { ingredients: [{ name: "Cottage Cheese", expiresOn: null, quantity: 1 }, { name: "Bread", expiresOn: null, quantity: 1 }] };
    expect(hasEnoughItems(parse(choices({ diets: ["dairyFree"] }), items))).toBe(true);
    expect(hasEnoughItems(parse(choices({ diets: ["glutenFree"] }), items))).toBe(true);
    expect(hasEnoughItems(parse(choices({ diets: ["dairyFree", "glutenFree"] }), items))).toBe(false);
  });
});

describe("worst-case cost", () => {
  const heavy = (k: number, n: number) => Array.from({ length: n }, (_, i) => String.fromCodePoint(0x20000 + k * 64 + i)).join("");
  const item = (k: number) => ({ name: heavy(k, 40), expiresOn: "2000-01-01", quantity: 9999 });
  const largest = (kind: string, courses: number | undefined, config: Record<string, unknown>) => validateGenerateRequest({
    ...raw({ difficulty: "ambitious", detail: "stepByStep", servings: 12, language: "nb", ...config }),
    kind,
    ...(courses ? { courses } : {}),
    ingredients: Array.from({ length: 30 }, (_, i) => item(i)),
    allergens: Array.from({ length: 20 }, (_, i) => heavy(100 + i, 40)),
    allergenConsent: true,
  }, Date.now());
  const fullest = { minMinutes: 100, maxMinutes: 105, mealTypes: ["breakfast", "lunch", "dinner", "snack", "dessert"], diets: ["pescatarian", "glutenFree", "dairyFree"] };

  test.each([["recipe", undefined], ["menu", 2], ["menu", 3]] as const)("%s %s: the fullest config adds little to the largest legacy request", (kind, courses) => {
    const config = parseRuntimeConfig({});
    const legacy = largest(kind, courses, { maxMinutes: null, mealType: "breakfast", diet: "pescatarian" });
    const multi = largest(kind, courses, fullest);
    const grew = inputTokenBound(multi) - inputTokenBound(legacy);
    expect(grew).toBeGreaterThan(0);
    expect(grew).toBeLessThan(400);
    // At most USD 0.001 more on the worst case.
    expect(planFor(multi, config).reservedMicroUsd - planFor(legacy, config).reservedMicroUsd).toBeLessThan(1_000);
  });
});

describe("replay fixtures", () => {
  const fixture = (name: string) => JSON.parse(readFileSync(join(__dirname, "fixtures", name), "utf8")) as Record<string, unknown>;
  test.each(["config-recipe-2026-11-14.json", "config-menu2-2026-11-14.json", "config-menu3-2026-11-14.json"])("%s parses with the multi-choice config and has enough items", (name) => {
    const body = fixture(name);
    const req = validateGenerateRequest({ requestId: "00000000-0000-4000-8000-000000000000", ...body }, Date.parse(`${body.today as string}T12:00:00Z`));
    expect(req.config.mealTypes.length + req.config.diets.length).toBeGreaterThan(1);
    expect(hasEnoughItems(req)).toBe(true);
  });
});

describe("generateRecipe (emulators)", () => {
  const UID = "user-config-choices";
  const caller = callerFor(UID);
  let deps: TestDeps;
  beforeEach(async () => {
    await resetEmulators();
    deps = makeDeps();
    await db.collection("users").doc(UID).set({ ...emptyUser(), balance: 5, purchasedCredits: 5 });
  });

  test("says it reads the multi-choice config beside every balance", async () => {
    expect(await getBalance(deps, caller)).toMatchObject({ balance: 5, acceptsMultiChoiceConfig: true, acceptsDanish: true });
    expect(await generateRecipe(deps, caller, raw(LEGACY))).toMatchObject({ status: "DONE", acceptsMultiChoiceConfig: true, acceptsDanish: true });
  });

  test("a multi-choice request is written at the same price, with its settings in the prompt", async () => {
    deps.generator.returns({ ...sampleRecipe(), minutes: 40 });
    const res = await generateRecipe(deps, caller, raw(choices({ minMinutes: 20, maxMinutes: 45, mealTypes: ["lunch", "dinner"], diets: ["vegetarian", "dairyFree"] })));
    expect(res).toMatchObject({ status: "DONE", balance: 4, charged: true });
    const prompt = deps.generator.prompts[0];
    expect(prompt.systemInstruction).toContain("at most 45 minutes; aim for about 20 minutes or more");
    expect(prompt.systemInstruction).toContain("any one of lunch, dinner");
    expect(prompt.systemInstruction).toContain("all of these at once: vegetarian");
  });

  test("a menu with two diets and nothing usable is NOT_ENOUGH_ITEMS, nothing charged", async () => {
    let reason = "no error";
    try {
      await generateRecipe(deps, caller, raw(choices({ diets: ["vegan", "glutenFree"] }), {
        kind: "menu",
        courses: 2,
        ingredients: [{ name: "Milk", expiresOn: null, quantity: 1 }, { name: "Bread", expiresOn: null, quantity: 1 }],
      }));
    } catch (e) {
      reason = ((e as HttpsError).details as { reason: string }).reason;
    }
    expect(reason).toBe("NOT_ENOUGH_ITEMS");
    expect(deps.generator.calls).toBe(0);
    expect((await db.collection("users").doc(UID).get()).data()).toMatchObject({ balance: 5 });
  });
});
