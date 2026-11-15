import Foundation

// Runs `RecipeWire.cleanName` and `cleanAllergenName` (pasted in by check.sh from
// iosApp/FoodTracker/Recipe/RecipeWire.swift) over functions/test/fixtures/names.json and
// prints every row where iOS disagrees with the fixture. The app has no test target; this is
// how the iOS cleaner is held to the same table as the server and Android.

struct Row: Decodable {
    let input: String
    let clean: String?
    let allergen: String?
}

struct Fixture: Decodable {
    let names: [Row]
}

let path = CommandLine.arguments[1]
let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: URL(fileURLWithPath: path)))

/// Strings compared scalar by scalar: Swift's `==` on String is canonical equivalence, which
/// would hide a composed/decomposed difference the server sees.
func same(_ a: String?, _ b: String?) -> Bool {
    switch (a, b) {
    case (nil, nil): return true
    case let (x?, y?): return Array(x.unicodeScalars) == Array(y.unicodeScalars)
    default: return false
    }
}

func show(_ s: String?) -> String {
    guard let s else { return "nil" }
    return s.unicodeScalars.map { $0.isASCII && $0.value >= 0x20 ? String($0) : String(format: "\\u{%X}", $0.value) }.joined()
}

var failures = 0
for row in fixture.names {
    let clean = RecipeWire.cleanName(row.input)
    let allergen = RecipeWire.cleanAllergenName(row.input)
    if !same(clean, row.clean) || !same(allergen, row.allergen) {
        failures += 1
        print("MISMATCH \(show(row.input)): clean \(show(clean)) want \(show(row.clean)); allergen \(show(allergen)) want \(show(row.allergen))")
    }
}
print("\(fixture.names.count) rows, \(failures) mismatches")
exit(failures == 0 ? 0 : 1)
