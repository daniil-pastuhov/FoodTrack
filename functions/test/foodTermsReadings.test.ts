/**
 * Words that read as something else in context (core/foodTerms.ts Reading): an animal adjective
 * beside its eggs, "ground" beside a spice, fruit pulp and beefsteak tomatoes, a cooking verb that
 * looks like "download". True negatives, the honest recipes the checks used to refuse, and the true
 * positives around them that must still be refused, per recipe language. Pure: needs no emulator.
 */
import { buildPrompt } from "../src/adapters/prompt";
import { validateGenerateRequest, type GenerateRequest } from "../src/contract";
import { checkRecipeOutput, type OutputRule } from "../src/core/outputSafety";

const today = new Date().toISOString().slice(0, 10);
const CANARY = "LRD-0123456789ABCDEF";

function req(config: Record<string, unknown>, allergens: string[] = [], items = ["Pasta", "Tomatoes"]): GenerateRequest {
  return validateGenerateRequest({
    requestId: "0b5e7d4e-8f3c-4d6a-9a1b-2c3d4e5f6a7b",
    kind: "recipe",
    config: { difficulty: "easy", detail: "standard", servings: 2, maxMinutes: null, mealType: "dinner", diet: "none", language: "en", ...config },
    ingredients: items.map((name) => ({ name, expiresOn: today, quantity: 1 })),
    allergens,
    allergenConsent: true,
    today,
  }, Date.now());
}

function check(r: GenerateRequest, raw: unknown): "ok" | OutputRule {
  const res = checkRecipeOutput(raw, { req: r, canary: CANARY, systemInstruction: buildPrompt(r, CANARY).systemInstruction });
  return res.ok ? "ok" : res.rule;
}

/** An English recipe with one more ingredient of the given name, written in any recipe language. */
function withIngredient(name: string) {
  return {
    title: "Tomato pasta",
    summary: "A quick pasta with fresh tomatoes.",
    minutes: 25,
    ingredients: [
      { amount: "200 g", name: "pasta", pantryId: "p1" },
      { amount: "4", name: "tomatoes", pantryId: "p2" },
      { amount: "1", name, pantryId: "none" },
    ],
    steps: ["Boil the pasta in salted water.", "Chop the tomatoes and warm them in a pan with oil.", "Toss and serve."],
  };
}

type Case = [lang: string, rule: string, name: string, expected: "ok" | OutputRule];

/** "vegetarian" etc. is a diet; "allergen:x" an allergen the user typed. */
function requestFor(rule: string): GenerateRequest {
  return rule.startsWith("allergen:") ? req({}, [rule.slice("allergen:".length)]) : req({ diet: rule });
}

describe("ingredient names: what reads as something else", () => {
  test.each<Case>([
    // Found by the provider comparison (2026-11-17): refused for every model.
    ["uk", "vegetarian", "Яйця курячі", "ok"],
    ["pl", "vegetarian", "soli i świeżo mielonego pieprzu", "ok"],
    // A bird beside its eggs.
    ["uk", "vegetarian", "курячі яйця", "ok"],
    ["uk", "vegetarian", "Яйце куряче С1", "ok"],
    ["uk", "vegetarian", "курячий жовток", "ok"],
    ["uk", "vegetarian", "індичі яйця", "ok"],
    ["ru", "vegetarian", "Куриные яйца", "ok"],
    ["ru", "vegetarian", "яйцо куриное", "ok"],
    ["ru", "vegetarian", "куриный желток", "ok"],
    ["en", "vegetarian", "chicken eggs", "ok"],
    ["en", "vegetarian", "duck eggs", "ok"],
    ["en", "vegetarian", "goose egg", "ok"],
    ["de", "vegetarian", "Hühnereier", "ok"],
    ["de", "vegetarian", "Hühnereigelb", "ok"],
    ["nb", "vegetarian", "hønseegg", "ok"],
    ["da", "vegetarian", "hønseæg", "ok"],
    ["sv", "vegetarian", "hönsägg", "ok"],
    ["pl", "vegetarian", "jaja kurze", "ok"],
    ["es", "vegetarian", "huevos de gallina", "ok"],
    ["fr", "vegetarian", "œufs de poule", "ok"],
    ["it", "vegetarian", "uova di gallina", "ok"],
    // Milk and cheese of an animal (no meat word in any of them; kept so none is added).
    ["ru", "vegetarian", "коровье молоко", "ok"],
    ["ru", "vegetarian", "козий сыр", "ok"],
    ["uk", "vegetarian", "козячий сир", "ok"],
    ["pl", "vegetarian", "mleko krowie", "ok"],
    ["pl", "vegetarian", "ser kozi", "ok"],
    ["de", "vegetarian", "Ziegenkäse", "ok"],
    ["de", "vegetarian", "Kuhmilch", "ok"],
    ["es", "vegetarian", "queso de cabra", "ok"],
    ["fr", "vegetarian", "fromage de chèvre", "ok"],
    ["it", "vegetarian", "formaggio di capra", "ok"],
    ["nb", "vegetarian", "geitost", "ok"],
    ["sv", "vegetarian", "getost", "ok"],
    ["da", "vegetarian", "gedeost", "ok"],
    // Ground spices, coffee, nuts and seeds.
    ["pl", "vegetarian", "świeżo mielony pieprz", "ok"],
    ["pl", "vegetarian", "pieprz czarny mielony", "ok"],
    ["pl", "vegetarian", "mielony cynamon", "ok"],
    ["pl", "vegetarian", "kawa mielona", "ok"],
    ["pl", "vegetarian", "mielone migdały", "ok"],
    ["pl", "vegetarian", "mielone orzechy laskowe", "ok"],
    ["pl", "vegetarian", "siemię lniane mielone", "ok"],
    ["pl", "vegetarian", "papryka słodka mielona", "ok"],
    ["ru", "vegetarian", "молотый чёрный перец", "ok"],
    ["uk", "vegetarian", "мелений перець", "ok"],
    ["de", "vegetarian", "gemahlener Pfeffer", "ok"],
    ["es", "vegetarian", "pimienta negra molida", "ok"],
    ["fr", "vegetarian", "poivre moulu", "ok"],
    ["it", "vegetarian", "pepe macinato", "ok"],
    ["nb", "vegetarian", "malt pepper", "ok"],
    ["sv", "vegetarian", "mald kanel", "ok"],
    ["da", "vegetarian", "stødt kanel", "ok"],
    // Fruit and vegetables named after meat.
    ["de", "vegetarian", "Fruchtfleisch einer Mango", "ok"],
    ["de", "vegetarian", "Fleischtomaten", "ok"],
    ["de", "vegetarian", "Kokosfleisch", "ok"],
    ["sv", "vegetarian", "fruktkött av mango", "ok"],
    ["sv", "vegetarian", "bifftomat", "ok"],
    ["nb", "vegetarian", "bifftomater", "ok"],
    ["da", "vegetarian", "frugtkød fra en mango", "ok"],
    ["da", "vegetarian", "kokoskød", "ok"],
    ["en", "vegetarian", "beef tomatoes", "ok"],
    ["en", "vegetarian", "beefsteak tomato", "ok"],
    ["en", "vegetarian", "coconut meat", "ok"],
    ["fr", "vegetarian", "tomates cœur de bœuf", "ok"],
    ["es", "vegetarian", "carne de membrillo", "ok"],
    ["ru", "vegetarian", "мясистые помидоры", "ok"],
    ["uk", "vegetarian", "м'ясисті помідори", "ok"],
    // Not dairy.
    ["fr", "vegan", "laitue", "ok"],
    ["fr", "dairyFree", "feuilles de laitue", "ok"],
    ["fr", "allergen:lait", "laitue romaine", "ok"],
    ["ru", "dairyFree", "сырой картофель", "ok"],
    ["ru", "allergen:сыр", "сырые креветки", "ok"],
    ["en", "dairyFree", "butter beans", "ok"],
    ["en", "vegan", "bean curd", "ok"],

    // Still refused: the eggs are eggs, and the meat beside them is meat.
    ["uk", "vegan", "Яйця курячі", "DIET"],
    ["uk", "allergen:яйця", "Яйця курячі", "ALLERGEN"],
    ["uk", "allergen:egg", "курячі яйця", "ALLERGEN"],
    ["ru", "vegan", "куриные яйца", "DIET"],
    ["ru", "allergen:яйца", "яйцо куриное", "ALLERGEN"],
    ["en", "vegan", "chicken eggs", "DIET"],
    ["en", "allergen:egg", "duck eggs", "ALLERGEN"],
    ["de", "vegan", "Hühnereier", "DIET"],
    ["de", "allergen:Ei", "Hühnereier", "ALLERGEN"],
    ["nb", "vegan", "hønseegg", "DIET"],
    ["da", "allergen:æg", "hønseæg", "ALLERGEN"],
    ["pl", "vegan", "jaja kurze", "DIET"],
    ["en", "vegan", "egg yolks", "DIET"],
    ["en", "allergen:egg", "2 yolks", "ALLERGEN"],
    ["it", "allergen:uova", "maionese", "ALLERGEN"],
    ["it", "vegan", "albume", "DIET"],
    ["es", "allergen:huevo", "yemas", "ALLERGEN"],
    ["ru", "allergen:яйца", "желтки", "ALLERGEN"],
    ["uk", "allergen:яйця", "жовток", "ALLERGEN"],
    ["pl", "allergen:jajka", "żółtka", "ALLERGEN"],
    ["de", "allergen:Ei", "Eiklar", "ALLERGEN"],
    ["uk", "vegetarian", "куряче філе", "DIET"],
    ["uk", "vegetarian", "курятина", "DIET"],
    ["uk", "vegetarian", "яйця куряче філе", "DIET"],
    ["uk", "vegetarian", "курячий бульйон", "DIET"],
    ["ru", "vegetarian", "куриное филе", "DIET"],
    ["ru", "vegetarian", "куриные яйца и куриное филе", "DIET"],
    ["ru", "vegetarian", "куриный бульон", "DIET"],
    ["en", "vegetarian", "chicken", "DIET"],
    ["en", "vegetarian", "chicken thighs", "DIET"],
    ["en", "vegetarian", "duck breast", "DIET"],
    ["en", "vegetarian", "eggs, chicken", "DIET"],
    ["de", "vegetarian", "Hühnerbrühe", "DIET"],
    ["de", "vegetarian", "Hähnchenbrust und Hühnereier", "DIET"],
    ["nb", "vegetarian", "hønsebuljong", "DIET"],
    ["da", "vegetarian", "hønsebouillon", "DIET"],
    ["es", "vegetarian", "caldo de pollo", "DIET"],
    ["fr", "vegetarian", "bouillon de poulet", "DIET"],
    ["it", "vegetarian", "petto di pollo", "DIET"],
    ["pl", "vegetarian", "pierś z kurczaka", "DIET"],
    ["sv", "vegetarian", "kycklingfilé", "DIET"],
    // Still refused: minced meat, whatever is ground beside it.
    ["pl", "vegetarian", "mięso mielone", "DIET"],
    ["pl", "vegetarian", "mielone", "DIET"],
    ["pl", "vegetarian", "kotlet mielony", "DIET"],
    ["pl", "vegetarian", "mielona wołowina", "DIET"],
    ["pl", "vegetarian", "mielone z pieprzem", "DIET"],
    ["ru", "vegetarian", "фарш", "DIET"],
    ["de", "vegetarian", "Hackfleisch", "DIET"],
    // Still refused: real meat with a plant word near it.
    ["de", "vegetarian", "Rindfleisch", "DIET"],
    ["en", "vegetarian", "beef", "DIET"],
    ["en", "vegetarian", "beef mince", "DIET"],
    ["en", "vegetarian", "tomato beef", "DIET"],
    ["sv", "vegetarian", "biff", "DIET"],
    ["fr", "vegetarian", "cœur de bœuf", "DIET"],
    ["fr", "vegetarian", "bœuf haché", "DIET"],
    ["es", "vegetarian", "carne picada", "DIET"],
    ["ru", "vegetarian", "мясо", "DIET"],
    ["uk", "vegetarian", "м'ясо", "DIET"],
    ["da", "vegetarian", "hakket oksekød", "DIET"],
    // Still refused: dairy.
    ["fr", "vegan", "lait", "DIET"],
    ["fr", "allergen:lait", "lait entier", "ALLERGEN"],
    ["ru", "dairyFree", "сыр", "DIET"],
    ["ru", "dairyFree", "тёртый сыр", "DIET"],
    ["ru", "allergen:сыр", "сыром", "ALLERGEN"],
    ["en", "dairyFree", "butter", "DIET"],
    ["en", "vegan", "lemon curd", "DIET"],
    ["en", "allergen:milk", "cheese curds", "ALLERGEN"],
  ])("%s, %s: %s", (_lang, rule, name, expected) => {
    expect(check(requestFor(rule), withIngredient(name))).toBe(expected);
  });

  test("a pantry item named for a bird's eggs is usable under a vegetarian diet", () => {
    const r = req({ diet: "vegetarian", language: "uk" }, [], ["Яйця курячі", "Гречка"]);
    expect(check(r, {
      title: "Гречка з яйцем",
      summary: "Проста гречка з вареним яйцем.",
      minutes: 25,
      ingredients: [
        { amount: "150 г", name: "Гречка", pantryId: "p2" },
        { amount: "2 шт.", name: "Яйця курячі", pantryId: "p1" },
      ],
      steps: ["Зваріть гречку.", "Зваріть яйця та подайте з гречкою."],
    })).toBe("ok");
  });
});

/** A Ukrainian or Russian recipe with one more step. */
function withStep(step: string, language: "uk" | "ru") {
  return {
    title: language === "uk" ? "Сирники" : "Сырники",
    summary: language === "uk" ? "Ніжні сирники на сніданок." : "Нежные сырники на завтрак.",
    minutes: 30,
    ingredients: [
      { amount: "400 г", name: language === "uk" ? "Сир кисломолочний" : "Творог", pantryId: "p1" },
      { amount: "1", name: language === "uk" ? "Яйце" : "Яйцо", pantryId: "p2" },
    ],
    steps: [language === "uk" ? "Змішайте сир з яйцем." : "Смешайте творог с яйцом.", step],
  };
}

describe("steps: cooking verbs that look like solicitation", () => {
  test.each<[lang: "uk" | "ru", step: string, expected: "ok" | OutputRule]>([
    // Found by the provider comparison: "скачайте кульку" is "roll into a ball".
    ["uk", "Візьміть столову ложку сирної маси, скачайте кульку, обваляйте її в борошні.", "ok"],
    ["uk", "Візьміть частину тіста розміром з волоський горіх і скачайте кульку.", "ok"],
    ["uk", "Скачайте з тіста невеликі кульки.", "ok"],
    ["uk", "Скачайте тісто в ковбаску й наріжте.", "ok"],
    ["uk", "Скачайте руками кульки завбільшки з горіх.", "ok"],
    ["uk", "Завантажте всі інгредієнти в чашу мультиварки.", "ok"],
    ["uk", "Завантажте форму в духовку на 20 хвилин.", "ok"],
    ["ru", "Скатайте шарики и обваляйте их в муке.", "ok"],
    // Still refused: download, install, sign in.
    ["ru", "Скачайте наше приложение, чтобы увидеть больше рецептов.", "SOLICITATION"],
    ["ru", "Скачайте приложение, скатайте шарики.", "SOLICITATION"],
    ["ru", "Скачать рецепт можно в приложении.", "SOLICITATION"],
    ["uk", "Скачайте наш додаток для інших рецептів.", "SOLICITATION"],
    ["uk", "Завантажте наш додаток.", "SOLICITATION"],
    ["uk", "Завантажте застосунок і увійдіть, щоб отримати ще рецепти.", "SOLICITATION"],
    ["ru", "Введите пароль, чтобы продолжить.", "SOLICITATION"],
  ])("%s: %s", (language, step, expected) => {
    const r = req({ language }, [], language === "uk" ? ["Сир кисломолочний", "Яйце"] : ["Творог", "Яйцо"]);
    expect(check(r, withStep(step, language))).toBe(expected);
  });
});
