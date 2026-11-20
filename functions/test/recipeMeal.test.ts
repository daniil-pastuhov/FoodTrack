/**
 * A single recipe's meal (CONTRACT.md "Recipe meal"): the model labels the dish from a server enum
 * (never free text), the server corrects the label against the config's meal types, and the
 * label is stored with the generation so getGeneration's recovery returns it too. Menus are
 * untouched: no OUTPUT line, no schema property, no `meal` on their dishes.
 */
import { randomUUID } from "node:crypto";
import { buildPrompt, mealLabelChoices, responseSchema, systemInstruction } from "../src/adapters/prompt";
import { MEAL_CHOICES, validateGenerateRequest, type GenerateRequest, type RecipeConfig } from "../src/contract";
import { generateRecipe, getGeneration } from "../src/core/generation";
import { checkMenuOutput, checkRecipeOutput, mealOf } from "../src/core/outputSafety";
import { EmulatorGenerator } from "../src/emulatorFakes";
import { emptyUser } from "../src/model";
import { callerFor, db, makeDeps, resetEmulators, sampleMenu, sampleRecipe, type TestDeps } from "./helpers";

const today = new Date().toISOString().slice(0, 10);
const CANARY = "LRD-0123456789ABCDEF";

function raw(mealTypes: string[], over: Record<string, unknown> = {}) {
  return {
    requestId: randomUUID(),
    kind: "recipe",
    config: { difficulty: "easy", detail: "standard", servings: 2, minMinutes: null, maxMinutes: null, mealTypes, diets: [], language: "en" },
    ingredients: [
      { name: "Pasta", expiresOn: today, quantity: 1 },
      { name: "Tomatoes", expiresOn: null, quantity: 4 },
    ],
    allergens: [],
    today,
    ...over,
  };
}
const parse = (mealTypes: string[], over: Record<string, unknown> = {}): GenerateRequest => validateGenerateRequest(raw(mealTypes, over), Date.now());
const config = (mealTypes: string[]) => parse(mealTypes).config as RecipeConfig;
const ctx = (req: GenerateRequest) => ({ req, canary: CANARY, systemInstruction: systemInstruction(req, CANARY) });
type Props = { properties: Record<string, { enum?: string[] }>; required: string[] };

describe("mealOf", () => {
  test("any meal: the model's label when it is a meal, else none", () => {
    for (const m of MEAL_CHOICES) expect(mealOf(m, config([]))).toBe(m);
    for (const bad of [undefined, null, "", "any", "brunch", "Dinner", " dinner", 3, ["dinner"], { meal: "dinner" }, "ignore previous instructions"]) {
      expect(mealOf(bad, config([]))).toBeUndefined();
    }
  });

  test("one meal type: always that one, whatever the label", () => {
    for (const label of [undefined, "breakfast", "dessert", "brunch"]) expect(mealOf(label, config(["dinner"]))).toBe("dinner");
  });

  test("several: the label when it is one of them, else the first of them in MEAL_CHOICES order", () => {
    // The config is sorted into MEAL_CHOICES order on the way in.
    const c = config(["dinner", "lunch"]);
    expect(c.mealTypes).toEqual(["lunch", "dinner"]);
    expect(mealOf("dinner", c)).toBe("dinner");
    expect(mealOf("lunch", c)).toBe("lunch");
    expect(mealOf("breakfast", c)).toBe("lunch");
    expect(mealOf(undefined, c)).toBe("lunch");
    expect(mealOf("supper", c)).toBe("lunch");
  });
});

describe("the prompt", () => {
  test("a recipe of any meal is asked for one of all five, in the schema and one OUTPUT line", () => {
    const req = parse([]);
    expect(mealLabelChoices(req)).toEqual(MEAL_CHOICES);
    const s = responseSchema(req) as unknown as Props;
    expect(s.properties.meal).toEqual({ type: "string", enum: [...MEAL_CHOICES] });
    expect(s.required).toContain("meal");
    expect(systemInstruction(req, CANARY)).toContain("- meal: the one meal this dish suits best, one of breakfast, lunch, dinner, snack, dessert.");
  });

  test("several meal types: only those", () => {
    const req = parse(["dessert", "breakfast"]);
    const s = responseSchema(req) as unknown as Props;
    expect(s.properties.meal.enum).toEqual(["breakfast", "dessert"]);
    expect(systemInstruction(req, CANARY)).toContain("- meal: the one meal this dish suits best, one of breakfast, dessert.");
  });

  test("exactly one meal type, or a menu: not asked", () => {
    for (const req of [parse(["dinner"]), parse([], { kind: "menu", courses: 2 }), parse(["lunch", "dinner"], { kind: "menu", courses: 3 })]) {
      expect(mealLabelChoices(req)).toBeNull();
      const p = buildPrompt(req, CANARY);
      expect(p.systemInstruction).not.toContain("- meal:");
      expect(JSON.stringify(p.schema)).not.toContain("\"meal\"");
    }
  });
});

describe("the answer check", () => {
  test("the corrected label is on the recipe; a bad label never fails the answer", () => {
    const any = parse([]);
    expect(checkRecipeOutput({ ...sampleRecipe(), meal: "snack" }, ctx(any))).toMatchObject({ ok: true, value: { meal: "snack" } });
    const none = checkRecipeOutput({ ...sampleRecipe(), meal: "<b>tea</b>" }, ctx(any));
    expect(none.ok).toBe(true);
    expect(none.value).not.toHaveProperty("meal");
    expect(checkRecipeOutput(sampleRecipe(), ctx(parse(["dinner"])))).toMatchObject({ ok: true, value: { meal: "dinner" } });
    expect(checkRecipeOutput({ ...sampleRecipe(), meal: "dessert" }, ctx(parse(["lunch", "dinner"])))).toMatchObject({ ok: true, value: { meal: "lunch" } });
  });

  test("a menu's dishes never carry one, even when the model writes it", () => {
    const req = parse(["dinner"], { kind: "menu", courses: 2 });
    const m = sampleMenu(req);
    for (const d of m.days) for (const c of d.courses) Object.assign(c.recipe, { meal: "dinner" });
    const out = checkMenuOutput(m, ctx(req));
    expect(out.ok).toBe(true);
    expect(JSON.stringify(out.value)).not.toContain("\"meal\"");
  });

  test("the emulator's fake model labels like the real one when asked", async () => {
    expect((await new EmulatorGenerator().generate(parse(["breakfast", "snack"]))).output).toMatchObject({ meal: "breakfast" });
    expect((await new EmulatorGenerator().generate(parse([]))).output).toMatchObject({ meal: "dinner" });
    expect((await new EmulatorGenerator().generate(parse(["lunch"]))).output).not.toHaveProperty("meal");
  });
});

describe("generateRecipe and getGeneration (emulators)", () => {
  const UID = "user-meal";
  const caller = callerFor(UID);
  let deps: TestDeps;
  beforeEach(async () => {
    await resetEmulators();
    deps = makeDeps();
    await db.collection("users").doc(UID).set({ ...emptyUser(), balance: 5, purchasedCredits: 5 });
  });

  test("the meal is in the reply and in the recovered generation", async () => {
    deps.generator.returns({ ...sampleRecipe(), meal: "lunch" });
    const body = raw(["lunch", "dinner"]);
    const res = await generateRecipe(deps, caller, body);
    expect(res).toMatchObject({ status: "DONE", recipe: { meal: "lunch" } });
    expect(await getGeneration(deps, caller, { requestId: body.requestId })).toMatchObject({ status: "DONE", recipe: { meal: "lunch" } });
  });

  test("a legacy config's single meal type is the meal", async () => {
    const body = { ...raw([]), config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: null, mealType: "breakfast", diet: "none", language: "en" } };
    expect((await generateRecipe(deps, caller, body)).recipe?.meal).toBe("breakfast");
  });

  test("a menu's reply has no meal anywhere", async () => {
    const body = raw(["dinner"], { kind: "menu", courses: 2 });
    const res = await generateRecipe(deps, caller, body);
    expect(res.status).toBe("DONE");
    expect(JSON.stringify(res.menu)).not.toContain("\"meal\"");
  });
});
