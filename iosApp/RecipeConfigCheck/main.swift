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

if failures > 0 {
    print("\(failures) failed")
    exit(1)
}
print("RecipeConfigCheck: all passed")
