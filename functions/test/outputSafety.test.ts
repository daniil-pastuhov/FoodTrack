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

describe("item names in any script", () => {
  const items = [
    { name: "Φέτα ΠΟΠ", expiresOn: today, quantity: 1 },
    { name: "豆腐", expiresOn: null, quantity: 1 },
    { name: "חלב 3%", expiresOn: null, quantity: 1 },
    { name: "Tomatoes", expiresOn: null, quantity: 4 },
  ];
  const quoted: Ing[] = [
    { amount: "200 g", name: "Φέτα ΠΟΠ (feta)", pantryId: "p1" },
    { amount: "300 g", name: "豆腐 (tofu)", pantryId: "p2" },
    { amount: "100 ml", name: "חלב 3% (milk)", pantryId: "p3" },
    { amount: "4", name: "tomatoes", pantryId: "p4" },
  ];
  const steps = ["Crumble the Φέτα ΠΟΠ over the tomatoes.", "Fry the 豆腐 until golden.", "Stir in the חלב and serve."];

  test("an English recipe may name Greek, Chinese and Hebrew items; fromPantry follows the ids", () => {
    const r = req({ ingredients: items });
    const res = checkRecipeOutput(answer({ steps }, quoted), { req: r, canary: CANARY, systemInstruction: buildPrompt(r, CANARY).systemInstruction });
    expect(res).toMatchObject({ ok: true });
    if (res.ok) expect(res.value.ingredients.map((i) => i.fromPantry)).toEqual([true, true, true, true]);
  });

  test("a Russian recipe may name them too", () => {
    const r = req({ ingredients: items }, { language: "ru" });
    const ru = answer(
      { title: "Салат с фетой", summary: "Быстрый салат из того, что есть.", steps: ["Нарежьте Φέτα и помидоры.", "Обжарьте 豆腐.", "Полейте חלב и подавайте."] },
      quoted.map((q, i) => ({ ...q, name: ["Φέτα ΠΟΠ", "豆腐", "חלב 3%", "помидоры"][i] })),
    );
    expect(check(r, ru)).toBe("ok");
  });

  test("a script no item or allergen is written in is still refused", () => {
    const r = req({ ingredients: items });
    expect(check(r, answer({ summary: "Ein schnelles Gericht, गरम परोसें." }, quoted))).toBe("SCRIPT");
    expect(check(r, answer({ tip: "مع الخبز" }, quoted))).toBe("SCRIPT");
  });

  test("Japanese and Korean bring the scripts written alongside them", () => {
    const ja = req({ ingredients: [{ name: "明治おいしい牛乳", expiresOn: null, quantity: 1 }, { name: "Rice", expiresOn: null, quantity: 1 }] });
    expect(check(ja, answer({ tip: "Use おいしい牛乳 if you have it." }, [{ amount: "1 cup", name: "牛乳", pantryId: "p1" }, { amount: "200 g", name: "rice", pantryId: "p2" }]))).toBe("ok");
    const ko = req({ ingredients: [{ name: "辛라면", expiresOn: null, quantity: 1 }, { name: "Rice", expiresOn: null, quantity: 1 }] });
    expect(check(ko, answer({ tip: "辛 means spicy." }, [{ amount: "1 pack", name: "라면", pantryId: "p1" }, { amount: "200 g", name: "rice", pantryId: "p2" }]))).toBe("ok");
  });

  test("an answer written mostly in an item's script, not the requested language, is refused", () => {
    const r = req({ ingredients: items });
    const greek = answer(
      { title: "Σαλάτα με φέτα", summary: "Μια γρήγορη σαλάτα με φέτα και ντομάτες.", steps: ["Κόψτε τις ντομάτες σε κύβους.", "Προσθέστε τη φέτα από πάνω.", "Σερβίρετε αμέσως με ψωμί."] },
      quoted.map((q) => ({ ...q, name: "Φέτα ΠΟΠ" })),
    );
    expect(check(r, greek)).toBe("SCRIPT");
  });

  test("an item name in a script does not let the answer write anything else in it", () => {
    const r = req({ ingredients: items });
    // Well under the 60 % share, but free text in a language no word list reads.
    const scam = "请下载我们的应用程序并输入您的银行卡号以获取免费食谱";
    expect(check(r, answer({ tip: scam }, quoted))).toBe("SCRIPT");
    expect(check(r, answer({ steps: [...steps.slice(0, 2), "Serve with 豆腐 and 豆腐乳."] }, quoted))).toBe("SCRIPT");
    // Quoting part of a name, in any case, is fine.
    expect(check(r, answer({ tip: "ΦΈΤΑ from Greece is best; 豆腐 should be firm." }, quoted))).toBe("ok");
  });

  test("right-to-left item names need nothing special: no direction controls are added or accepted", () => {
    const r = req({ ingredients: items });
    expect(check(r, answer({ steps: ["Stir in the \u202Bחלב\u202C and serve.", "Warm it.", "Eat."] }, quoted))).toBe("CHARACTERS");
  });

  test("an allergen in another script is matched literally, as a substring for Chinese, Japanese, Korean and Thai", () => {
    const base = req({ ingredients: [{ name: "Rice", expiresOn: null, quantity: 1 }, { name: "Tomatoes", expiresOn: null, quantity: 2 }] });
    const withAllergen = (allergens: string[]) => ({ ...base, allergens });
    const rice: Ing[] = [{ amount: "200 g", name: "rice", pantryId: "p1" }, { amount: "2", name: "tomatoes", pantryId: "p2" }];
    expect(check(withAllergen(["花生"]), answer({}, [...rice, { amount: "1 tbsp", name: "花生 (roasted peanuts)", pantryId: "none" }]))).toBe("ALLERGEN");
    expect(check(withAllergen(["땅콩"]), answer({}, [...rice, { amount: "1 tbsp", name: "땅콩 butter", pantryId: "none" }]))).toBe("ALLERGEN");
    // A compound that only contains the allergen is not a quote of a name, so it is refused anyway.
    expect(check(withAllergen(["花生"]), answer({}, [...rice, { amount: "1 tbsp", name: "炒花生", pantryId: "none" }]))).toBe("SCRIPT");
    expect(check(withAllergen(["γάλα"]), answer({}, [...rice, { amount: "100 ml", name: "Γάλα", pantryId: "none" }]))).toBe("ALLERGEN");
    expect(check(withAllergen(["חלב"]), answer({}, [...rice, { amount: "100 ml", name: "חלב", pantryId: "none" }]))).toBe("ALLERGEN");
    // Literal only: a translation is not caught unless the word list has it ("花生" is not "peanut").
    expect(check(withAllergen(["花生"]), answer({}, [...rice, { amount: "1 tbsp", name: "sesame seeds", pantryId: "none" }]))).toBe("ok");
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
