/**
 * Menu sets (CONTRACT.md "Capability `acceptsMenuDays`"): a menu of 1 to 4 days and 1 to 3
 * courses, priced from `costs.menuSets`. A request without `days` (every client before menu sets)
 * is the 3-day menu it always was: same price, same prompt, same request hash.
 */
import { randomUUID } from "node:crypto";
import { HttpsError } from "firebase-functions/v2/https";
import { buildPrompt, inputTokenBound } from "../src/adapters/prompt";
import {
  COST_KEYS,
  COST_MAX,
  DEFAULT_COSTS,
  MAX_OUTPUT_TOKENS,
  MENU_COURSES,
  MENU_DAYS,
  THINKING_FACTOR,
  type MenuCourses,
  type MenuDays,
} from "../src/constants";
import { CAPABILITIES, coursesFor, inputHashOf, menuDates, validateGenerateRequest } from "../src/contract";
import { getBalance } from "../src/core/account";
import { generateRecipe, getGeneration, planFor } from "../src/core/generation";
import { checkMenuOutput, hasEnoughItems } from "../src/core/outputSafety";
import { emptyUser, parseRuntimeConfig, validCosts } from "../src/model";
import { callerFor, db, makeDeps, resetEmulators, sampleMenu, setConfig, type TestDeps } from "./helpers";

const NOW = Date.parse("2026-11-15T12:00:00Z");
const TODAY = "2026-11-15";
const COMBOS = MENU_DAYS.flatMap((d) => MENU_COURSES.map((c) => [d, c] as [MenuDays, MenuCourses]));

function body(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    requestId: "11111111-1111-4111-8111-111111111111",
    kind: "menu",
    courses: 3,
    today: TODAY,
    config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: 30, mealType: "any", diet: "none", language: "en" },
    ingredients: [{ name: "Pasta", expiresOn: "2026-11-20", quantity: 1 }, { name: "Tomatoes", expiresOn: null, quantity: 3 }],
    ...over,
  };
}
const validate = (over: Record<string, unknown> = {}) => validateGenerateRequest(body(over), NOW);

function reasonOf(f: () => unknown): string {
  try {
    f();
  } catch (e) {
    if (e instanceof HttpsError) return (e.details as { reason: string }).reason;
    throw e;
  }
  return "no error";
}

describe("request validation", () => {
  test("absent or null days is a 3-day menu; 1 to 4 are taken as sent", () => {
    expect(validate().days).toBe(3);
    expect(validate({ days: null }).days).toBe(3);
    for (const d of MENU_DAYS) expect(validate({ days: d }).days).toBe(d);
  });

  test("courses 1, 2 and 3 are menus; coursesFor(1) is the main course alone", () => {
    for (const c of MENU_COURSES) expect(validate({ courses: c }).courses).toBe(c);
    expect(coursesFor(1)).toEqual(["main"]);
    expect(coursesFor(2)).toEqual(["starter", "main"]);
    expect(coursesFor(3)).toEqual(["starter", "main", "dessert"]);
  });

  test.each([0, 5, -1, 2.5, "3", true, [3]])("days %j is INVALID_REQUEST", (days) => {
    expect(reasonOf(() => validate({ days }))).toBe("INVALID_REQUEST");
  });

  test.each([0, 4, "1"])("courses %j is INVALID_REQUEST", (courses) => {
    expect(reasonOf(() => validate({ courses }))).toBe("INVALID_REQUEST");
  });

  test("days on a recipe is INVALID_REQUEST, as courses is; a recipe has no days", () => {
    expect(reasonOf(() => validate({ kind: "recipe", courses: undefined, days: 2 }))).toBe("INVALID_REQUEST");
    expect(reasonOf(() => validate({ kind: "recipe", courses: undefined, days: 3 }))).toBe("INVALID_REQUEST");
    expect(reasonOf(() => validate({ kind: "recipe", courses: 1 }))).toBe("INVALID_REQUEST");
    expect(validate({ kind: "recipe", courses: undefined, days: null }).days).toBeUndefined();
  });

  test("a 3-day menu hashes exactly as before menu sets; other days hash differently", () => {
    // Recorded from the server before `days` existed (same request body).
    expect(inputHashOf(validate())).toBe("647afad63fa8c63999915f27789fef8a6a2687c047794bc3995a1826548ea701");
    expect(inputHashOf(validate({ days: 3 }))).toBe(inputHashOf(validate()));
    const hashes = new Set(COMBOS.map(([d, c]) => inputHashOf(validate({ days: d, courses: c }))));
    expect(hashes.size).toBe(COMBOS.length);
  });
});

describe("dates, prompt and schema", () => {
  test("menuDates gives today .. today + days - 1", () => {
    expect(menuDates("2026-12-30", 1)).toEqual(["2026-12-30"]);
    expect(menuDates("2026-12-30", 4)).toEqual(["2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02"]);
  });

  test.each(COMBOS)("%i days x %i courses: the schema asks for exactly those days and courses", (d, c) => {
    const req = validate({ days: d, courses: c });
    const schema = buildPrompt(req, "LRD-0").schema as { properties: { days: Record<string, unknown> & { items: { properties: { date: { enum: string[] }; courses: { minItems: number; maxItems: number; items: { properties: { course: { enum: string[] } } } } } } } } };
    const days = schema.properties.days;
    expect([days.minItems, days.maxItems]).toEqual([d, d]);
    expect(days.items.properties.date.enum).toEqual(menuDates(TODAY, d));
    expect(days.items.properties.courses.minItems).toBe(c);
    expect(days.items.properties.courses.items.properties.course.enum).toEqual(coursesFor(c));
  });

  test("the instruction names the days and courses, in the singular for one day", () => {
    const sys = (over: Record<string, unknown>) => buildPrompt(validate(over), "LRD-0").systemInstruction;
    expect(sys({ days: 4, courses: 2 })).toContain(`- Write a menu for 4 days: ${menuDates(TODAY, 4).join(", ")}, in that order, with exactly these courses each day: starter, main.`);
    expect(sys({ days: 1, courses: 3 })).toContain(`- Write a menu for 1 day: ${TODAY}, with exactly these courses: starter, main, dessert.`);
    expect(sys({ days: 1, courses: 3 })).toContain("The day uses at least one pantry item.");
    const single = sys({ days: 1, courses: 1 });
    expect(single).toContain("- The main course is a full recipe and uses at least one pantry item.");
    expect(single).toContain("The meal type, the time and the diet hold for the main course.");
    expect(single).not.toContain("starters and desserts");
    // One day: past-date items are simply used first, as in a recipe.
    expect(single).toContain("- Items already past their date are ordinary ingredients: use them first");
    expect(sys({ days: 2, courses: 1 })).toContain("- Every course is a full recipe and a different dish. Every day uses at least one pantry item.");
  });

  test("a 3-day menu's prompt is the one from before menu sets, with or without days: 3", () => {
    expect(buildPrompt(validate({ days: 3 }), "LRD-0")).toEqual(buildPrompt(validate(), "LRD-0"));
    expect(buildPrompt(validate(), "LRD-0").systemInstruction).toContain("- Write a menu for 3 days:");
  });
});

describe("output check and NOT_ENOUGH_ITEMS", () => {
  const ctx = (req: ReturnType<typeof validate>) => {
    const prompt = buildPrompt(req, "LRD-0");
    return { req, canary: "LRD-0", systemInstruction: prompt.systemInstruction };
  };

  test.each(COMBOS)("%i days x %i courses: an answer with exactly those days passes", (d, c) => {
    const req = validate({ days: d, courses: c });
    const r = checkMenuOutput(sampleMenu(req), ctx(req));
    expect(r.ok).toBe(true);
    expect(r.value?.days.map((x) => x.date)).toEqual(menuDates(TODAY, d));
    expect(r.value?.days.every((x) => x.courses.map((k) => k.course).join() === coursesFor(c).join())).toBe(true);
  });

  test("an answer with a day too many or too few is refused (SHAPE)", () => {
    const req2 = validate({ days: 2 });
    expect(checkMenuOutput(sampleMenu(validate({ days: 3 })), ctx(req2))).toMatchObject({ ok: false, rule: "SHAPE" });
    expect(checkMenuOutput(sampleMenu(validate({ days: 1 })), ctx(req2))).toMatchObject({ ok: false, rule: "SHAPE" });
  });

  test("a 1-day 1-course menu is one dish: no duplicate is possible, the staples gate still applies", () => {
    const req = validate({ days: 1, courses: 1 });
    expect(checkMenuOutput(sampleMenu(req), ctx(req)).ok).toBe(true);
    const staples = {
      days: [{
        date: TODAY,
        uses: [],
        courses: [{
          course: "main",
          recipe: {
            title: "Salted water", summary: "Hot water.", minutes: 5, servings: 2, difficulty: "easy",
            ingredients: [{ amount: "1 l", name: "water", pantryId: "none" }, { amount: "", name: "salt", pantryId: "none" }],
            steps: ["Boil the water.", "Salt it."],
          },
        }],
      }],
    };
    expect(checkMenuOutput(staples, ctx(req))).toMatchObject({ ok: false });
  });

  test("a menu day needs an item it may be planned on: more days need items that last longer", () => {
    // The only item expires tomorrow: fine for 1 or 2 days, not for 3 or 4.
    const ingredients = [{ name: "Milk", expiresOn: "2026-11-16", quantity: 1 }];
    expect(MENU_DAYS.map((d) => hasEnoughItems(validate({ days: d, ingredients })))).toEqual([true, true, false, false]);
    // Undated items may go on any day.
    expect(hasEnoughItems(validate({ days: 4, ingredients: [{ name: "Rice", expiresOn: null, quantity: 1 }] }))).toBe(true);
  });
});

describe("cost table", () => {
  test("the default table: days + courses - 1, its 3-day row is menu2 / menu3", () => {
    expect(DEFAULT_COSTS).toEqual({
      recipe: 1, menu2: 4, menu3: 5,
      menuSets: { 1: { 1: 1, 2: 2, 3: 3 }, 2: { 1: 2, 2: 3, 3: 4 }, 3: { 1: 3, 2: 4, 3: 5 }, 4: { 1: 4, 2: 5, 3: 6 } },
    });
    expect(parseRuntimeConfig({}).costs).toEqual(DEFAULT_COSTS);
  });

  test("the stored shape from before menu sets parses: the default table when it says the defaults", () => {
    expect(validCosts({ recipe: 1, menu2: 4, menu3: 5 })).toEqual(DEFAULT_COSTS);
    expect(validCosts({ recipe: 1, menu2: 4, menu3: 5, menuSets: null })).toEqual(DEFAULT_COSTS);
  });

  test("an old shape with other prices: the other cells keep the default step from the 3-day row", () => {
    expect(validCosts({ recipe: 2, menu2: 6, menu3: 8 })).toEqual({
      recipe: 2, menu2: 6, menu3: 8,
      menuSets: { 1: { 1: 3, 2: 4, 3: 6 }, 2: { 1: 4, 2: 5, 3: 7 }, 3: { 1: 5, 2: 6, 3: 8 }, 4: { 1: 6, 2: 7, 3: 9 } },
    });
  });

  test("derived cells stay within [recipe, COST_MAX]", () => {
    const low = validCosts({ recipe: 3, menu2: 3, menu3: 4 })!;
    expect(low.menuSets[1]).toEqual({ 1: 3, 2: 3, 3: 3 });
    expect(low.menuSets[3]).toEqual({ 1: 3, 2: 3, 3: 4 });
    const high = validCosts({ recipe: 1, menu2: 100, menu3: 100 })!;
    expect(high.menuSets[4]).toEqual({ 1: 100, 2: 100, 3: 100 });
  });

  test("explicit rows and cells are taken; the 3-day 1-course cell may be set", () => {
    const c = validCosts({ recipe: 1, menu2: 4, menu3: 5, menuSets: { 1: { 1: 1, 2: 1, 3: 2 }, 3: { 1: 2 }, 4: { 3: 9 } } })!;
    // Missing cells step from the 3-day row as stored, so its 1-course cell moves the 2- and 4-day ones.
    expect(c.menuSets).toEqual({ 1: { 1: 1, 2: 1, 3: 2 }, 2: { 1: 1, 2: 3, 3: 4 }, 3: { 1: 2, 2: 4, 3: 5 }, 4: { 1: 3, 2: 5, 3: 9 } });
    // A 3-day row that repeats menu2 / menu3 is accepted.
    expect(validCosts({ recipe: 1, menu2: 4, menu3: 5, menuSets: { 3: { 1: 3, 2: 4, 3: 5 } } })).toEqual(DEFAULT_COSTS);
  });

  test.each([
    ["a 3-day row saying another menu2", { recipe: 1, menu2: 4, menu3: 5, menuSets: { 3: { 2: 5 } } }],
    ["a 3-day row saying another menu3", { recipe: 1, menu2: 4, menu3: 5, menuSets: { 3: { 3: 6 } } }],
    ["menuSets without menu2 / menu3", { recipe: 1, menuSets: { 3: { 1: 3, 2: 4, 3: 5 } } }],
    ["an unknown day", { recipe: 1, menu2: 4, menu3: 5, menuSets: { 5: { 1: 5, 2: 6, 3: 7 } } }],
    ["an unknown course", { recipe: 1, menu2: 4, menu3: 5, menuSets: { 2: { 4: 9 } } }],
    ["a row that is not an object", { recipe: 1, menu2: 4, menu3: 5, menuSets: { 2: 3 } }],
    ["menuSets that is not an object", { recipe: 1, menu2: 4, menu3: 5, menuSets: [1, 2] }],
    ["a fractional cell", { recipe: 1, menu2: 4, menu3: 5, menuSets: { 2: { 2: 2.5 } } }],
    ["a cell above COST_MAX", { recipe: 1, menu2: 4, menu3: 5, menuSets: { 4: { 3: COST_MAX + 1 } } }],
    ["more days cheaper", { recipe: 1, menu2: 4, menu3: 5, menuSets: { 4: { 2: 3 } } }],
    ["fewer days dearer", { recipe: 1, menu2: 4, menu3: 5, menuSets: { 2: { 3: 6 } } }],
    ["more courses cheaper", { recipe: 1, menu2: 4, menu3: 5, menuSets: { 1: { 1: 3, 2: 2 } } }],
    ["a menu cheaper than a recipe", { recipe: 2, menu2: 4, menu3: 5, menuSets: { 1: { 1: 1 } } }],
  ])("%s: the whole table is refused", (_label, v) => {
    expect(validCosts(v)).toBeNull();
    expect(parseRuntimeConfig({ costs: v }).costs).toEqual(DEFAULT_COSTS);
  });
});

describe("spend bounds", () => {
  const heavy = (k: number, n: number) => Array.from({ length: n }, (_, i) => String.fromCodePoint(0x20000 + k * 64 + i)).join("");
  const largest = (d: MenuDays, c: MenuCourses) => validateGenerateRequest(body({
    days: d,
    courses: c,
    config: { difficulty: "ambitious", detail: "stepByStep", servings: 12, maxMinutes: null, mealType: "breakfast", diet: "pescatarian", language: "nb" },
    ingredients: Array.from({ length: 30 }, (_, i) => ({ name: heavy(i, 40), expiresOn: "2026-11-14", quantity: 9999 })),
    allergens: Array.from({ length: 20 }, (_, i) => heavy(100 + i, 40)),
    allergenConsent: true,
  }), NOW);

  test("every variant has an output cap; more days or courses never get less", () => {
    expect(Object.keys(MAX_OUTPUT_TOKENS).sort()).toEqual([...COST_KEYS].sort());
    for (const [d, c] of COMBOS) {
      if (d > 1) expect(MAX_OUTPUT_TOKENS[`menu${d}x${c}`]).toBeGreaterThan(MAX_OUTPUT_TOKENS[`menu${(d - 1) as MenuDays}x${c}`]);
      if (c > 1) expect(MAX_OUTPUT_TOKENS[`menu${d}x${c}`]).toBeGreaterThan(MAX_OUTPUT_TOKENS[`menu${d}x${(c - 1) as MenuCourses}`]);
    }
    expect(MAX_OUTPUT_TOKENS.menu3x2).toBe(14_000);
    expect(MAX_OUTPUT_TOKENS.menu3x3).toBe(20_000);
  });

  test.each(["off", "enforce"] as const)("judge %s: the largest request of every variant fits the per-user daily ceiling", (judge) => {
    const config = { ...parseRuntimeConfig({}), outputJudge: judge };
    for (const [d, c] of COMBOS) {
      const plan = planFor(largest(d, c), config);
      expect(plan.limits.maxOutputTokens).toBe(MAX_OUTPUT_TOKENS[`menu${d}x${c}`]);
      expect(plan.limits.hardTokenCeiling).toBe(THINKING_FACTOR * plan.limits.maxOutputTokens);
      expect(plan.reservedMicroUsd).toBeLessThanOrEqual(config.perUserDailySpendMicroUsd);
      expect(plan.cost).toBe(DEFAULT_COSTS.menuSets[`${d}`][`${c}`]);
    }
    // The 4-day 3-course menu is the largest reservation.
    expect(planFor(largest(4, 3), config).reservedMicroUsd).toBeGreaterThan(planFor(largest(3, 3), config).reservedMicroUsd);
    expect(inputTokenBound(largest(4, 3))).toBeGreaterThan(inputTokenBound(largest(1, 1)));
  });
});

describe("generateRecipe (emulators)", () => {
  const UID = "user-menu-sets";
  const caller = callerFor(UID);
  let deps: TestDeps;
  beforeEach(async () => {
    await resetEmulators();
    deps = makeDeps();
    await setConfig({ perUserPerMinute: 100, perUserPerDay: 100, perUserDailySpendMicroUsd: 100_000_000 });
  });
  const today = () => new Date(deps.now()).toISOString().slice(0, 10);
  const request = (over: Record<string, unknown> = {}) => ({ ...body({ requestId: randomUUID(), today: today(), ...over }) });
  const seed = (balance: number) => db.collection("users").doc(UID).set({ ...emptyUser(), balance, purchasedCredits: balance });
  const userDoc = async () => (await db.collection("users").doc(UID).get()).data()!;

  test.each(COMBOS)("%i days x %i courses: charged from menuSets, those days and courses delivered, days stored", async (d, c) => {
    await seed(20);
    const req = request({ days: d, courses: c });
    const res = await generateRecipe(deps, caller, req);
    const cost = DEFAULT_COSTS.menuSets[`${d}`][`${c}`];
    expect(res).toMatchObject({ status: "DONE", charged: true, balance: 20 - cost, costs: DEFAULT_COSTS, ...CAPABILITIES });
    expect(res.acceptsMenuDays).toBe(true);
    expect(res.menu?.days.map((x) => x.date)).toEqual(menuDates(today(), d));
    expect(res.menu?.days.every((x) => x.courses.map((k) => k.course).join() === coursesFor(c).join())).toBe(true);
    expect(deps.generator.limits.at(-1)?.maxOutputTokens).toBe(MAX_OUTPUT_TOKENS[`menu${d}x${c}`]);
    const gen = (await db.collection("users").doc(UID).collection("generations").doc(req.requestId as string).get()).data()!;
    expect(gen).toMatchObject({ kind: "menu", courses: c, days: d, cost, state: "done" });
    expect(await userDoc()).toMatchObject({ balance: 20 - cost, consumedCredits: cost });
    expect(deps.logs.find((l) => l.message === "llm_call")?.data).toMatchObject({ kind: "menu", courses: c, days: d, cost });
  });

  test("an old client's menu (no days) is a 3-day menu at menu2 / menu3, exactly as before", async () => {
    await seed(9);
    const two = await generateRecipe(deps, caller, request({ courses: 2 }));
    expect(two).toMatchObject({ status: "DONE", balance: 5 });
    expect(two.menu?.days).toHaveLength(3);
    const three = await generateRecipe(deps, caller, request({ courses: 3 }));
    expect(three).toMatchObject({ status: "DONE", balance: 0 });
    // What an old client reads of the table is unchanged.
    expect(three.costs).toMatchObject({ recipe: 1, menu2: 4, menu3: 5 });
  });

  test("an old stored config/runtime.costs: 3-day prices from it, the other days derived from it", async () => {
    await setConfig({ costs: { recipe: 1, menu2: 6, menu3: 8 } });
    await seed(30);
    const four = await generateRecipe(deps, caller, request({ days: 4, courses: 3 }));
    expect(four).toMatchObject({ status: "DONE", balance: 21 });
    expect(four.costs.menuSets[4][3]).toBe(9);
    const old = await generateRecipe(deps, caller, request({ courses: 2 }));
    expect(old).toMatchObject({ status: "DONE", balance: 15 });
    const one = await generateRecipe(deps, caller, request({ days: 1, courses: 1 }));
    expect(one).toMatchObject({ status: "DONE", balance: 12 });
  });

  test("not enough for the set: INSUFFICIENT_CREDITS, no model call", async () => {
    await seed(5);
    const err = await generateRecipe(deps, caller, request({ days: 4, courses: 3 })).catch((e: HttpsError) => e);
    expect((err as HttpsError).details).toMatchObject({ reason: "INSUFFICIENT_CREDITS" });
    expect(deps.generator.calls).toBe(0);
  });

  test("a 4-day menu with an item that does not last is NOT_ENOUGH_ITEMS; the same items make a 2-day menu", async () => {
    await seed(10);
    const tomorrow = new Date(deps.now() + 86_400_000).toISOString().slice(0, 10);
    const ingredients = [{ name: "Milk", expiresOn: tomorrow, quantity: 1 }];
    const err = await generateRecipe(deps, caller, request({ days: 4, ingredients })).catch((e: HttpsError) => e);
    expect((err as HttpsError).details).toMatchObject({ reason: "NOT_ENOUGH_ITEMS" });
    expect(await userDoc()).toMatchObject({ balance: 10 });
    expect((await generateRecipe(deps, caller, request({ days: 2, ingredients }))).status).toBe("DONE");
  });

  test("the same requestId with other days is REQUEST_ID_REUSED", async () => {
    await seed(20);
    const req = request({ days: 2 });
    await generateRecipe(deps, caller, req);
    const err = await generateRecipe(deps, caller, { ...req, days: 4 }).catch((e: HttpsError) => e);
    expect((err as HttpsError).details).toMatchObject({ reason: "REQUEST_ID_REUSED" });
    // An old client's retry of a 3-day menu without days matches days: 3.
    const old = request({ courses: 2 });
    await generateRecipe(deps, caller, old);
    expect((await generateRecipe(deps, caller, { ...old, days: 3 })).status).toBe("DONE");
    expect(deps.generator.calls).toBe(2);
  });

  test("a generation stored before menu sets (no days field) is still recovered and retried", async () => {
    await seed(10);
    const req = request({ courses: 3 });
    await generateRecipe(deps, caller, req);
    const ref = db.collection("users").doc(UID).collection("generations").doc(req.requestId as string);
    const before: Record<string, unknown> = { ...(await ref.get()).data()! };
    delete before.days;
    await ref.set(before);
    expect((await ref.get()).data()).not.toHaveProperty("days");
    const polled = await getGeneration(deps, caller, { requestId: req.requestId });
    expect(polled).toMatchObject({ status: "DONE", balance: 5, costs: DEFAULT_COSTS, acceptsMenuDays: true, acceptsTaste: true, acceptsWelcome: true, welcomeRecipes: 3 });
    expect(polled.menu?.days).toHaveLength(3);
    // A retry by the old client (same body, no days) returns it without charging or calling the model.
    expect(await generateRecipe(deps, caller, req)).toMatchObject({ status: "DONE", balance: 5 });
    expect(deps.generator.calls).toBe(1);
    // And one stored without inputHash (the oldest records) is matched on kind, courses and 3 days.
    const oldest = { ...before };
    delete oldest.inputHash;
    await ref.set(oldest);
    expect((await generateRecipe(deps, caller, req)).status).toBe("DONE");
    const err = await generateRecipe(deps, caller, { ...req, days: 2 }).catch((e: HttpsError) => e);
    expect((err as HttpsError).details).toMatchObject({ reason: "REQUEST_ID_REUSED" });
  });

  test("getBalance carries the full table and the capability", async () => {
    expect(await getBalance(deps, caller)).toEqual({ balance: 0, costs: DEFAULT_COSTS, ...CAPABILITIES, tasteQuizVersion: expect.any(String), welcomeRecipes: 3 });
    expect(CAPABILITIES).toMatchObject({ acceptsMenuDays: true, acceptsTaste: true, acceptsWelcome: true });
  });
});
