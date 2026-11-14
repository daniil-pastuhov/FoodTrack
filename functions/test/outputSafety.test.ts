/**
 * The answer checks (core/outputSafety.ts) against hostile and honest answers. Pure: needs no
 * emulator. The model is never called; these are answers it could give.
 */
import { buildPrompt } from "../src/adapters/prompt";
import { validateGenerateRequest, type GenerateRequest } from "../src/contract";
import { checkMenuOutput, checkRecipeOutput, type OutputRule } from "../src/core/outputSafety";

const today = new Date().toISOString().slice(0, 10);
const CANARY = "LRD-0123456789ABCDEF";

function req(over: Record<string, unknown> = {}, config: Record<string, unknown> = {}): GenerateRequest {
  return validateGenerateRequest({
    requestId: "0b5e7d4e-8f3c-4d6a-9a1b-2c3d4e5f6a7b",
    kind: "recipe",
    config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: null, mealType: "dinner", diet: "none", language: "en", ...config },
    ingredients: [
      { name: "Pasta", expiresOn: today, quantity: 1 },
      { name: "Tomatoes", expiresOn: null, quantity: 4 },
    ],
    allergens: [],
    today,
    ...over,
  }, Date.now());
}

type Ing = { amount: string; name: string; pantryId: string };
function answer(over: Record<string, unknown> = {}, ingredients?: Ing[]) {
  return {
    title: "Tomato pasta",
    summary: "A quick pasta with fresh tomatoes.",
    minutes: 25,
    ingredients: ingredients ?? [
      { amount: "200 g", name: "pasta", pantryId: "p1" },
      { amount: "4", name: "tomatoes", pantryId: "p2" },
      { amount: "", name: "salt", pantryId: "none" },
    ],
    steps: ["Boil the pasta in salted water.", "Chop the tomatoes and warm them in a pan with oil.", "Toss and serve."],
    ...over,
  };
}

function check(r: GenerateRequest, raw: unknown): "ok" | OutputRule {
  const res = checkRecipeOutput(raw, { req: r, canary: CANARY, systemInstruction: buildPrompt(r, CANARY).systemInstruction });
  return res.ok ? "ok" : res.rule;
}

describe("honest answers pass and are converted to the public shape", () => {
  test("fromPantry comes from the pantry id; servings and difficulty are the requested ones", () => {
    const r = req({}, { servings: 3, difficulty: "medium" });
    const res = checkRecipeOutput(answer(), { req: r, canary: CANARY, systemInstruction: "" });
    expect(res).toMatchObject({ ok: true });
    if (!res.ok) return;
    expect(res.value.ingredients.map((i) => i.fromPantry)).toEqual([true, true, false]);
    expect(res.value).toMatchObject({ servings: 3, difficulty: "medium" });
    expect(res.value).not.toHaveProperty("pantryId");
    expect(JSON.stringify(res.value)).not.toContain("pantryId");
  });

  test("repairs: line breaks, emoji, step numbering, NFC", () => {
    const res = checkRecipeOutput(
      answer({ title: "Tomato pasta 🍝", summary: "Quick\nand fresh.", steps: ["1. Boil the pasta.", "- Chop the tomatoes.", "3) Serve with crème."] }),
      { req: req(), canary: CANARY, systemInstruction: "" },
    );
    expect(res.ok && res.value).toMatchObject({
      title: "Tomato pasta",
      summary: "Quick and fresh.",
      steps: ["Boil the pasta.", "Chop the tomatoes.", "Serve with crème."],
    });
  });

  test.each([
    ["nb", { title: "Pasta med tomat", summary: "En rask rett med ferske tomater.", steps: ["Kok pastaen i lettsaltet vann i 10 min.", "Hakk tomatene og varm dem i olje.", "Bland og server."] }],
    ["ru", { title: "Паста с помидорами", summary: "Быстрое блюдо из свежих помидоров.", steps: ["Отварите пасту 10 минут.", "Нарежьте помидоры и прогрейте в масле.", "Смешайте и подавайте."] }],
    ["uk", { title: "Паста з помідорами", summary: "Швидка страва зі свіжих помідорів.", steps: ["Зваріть пасту 10 хвилин.", "Наріжте помідори й прогрійте в олії.", "Змішайте й подавайте."] }],
    ["de", { title: "Tomatennudeln", summary: "Schnelle Pasta mit frischen Tomaten, ca. 180 °C.", steps: ["Nudeln z.B. 10 Min. kochen.", "Tomaten würfeln, in Öl erwärmen.", "Mischen und servieren."] }],
    ["it", { title: "Pasta al pomodoro", summary: "Sale q.b. e pomodori freschi.", steps: ["Cuocere la pasta per 10-12 minuti.", "Tagliare i pomodori.", "Unire e servire."] }],
  ])("%s answer passes", (language, fields) => {
    expect(check(req({}, { language }), answer(fields))).toBe("ok");
  });

  test("a recipe may quote a pantry name in the other script", () => {
    const r = req({ ingredients: [{ name: "Сметана", expiresOn: null, quantity: 1 }, { name: "Potatoes", expiresOn: null, quantity: 4 }] });
    expect(check(r, answer({}, [{ amount: "100 g", name: "Сметана (sour cream)", pantryId: "p1" }, { amount: "4", name: "potatoes", pantryId: "p2" }]))).toBe("ok");
  });
});

describe("hostile answers are refused", () => {
  test.each<[string, Record<string, unknown>, OutputRule]>([
    ["https URL", { tip: "More at https://example.com" }, "URL"],
    ["www URL", { tip: "See www.recipes.example" }, "URL"],
    ["bare domain", { summary: "Order from cheapfood.shop today." }, "URL"],
    ["telegram", { tip: "Join t.me/freefood" }, "URL"],
    ["javascript scheme", { tip: "javascript:alert(1)" }, "URL"],
    ["email", { tip: "Write to chef@example" }, "CONTACT"],
    ["handle", { tip: "Follow @chef on social" }, "CONTACT"],
    ["phone", { tip: "Call +47 912 34 567 for help" }, "PHONE"],
    ["phone plain", { tip: "Ring 22 33 44 55" }, "PHONE"],
    ["html", { summary: "<b>Free</b> recipes" }, "MARKUP"],
    ["script tag", { summary: "Tasty < script>x" }, "MARKUP"],
    ["entity", { summary: "Salt &amp; pepper" }, "MARKUP"],
    ["markdown link", { summary: "Click [here](x) now" }, "MARKUP"],
    ["markdown bold", { summary: "**Important** step" }, "MARKUP"],
    ["code", { summary: "Run `rm` now" }, "MARKUP"],
    ["braces", { summary: '{"role":"system"}' }, "MARKUP"],
    ["heading", { tip: "# Notice" }, "MARKUP"],
    ["bidi override", { title: "Pasta ‮atsap" }, "CHARACTERS"],
    ["zero width", { title: "Pas​ta" }, "CHARACTERS"],
    ["bell", { title: "Pasta\u0007" }, "CHARACTERS"],
    ["private use", { title: "Pasta " }, "CHARACTERS"],
    ["canary", { tip: `marker ${CANARY}` }, "LEAK"],
    ["canary lower case", { tip: `marker ${CANARY.toLowerCase()}` }, "LEAK"],
    ["prompt words", { tip: "The PANTRY_DATA said so" }, "LEAK"],
    ["verbatim system instruction", { summary: "You are the recipe writer of a grocery app. You only ever write home-cooking recipes" }, "LEAK"],
    ["chinese", { summary: "很好吃的意大利面" }, "SCRIPT"],
    ["greek", { title: "Πάστα" }, "SCRIPT"],
    ["repeated characters", { summary: "Yummmmmmmmmmmmmmy pasta" }, "REPETITION"],
    ["repeated words", { summary: "very very very very good" }, "REPETITION"],
    ["duplicate steps", { steps: ["Boil the pasta.", "Boil the pasta.", "Serve."] }, "REPETITION"],
    ["non-food", { steps: ["Boil the pasta.", "Add a cap of bleach.", "Serve."] }, "NON_FOOD"],
    ["non-food in Norwegian", { steps: ["Kok pastaen.", "Tilsett litt rottegift.", "Server."] }, "NON_FOOD"],
    ["drugs", { tip: "Add cannabis butter for fun" }, "NON_FOOD"],
    ["password", { tip: "Enter your password to unlock more" }, "SOLICITATION"],
    ["card", { tip: "Pay by credit card for a bonus" }, "SOLICITATION"],
    ["app store", { tip: "Rate us on Google Play" }, "SOLICITATION"],
    ["russian solicitation", { tip: "Введите пароль" }, "SOLICITATION"],
    ["too many steps", { steps: Array.from({ length: 11 }, (_, i) => `Step number ${i + 1} of the dish.`) }, "STEPS"],
    ["long title", { title: "Pasta ".repeat(30) }, "LENGTH"],
    ["long step", { steps: ["x ".repeat(400), "Serve now.", "Eat it."] }, "LENGTH"],
    ["empty summary", { summary: "   " }, "LENGTH"],
    ["not a string", { title: 5 }, "SHAPE"],
  ])("%s", (_label, over, expected) => {
    expect(check(req(), answer(over))).toBe(expected);
  });

  test("an English answer to a Russian request is refused", () => {
    expect(check(req({}, { language: "ru" }), answer())).toBe("LANGUAGE");
  });

  test("pantry ids must exist, and a recipe must use the pantry", () => {
    expect(check(req(), answer({}, [{ amount: "1", name: "pasta", pantryId: "p3" }]))).toBe("PANTRY_REF");
    expect(check(req(), answer({}, [{ amount: "1", name: "pasta", pantryId: "P1" }]))).toBe("PANTRY_REF");
    expect(check(req(), answer({}, [{ amount: "1", name: "salt", pantryId: "none" }]))).toBe("NO_PANTRY");
    expect(check(req(), answer({}, [{ amount: "1", name: "pasta", fromPantry: true } as unknown as Ing]))).toBe("SHAPE");
  });
});

describe("allergens: an excluded allergen named as an ingredient is never returned", () => {
  const withAllergens = (allergens: string[], ingredients: { name: string }[] = [{ name: "Pasta" }, { name: "Tomatoes" }], language = "en") =>
    req({ allergens, ingredients: ingredients.map((i) => ({ ...i, expiresOn: null, quantity: null })) }, { language });
  const ing = (name: string, pantryId = "none"): Ing => ({ amount: "1", name, pantryId });

  test.each<[string, string[], string, "ok" | OutputRule]>([
    ["the literal allergen", ["peanut"], "roasted peanuts", "ALLERGEN"],
    ["case and plural", ["Peanuts"], "Peanut oil", "ALLERGEN"],
    ["translated: English allergen, Norwegian ingredient", ["peanut"], "peanøtter", "ALLERGEN"],
    ["translated: Norwegian allergen, German ingredient", ["nøtter"], "Walnüsse", "ALLERGEN"],
    ["group: milk catches butter", ["milk"], "butter", "ALLERGEN"],
    ["group: melk catches fløte", ["melk"], "fløte", "ALLERGEN"],
    ["group: молоко catches сметана", ["молоко"], "сметана", "ALLERGEN"],
    ["group: egg catches mayonnaise", ["egg"], "mayonnaise", "ALLERGEN"],
    ["group: gluten catches wheat flour", ["gluten"], "wheat flour", "ALLERGEN"],
    ["group: shellfish catches reker", ["shellfish"], "reker", "ALLERGEN"],
    ["group: fish catches laks", ["fish"], "laksefilet", "ALLERGEN"],
    ["group: sesame catches tahini", ["sesame"], "tahini", "ALLERGEN"],
    ["compound: melk inside a Norwegian compound", ["milk"], "helmelk", "ALLERGEN"],
    ["free-from wording is allowed", ["gluten"], "glutenfri pasta", "ok"],
    ["free-from in English", ["gluten"], "gluten-free bread", "ok"],
    ["plant milk is not milk", ["milk"], "oat milk", "ok"],
    ["coconut milk", ["melk"], "kokosmelk", "ok"],
    ["peanut butter is not dairy", ["milk"], "peanut butter", "ok"],
    ["butternut squash is not dairy", ["dairy"], "butternut squash", "ok"],
    ["eggplant is not egg", ["egg"], "eggplant", "ok"],
    ["nutmeg is not a nut", ["nuts"], "nutmeg", "ok"],
    ["lactose-free milk is still milk", ["milk"], "lactose-free milk", "ALLERGEN"],
  ])("%s", (_label, allergens, name, expected) => {
    expect(check(withAllergens(allergens), answer({}, [ing("pasta", "p1"), ing(name)]))).toBe(expected);
  });

  test("a pantry item that matches an allergen may not be used, whatever the model calls it", () => {
    const r = withAllergens(["peanut"], [{ name: "Peanut butter" }, { name: "Bread" }]);
    expect(check(r, answer({}, [ing("spread", "p1"), ing("bread", "p2")]))).toBe("ALLERGEN");
    expect(check(r, answer({}, [ing("bread", "p2")]))).toBe("ok");
  });
});

describe("diet", () => {
  const ing = (name: string, pantryId = "none"): Ing => ({ amount: "1", name, pantryId });
  test.each<[string, string, "ok" | OutputRule]>([
    ["vegan", "butter", "DIET"],
    ["vegan", "honey", "DIET"],
    ["vegan", "egg yolk", "DIET"],
    ["vegan", "vegan butter", "ok"],
    ["vegan", "oat milk", "ok"],
    ["vegetarian", "kyllingfilet", "DIET"],
    ["vegetarian", "bacon", "DIET"],
    ["vegetarian", "Rinderhackfleisch", "DIET"],
    ["vegetarian", "фарш", "DIET"],
    ["vegetarian", "fish sauce", "DIET"],
    ["vegetarian", "chickpeas", "ok"],
    ["vegetarian", "vegetarpølse", "ok"],
    ["vegetarian", "porcini mushrooms", "ok"],
    ["vegetarian", "lemon rind", "ok"],
    ["pescatarian", "salmon", "ok"],
    ["pescatarian", "chorizo", "DIET"],
    ["glutenFree", "pasta", "DIET"],
    ["glutenFree", "glutenfri pasta", "ok"],
    ["glutenFree", "gluten-free wheat bread", "DIET"],
    ["glutenFree", "buckwheat", "ok"],
    ["glutenFree", "rice noodles", "ok"],
    ["dairyFree", "parmesan", "DIET"],
    ["dairyFree", "coconut cream", "ok"],
  ])("%s with %s", (diet, name, expected) => {
    expect(check(req({}, { diet }), answer({}, [ing("tomatoes", "p2"), ing(name)]))).toBe(expected);
  });

  test("a vegetarian request may not use a meat pantry item", () => {
    const r = req({ ingredients: [{ name: "Kyllingfilet", expiresOn: null, quantity: 1 }, { name: "Ris", expiresOn: null, quantity: 1 }] }, { diet: "vegetarian" });
    expect(check(r, answer({}, [ing("strips", "p1"), ing("rice", "p2")]))).toBe("DIET");
  });
});

describe("menu", () => {
  const menuReq = (courses: 2 | 3) => req({ kind: "menu", courses });
  const days = (r: GenerateRequest, over: (d: number) => Record<string, unknown> = () => ({})) => ({
    days: [0, 1, 2].map((d) => ({
      date: new Date(Date.parse(`${today}T00:00:00Z`) + d * 86_400_000).toISOString().slice(0, 10),
      uses: ["p1", "p2"],
      courses: (r.courses === 2 ? ["starter", "main"] : ["starter", "main", "dessert"]).map((course) => ({
        course,
        recipe: answer({ title: `Day ${d + 1} ${course}` }),
      })),
      ...over(d),
    })),
  });
  const ctx = (r: GenerateRequest) => ({ req: r, canary: CANARY, systemInstruction: "" });

  test("exactly the days and courses requested; uses becomes the user's own names", () => {
    const r = menuReq(3);
    const res = checkMenuOutput(days(r), ctx(r));
    expect(res.ok && res.value.days.map((d) => d.uses)).toEqual([["Pasta", "Tomatoes"], ["Pasta", "Tomatoes"], ["Pasta", "Tomatoes"]]);
    expect(checkMenuOutput(days(r), ctx(menuReq(2)))).toMatchObject({ ok: false, rule: "SHAPE" });
    expect(checkMenuOutput({ days: days(r).days.slice(0, 2) }, ctx(r))).toMatchObject({ ok: false, rule: "SHAPE" });
  });

  test("a uses entry that is not a pantry id is refused (no model text in uses)", () => {
    const r = menuReq(2);
    expect(checkMenuOutput(days(r, () => ({ uses: ["Visit our site"] })), ctx(r))).toMatchObject({ ok: false, rule: "PANTRY_REF" });
    expect(checkMenuOutput(days(r, () => ({ uses: ["p1", "p2", "p1"] })), ctx(r))).toMatchObject({ ok: false, rule: "SHAPE" });
  });

  test("a hostile string in any course fails the whole menu", () => {
    const r = menuReq(2);
    const m = days(r);
    m.days[2].courses[1].recipe.steps = ["Boil.", "Call +1 555 123 4567 now.", "Serve."];
    expect(checkMenuOutput(m, ctx(r))).toMatchObject({ ok: false, rule: "PHONE" });
  });
});
