import Foundation

// Checks the iOS ports of the multi-choice recipe config, its legacy wire mapping, the exact
// past-date warnings, the shopping-list rows and the taste profile against the same cases as Android's
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
expect(RecipeUnread.afterDelete(["a", "b", "c"], ids: ["a", "c", "gone"]), ["b"], "several deleted at once leave the unread ones")

// Saved recipes' multi-select (Android: SavedSelectionTest, the same cases).
let savedIDs = ["c", "b", "a"]
expect(SavedSelection.toggled([], id: "a"), ["a"], "a tap selects")
expect(SavedSelection.toggled(["a"], id: "b"), ["a", "b"], "another tap adds")
expect(SavedSelection.toggled(["a", "b"], id: "a"), ["b"], "a tap on a selected row clears it")
expect(SavedSelection.allSelected([], savedIDs: savedIDs), false, "nothing selected")
expect(SavedSelection.allSelected(["a", "b"], savedIDs: savedIDs), false, "some selected")
expect(SavedSelection.allSelected(["a", "b", "c"], savedIDs: savedIDs), true, "all selected")
expect(SavedSelection.allSelected(["a", "b", "c", "gone"], savedIDs: savedIDs), true, "a stale id doesn't count against")
expect(SavedSelection.allSelected([], savedIDs: []), false, "an empty list is never all selected")
expect(SavedSelection.toggledAll(["b"], savedIDs: savedIDs), ["a", "b", "c"], "Select all")
expect(SavedSelection.toggledAll(["a", "b", "c"], savedIDs: savedIDs), [], "Deselect all")
expect(SavedSelection.pruned(["a", "gone"], savedIDs: savedIDs), ["a"], "ids that leave the list leave the selection")
expect(SavedSelection.pruned(["a"], savedIDs: []), [], "an emptied list empties it")
let savedRows = savedIDs.enumerated().map { index, id in
    SavedRecipe(id: id, createdAt: Date(timeIntervalSince1970: Double(10 - index)), content: .recipe(Recipe(title: id, summary: "", minutes: 10, servings: 2, difficulty: .easy, ingredients: [], steps: ["s"], source: .server)))
}
expect(SavedSelection.toDelete(savedRows, selected: ["a", "c", "gone"]).map(\.id), ["c", "a"], "Delete removes the selected rows still listed, in list order")
expect(SavedSelection.toDelete(savedRows, selected: []).map(\.id), [], "nothing selected, nothing removed")

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

// The menu form's "few items" hint (Android's MenuItemsHintTest): fewer usable items than half
// the dishes, rounded up, with something ticked; items "Send all products" would send count.
expect(MenuSet(days: 1, courses: 1).itemsHint(ticked: 1, optional: 0), nil, "1 x 1 never")
expect(MenuSet(days: 1, courses: 3).itemsHint(ticked: 1, optional: 0), MenuItemsHint(items: 1, dishes: 3), "1 x 3 with 1")
expect(MenuSet(days: 1, courses: 3).itemsHint(ticked: 2, optional: 0), nil, "1 x 3 with 2")
expect(MenuSet(days: 3, courses: 2).itemsHint(ticked: 2, optional: 0), MenuItemsHint(items: 2, dishes: 6), "3 x 2 with 2")
expect(MenuSet(days: 3, courses: 2).itemsHint(ticked: 3, optional: 0), nil, "3 x 2 with 3")
expect(MenuSet(days: 4, courses: 3).itemsHint(ticked: 5, optional: 0), MenuItemsHint(items: 5, dishes: 12), "4 x 3 with 5")
expect(MenuSet(days: 4, courses: 3).itemsHint(ticked: 6, optional: 0), nil, "4 x 3 with 6")
expect(MenuSet(days: 4, courses: 3).itemsHint(ticked: 2, optional: 10), nil, "4 x 3 with 2 ticked and 10 sent with them")
expect(MenuSet(days: 4, courses: 3).itemsHint(ticked: 2, optional: 3), MenuItemsHint(items: 5, dishes: 12), "4 x 3 with 2 ticked and 3 sent")
expect(MenuSet(days: 4, courses: 3).itemsHint(ticked: 0, optional: 10), nil, "nothing ticked has its own hint")
// The menu set actually asked for: 4 x 3 chosen from a server without menu sets is 3 x 3.
expect(MenuSet.effective(days: 4, courses: 3, takesMenuDays: false, costs: withSets).itemsHint(ticked: 4, optional: 0), MenuItemsHint(items: 4, dishes: 9), "the fallback pair's dishes")

// Taste profile: the server's quiz definition, decoded leniently, and the rules on it (Android:
// TasteProfileTest, the same cases).
func quiz(_ json: String) -> TasteQuiz? { TasteQuiz.decoded(from: Data(json.utf8)) }
func opts(_ ids: [String]) -> String { ids.map { #"{"id":"\#($0)","label":"\#($0.capitalized)"}"# }.joined(separator: ",") }
func multi(_ id: String, _ ids: [String], max: Int, tone: String = "like", extra: String = "") -> String {
    #"{"id":"\#(id)","kind":"multi","tone":"\#(tone)","icon":"globe","question":"\#(id)?","max":\#(max),"groups":[{"title":null,"options":[\#(opts(ids))]}]\#(extra)}"#
}
let heatStep = #"{"id":"heat","kind":"single","tone":"like","icon":"flame","question":"Hot?","max":1,"groups":[{"options":[{"id":"mild","label":"Mild","sub":"Little","level":1},{"id":"medium","label":"Medium","level":2},{"id":"hot","label":"Hot","level":9}]}]}"#
func definition(_ steps: [String], conflicts: String = #"[["favourites:mushrooms","avoid:mushrooms"],["flavours:garlic","avoid:garlic"]]"#, version: String = "v1") -> String {
    #"{"version":"\#(version)","language":"en","steps":[\#(steps.joined(separator: ","))],"conflicts":\#(conflicts),"summary":["cuisines","flavours","favourites","methods"]}"#
}
let baseSteps = [
    multi("cuisines", ["italian", "french", "spanish", "greek", "nordic", "thai", "korean"], max: 6),
    heatStep,
    multi("flavours", ["garlic", "citrus", "umami"], max: 6),
    multi("favourites", ["tomatoes", "mushrooms", "eggs", "berries"], max: 3),
    multi("avoid", ["onion", "garlic", "mushrooms", "olives"], max: 8, tone: "avoid"),
    multi("methods", ["oven", "pan"], max: 5),
]
let q = quiz(definition(baseSteps))!
expect(q.steps.map(\.id), ["cuisines", "heat", "flavours", "favourites", "avoid", "methods"], "steps in order")
expect(q.step("heat")?.option("hot")?.level, 3, "a level past 3 is 3")
expect(q.step("heat")?.option("mild")?.sub, "Little", "a card's second line")
expect(q.step("avoid")?.tone, .avoid, "tone")
expect(q.conflicts.count, 2, "conflicts read")

// Odd definitions: an unknown kind, an unknown field, an unknown icon, no options, a seventh step.
let oddQuiz = quiz(definition(baseSteps + [
    #"{"id":"mood","kind":"slider","question":"?","max":1,"groups":[{"options":[{"id":"a","label":"A"}]}]}"#,
    #"{"id":"empty","kind":"multi","question":"?","max":2,"groups":[{"title":"x","options":[]}]}"#,
    #"{"id":"moods","kind":"multi","tone":"curious","question":"?","max":1,"groups":[{"options":[{"id":"a","label":"A"}]}]}"#,
    #"{"id":"meals","kind":"multi","icon":"no-such-icon","question":"Which meals?","max":3,"colour":"red","groups":[{"title":null,"options":[{"id":"breakfast","label":"Breakfast","shiny":true},{"label":"no id"},{"id":"snacks","label":"Snacks"}]}]}"#,
    #"{"id":"cuisines","kind":"multi","question":"repeat","max":1,"groups":[{"options":[{"id":"z","label":"Z"}]}]}"#,
    "42",
], conflicts: #"[["favourites:eggs"],["nonsense","avoid:onion"],["methods:oven","methods:pan"]]"#))!
expect(oddQuiz.steps.map(\.id), ["cuisines", "heat", "flavours", "favourites", "avoid", "methods", "meals"], "unknown kind or tone, no options, a repeat and junk skipped; a seventh kept")
expect(oddQuiz.step("meals")?.options.map(\.id), ["breakfast", "snacks"], "an option without an id is skipped")
expect(oddQuiz.step("meals")?.tone, .like, "no tone reads as like")
expect(oddQuiz.step("moods") == nil, true, "an unknown tone skips the step")
expect(oddQuiz.step("meals")?.icon, "no-such-icon", "an unknown icon is kept (the view falls back)")
expect(oddQuiz.conflicts.count, 1, "malformed conflicts skipped")
expect(oddQuiz.step("cuisines")?.question, "cuisines?", "the first of a repeated id")
expect(quiz(#"{"version":"x","steps":[]}"#) == nil, true, "no usable step: couldn't load")
expect(quiz(#"{"version":"x","steps":[\#(#"{"id":"m","kind":"slider","question":"?","max":1,"groups":[]}"#)]}"#) == nil, true, "only unknown steps: couldn't load")
expect(quiz("not json") == nil, true, "undecodable: couldn't load")
expect(TasteQuiz.afterFetch(nil, cached: q), q, "undecodable with a good cache: the cache")
expect(TasteQuiz.afterFetch(nil, cached: nil), nil, "undecodable without a cache: the error")
expect(TasteQuiz.afterFetch(oddQuiz, cached: q), oddQuiz, "a good fetch wins")
let roundTrip = try! JSONDecoder().decode(TasteQuiz.self, from: try! JSONEncoder().encode(oddQuiz))
expect(roundTrip, oddQuiz, "a cached definition reads back the same")

// Toggling, caps and conflicts.
let cuisines = q.step("cuisines")!, heat = q.step("heat")!, flavours = q.step("flavours")!
let favourites = q.step("favourites")!, avoid = q.step("avoid")!
var t = TasteProfile()
t = t.toggled(cuisines, "nordic", in: q).toggled(cuisines, "italian", in: q)
expect(t.selected("cuisines"), ["italian", "nordic"], "display order")
expect(t.toggled(cuisines, "nordic", in: q).selected("cuisines"), ["italian"], "a chosen one goes")
expect(t.toggled(cuisines, "vulcan", in: q), t, "an unknown option changes nothing")
var full = TasteProfile()
for id in ["italian", "french", "spanish", "greek", "nordic", "thai"] { full = full.toggled(cuisines, id, in: q) }
expect(full.selected("cuisines").count, 6, "up to max")
expect(full.toggled(cuisines, "korean", in: q), full, "past max changes nothing")
expect(full.canToggle(cuisines, "korean"), false, "dimmed at max")
expect(full.canToggle(cuisines, "thai"), true, "a chosen one still comes off")
expect(TasteProfile().toggled(heat, "mild", in: q).toggled(heat, "hot", in: q).selected("heat"), ["hot"], "single replaces")
expect(TasteProfile(answers: ["heat": ["hot"]]).toggled(heat, "hot", in: q).selected("heat"), [], "tapping the chosen card clears it")
let likes = TasteProfile().toggled(favourites, "mushrooms", in: q).toggled(favourites, "eggs", in: q)
expect(likes.conflictChosen(avoid, "mushrooms", in: q), TasteQuiz.Choice(step: "favourites", option: "mushrooms"), "marked on the skip step")
let skips = likes.toggled(avoid, "mushrooms", in: q)
expect(skips.selected("favourites"), ["eggs"], "moved off what you love")
expect(skips.toggled(favourites, "mushrooms", in: q).selected("avoid"), [], "and back")
expect(TasteProfile().toggled(flavours, "garlic", in: q).toggled(avoid, "garlic", in: q).selected("flavours"), [], "garlic flavour against garlic to skip")
expect(skips.conflictChosen(favourites, "eggs", in: q), nil, "no mark without a conflict")

// Shown and sent: the stored answers filtered through the definition, never rewritten.
let oldAnswers = TasteProfile(answers: [
    "cuisines": ["thai", "italian", "thai", "french", "spanish", "greek", "nordic", "korean", "vulcan"],
    "heat": ["medium"], "favourites": ["mushrooms", "eggs"], "avoid": ["mushrooms"], "gone": ["x"],
], version: "v0")
let seenBefore: Set<String> = ["cuisines", "heat", "flavours", "favourites", "avoid", "methods"]
let shown = oldAnswers.filtered(by: q)
expect(shown.selected("cuisines"), ["italian", "french", "spanish", "greek", "nordic", "thai"], "known, unique, display order, cut to max")
expect(shown.selected("favourites"), ["eggs"], "a conflict goes to the avoid side")
expect(shown.selected("gone"), [], "a step the definition doesn't have is left out")
expect(shown.version, "v0", "filtering doesn't restamp")
// Option removed, step removed, option hidden then back, cap lowered, kind changed, conflict added.
let v2 = quiz(definition([
    multi("cuisines", ["italian", "french", "nordic"], max: 2),
    #"{"id":"heat","kind":"multi","question":"Hot?","max":3,"groups":[{"options":[{"id":"mild","label":"Mild"},{"id":"medium","label":"Medium"}]}]}"#,
    multi("favourites", ["mushrooms", "eggs"], max: 3),
    multi("avoid", ["mushrooms"], max: 8, tone: "avoid"),
], conflicts: #"[["favourites:eggs","heat:medium"]]"#, version: "v2"))!
let underV2 = oldAnswers.filtered(by: v2)
expect(underV2.selected("cuisines"), ["italian", "french"], "options removed, cap lowered: cut in display order")
expect(underV2.selected("flavours"), [], "step removed")
expect(underV2.selected("favourites"), ["mushrooms", "eggs"], "a conflict added between two stored answers: neither avoids, so the second listed goes")
expect(underV2.selected("heat"), [], "the second listed")
expect(oldAnswers.filtered(by: q).selected("cuisines").contains("thai"), true, "hidden in v2, back in v1: chosen again")
expect(oldAnswers.answers["cuisines"]?.contains("korean"), true, "the stored answers are untouched")
let toSingle = quiz(definition([#"{"id":"cuisines","kind":"single","question":"?","max":6,"groups":[{"options":[{"id":"italian","label":"I"},{"id":"french","label":"F"}]}]}"#]))!
expect(oldAnswers.filtered(by: toSingle).selected("cuisines"), ["italian"], "kind changed to single: the first in display order")
let allGone = quiz(definition([multi("methods", ["oven"], max: 1)]))!
expect(oldAnswers.filtered(by: allGone).isEmpty, true, "every oldAnswers answer gone")
expect(oldAnswers.summary(allGone), nil, "then the row reads as without a profile")
expect(TasteProfile.toSend(oldAnswers, quiz: allGone, serverTakesTaste: true), nil, "and nothing is sent")
// Saving stores the filtered answers as edited, the new version, every step seen.
let resaved = oldAnswers.saved(from: v2)
expect(resaved.answers, underV2.answers, "Save stores what was shown")
expect(resaved.version, "v2", "Save stamps the version")
expect(TasteProfile.seenAfter(seenBefore, visited: ["meals"]), seenBefore.union(["meals"]), "seen after: what was seen plus what was visited")

// Review fixes (Android and the server do the same).
// 1. Conflicts are settled before the like steps are cut to their max.
let capThree = quiz(definition([
    multi("favourites", ["mushrooms", "tomatoes", "eggs", "berries"], max: 3),
    multi("avoid", ["mushrooms", "olives"], max: 8, tone: "avoid"),
]))!
let beforeCut = TasteProfile(answers: ["favourites": ["mushrooms", "tomatoes", "eggs", "berries"], "avoid": ["mushrooms"]])
expect(beforeCut.filtered(by: capThree).selected("favourites"), ["tomatoes", "eggs", "berries"], "the conflict goes first, then the cut")
let avoidCap = quiz(definition([
    multi("favourites", ["mushrooms", "eggs"], max: 3),
    multi("avoid", ["olives", "mushrooms"], max: 1, tone: "avoid"),
]))!
expect(TasteProfile(answers: ["favourites": ["mushrooms"], "avoid": ["olives", "mushrooms"]]).filtered(by: avoidCap).selected("favourites"), ["mushrooms"],
       "the avoid step is cut first, so a skip it no longer has doesn't take a favourite")
// 2. Save marks only the visited steps: with two new steps, Save on the first leaves one note.
let twoNew = quiz(definition(baseSteps + [multi("meals", ["breakfast"], max: 1), multi("drinks", ["tea"], max: 1)], version: "v4"))!
let seenEarly = TasteProfile.seenAfter(seenBefore, visited: ["meals"])
expect(TasteProfile.newSteps(seen: seenEarly, quiz: twoNew).map(\.id), ["drinks"], "Save on the first of two new steps: the other keeps its note")
// 3. The single step's label is never the named step's.
let singleFirst = quiz(#"{"version":"s","language":"en","steps":[\#(heatStep),\#(multi("cuisines", ["italian"], max: 2))],"summary":["heat","cuisines"]}"#)!
expect(settled(TasteProfile(answers: ["heat": ["hot"], "cuisines": ["italian"]]), singleFirst),
       TasteProfile.Summary(named: ["Hot"], single: nil, more: 1), "a single step named first isn't shown twice")
// 4. Without a definition the answers go within the wire's shape; a max never exceeds the options.
var wide: [String: [String]] = [:]
for i in 0..<20 { wide["step\(i)"] = ["a"] }
wide["step0"] = (0..<40).map { "o\($0)" } + ["o1", "", String(repeating: "x", count: 41)]
wide[String(repeating: "k", count: 41)] = ["a"]
let shaped = TasteProfile.toSend(TasteProfile(answers: wide), quiz: nil, serverTakesTaste: true)!
expect(shaped.count, 16, "at most 16 steps")
expect(shaped["step0"]?.count, 32, "at most 32 ids, repeats, blanks and long ids left out")
expect(shaped.keys.contains { $0.count > 40 }, false, "no key over 40 characters")
expect(quiz(definition([multi("few", ["a", "b"], max: 9)]))!.step("few")?.max, 2, "max clamped to the option count")
// 5. Undo of a move takes back only that move.
let moveQuiz = q
var draft = TasteProfile(answers: ["avoid": ["mushrooms"]])
let mushroomsHere = TasteQuiz.Choice(step: "favourites", option: "mushrooms")
let movedOff = draft.conflictChosen(favourites, "mushrooms", in: moveQuiz)!
draft = draft.toggled(favourites, "mushrooms", in: moveQuiz)
draft = draft.toggled(favourites, "eggs", in: moveQuiz).toggled(cuisines, "thai", in: moveQuiz)
draft = draft.undoneMove(quiz: moveQuiz, step: mushroomsHere.step, option: mushroomsHere.option, movedFrom: movedOff)
expect(draft.selected("favourites"), ["eggs"], "Undo takes off only the moved choice")
expect(draft.selected("avoid"), ["mushrooms"], "and puts it back where it was")
expect(draft.selected("cuisines"), ["thai"], "later choices stay")

// New questions.
let v3 = quiz(definition(baseSteps + [multi("meals", ["breakfast", "snacks"], max: 3)], version: "v3"))!
expect(TasteProfile.newSteps(seen: seenBefore, quiz: v3).map(\.id), ["meals"], "a step added since saving")
expect(TasteProfile.firstStep(seen: seenBefore, quiz: v3), 6, "the quiz opens at the first new step")
expect(TasteProfile.firstStep(seen: seenBefore, quiz: q), 0, "nothing new: the first step")
expect(TasteProfile.newSteps(seen: [], quiz: v3).count, 7, "never opened: every question is new")
expect(TasteProfile.isNewStep(v3.steps[0], seen: []), false, "but no \"New question\" tag on a first visit")
expect(TasteProfile.isNewStep(v3.steps[6], seen: seenBefore), true, "the tag on a step added since")

// The dot on Recipe settings (section 5).
let seenAll = Set(q.steps.map(\.id))
expect(TasteProfile.hasNewQuestions(entry: .quiz, seen: [], quiz: q), true, "recipes in a pack, never opened: the dot")
expect(TasteProfile.hasNewQuestions(entry: .promo, seen: [], quiz: q), false, "the promo case: never")
expect(TasteProfile.hasNewQuestions(entry: .hidden, seen: [], quiz: q), false, "no row: never")
expect(TasteProfile.hasNewQuestions(entry: .quiz, seen: [], quiz: nil), false, "no definition yet: no dot until it is fetched")
let openedAndLeft = TasteProfile.seenAfter([], visited: ["cuisines", "heat"])
expect(TasteProfile.hasNewQuestions(entry: .quiz, seen: openedAndLeft, quiz: q), true, "opened and left after two steps: the rest still new")
expect(TasteProfile.firstStep(seen: openedAndLeft, quiz: q), 2, "and the quiz reopens at the first not reached")
expect(TasteProfile.hasNewQuestions(entry: .quiz, seen: seenAll, quiz: q), false, "every step shown, saved or not: no dot")
expect(TasteProfile.hasNewQuestions(entry: .quiz, seen: seenAll, quiz: v3), true, "a step added after saving: the dot")
// Clearing the profile leaves the seen steps alone: they are stored apart and the profile carries none.
expect(String(decoding: try! JSONEncoder().encode(TasteProfile(answers: ["heat": ["hot"]])), as: UTF8.self).contains("seen"), false, "the profile stores no seen steps")
expect(TasteProfile.hasNewQuestions(entry: .quiz, seen: seenAll, quiz: q), false, "clearing doesn't bring the dot back")

// The quiet background fetch on a balance reply's tasteQuizVersion.
func prefetch(_ server: String?, cached: String?, entry: TasteProfile.Entry = .quiz, account: Bool = true, fetched: Bool = false) -> Bool {
    TasteProfile.shouldPrefetch(serverVersion: server, cachedVersion: cached, entry: entry, hasAccount: account, alreadyFetched: fetched)
}
expect(prefetch("v2", cached: "v1"), true, "a newer version: fetched")
expect(prefetch("v2", cached: nil), true, "nothing cached: fetched")
expect(prefetch("v1", cached: "v1"), false, "the cached one: not fetched")
expect(prefetch(nil, cached: "v1"), false, "an old server names none: unknown, not changed")
expect(prefetch("v2", cached: "v1", entry: .promo), false, "the promo case: never")
expect(prefetch("v2", cached: "v1", entry: .hidden), false, "no row: never")
expect(prefetch("v2", cached: "v1", account: false), false, "no server account: never (none is created)")
expect(prefetch("v2", cached: "v1", fetched: true), false, "once per version and language")
expect(try! JSONDecoder().decode(BalanceResponse.self, from: Data(#"{"balance":1,"tasteQuizVersion":"abc"}"#.utf8)).tasteQuizVersion?.value, "abc", "tasteQuizVersion read")
expect(try! JSONDecoder().decode(BalanceResponse.self, from: Data(#"{"balance":1,"tasteQuizVersion":7}"#.utf8)).tasteQuizVersion?.value, nil, "not a string: none, the reply still reads")
expect(TasteProfile.newSteps(seen: TasteProfile.seenAfter(seenBefore, visited: ["meals"]), quiz: v3).map(\.id), [], "leaving after seeing it clears the note")

/// The summary of `profile` with every step of `quiz` been through (no new-question note).
func settled(_ profile: TasteProfile, _ quiz: TasteQuiz) -> TasteProfile.Summary? {
    profile.summary(quiz)
}

// The summary: two labels from the first answered summary step, the first single step's label, N more.
let summed = TasteProfile(answers: ["cuisines": ["nordic", "italian"], "heat": ["medium"], "flavours": ["garlic", "citrus"],
                                    "favourites": ["eggs"], "avoid": ["olives"], "methods": ["oven"]])
expect(settled(summed, q), TasteProfile.Summary(named: ["Italian", "Nordic"], single: "Medium", more: 5), "Italian, Nordic · Medium · 5 more")
expect(settled(TasteProfile(answers: ["heat": ["mild"]]), q), TasteProfile.Summary(named: [], single: "Mild", more: 0), "heat alone")
expect(settled(TasteProfile(answers: ["flavours": ["umami", "garlic", "citrus"]]), q),
       TasteProfile.Summary(named: ["Garlic", "Citrus"], single: nil, more: 1), "no cuisines: the flavours are named")
expect(settled(TasteProfile(answers: ["avoid": ["olives", "onion"]]), q), TasteProfile.Summary(named: [], single: nil, more: 2), "the skip list counts, never named")

// The gate.
expect(TasteProfile.entry(paidRouteAvailable: false, serverTakesTaste: true, purchasesBlocked: false, balance: 40, hasSavedProfile: true), .hidden, "no paid route: no row")
expect(TasteProfile.entry(paidRouteAvailable: true, serverTakesTaste: false, purchasesBlocked: false, balance: 40, hasSavedProfile: true), .hidden, "no acceptsTaste: no row")
expect(TasteProfile.entry(paidRouteAvailable: true, serverTakesTaste: true, purchasesBlocked: false, balance: 3, hasSavedProfile: false), .quiz, "recipes in a pack: the quiz")
expect(TasteProfile.entry(paidRouteAvailable: true, serverTakesTaste: true, purchasesBlocked: false, balance: 0, hasSavedProfile: true), .quiz, "a saved profile: the quiz")
expect(TasteProfile.entry(paidRouteAvailable: true, serverTakesTaste: true, purchasesBlocked: false, balance: nil, hasSavedProfile: false), .promo, "never bought: the promo")
expect(TasteProfile.entry(paidRouteAvailable: true, serverTakesTaste: true, purchasesBlocked: true, balance: 0, hasSavedProfile: false), .hidden, "buying blocked, nothing to use: no row")
expect(TasteProfile.entry(paidRouteAvailable: true, serverTakesTaste: true, purchasesBlocked: true, balance: 5, hasSavedProfile: false), .quiz, "buying blocked with recipes: the quiz")

// The request: only answers, only with the capability, never anywhere else.
expect(TasteProfile.toSend(summed, quiz: q, serverTakesTaste: false), nil, "not without acceptsTaste")
expect(TasteProfile.toSend(TasteProfile(), quiz: q, serverTakesTaste: true), nil, "not when empty")
expect(TasteProfile.toSend(oldAnswers, quiz: q, serverTakesTaste: true)?["favourites"], ["eggs"], "filtered when sent")
expect(TasteProfile.toSend(oldAnswers, quiz: nil, serverTakesTaste: true)?["gone"], ["x"], "without a definition: as oldAnswers, for the server to clean")
func tasteWire(_ answers: [String: [String]]?, kind: RecipeKind = .recipe) -> GenerateRequest {
    RecipeWire.request(for: menuRequest, kind: kind, courses: 2, requestID: UUID(), taste: answers)!
}
func json(_ request: GenerateRequest) -> [String: Any] {
    try! JSONSerialization.jsonObject(with: try! JSONEncoder().encode(request)) as! [String: Any]
}
expect(json(tasteWire(nil))["taste"] == nil, true, "left out without answers")
let sentTaste = json(tasteWire(["cuisines": ["italian"], "heat": ["hot"]]))["taste"] as? [String: [String]]
expect(sentTaste ?? [:], ["cuisines": ["italian"], "heat": ["hot"]], "a map of step to option ids, a single step as a list of one")
expect(tasteWire(["heat": ["hot"]], kind: .menu).taste, ["heat": ["hot"]], "a menu carries it too")
expect(tasteWire(["heat": ["hot"]]).hasSameInputs(as: tasteWire(["heat": ["mild"]])), false, "the answers are an input")
var storedWithoutTaste = json(tasteWire(nil))
storedWithoutTaste.removeValue(forKey: "taste")
expect(try! JSONDecoder().decode(GenerateRequest.self, from: try! JSONSerialization.data(withJSONObject: storedWithoutTaste)).taste == nil, true,
       "a request oldAnswers before taste profiles")
expect(TasteProfile.clearsFlag(after: .invalidRequest, request: tasteWire(["heat": ["hot"]])), true, "invalid with taste: cleared")
expect(TasteProfile.clearsFlag(after: .invalidRequest, request: tasteWire(nil)), false, "invalid without taste: kept")
expect(TasteProfile.clearsFlag(after: .refusedName(field: "ingredients[0].name"), request: tasteWire(["heat": ["hot"]])), false, "a refused name: kept")

// The flag comes from every reply that carries a balance.
expect(try! JSONDecoder().decode(BalanceResponse.self, from: Data(#"{"balance":3,"acceptsTaste":true}"#.utf8)).acceptsTaste?.value, true, "acceptsTaste read")
expect(try! JSONDecoder().decode(GenerateResponse.self, from: Data(#"{"status":"DONE","balance":3,"charged":true,"acceptsTaste":true}"#.utf8)).acceptsTaste?.value, true, "on generateRecipe")
expect(try! JSONDecoder().decode(VerifyPurchaseResponse.self, from: Data(#"{"status":"GRANTED","balance":3,"acceptsTaste":true}"#.utf8)).acceptsTaste?.value, true, "on verifyPurchase")
expect(try! JSONDecoder().decode(MergeBalanceResponse.self, from: Data(#"{"balance":3,"moved":1,"acceptsTaste":true}"#.utf8)).acceptsTaste?.value, true, "on mergeBalance")
expect(try! JSONDecoder().decode(BalanceResponse.self, from: Data(#"{"balance":3,"acceptsTaste":"yes"}"#.utf8)).acceptsTaste?.value ?? false, false, "not a bool: no")

// Stored: field by field, anything unreadable as no answer.
let storedTaste = try! JSONDecoder().decode(TasteProfile.self, from: Data(#"{"answers":{"cuisines":["korean",7],"heat":"hot","x":3},"seenSteps":["cuisines"],"future":1}"#.utf8))
expect(storedTaste.answers, ["cuisines": ["korean"], "heat": ["hot"]], "a stored profile reads leniently")
expect(try! JSONDecoder().decode(TasteProfile.self, from: try! JSONEncoder().encode(oldAnswers)), oldAnswers, "round trip")

// Never in a free route: the shared prompt and the phone's model take a RecipeRequest, which has
// no place for the profile.
let freeRequest = RecipeRequest(items: [RecipeIngredientInput(name: "Milk", daysRemaining: 1, expiryText: "")], config: RecipeConfig(), allergens: ["Peanut"])
func mentionsTaste(_ value: Any) -> Bool { Mirror(reflecting: value).children.contains { ($0.label ?? "").lowercased().contains("taste") || String(describing: type(of: $0.value)).contains("Taste") } }
expect(mentionsTaste(tasteWire(["heat": ["hot"]])), true, "the check sees it where it is")
expect(mentionsTaste(freeRequest), false, "no taste in a RecipeRequest")
expect(mentionsTaste(RecipeConfig()), false, "no taste in RecipeConfig")
expect(String(decoding: try! JSONEncoder().encode(RecipeConfig()), as: UTF8.self).contains("taste"), false, "nor in a oldAnswers config")

// Free recipes for signing in (WelcomeRecipes), as Android's WelcomeRecipesTest.
let wNow = Date(timeIntervalSince1970: 1_800_000_000)
func wAnswer(_ n: Int, ageHours: Double) -> WelcomeOfferAnswer { WelcomeOfferAnswer(recipes: n, receivedAt: wNow.addingTimeInterval(-ageHours * 3600)) }
func wSituation(_ account: WelcomeAccount = .none, offer: Int? = 3, accountRecipes: Int? = nil, claimed: Bool = false,
                paid: Bool = true, packs: Bool = true, paused: Bool = false, blocked: Bool = false) -> WelcomeSituation {
    WelcomeSituation(paidRouteAvailable: paid, packsOffered: packs, buyingPaused: paused, purchasesBlocked: blocked,
                     account: account, claimed: claimed, offer: offer, accountRecipes: accountRecipes)
}
func wSit(_ account: WelcomeAccount = .none, offer: Int? = 3, accountRecipes: Int? = nil, claimed: Bool = false,
          paid: Bool = true, packs: Bool = true, paused: Bool = false, blocked: Bool = false) -> WelcomeHint? {
    WelcomeRecipes.hint(wSituation(account, offer: offer, accountRecipes: accountRecipes, claimed: claimed, paid: paid, packs: packs, paused: paused, blocked: blocked))
}
// The cached answer and its age.
expect(WelcomeRecipes.fresh(wAnswer(3, ageHours: 1), now: wNow), 3, "an answer from an hour ago is shown at once")
expect(WelcomeRecipes.fresh(wAnswer(3, ageHours: 23.9), now: wNow), 3, "under a day old: shown")
expect(WelcomeRecipes.fresh(wAnswer(3, ageHours: 24), now: wNow), nil, "a day old counts as none")
expect(WelcomeRecipes.fresh(wAnswer(3, ageHours: -2), now: wNow), nil, "from the future (clock change): none")
expect(WelcomeRecipes.fresh(nil, now: wNow), nil, "no answer yet: none")
// Refreshing: on showing the tab at most every 15 minutes, always before the sign-in sheet.
func ask(_ lastAsked: Date?, _ answer: WelcomeOfferAnswer? = nil, at now: Date = wNow, before: Bool = false) -> Bool {
    WelcomeRecipes.shouldAsk(lastAsked: lastAsked, answer: answer, now: now, beforeSignIn: before)
}
expect(ask(nil), true, "never asked, nothing answered: ask")
expect(ask(wNow.addingTimeInterval(-14 * 60)), false, "asked 14 minutes ago: not yet")
expect(ask(wNow.addingTimeInterval(-15 * 60)), true, "15 minutes: ask again")
expect(ask(wNow.addingTimeInterval(-60), before: true), true, "before the sign-in sheet: always")
expect(ask(nil, wAnswer(3, ageHours: 0.1)), false, "no ask recorded, answered 6 minutes ago: not yet")
expect(ask(nil, wAnswer(3, ageHours: 0.3)), true, "no ask recorded, answered 18 minutes ago: ask")
expect(ask(wNow.addingTimeInterval(-5 * 60), wAnswer(3, ageHours: 5)), false, "a cold start 5 minutes after a failed ask: no ask (the last ask counts, not the old answer)")
// An answer dated in the future (a clock moved back after it): ignored, so it can't cause asks on every showing.
expect(ask(nil, wAnswer(3, ageHours: -2)), true, "an answer from the future and no ask: ask once")
expect(ask(wNow.addingTimeInterval(-60), wAnswer(3, ageHours: -2)), false, "then the ask's own time rules: not again for 15 minutes")
// The clock moved back with failing calls: asked once, then the new ask's time takes over.
let movedBack = wNow.addingTimeInterval(-3600)
expect(ask(wNow, at: movedBack), true, "last ask in the future (clock moved back): ask once")
expect(ask(movedBack, at: movedBack.addingTimeInterval(60)), false, "that failed ask was recorded: waits")
expect(ask(movedBack, at: movedBack.addingTimeInterval(15 * 60)), true, "15 minutes after it: ask again")
expect(WelcomeRecipes.offerAskable(wSituation(paid: false)), false, "an install that can't buy never asks")
expect(WelcomeRecipes.offerAskable(wSituation(packs: false)), false, "the store offered no packs (restricted, unreachable, none sold): never asks")
expect(WelcomeRecipes.offerAskable(wSituation(paused: true)), false, "paused: doesn't ask")
expect(WelcomeRecipes.offerAskable(wSituation(.none)), true, "signed out: asks")
expect(WelcomeRecipes.offerAskable(wSituation(.device)), true, "recipes on this device: asks")
expect(WelcomeRecipes.offerAskable(wSituation(.signedIn)), false, "signed in: never asks (the number comes beside the balance)")
expect(wSit(packs: false), nil, "no packs offered by the store: no hint")
expect(wSit(.device, offer: 0, accountRecipes: 3), nil, "recipes on this device: the account's number never overrides the offer's 0")
expect(wSit(.device, offer: 2, accountRecipes: 3), WelcomeHint(recipes: 2, needsSignIn: true), "recipes on this device: the offer's number")
expect(wSit(.none, offer: nil, accountRecipes: 3), nil, "no account: only the offer counts")
// The account's number is kept with its time: shown under a day old, then the day's offer stands in.
expect(wSit(.signedIn, offer: 2, accountRecipes: WelcomeRecipes.fresh(wAnswer(3, ageHours: 23), now: wNow)), WelcomeHint(recipes: 3, needsSignIn: false), "the account's number from 23 hours ago")
expect(wSit(.signedIn, offer: 2, accountRecipes: WelcomeRecipes.fresh(wAnswer(3, ageHours: 25), now: wNow)), WelcomeHint(recipes: 2, needsSignIn: false), "a day old: the cached offer stands in")
expect(wSit(.signedIn, offer: nil, accountRecipes: WelcomeRecipes.fresh(wAnswer(3, ageHours: 25), now: wNow)), nil, "neither fresh: nothing")
// Failures are silent: the last answer stays (until a day old); an old server reads as 0.
let wCached = wAnswer(3, ageHours: 2)
expect(WelcomeRecipes.answer(afterFailure: .offline, cached: wCached, now: wNow), wCached, "offline keeps the last answer")
expect(WelcomeRecipes.answer(afterFailure: .unauthenticated, cached: wCached, now: wNow), wCached, "App Check keeps it too")
expect(WelcomeRecipes.answer(afterFailure: .callableMissing, cached: wCached, now: wNow), WelcomeOfferAnswer(recipes: 0, receivedAt: wNow), "an old server: 0")
expect(WelcomeRecipes.answer(afterFailure: .offline, cached: nil, now: wNow), nil, "nothing cached: still nothing")
// The answer, read leniently.
func wOffer(_ json: String) -> Int { try! JSONDecoder().decode(WelcomeOfferResponse.self, from: Data(json.utf8)).recipes }
expect(wOffer(#"{"recipes":3}"#), 3, "the offer's number")
expect(wOffer(#"{"recipes":0}"#), 0, "off")
expect(wOffer(#"{"recipes":"3"}"#), 0, "not a number: none")
expect(wOffer(#"{"recipes":-1}"#), 0, "negative: none")
expect(wOffer(#"{"recipes":21}"#), 0, "above the server's limit: not believed")
expect(wOffer(#"{}"#), 0, "absent: none")
// What is shown where.
expect(wSit(), WelcomeHint(recipes: 3, needsSignIn: true), "signed out with an offer of 3: Sign in and get 3")
expect(wSit(offer: 1), WelcomeHint(recipes: 1, needsSignIn: true), "an offer of 1")
expect(wSit(offer: 0), nil, "0: no hint, no placeholder")
expect(wSit(offer: nil), nil, "no answer (yet, failed, or a day old): no hint")
expect(wSit(paid: false), nil, "no paid route: never")
expect(wSit(paused: true), nil, "buying paused: hidden as Buy is")
expect(wSit(blocked: true), nil, "purchases blocked: hidden as Buy is")
expect(wSit(.device), WelcomeHint(recipes: 3, needsSignIn: true), "recipes on this device: signing in also gets them")
expect(wSit(.signedIn, offer: nil, accountRecipes: 3), WelcomeHint(recipes: 3, needsSignIn: false), "signed in, not claimed: Get 3 free recipes, from beside the balance")
expect(wSit(.signedIn, offer: 3, accountRecipes: 0), nil, "signed in, the server says 0: nothing")
expect(wSit(.signedIn, offer: 3, accountRecipes: nil), WelcomeHint(recipes: 3, needsSignIn: false), "signed in, no balance yet: the day's answer")
expect(wSit(.signedIn, offer: 3, accountRecipes: 3, claimed: true), nil, "claimed: never again for this account")
expect(wSit(.none, offer: 3, claimed: true), WelcomeHint(recipes: 3, needsSignIn: true), "signed out after a claim: the signed-out hint is back")
expect(WelcomeRecipes.accountRecipes(acceptsWelcome: true, welcomeRecipes: 3), 3, "welcomeRecipes read")
expect(WelcomeRecipes.accountRecipes(acceptsWelcome: false, welcomeRecipes: 3), 0, "without acceptsWelcome: no grant")
expect(WelcomeRecipes.accountRecipes(acceptsWelcome: true, welcomeRecipes: nil), 0, "absent: 0")
let wBalance = try! JSONDecoder().decode(BalanceResponse.self, from: Data(#"{"balance":0,"acceptsWelcome":true,"welcomeRecipes":3}"#.utf8))
expect(wBalance.acceptsWelcome?.value, true, "acceptsWelcome on getBalance")
expect(wBalance.welcomeRecipes?.value, 3, "welcomeRecipes on getBalance")
expect(try! JSONDecoder().decode(GenerateResponse.self, from: Data(#"{"status":"DONE","balance":3,"charged":true,"welcomeRecipes":2,"acceptsWelcome":true}"#.utf8)).welcomeRecipes?.value, 2, "on generateRecipe")
// Any sign-in while an offer shows claims; an account that claimed here isn't claimed for again.
expect(WelcomeRecipes.afterSignIn(offerShowing: false, claimedHere: false), .nothing, "no offer: an ordinary sign-in")
expect(WelcomeRecipes.afterSignIn(offerShowing: true, claimedHere: false), .claim, "an offer: claim once")
expect(WelcomeRecipes.afterSignIn(offerShowing: true, claimedHere: true), .alreadyClaimed, "claimed here before: ALREADY without a call")
// The claim's outcomes.
func wClaim(_ json: String) -> WelcomeClaimOutcome { WelcomeRecipes.outcome(of: try! JSONDecoder().decode(ClaimWelcomeResponse.self, from: Data(json.utf8))) }
expect(wClaim(#"{"status":"GRANTED","granted":3,"balance":3,"acceptsWelcome":true,"welcomeRecipes":3}"#), .granted(3), "GRANTED")
expect(wClaim(#"{"status":"ALREADY","granted":0,"balance":7}"#), .already, "ALREADY")
expect(wClaim(#"{"status":"OFF","granted":0,"balance":0}"#), .off, "OFF")
expect(wClaim(#"{"status":"LATER","balance":0}"#), .notReached, "an unknown status: not understood, Try again")
expect(WelcomeRecipes.outcome(of: .offline), .notReached, "claim not reached: Try again")
expect(WelcomeRecipes.outcome(of: .signInRequired), .notReached, "SIGN_IN_REQUIRED: nothing granted")
expect(WelcomeRecipes.outcome(of: .callableMissing), .off, "an old server: OFF, no Try again")
expect(WelcomeRecipes.outcome(of: .accountClosed), .notReached, "ACCOUNT_CLOSED: not reached")
expect(RecipeServerError.from(code: "failed-precondition", reason: "SIGN_IN_REQUIRED"), .signInRequired, "SIGN_IN_REQUIRED read")
expect(WelcomeRecipes.claimSettled(.granted(3)), true, "GRANTED: never again")
expect(WelcomeRecipes.announces(.already, quiet: false), true, "ALREADY said after an ordinary sign-in")
expect(WelcomeRecipes.announces(.granted(3), quiet: true), true, "beside a purchase: a grant is said")
expect(WelcomeRecipes.announces(.already, quiet: true), false, "beside a purchase: ALREADY is not")
expect(WelcomeRecipes.announces(.off, quiet: true), false, "beside a purchase: OFF is not")
expect(WelcomeRecipes.announces(.notReached, quiet: true), false, "beside a purchase: not reached is not")
expect(WelcomeRecipes.claimSettled(.already), true, "ALREADY: never again")
expect(WelcomeRecipes.claimSettled(.off), false, "OFF: may claim later")
expect(WelcomeRecipes.claimSettled(.notReached), false, "not reached: retry (idempotent)")
// The menu line, from the costs table.
let wSets = RecipeCosts(recipe: 1, menu2: 4, menu3: 5, menuSets: ["1": ["1": 1, "2": 2, "3": 3], "3": ["2": 4, "3": 5]])
expect(WelcomeRecipes.menuCover(recipes: 3, balance: 0, menuCost: 3, costs: wSets, takesMenuDays: true), .enough, "3 cover a 1-day menu of 3 courses")
expect(WelcomeRecipes.menuCover(recipes: 3, balance: 0, menuCost: 5, costs: wSets, takesMenuDays: true), .smallerMenu, "3 don't cover 5, a smaller set fits")
expect(WelcomeRecipes.menuCover(recipes: 3, balance: 0, menuCost: 5, costs: .fallback, takesMenuDays: false), .singleOnly, "no set fits 3: single recipes only")
expect(WelcomeRecipes.menuCover(recipes: 3, balance: 1, menuCost: 4, costs: .fallback, takesMenuDays: false), .enough, "with the pack's 1 they cover 4")
expect(WelcomeRecipes.menuCover(recipes: 1, balance: -2, menuCost: 3, costs: wSets, takesMenuDays: true), .smallerMenu, "a debt doesn't count against them")
expect(WelcomeRecipes.menuCover(recipes: 1, balance: 0, menuCost: 4, costs: .fallback, takesMenuDays: false), .singleOnly, "1 free recipe: single recipes only")

// A linked sign-in: the token refreshed, then exactly one balance call; a failure is silent.
final class LinkCalls { var tokens = 0; var balances = 0 }
struct LinkFailure: Error {}
func linked(tokenFails: Bool = false, balanceFails: Bool = false) async -> (Int?, LinkCalls) {
    let calls = LinkCalls()
    let answer: Int? = await WelcomeRecipes.recordLinkedSignIn(
        refreshToken: { calls.tokens += 1; if tokenFails { throw LinkFailure() } },
        balance: { calls.balances += 1; if balanceFails { throw LinkFailure() }; return 7 }
    )
    return (answer, calls)
}
let linkOK = await linked()
expect(linkOK.0, 7, "a link is followed by the balance")
expect([linkOK.1.tokens, linkOK.1.balances], [1, 1], "one token refresh, exactly one balance call")
let linkBalanceFails = await linked(balanceFails: true)
expect(linkBalanceFails.0, nil, "a failed balance call is silent (nil, nothing thrown)")
expect(linkBalanceFails.1.balances, 1, "and not retried there")
let linkTokenFails = await linked(tokenFails: true)
expect(linkTokenFails.0, nil, "a failed token refresh is silent")
expect(linkTokenFails.1.balances, 0, "no balance call with a stale token")

// Staples (Staple.swift, RecipeWire.stapleNames, RecipeShopping), as Android's StaplesTest,
// StapleWireTest and the shopping cases of RecipeShoppingTest.
expect(StaplePresets.all.count, 14, "14 presets")
expect(Set(StaplePresets.all.map(\.id)).count, 14, "unique ids")
expect(StaplePresets.all.map(\.id), ["salt", "black_pepper", "sugar", "flour", "cooking_oil", "olive_oil", "butter", "eggs", "milk", "rice", "pasta", "garlic", "onions", "vinegar"], "table order")
expect(StaplePresets.all.allSatisfy { p in Staples.languages.allSatisfy { !(p.names[$0] ?? "").trimmingCharacters(in: .whitespaces).isEmpty } }, true, "every language named")
expect(StaplePresets.byId("eggs")?.name("fr"), "Œufs", "a preset's name")
expect(StaplePresets.byId("eggs")?.name("it"), "Eggs", "English outside the eight")
expect(Staples.language("nn-NO"), "nb", "nn is nb")
expect(Staples.language("no"), "nb", "no is nb")
expect(Staples.language("de-AT"), "de", "the base")
expect(Staples.language("pt-BR"), "en", "outside the eight: en")
expect(Staples.language(preferredLocalizations: ["it", "de"]), "en", "Italian is a recipe language, not an app one: en")
expect(Staples.language(preferredLocalizations: ["xx", "sv"]), "sv", "the first recipe language")
let sSalt = Staple(id: "a", name: "Соль", presetId: "salt", addedAtEpochMillis: 9)
let sEggs = Staple(id: "b", name: "Eggs", presetId: "eggs", addedAtEpochMillis: 1)
let sOwn = Staple(id: "c", name: "Smoked paprika", presetId: nil, addedAtEpochMillis: 5)
let sFuture = Staple(id: "d", name: "Truffle oil", presetId: "truffle_oil", addedAtEpochMillis: 3)
let sOwn2 = Staple(id: "e", name: "Soy sauce", presetId: nil, addedAtEpochMillis: 5)
expect(Staples.ordered([sOwn2, sOwn, sFuture, sEggs, sSalt]).map(\.id), ["a", "b", "d", "c", "e"], "presets in table order, then the rest by time, then id")
expect(Staples.displayName(sSalt, language: "de"), "Salz", "a preset shows in the app's language")
expect(Staples.displayName(sFuture, language: "de"), "Truffle oil", "an unknown preset shows its name")
let saltKeys = Staples.matchKeys(sSalt)
expect(["salt", "sel", "sal", "salz", "соль"].allSatisfy(saltKeys.contains), true, "a preset's keys in every language")
expect(Staples.matchKeys(sOwn), ["smoked paprika"], "an own entry's key")
expect(Staples.matchKeys(Staple(id: "f", name: "  Paprika   powder ", presetId: nil)).contains("paprika powder"), true, "and as cleaned for the request")
expect(Staples.isOut(sSalt, shoppingNames: ["  SEL "]), true, "on the list in another language: out")
expect(Staples.isOut(sSalt, shoppingNames: ["Sea salt"]), false, "another name: not out")
expect(Staples.isOut(sEggs, shoppingNames: []), false, "an empty list")
let sRows = Staples.rows([sOwn, sEggs, sSalt], shoppingNames: ["eggs"], language: "en")
expect(sRows.map(\.name), ["Salt", "Eggs", "Smoked paprika"], "rows, shown names")
expect(sRows.map(\.out), [false, true, false], "eggs on the list: out")
expect(Staples.namesToSend(sRows), ["Salt", "Smoked paprika"], "out staples are not sent")
expect(Staples.currentKeys(sRows).contains("eggs"), false, "an out staple is no current key")
expect(Staples.currentKeys(sRows).contains("sel"), true, "a current preset in every language")
expect(Staples.addCheck([sSalt], typed: "   ", language: "en"), .empty, "empty")
expect(Staples.addCheck([sSalt], typed: " SALT ", language: "en"), .duplicate("Salt"), "already a staple, shown in the app's language")
expect(Staples.addCheck([sSalt], typed: "Sel", language: "fr"), .duplicate("Sel"), "in another language too")
expect(Staples.addCheck([sSalt], typed: "sukker", language: "en"), .preset("sugar"), "a preset's name ticks it")
expect(Staples.addCheck([sSalt], typed: " Smoked paprika ", language: "en"), .own("Smoked paprika"), "an own entry, trimmed")
let twenty = (0..<20).map { Staple(id: "x\($0)", name: "Thing \($0)", addedAtEpochMillis: Int64($0)) }
expect(Staples.addCheck(twenty, typed: "Sugar", language: "en"), .full, "20 already: full")
expect(Staples.addCheck(twenty, typed: "thing 3", language: "en"), .duplicate("Thing 3"), "a duplicate is said before full")
expect(Staples.ticking(StaplePresets.byId("butter")!, language: "nb", id: "z", now: 7), Staple(id: "z", name: "Smør", presetId: "butter", addedAtEpochMillis: 7), "a tick stores the name in the app's language")
let pRows = Staples.rows([Staple(id: "p1", name: "Salt", presetId: "salt", addedAtEpochMillis: 1), Staple(id: "p2", name: "Olive oil", presetId: "olive_oil", addedAtEpochMillis: 2),
                          Staple(id: "p3", name: "Eggs", presetId: "eggs", addedAtEpochMillis: 3), Staple(id: "p4", name: " salt ", addedAtEpochMillis: 4),
                          Staple(id: "p5", name: "Milk", presetId: "milk", addedAtEpochMillis: 5)], shoppingNames: ["milk"], language: "en")
expect(Staples.promptStaples(pRows, ticked: ["eggs"], others: ["Olive Oil"]), ["Salt"], "less ticked, other items, out staples and repeats")
expect(Staples.promptStaples(pRows, ticked: []), ["Salt", "Olive oil", "Eggs"], "nothing listed: every staple not out, in order")
// C4: any of a staple's names counts, so a German "Eier" isn't sent beside a pantry item "Eggs".
let deRows = Staples.rows([Staple(id: "q1", name: "Eier", presetId: "eggs", addedAtEpochMillis: 1), Staple(id: "q2", name: "Salz", presetId: "salt", addedAtEpochMillis: 2)], shoppingNames: [], language: "de")
expect(Staples.promptStaples(deRows, ticked: ["Eggs"]), ["Salz"], "Eier is left out beside Eggs, shown names kept")
expect(Staples.promptStaples(deRows, ticked: [], others: ["sel"]), ["Eier"], "and beside an other item in another language")
// C1: Undo of a removal only while no staple shares a name, and under 20.
let rSalt = Staple(id: "r1", name: "Salt", presetId: "salt", addedAtEpochMillis: 1)
expect(Staples.mayRestore(rSalt, into: [Staple(id: "r2", name: "Salt", presetId: "salt", addedAtEpochMillis: 2)]), false, "salt ticked again meanwhile: Undo does nothing")
expect(Staples.mayRestore(rSalt, into: [Staple(id: "r3", name: "sel")]), false, "nor beside the same name in another language")
expect(Staples.mayRestore(rSalt, into: (0..<20).map { Staple(id: "c\($0)", name: "Item \($0)") }), false, "20 already: nothing")
expect(Staples.mayRestore(rSalt, into: (0..<19).map { Staple(id: "c\($0)", name: "Item \($0)") }), true, "19 and no clash: restored")
expect(Staples.mayRestore(rSalt, into: [rSalt]), false, "already there")
// C3: 40 code points, never cut inside one.
expect(Staples.limitedName(String(repeating: "𠮷", count: 45)).unicodeScalars.count, 40, "astral characters count once each")
expect(Staples.limitedName(String(repeating: "e\u{301}", count: 25)).unicodeScalars.count, 40, "combining marks count as code points")
expect(Staples.limitedName("Smoked paprika"), "Smoked paprika", "short names untouched")

// The wire: cleaned, deduped against ticked, optional and earlier staples, 20 names and 400 code points.
let sTicked = [WireIngredient(name: "Eggs", expiresOn: nil, quantity: nil)]
let sOptional = [WireIngredient(name: "Milk", expiresOn: nil, quantity: nil)]
expect(RecipeWire.stapleNames(["  Salt  ", "eggs", "MILK", "salt", "Olive   oil", "!!!", "Butter"], ticked: sTicked, optional: sOptional, refused: ["butter"]),
       ["Salt", "Olive oil"], "cleaned, request names and repeats out, refused out")
let many = (0..<25).map { "Herb \(Character(UnicodeScalar(97 + $0)!))" }
expect(RecipeWire.stapleNames(many, ticked: [], optional: []).count, 20, "at most 20 names")
let sWide = ["Alpha spiced pumpkin seed and herb crumb", "Bravo spiced pumpkin seed and herb crumb", "Charlie spiced pumpkin seed and herb cru", "Delta spiced pumpkin seed and herb crumb", "Echo spiced pumpkin seed and herb crumbl", "Foxtrot spiced pumpkin seed and herb cru", "Golf spiced pumpkin seed and herb crumbl", "Hotel spiced pumpkin seed and herb crumb", "India spiced pumpkin seed and herb crumb", "Juliet spiced pumpkin seed and herb cr", "Kilo spiced pumpkin seed and herb crumbl", "Ox"]
let keptWide = RecipeWire.stapleNames(sWide, ticked: [], optional: [])
expect(keptWide.count, 11, "one that doesn't fit is skipped, a later shorter one kept")
expect(keptWide.reduce(0) { $0 + $1.unicodeScalars.count }, 400, "400 code points at most")
expect(keptWide.contains("Kilo spiced pumpkin seed and herb crumbl"), false, "the one over is the one skipped")
expect(keptWide.last, "Ox", "skip and continue")
expect(RecipeWire.stapleNames(["𠮷𠮷"], ticked: [], optional: []).first?.unicodeScalars.count, 2, "code points, not UTF-16")

// The request: staples only to a server that said acceptsStaples, never empty; a retry keeps them.
let sRequest = RecipeRequest(items: [RecipeIngredientInput(name: "Eggs", daysRemaining: 1, expiryText: ""), RecipeIngredientInput(name: "Leeks", daysRemaining: 2, expiryText: "")],
                             config: RecipeConfig(), allergens: [], staples: ["Salt", "Eggs", "Olive oil"])
let sWith = RecipeWire.request(for: sRequest, kind: .recipe, courses: 2, requestID: UUID(), withStaples: true)
expect(sWith?.staples, ["Salt", "Olive oil"], "sent, less a ticked name")
let sWithout = RecipeWire.request(for: sRequest, kind: .recipe, courses: 2, requestID: UUID())
expect(sWithout?.staples, nil, "no flag: no staples")
expect(String(decoding: try! JSONEncoder().encode(sWithout!), as: UTF8.self).contains("staples"), false, "and none in the JSON")
expect(String(decoding: try! JSONEncoder().encode(sWith!), as: UTF8.self).contains(#""staples":["Salt","Olive oil"]"#), true, "names only, in order")
let sNone = RecipeWire.request(for: RecipeRequest(items: sRequest.items, config: RecipeConfig(), allergens: []), kind: .recipe, courses: 2, requestID: UUID(), withStaples: true)
expect(sNone?.staples, nil, "no staples: the field is left out")
expect(RecipeWire.dropping(ingredientAt: 1, from: sWith!, newID: UUID())?.staples, ["Salt", "Olive oil"], "a retry keeps them")
expect(sWith!.hasSameInputs(as: sWithout!), false, "staples are bound to the request id")
let sOld = try! JSONDecoder().decode(GenerateRequest.self, from: JSONEncoder().encode(sWithout!))
expect(sOld.staples, nil, "a stored request without them reads as none")
expect(RecipeWire.clearsStaplesFlag(after: .invalidRequest, request: sWith!), true, "INVALID_REQUEST with staples: flag cleared")
expect(RecipeWire.clearsStaplesFlag(after: .refusedName(field: "ingredients[0].name"), request: sWith!), false, "a refused name is not a rollback")
expect(RecipeWire.clearsStaplesFlag(after: .invalidRequest, request: sWithout!), false, "no staples sent: not theirs")
expect(try! JSONDecoder().decode(BalanceResponse.self, from: Data(#"{"balance":3,"acceptsStaples":true}"#.utf8)).acceptsStaples?.value, true, "acceptsStaples on getBalance")
expect(try! JSONDecoder().decode(GenerateResponse.self, from: Data(#"{"status":"DONE","balance":3,"charged":true}"#.utf8)).acceptsStaples?.value ?? false, false, "absent: no")
// The free routes take the RecipeRequest's staples; the server's request carries them only as above.
expect(sRequest.staples, ["Salt", "Eggs", "Olive oil"], "a free route gets them as given")

// stapleIndex -> stapleName, for a recipe and every dish of a menu.
let sDecoded = try! JSONDecoder().decode(Recipe.self, from: Data(#"{"title":"T","ingredients":[{"amount":"1 tsp","name":"salt","fromPantry":false,"stapleIndex":0,"staple":true},{"amount":"","name":"oil","fromPantry":false,"stapleIndex":7},{"amount":"2","name":"eggs","fromPantry":true,"pantryIndex":0,"stapleName":"Injected"}]}"#.utf8))
expect(sDecoded.ingredients[0].stapleIndex, 0, "stapleIndex read")
let sNamed = sDecoded.withStapleNames(["Salt", "Olive oil"])
expect(sNamed.ingredients.map(\.stapleName), ["Salt", nil, nil], "by index; out of range and none: nil, overwriting what came")
let sMenu = RecipeMenu(days: [RecipeMenuDay(date: "2026-11-16", uses: [], courses: [RecipeMenuCourse(course: .main, recipe: sDecoded)])])
expect(sMenu.withStapleNames(["Salt"]).days[0].courses[0].recipe.ingredients[0].stapleName, "Salt", "every menu dish")
expect(try! JSONDecoder().decode(Recipe.self, from: JSONEncoder().encode(sNamed)).ingredients[0].stapleName, "Salt", "kept in the saved payload")

// stapleName(of:), the shared table.
let sTable: [(String, String?)] = [("Salt", "Salt"), ("salt", "Salt"), ("Salt, to taste", "Salt"), ("Salt and pepper", nil), ("Salt, pepper", nil),
                                   ("Olive oil", "Olive oil"), ("Extra virgin olive oil", nil), ("Eggs, beaten", "Eggs"), ("Water", nil)]
for (input, result) in sTable {
    expect(RecipeShopping.stapleName(of: input, staples: ["Salt", "Olive oil", "Eggs"]), result, "stapleName(of: \(input))")
}
// Add to shopping list leaves out current staples.
let sShop = Recipe(title: "S", summary: "", minutes: 0, servings: 0, difficulty: .easy, ingredients: [
    RecipeIngredient(amount: "", name: "Salt", fromPantry: false),
    RecipeIngredient(amount: "", name: "Salt, pepper", fromPantry: false),
    RecipeIngredient(amount: "", name: "Olive oil", fromPantry: false, stapleName: "Olive oil"),
    RecipeIngredient(amount: "", name: "Onion", fromPantry: false),
    RecipeIngredient(amount: "", name: "Sea salt", fromPantry: false, stapleName: "Salt"),
], steps: [], source: .server)
expect(RecipeShopping.candidates([sShop], listedNames: [], stapleKeys: ["salt", "olive oil"]).map(\.name), ["Pepper", "Onion"], "current staples left out")
expect(RecipeShopping.candidates([sShop], listedNames: []).map(\.name), ["Salt", "Pepper", "Olive oil", "Onion", "Sea salt"], "without staples, as before")

// Backup: the field read leniently, absent as none.
struct SBackup: Decodable {
    var staples: [Staple]
    enum CodingKeys: String, CodingKey { case staples }
    init(from decoder: Decoder) throws { staples = try Staples.decodeBackup(decoder.container(keyedBy: CodingKeys.self), forKey: .staples) }
}
expect(try! JSONDecoder().decode(SBackup.self, from: Data(#"{"version":1}"#.utf8)).staples, [], "a backup without staples")
let sBackup = try! JSONDecoder().decode(SBackup.self, from: Data(#"{"staples":[{"id":"staple_1763300000000_123","name":"Salt","presetId":"salt","addedAtEpochMillis":1763300000000},{"id":"x","name":"Smoked paprika","presetId":null,"addedAtEpochMillis":1763300100000},{"id":"y","name":"Yuzu","presetId":"yuzu"},{"id":"z","name":"Miso"}]}"#.utf8)).staples
expect(sBackup.map(\.presetId), ["salt", nil, "yuzu", nil], "presetId as written, an unknown one kept")
expect(sBackup[2].addedAtEpochMillis, 0, "no time: 0")
expect(String(decoding: try! JSONEncoder().encode(sBackup[1]), as: UTF8.self).contains(#""presetId":null"#), true, "none written as null, as Android")
expect(try! JSONDecoder().decode(Staple.self, from: JSONEncoder().encode(sBackup[0])), sBackup[0], "round trip")
expect(Staples.toRestore(replacing: sBackup).map(\.id), ["staple_1763300000000_123", "y", "z", "x"], "replace: in order")
expect(Staples.toRestore(replacing: twenty + sBackup).count, 20, "replace: at most 20")
// C2: a file with two Salt rows restores as one.
expect(Staples.toRestore(replacing: [Staple(id: "s1", name: "Salt", presetId: "salt", addedAtEpochMillis: 1), Staple(id: "s2", name: "salt", addedAtEpochMillis: 2), Staple(id: "s3", name: "Sel", presetId: "salt", addedAtEpochMillis: 3)]).map(\.id), ["s1"], "replace: two Salt rows restore as one")
expect(Staples.toRestore(merging: sBackup, into: [Staple(id: "m", name: "sel", presetId: nil)]).map(\.id), ["y", "z", "x"], "merge: a name already a staple, in any language, is left out")
expect(Staples.toRestore(merging: sBackup, into: Array(twenty.prefix(18))).map(\.id), ["staple_1763300000000_123", "y"], "merge: up to 20 in all")

// Onboarding's Staples step (OnboardingFlow.swift, Staples.presetChanges), as Android's OnboardingFlowTest.
func oFlow(_ notifications: Bool, _ camera: Bool) -> OnboardingFlow {
    var f = OnboardingFlow(); f.start(OnboardingAsks(notifications: notifications, camera: camera)); return f
}
var oAll = oFlow(true, true)
expect(oAll.step, .notifications, "notifications first")
oAll.notificationsAnswered(true, OnboardingAsks(notifications: false, camera: true))
expect(oAll.step, .camera, "then camera")
oAll.cameraAnswered(false)
expect(oAll.step, .staples, "then Staples, before the last screen")
oAll.staplesDone()
expect(oAll.step, .declined, "Staples goes on to Declined when the camera was refused")
oAll.back(OnboardingAsks(notifications: false, camera: false))
expect(oAll.step, .staples, "back from Declined is Staples")
oAll.staplesDone()
expect(oAll.step, .declined, "and on again")
var oNone = oFlow(false, false)
expect(oNone.step, .staples, "nothing to ask: straight to Staples")
oNone.staplesDone()
expect(oNone.step, .ready, "then Ready")
var oNoCamera = oFlow(true, false)
oNoCamera.notificationsAnswered(nil, OnboardingAsks(notifications: false, camera: false))
expect(oNoCamera.step, .staples, "camera not asked: Staples after notifications")
oNoCamera.staplesDone()
expect(oNoCamera.step, .ready, "no answer (a failed request) is not declined: Ready")
var oCameraOnly = oFlow(false, true)
expect(oCameraOnly.step, .camera, "notifications not asked: camera")
oCameraOnly.cameraAnswered(true)
expect(oCameraOnly.step, .staples, "then Staples")
var oDeclinedReminders = oFlow(true, false)
oDeclinedReminders.notificationsAnswered(false, OnboardingAsks(notifications: false, camera: false))
oDeclinedReminders.staplesDone()
expect(oDeclinedReminders.step, .declined, "reminders refused: Declined after Staples")
// Back, as Android's OnboardingFlowTest: retraces the screens shown, skipping those with nothing left to ask.
let oAskBoth = OnboardingAsks(notifications: true, camera: true)
let oAskCamera = OnboardingAsks(notifications: false, camera: true)
let oAskNone = OnboardingAsks(notifications: false, camera: false)
func oBack(from step: OnboardingStep, _ asks: OnboardingAsks) -> OnboardingStep {
    var f = OnboardingFlow()
    f.start(oAskBoth)
    if step != .notifications { f.notificationsAnswered(nil, oAskBoth) }
    if step == .staples || step == .ready || step == .declined { f.cameraAnswered(nil) }
    if step == .ready || step == .declined { f.staplesDone() }
    if step == .welcome { f.back(oAskBoth) }
    f.back(asks)
    return f.step
}
expect(oBack(from: .welcome, oAskBoth), .welcome, "Welcome has nothing before it")
expect(oBack(from: .notifications, oAskBoth), .welcome, "notifications back to Welcome")
expect(oBack(from: .camera, oAskBoth), .notifications, "camera back to notifications")
expect(oBack(from: .camera, oAskCamera), .welcome, "camera back skips answered notifications")
expect(oBack(from: .staples, oAskBoth), .camera, "Staples back to camera")
expect(oBack(from: .staples, OnboardingAsks(notifications: true, camera: false)), .notifications, "Staples back skips an answered camera")
expect(oBack(from: .staples, oAskNone), .welcome, "Staples back to Welcome when nothing is left to ask")
expect(oBack(from: .ready, oAskNone), .staples, "Ready back to Staples")
var oCanBack = OnboardingFlow()
expect(oCanBack.canGoBack, false, "no Back on Welcome")
// App Review 5.1.1(iv): a permission screen is left only through its system prompt.
expect(oFlow(true, true).canGoBack, false, "no Back on the notifications screen")
expect(oFlow(false, true).canGoBack, false, "no Back on the camera screen")
oCanBack.start(oAskNone)
expect(oCanBack.canGoBack, true, "Back from Staples")
// Going back and answering again replaces the answer, as on Android.
var oChanged = oFlow(false, true)
oChanged.cameraAnswered(false)
oChanged.staplesDone()
expect(oChanged.step, .declined, "camera refused: Declined")
oChanged.back(oAskCamera); oChanged.back(oAskCamera)
expect(oChanged.step, .camera, "back twice is the camera again")
oChanged.cameraAnswered(true)
oChanged.staplesDone()
expect(oChanged.step, .ready, "a new answer replaces the refusal")
let oHad = [Staple(id: "h1", name: "Salt", presetId: "salt", addedAtEpochMillis: 1), Staple(id: "h2", name: "Eggs", presetId: "eggs", addedAtEpochMillis: 2), Staple(id: "h3", name: "Soy sauce", addedAtEpochMillis: 3)]
expect(Staples.presetTicks(oHad), ["salt", "eggs"], "first ticks: the presets already there")
expect(Staples.presetTicks([]), [], "none at first run")
expect(Staples.presetChanges(oHad, ticked: ["salt", "eggs"]).isEmpty, true, "nothing changed: the same as Skip")
let oChange = Staples.presetChanges(oHad, ticked: ["vinegar", "salt", "black_pepper"])
expect(oChange.add, ["black_pepper", "vinegar"], "added in table order")
expect(oChange.remove.map(\.id), ["h2"], "the unticked preset removed; own entries untouched")
let oOwn18 = (0..<18).map { Staple(id: "o\($0)", name: "Own \($0)", addedAtEpochMillis: Int64($0)) }
expect(Staples.count(oOwn18, ticked: ["salt", "sugar"]), 20, "18 own + 2 ticked is the cap")
expect(Staples.presetChanges(oOwn18, ticked: ["salt", "sugar", "rice"]).add, ["salt", "sugar"], "never past 20")
expect(Staples.presetChanges(oOwn18 + [oHad[0]], ticked: ["sugar", "rice"]).add, ["sugar", "rice"], "a removal makes room")

if failures > 0 {
    print("\(failures) failed")
    exit(1)
}
print("RecipeConfigCheck: all passed")
