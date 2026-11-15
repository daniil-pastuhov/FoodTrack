import Foundation

// Checks the iOS ports of the multi-choice recipe config, its legacy wire mapping, the exact
// past-date warnings and the shopping-list rows against the same cases as Android's
// shared/src/commonTest/.../RecipeConfigChoicesTest.kt. Run with check.sh.

/// What RecipeModels.swift needs of the pantry's item; the app's own is in LocalModels.swift.
public struct PantryItem {
    var name = ""
    var daysRemaining = 0
    var daysRemainingFormatted = ""
    var expiryDate: Date? = nil
    var quantity: Int? = nil
}

var failures = 0
func expect<T: Equatable>(_ actual: T, _ expected: T, _ what: String) {
    if actual != expected {
        failures += 1
        print("FAIL \(what): \(actual) != \(expected)")
    }
}

// Toggling
expect(RecipeChoices.toggled(Set<RecipeMealType>(), .dinner), [.dinner], "a meal turns Any off")
expect(RecipeChoices.toggled([RecipeMealType.dinner, .lunch], nil), [], "Any clears the others")
expect(RecipeChoices.toggled([RecipeMealType.dinner], .dinner), [], "the last one off is Any again")
expect(RecipeChoices.promptDiets([.pescatarian, .vegetarian]), [.vegetarian], "the strictest wins")
expect(RecipeChoices.promptDiets(Set(RecipeChoices.diets)), [.vegan, .glutenFree], "vegan implies the rest")

// Normalising and stored configs
expect(RecipeConfig(minMinutes: 10, maxMinutes: 120).minMinutes, nil, "a lower bound at the end is none")
expect(RecipeConfig(minMinutes: 10, maxMinutes: 120).maxMinutes, nil, "an upper bound at the end is none")
expect(RecipeConfig(maxMinutes: 47).maxMinutes, 45, "onto the scale")
expect(RecipeConfig(minMinutes: 50, maxMinutes: 30).minMinutes, 30, "never above the upper")
let old = #"{"difficulty":"medium","detail":"brief","servings":4,"maxMinutes":15,"mealType":"dinner","diet":"vegan","language":"nb"}"#
let oldConfig = try! JSONDecoder().decode(RecipeConfig.self, from: Data(old.utf8))
expect(oldConfig, RecipeConfig(difficulty: .medium, detail: .brief, servings: 4, maxMinutes: 15, mealTypes: [.dinner], diets: [.vegan], language: .nb), "an old stored config")
let anyOld = try! JSONDecoder().decode(RecipeConfig.self, from: Data(#"{"maxMinutes":null,"mealType":"any","diet":"none"}"#.utf8))
expect(anyOld, RecipeConfig(maxMinutes: nil), "an old Any / None")
let fresh = RecipeConfig(minMinutes: 20, maxMinutes: 45, mealTypes: [.lunch], diets: [.glutenFree, .vegan])
expect(try! JSONDecoder().decode(RecipeConfig.self, from: JSONEncoder().encode(fresh)), fresh, "a round trip")

// Legacy mapping
func legacy(_ config: RecipeConfig) -> WireRecipeConfig? { WireRecipeConfig.legacy(config) }
expect([10, 15, 25, 30, 45, 60, 115, nil].map { legacy(RecipeConfig(minMinutes: 20, maxMinutes: $0))?.maxMinutes ?? -1 }, [15, 15, 15, 30, 30, 60, 60, -1], "time")
expect(legacy(RecipeConfig(mealTypes: [.snack]))?.mealType, "snack", "one meal")
expect(legacy(RecipeConfig(mealTypes: [.snack, .dessert]))?.mealType, "any", "several meals")
func diet(_ d: Set<RecipeDiet>) -> String? { legacy(RecipeConfig(diets: d))?.diet }
expect(diet([]), "none", "no diet")
expect(diet([.vegetarian, .pescatarian]), "vegetarian", "veg + pesc")
expect(diet([.vegan, .dairyFree]), "vegan", "vegan + df")
expect(diet([.vegetarian, .dairyFree]), "vegan", "veg + df")
expect(diet([.vegetarian, .glutenFree]), nil, "veg + gf can't be said")
expect(WireRecipeConfig.make(RecipeConfig(maxMinutes: 60, mealTypes: [.dinner]), takesMultiChoice: true)?.isMultiChoice, false, "exact legacy stays legacy")
let multi = WireRecipeConfig.make(RecipeConfig(minMinutes: 20, maxMinutes: 45, mealTypes: [.dinner, .lunch], diets: [.glutenFree, .vegetarian]), takesMultiChoice: true)
expect(multi?.mealTypes, ["lunch", "dinner"], "meals in order")
expect(multi?.diets, ["vegetarian", "glutenFree"], "diets in order")
expect(multi?.mealType, nil, "no legacy meal beside the lists")
let stored = #"{"difficulty":"easy","detail":"standard","servings":2,"maxMinutes":30,"mealType":"any","diet":"none","language":"en"}"#
expect(try! JSONDecoder().decode(WireRecipeConfig.self, from: Data(stored.utf8)).isMultiChoice, false, "a kept request reads as legacy")

// Past date by index
let ticked = [WireIngredient(name: "Bread", expiresOn: "2026-11-12", quantity: 1), WireIngredient(name: "Milk", expiresOn: "2026-11-20", quantity: 1), WireIngredient(name: "Cream", expiresOn: "2026-11-13", quantity: 1)]
let optional = [WireIngredient(name: "Yoghurt", expiresOn: "2026-11-10", quantity: 1)]
let dish = Recipe(title: "Dish", summary: "", minutes: 10, servings: 2, difficulty: .easy, ingredients: [
    RecipeIngredient(amount: "1", name: "milk", fromPantry: true, pantryIndex: 1),
    RecipeIngredient(amount: "1", name: "cream", fromPantry: true, pantryIndex: 2),
    RecipeIngredient(amount: "1", name: "yoghurt", fromPantry: true, optionalIndex: 0),
    RecipeIngredient(amount: "1", name: "ghost", fromPantry: true, pantryIndex: 9),
], steps: ["Cook."], source: .server)
expect(RecipeWire.pastDate(of: dish, ticked: ticked, optional: optional, today: "2026-11-14").map(\.name), ["Cream", "Yoghurt"], "exact past date")

// Shopping list
// The same table as Android's RecipeConfigChoicesTest.namesAreTidiedForAList.
let tidyCases: [(String, [String])] = [
    ("  red   onion, thinly sliced ", ["Red onion"]),
    ("garlic, minced.", ["Garlic"]),
    ("сметана", ["Сметана"]),
    ("crème fraîche;", ["Crème fraîche"]),
    (" , chopped", []),
    ("Salt, pepper, chilli flakes", ["Salt", "Pepper", "Chilli flakes"]),
    ("Salt, pepper, olive oil", ["Salt", "Pepper", "Olive oil"]),
    ("Tomatoes, peeled and diced small", ["Tomatoes"]),
    ("Salt, pepper or chilli flakes", ["Salt", "Pepper or chilli flakes"]),
    ("Butter or olive oil", ["Butter or olive oil"]),
    ("Salt, to taste", ["Salt"]),
    ("Salt and pepper to taste", ["Salt and pepper"]),
    ("Garlic, 2 cloves, minced", ["Garlic"]),
    ("Chicken thighs, boneless and skinless", ["Chicken thighs"]),
    ("Cheese, such as cheddar", ["Cheese"]),
    ("Parsley (optional)", ["Parsley"]),
    ("Lemon, zest and juice", ["Lemon"]),
    ("Соль, перец по вкусу", ["Соль", "Перец"]),
    ("Лук, мелко нарезанный", ["Лук"]),
    ("Морковь, натёртая на тёрке", ["Морковь"]),
    ("Укроп, петрушка, зелёный лук", ["Укроп", "Петрушка", "Зелёный лук"]),
    ("Чеснок, 2 зубчика", ["Чеснок"]),
    ("Løk, finhakket", ["Løk"]),
    ("Salt, pepper, olivenolje", ["Salt", "Pepper", "Olivenolje"]),
    ("Hvitløk, presset", ["Hvitløk"]),
    ("Gulrøtter, skrelt og i terninger", ["Gulrøtter"]),
    ("Salt og pepper etter smak", ["Salt og pepper"]),
    ("Rømme eller crème fraîche", ["Rømme eller crème fraîche"]),
    ("Sal, pimienta, aceite de oliva", ["Sal", "Pimienta", "Aceite de oliva"]),
    ("Cebolla, picada finamente", ["Cebolla"]),
    ("Sal y pimienta al gusto", ["Sal y pimienta"]),
    ("Ajo, 2 dientes", ["Ajo"]),
    ("Nata o leche", ["Nata o leche"]),
    ("Zwiebel, fein gehackt", ["Zwiebel"]),
    ("Salz, Pfeffer, Olivenöl", ["Salz", "Pfeffer", "Olivenöl"]),
    ("Salz und Pfeffer nach Geschmack", ["Salz und Pfeffer"]),
    ("Karotten, geschält und gewürfelt", ["Karotten"]),
    ("Oignon, émincé", ["Oignon"]),
    ("Sel, poivre, huile d'olive", ["Sel", "Poivre", "Huile d'olive"]),
    ("Sel et poivre au goût", ["Sel et poivre"]),
    ("Ail, 2 gousses, haché", ["Ail"]),
    ("Sel, petits pois", ["Sel", "Petits pois"]),
    ("Lök, finhackad", ["Lök"]),
    ("Salt, peppar, olivolja", ["Salt", "Peppar", "Olivolja"]),
    ("Salt och peppar efter smak", ["Salt och peppar"]),
    ("Morötter, skalade och tärnade", ["Morötter"]),
    ("Løg, finthakket", ["Løg"]),
    ("Salt, peber, olivenolie", ["Salt", "Peber", "Olivenolie"]),
    ("Salt og peber efter smag", ["Salt og peber"]),
    ("Gulerødder, skrællet og skåret i tern", ["Gulerødder"]),
    ("Salz, Pfeffer oder rote Chiliflocken", ["Salz", "Pfeffer oder rote Chiliflocken"]),
    ("Sal, pimienta si es necesario", ["Sal", "Pimienta"]),
    ("Tomates, pelées", ["Tomates"]),
]
for (raw, wanted) in tidyCases { expect(RecipeShopping.tidyNames(raw), wanted, "tidy \(raw)") }
let listDish = Recipe(title: "D", summary: "", minutes: 10, servings: 2, difficulty: .easy, ingredients: [
    RecipeIngredient(amount: "", name: "Salt, pepper, olive oil", fromPantry: false, staple: true),
    RecipeIngredient(amount: "", name: "salt", fromPantry: false),
], steps: ["Cook."], source: .server)
let listRows = RecipeShopping.candidates([listDish], listedNames: ["Pepper"])
expect(listRows.map(\.name), ["Salt", "Pepper", "Olive oil"], "a list in one ingredient is separate rows")
expect(listRows.map(\.alreadyListed), [false, true, false], "each row matched on its own")
expect(RecipeShopping.hasSomethingToAdd(listRows), true, "something to add")
expect(RecipeShopping.hasSomethingToAdd(RecipeShopping.candidates([listDish], listedNames: ["salt", "PEPPER", " olive oil"])), false, "everything listed: no button")
expect(RecipeShopping.hasSomethingToAdd([]), false, "nothing to buy: no button")
let shoppingDish = Recipe(title: "D", summary: "", minutes: 10, servings: 2, difficulty: .easy, ingredients: [
    RecipeIngredient(amount: "200 g", name: "pasta", fromPantry: true, pantryIndex: 0),
    RecipeIngredient(amount: "2 tbsp", name: "olive oil", fromPantry: false, staple: true),
    RecipeIngredient(amount: "1", name: "red onion, diced", fromPantry: false),
    RecipeIngredient(amount: "1 l", name: "water", fromPantry: false, staple: true),
    RecipeIngredient(amount: "", name: "Salt", fromPantry: false, staple: true),
    RecipeIngredient(amount: "1", name: "Lemon", fromPantry: false),
    RecipeIngredient(amount: "2", name: "red onion", fromPantry: false),
], steps: ["Cook."], source: .server)
let rows = RecipeShopping.candidates([shoppingDish], listedNames: ["  LEMON "])
expect(rows.map(\.name), ["Red onion", "Lemon", "Olive oil", "Salt"], "rows")
expect(rows.map(\.alreadyListed), [false, true, false, false], "already listed")
expect(RecipeShopping.defaultSelection(rows), ["red onion", "olive oil", "salt"], "ticked by default")
let unmarked = Recipe(title: "D", summary: "", minutes: 10, servings: 2, difficulty: .easy, ingredients: [RecipeIngredient(amount: "1 l", name: "Water", fromPantry: false)], steps: ["Cook."], source: .device)
expect(RecipeShopping.candidates([unmarked], listedNames: []).map(\.name), ["Water"], "without the mark water is listed")

// Every entry of the word lists, each in a case whose result depends on that entry alone (the
// same loops as Android's RecipeConfigChoicesTest.everyOrWord... / everyToTasteTail... /
// everyNoteWordAndStem... / everyWaterWord...).
for or in ["or", "или", "eller", "o", "u", "oder", "ou"] {
    expect(RecipeShopping.tidyNames("Salt, chilli \(or) sweet paprika"), ["Salt", "Chilli \(or) sweet paprika"], "or word \(or)")
}
let tasteTails = [
    "to taste", "as needed", "if needed", "optional", "по вкусу", "по желанию", "etter smak", "etter behov",
    "valgfritt", "al gusto", "a gusto", "opcional", "si es necesario", "nach geschmack", "nach belieben",
    "nach bedarf", "selon goût", "au goût", "à volonté", "facultatif", "si nécessaire", "efter smak",
    "efter behov", "valfritt", "efter smag", "valgfrit"
]
for tail in tasteTails {
    expect(RecipeShopping.tidyNames("Pepper \(tail)"), ["Pepper"], "tail \(tail)")
    expect(RecipeShopping.tidyNames("Pepper \(tail.uppercased())"), ["Pepper"], "tail \(tail) upper case")
}
let noteWords = [
    "sliced", "chopped", "minced", "diced", "peeled", "grated", "crushed", "cubed", "halved", "quartered",
    "drained", "rinsed", "washed", "trimmed", "beaten", "melted", "softened", "shredded", "julienned",
    "divided", "zest", "deseeded", "seeded", "pitted", "cored", "stemmed", "torn", "zested", "juiced",
    "squeezed", "thinly", "finely", "roughly", "coarsely", "cut", "into", "taste", "optional", "needed",
    "such", "like", "for", "to", "about", "plus", "at", "room", "temperature", "serving", "garnish",
    "preferably", "boneless", "skinless", "ripe", "large", "small", "medium", "thick", "thin", "whole",
    "cooked", "uncooked", "по", "для", "без", "очень", "кубиками", "ломтиками", "кольцами", "соломкой",
    "полосками", "дольками", "i", "til", "etter", "uten", "hakket", "skivet", "skiver", "revet", "finrevet",
    "skrelt", "presset", "knust", "smeltet", "delt", "terninger", "strimler", "båter", "smak", "fint",
    "grovt", "tynt", "tynne", "store", "stor", "små", "liten", "moden", "modne", "romtemperert", "servering",
    "pynt", "en", "para", "sin", "al", "gusto", "rodajas", "dados", "tiras", "trozos", "cubos", "finamente",
    "fino", "fina", "grande", "grandes", "pequeño", "pequeña", "pequeños", "pequeñas", "mediano", "mediana",
    "maduro", "madura", "maduros", "maduras", "temperatura", "ambiente", "servir", "decorar",
    "aproximadamente", "opcional", "cocido", "cocida", "in", "für", "ohne", "zum", "zur", "fein", "grob",
    "scheiben", "würfel", "würfeln", "streifen", "stücke", "stücken", "ringe", "ringen", "geschmack",
    "belieben", "groß", "große", "großer", "klein", "kleine", "kleiner", "reif", "reife", "zimmertemperatur",
    "servieren", "garnieren", "etwa", "ca", "gekocht", "gekochte", "pour", "sans", "dés", "tranches",
    "rondelles", "lamelles", "morceaux", "finement", "grossièrement", "goût", "volonté", "facultatif", "mûr",
    "mûre", "mûrs", "mûres", "température", "ambiante", "décorer", "environ", "cuit", "cuite", "till",
    "efter", "utan", "hackad", "hackade", "skivad", "skivade", "skivor", "riven", "rivna", "skalad",
    "skalade", "pressad", "pressade", "krossad", "krossade", "smält", "smälta", "delad", "delade", "tärnad",
    "tärnade", "tärningar", "strimlor", "klyftor", "tunt", "tunna", "stora", "mogen", "mogna",
    "rumstempererad", "rumstempererat", "garnering", "kokt", "kokta", "uden", "groft", "skrællet",
    "skrællede", "tern", "både", "smag", "tyndt", "tynde", "lille", "stuetemperatur", "kogt", "kogte"
]
let noteStems = [
    "нарез", "мелк", "крупн", "тонк", "очищ", "натер", "натёр", "тёрт", "терт", "измельч", "рублен",
    "порублен", "нашинк", "размят", "растоп", "размягч", "охлажд", "комнатн", "вкус", "желани", "спел",
    "отвар", "finhakk", "grovhakk", "valgfri", "picad", "cortad", "rallad", "pelad", "trocead", "escurrid",
    "derretid", "machacad", "lavad", "deshuesad", "gehackt", "geschnitt", "gewürfelt", "gerieb", "geschält",
    "halbiert", "geviertelt", "zerdrück", "geschmolz", "gepresst", "abgetropf", "gewaschen", "entkernt",
    "zerlassen", "hach", "éminc", "coupé", "râpé", "éplu", "pelé", "écras", "égoutt", "fondu", "ciselé",
    "épépin", "dénoyaut", "finhack", "grovhack", "finriv", "valfri", "finthakk", "grofthakk", "skåre"
]
// Also in the lists, but each starts with a stem above, which is what makes it a note.
let coveredByAStem = ["finhakket", "grovhakket", "valfritt", "finthakket", "skåret", "valgfrit", "coupée"]
for word in noteWords + noteStems + coveredByAStem {
    expect(RecipeShopping.tidyNames("Salt, \(word) kale"), ["Salt"], "note word \(word)")
}
expect(RecipeShopping.tidyNames("Salt, curly kale"), ["Salt", "Curly kale"], "without a note word the part is an item")
for water in ["water", "vann", "vannet", "wasser", "agua", "eau", "acqua", "woda", "wody", "wodę", "vatten", "вода", "воды", "воду", "води", "кипяток", "окріп", "vand", "vandet"] {
    expect(RecipeShopping.isPlainWater("Cold \(water)"), true, "water \(water)")
    let waterDish = Recipe(title: "D", summary: "", minutes: 10, servings: 2, difficulty: .easy, ingredients: [RecipeIngredient(amount: "1 l", name: water, fromPantry: false, staple: true)], steps: ["Cook."], source: .server)
    expect(RecipeShopping.candidates([waterDish], listedNames: []).map(\.name), [], "staple water \(water) is left out")
}
expect(RecipeShopping.isPlainWater("Cold milk"), false, "milk is not water")

// The app's languages map to recipe languages; Danish among them (sent to the server only once it
// says acceptsDanish, RecipeViewModel).
for code in ["en", "ru", "es", "de", "fr", "sv", "da"] {
    expect(RecipeLanguage.app(preferredLocalizations: [code]).rawValue, code, "app language \(code)")
}
expect(RecipeLanguage.app(preferredLocalizations: ["no"]), .nb, "Norwegian")
expect(RecipeLanguage.app(preferredLocalizations: ["da-DK"]), .da, "Danish with a region")
expect(RecipeLanguage.da.endonym, "Dansk", "Danish endonym")
expect(RecipeLanguage.da.englishName, "Danish", "Danish in English")
// The whole list, and every language's English name for the phone's model (Android's
// recipeLanguageEnglishName); none falls back to English.
expect(RecipeLanguage.allCases.map(\.rawValue), ["en", "nb", "ru", "de", "es", "fr", "it", "pl", "sv", "uk", "da"], "recipe languages")
expect(RecipeLanguage.allCases.map(\.englishName), ["English", "Norwegian Bokmål", "Russian", "German", "Spanish", "French", "Italian", "Polish", "Swedish", "Ukrainian", "Danish"], "English names")

// The phone's own model follows the chosen recipe language (Android's RecipeStateHolderTest,
// onDeviceRouteFollowsTheModelAndTheLanguage and friends).
expect(OnDeviceRecipeAvailability.of(.ready, writesLanguage: true), .available, "ready in a language it writes")
expect(OnDeviceRecipeAvailability.of(.ready, writesLanguage: false), .unsupportedLanguage, "ready, but not in this language")
expect(OnDeviceRecipeAvailability.of(.preparing, writesLanguage: true), .preparing, "still downloading")
expect(OnDeviceRecipeAvailability.of(.preparing, writesLanguage: false), .unsupportedLanguage, "downloading is not promised for a language it can't write")
expect(OnDeviceRecipeAvailability.of(.none, writesLanguage: true), .unsupported, "no model")
expect(OnDeviceRecipeAvailability.of(.none, writesLanguage: false), .unsupported, "no model says nothing about the language")

// Menu sets (days 1-4, courses a day 1-3): prices only from the server's `menuSets`, offered and
// sent only after `acceptsMenuDays` (Android's MenuSetTest).
let serverSets = #"{"recipe":1,"menu2":4,"menu3":5,"menuSets":{"1":{"1":1,"2":2,"3":3},"2":{"1":2,"2":3,"3":4},"3":{"1":3,"2":4,"3":5},"4":{"1":4,"2":5,"3":6}}}"#
let withSets = try! JSONDecoder().decode(RecipeCosts.self, from: Data(serverSets.utf8))
expect(withSets.menuPrice(days: 1, courses: 1), 1, "1 day, 1 course")
expect(withSets.menuPrice(days: 4, courses: 3), 6, "4 days, 3 courses")
expect(withSets.cost(of: .menu, courses: 2, days: 2), 3, "2 days, 2 courses")
expect(withSets.cost(of: .menu, courses: 3), 5, "3 days by default")
let olderServer = try! JSONDecoder().decode(RecipeCosts.self, from: Data(#"{"recipe":1,"menu2":4,"menu3":6}"#.utf8))
expect(olderServer.menuSets, nil, "an older server sends no menuSets")
expect(olderServer.menuPrice(days: 3, courses: 3), 6, "3 days from menu3")
expect(olderServer.menuPrice(days: 2, courses: 2), nil, "no price for 2 days")
expect(olderServer.menuPrice(days: 3, courses: 1), nil, "no flat price for one course")
// A table cached by the previous version (no menuSets) keeps decoding; the new one round-trips.
let cachedOld = try! JSONDecoder().decode(RecipeCosts.self, from: try! JSONEncoder().encode(RecipeCosts(recipe: 2, menu2: 4, menu3: 5)))
expect(cachedOld, RecipeCosts(recipe: 2, menu2: 4, menu3: 5), "an old cached table")
expect(try! JSONDecoder().decode(RecipeCosts.self, from: try! JSONEncoder().encode(withSets)), withSets, "a cached table with menu sets")
let odd = try! JSONDecoder().decode(RecipeCosts.self, from: Data(#"{"recipe":1,"menu2":4,"menu3":5,"menuSets":{"2":{"2":"x","3":0,"1":2},"9":{"2":3},"x":{}}}"#.utf8))
expect(odd.menuSets, ["2": ["1": 2]], "unreadable menu-set entries are left out")
let broken = try! JSONDecoder().decode(RecipeCosts.self, from: Data(#"{"recipe":1,"menu2":4,"menu3":5,"menuSets":[1,2]}"#.utf8))
expect(broken.menuSets, nil, "an unreadable menuSets is absent")
expect(RecipeCosts.fallback.menuPrice(days: 1, courses: 2), nil, "the fallback is 3-day only")
// The packs text's range (Android's menuSetRange), and prices above 100 not believed.
expect(withSets.menuSetRange, 1...6, "range over menuSets with menu2 / menu3")
expect(olderServer.menuSetRange, nil, "no table, no range: the 3-day wording")
expect(RecipeCosts(recipe: 1, menu2: 4, menu3: 9, menuSets: ["1": ["2": 2]]).menuSetRange, 2...9, "menu2 / menu3 count in the range")
let dear = try! JSONDecoder().decode(RecipeCosts.self, from: Data(#"{"recipe":101,"menu2":4,"menu3":5,"menuSets":{"1":{"1":101,"2":100}}}"#.utf8))
expect(dear.recipe, 1, "a recipe price above 100 takes the fallback")
expect(dear.menuSets, ["1": ["2": 100]], "a menu-set price above 100 is left out")

// Effective choice and what the form offers.
expect(MenuSet.effective(days: 1, courses: 1, takesMenuDays: false, costs: withSets), MenuSet(days: 3, courses: 2), "no capability: 3 days, one course is two")
expect(MenuSet.effective(days: 2, courses: 3, takesMenuDays: false, costs: withSets), MenuSet(days: 3, courses: 3), "no capability keeps three courses")
expect(MenuSet.effective(days: 1, courses: 1, takesMenuDays: true, costs: withSets), MenuSet(days: 1, courses: 1), "1 day x 1 course")
expect(MenuSet.effective(days: 2, courses: 2, takesMenuDays: true, costs: olderServer), MenuSet(days: 3, courses: 2), "unpriced days fall back to 3")
expect(MenuSet.effective(days: 2, courses: 1, takesMenuDays: true, costs: olderServer), MenuSet(days: 3, courses: 2), "unpriced one course falls back to two")
expect(MenuSet.dayOptions(for: MenuSet(days: 3, courses: 2), takesMenuDays: false, costs: withSets), [], "Days hidden without the capability")
expect(MenuSet.courseOptions(for: MenuSet(days: 3, courses: 2), takesMenuDays: false, costs: withSets), [2, 3], "two and three without it")
expect(MenuSet.dayOptions(for: MenuSet(days: 3, courses: 1), takesMenuDays: true, costs: withSets), [1, 2, 3, 4], "every day count priced")
expect(MenuSet.courseOptions(for: MenuSet(days: 1, courses: 2), takesMenuDays: true, costs: withSets), [1, 2, 3], "every course count priced")
let partial = RecipeCosts(recipe: 1, menu2: 4, menu3: 5, menuSets: ["1": ["2": 2], "3": ["1": 3, "2": 4, "3": 5]])
expect(MenuSet.dayOptions(for: MenuSet(days: 3, courses: 3), takesMenuDays: true, costs: partial), [3], "a day count without a price isn't offered")
expect(MenuSet.dayOptions(for: MenuSet(days: 3, courses: 2), takesMenuDays: true, costs: partial), [1, 3], "priced for two courses")
expect(MenuSet.courseOptions(for: MenuSet(days: 1, courses: 2), takesMenuDays: true, costs: partial), [2], "one day priced only for two")

// The request's shape: `days` only when not 3 and only with the capability; one course likewise.
let menuRequest = RecipeRequest(items: [RecipeIngredientInput(name: "Milk", daysRemaining: 1, expiryText: "")], config: RecipeConfig(), allergens: [])
func wire(_ courses: Int, _ days: Int, _ takes: Bool, kind: RecipeKind = .menu) -> GenerateRequest {
    RecipeWire.request(for: menuRequest, kind: kind, courses: courses, days: days, requestID: UUID(), menuDays: takes)!
}
expect(wire(2, 3, true).days, nil, "3 days is left out")
expect(wire(2, 1, true).days, 1, "1 day is sent")
expect(wire(3, 4, true).days, 4, "4 days is sent")
expect(wire(2, 2, false).days, nil, "no days without the capability")
expect(wire(1, 3, true).courses, 1, "one course with the capability")
expect(wire(1, 3, false).courses, 2, "one course is never sent without it")
expect(wire(2, 2, true, kind: .recipe).days, nil, "a recipe has no days")
expect(wire(2, 2, true, kind: .recipe).courses, nil, "a recipe has no courses")
let encoded = String(decoding: try! JSONEncoder().encode(wire(2, 3, true)), as: UTF8.self)
expect(encoded.contains("\"days\""), false, "a 3-day request is the legacy shape")
expect(MenuSet.needsMenuDays(wire(2, 3, true)), false, "legacy request: a refusal keeps the flag")
expect(MenuSet.needsMenuDays(wire(2, 1, true)), true, "days: a refusal clears the flag")
expect(MenuSet.needsMenuDays(wire(1, 3, true)), true, "one course: a refusal clears the flag")
expect(withSets.cost(of: wire(1, 1, true)), 1, "a request's cost by its days and courses")
expect(withSets.cost(of: wire(3, 3, true)), 5, "a 3-day request's cost")
// A request stored by the previous version (no days) still decodes, as 3 days.
var storedRequest = try! JSONSerialization.jsonObject(with: try! JSONEncoder().encode(wire(2, 1, true))) as! [String: Any]
storedRequest.removeValue(forKey: "days")
let restored = try! JSONDecoder().decode(GenerateRequest.self, from: try! JSONSerialization.data(withJSONObject: storedRequest))
expect(restored.days, nil, "an old stored request")
expect(olderServer.cost(of: restored), 4, "priced as 3 days")

// A menu saved before menu sets (three days, no new fields) still loads.
let oldMenu = #"{"days":[{"date":"2026-11-01","uses":[],"courses":[]},{"date":"2026-11-02","uses":[],"courses":[]},{"date":"2026-11-03","uses":[],"courses":[]}]}"#
expect(try! JSONDecoder().decode(RecipeMenu.self, from: Data(oldMenu.utf8)).days.count, 3, "an old saved menu")

// Unread saved recipes: the header's badge.
var unread = Set<String>()
unread = RecipeUnread.afterSave(unread, id: "a", shown: true)
expect(unread, [], "shown on arrival is read")
unread = RecipeUnread.afterSave(unread, id: "b", shown: false)
unread = RecipeUnread.afterSave(unread, id: "c", shown: false)
expect(RecipeUnread.count(unread, savedIDs: ["a", "b", "c"]), 2, "two unread")
expect(RecipeUnread.count(unread, savedIDs: ["a", "b"]), 1, "only ids still saved count")
unread = RecipeUnread.afterOpen(unread, id: "b")
expect(unread, ["c"], "opened is read")
expect(RecipeUnread.pruned(unread, savedIDs: []), [], "deleted ones go")

// The Buy sheet's saving badge, from the store's prices only (Android: the same cases).
func offers(_ currency: String?, _ list: [(Int, String?)]) -> [PackOffer] {
    list.map { PackOffer(recipes: $0.0, price: $0.1.flatMap { Decimal(string: $0) }, currencyCode: currency) }
}
expect(PackSavings.percents(offers("GBP", [(10, "2.99"), (50, "10.99"), (100, "18.99")])), [nil, 26, 36], "10 for 2.99")
expect(PackSavings.percents(offers("GBP", [(10, "3.99"), (50, "10.99"), (100, "18.99")])), [nil, 44, 52], "10 for 3.99: 44, not 45")
expect(PackSavings.percents(offers("GBP", [(10, "1.99"), (50, "10.99"), (100, "18.99")])), [nil, nil, nil], "dearer, then only 4 %")
expect(PackSavings.percents(offers("NOK", [(10, "35"), (50, "129"), (100, "219")])), [nil, 26, 37], "NOK 35 / 129 / 219")
expect(PackSavings.percents(offers("GBP", [(10, "4.00"), (50, "14.80")])), [nil, 26], "exactly 26 % stays 26")
expect(PackSavings.percents(offers("GBP", [(10, "1.00"), (50, "3.55")])), [nil, 29], "exactly 29 % stays 29")
expect(PackSavings.percents(offers("GBP", [(10, "1.00"), (50, "4.75")])), [nil, 5], "exactly 5 % shows")
expect(PackSavings.percents(offers("GBP", [(10, "1.00"), (50, "4.76")])), [nil, nil], "4.8 % doesn't")
expect(PackSavings.percents(offers("GBP", [(10, "2.99")])), [nil], "one pack")
expect(PackSavings.percents([]), [], "no packs")
expect(PackSavings.percents(offers("GBP", [(100, "18.99"), (10, "2.99"), (50, "10.99")])), [36, nil, 26], "in the order given")
let mixed = [PackOffer(recipes: 10, price: Decimal(string: "2.99"), currencyCode: "GBP"),
             PackOffer(recipes: 50, price: Decimal(string: "10.99"), currencyCode: "EUR"),
             PackOffer(recipes: 100, price: Decimal(string: "18.99"), currencyCode: "EUR")]
expect(PackSavings.percents(mixed), [nil, nil, nil], "mixed currencies")
expect(PackSavings.percents(offers(nil, [(10, "2.99"), (50, "10.99")])), [nil, nil], "no currency")
expect(PackSavings.percents(offers("GBP", [(10, nil), (50, "10.99"), (100, "18.99")])), [nil, nil, 13], "no price: the next pack is the base")
expect(PackSavings.percents(offers("GBP", [(10, "0"), (50, "10.99"), (100, "18.99")])), [nil, nil, 13], "a free pack isn't the base")
expect(PackSavings.percents(offers("GBP", [(10, "2.99"), (50, "0"), (100, "18.99")])), [nil, nil, 36], "a free larger pack gets none")
expect(PackSavings.badgeText(26), "\u{2212}26%", "minus sign, no space")

if failures > 0 {
    print("\(failures) failed")
    exit(1)
}
print("RecipeConfigCheck: all passed")
