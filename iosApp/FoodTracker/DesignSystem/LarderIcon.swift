import SwiftUI

/// The Lucide 0.460.0 icons the design uses, as SVG template images in
/// `Assets.xcassets/Lucide`. Each is the published file with its stroke width set to 1.75.
/// To add one, copy its SVG from the `lucide-static` package the same way and add a case here.
/// Licence: `Resources/Licenses/Lucide-LICENSE.txt`.
enum LarderIconName: String, CaseIterable {
    case apple
    case archive
    case arrowLeft = "arrow-left"
    case arrowUpDown = "arrow-up-down"
    case beef
    case bell
    case bellOff = "bell-off"
    case bookmark
    case calendarDays = "calendar-days"
    case camera
    case cameraOff = "camera-off"
    case carrot
    case check
    case chefHat = "chef-hat"
    case cherry
    case chevronDown = "chevron-down"
    case chevronLeft = "chevron-left"
    case chevronRight = "chevron-right"
    case circleAlert = "circle-alert"
    case circleCheck = "circle-check"
    case circleMinus = "circle-minus"
    case clock
    case cloud
    case copy
    case croissant
    case cupSoda = "cup-soda"
    case download
    case egg
    case ellipsisVertical = "ellipsis-vertical"
    case fish
    case flashlight
    case gauge
    case gripVertical = "grip-vertical"
    case history
    case house
    case image
    case info
    case languages
    case layers
    case leaf
    case lightbulb
    case listPlus = "list-plus"
    case mapPin = "map-pin"
    case milk
    case minus
    case package
    case pencil
    case plus
    case qrCode = "qr-code"
    case receipt
    case refrigerator
    case scanBarcode = "scan-barcode"
    case scanText = "scan-text"
    case search
    case settings
    case share2 = "share-2"
    case shoppingBasket = "shopping-basket"
    case slidersHorizontal = "sliders-horizontal"
    case smartphone
    case snowflake
    case sparkles
    case star
    case store
    case timer
    case trash2 = "trash-2"
    case triangleAlert = "triangle-alert"
    case upload
    case userRound = "user-round"
    case users
    case utensils
    case wifiOff = "wifi-off"
    case x

    /// Catalog name, for APIs that take one, such as `Label(_:image:)` in a swipe action.
    var assetName: String { "Lucide/\(rawValue)" }

    /// Stand-in for a product with no photo, matched on words in its category. Category names
    /// are free text from Open Food Facts or the user, in any of the app's languages.
    static func forCategory(_ category: String?) -> LarderIconName {
        guard let category = category?.lowercased(), !category.isEmpty else { return .package }
        return categoryKeywords.first { entry in
            entry.words.contains { category.contains($0) }
        }?.icon ?? .package
    }

    /// In order: "fish" must win over the "meat" in names like "Meat & fish".
    private static let categoryKeywords: [(icon: LarderIconName, words: [String])] = [
        (.fish, ["fish", "seafood", "рыб", "морепродукт", "fisk", "sjømat"]),
        (.beef, ["meat", "beef", "pork", "poultry", "chicken", "мяс", "птиц", "kjøtt", "kylling"]),
        (.egg, ["egg", "яйц"]),
        (.milk, ["dairy", "milk", "yogh", "yogurt", "cheese", "молоч", "молок", "йогурт", "сыр", "meieri", "melk", "ost"]),
        (.croissant, ["bak", "bread", "pastr", "хлеб", "выпеч", "brød"]),
        (.carrot, ["root", "carrot", "морков", "корнеплод", "gulrot"]),
        (.leaf, ["vegetable", "salad", "greens", "овощ", "зелен", "салат", "grønnsak"]),
        (.cherry, ["berr", "cherr", "ягод", "вишн", "bær"]),
        (.apple, ["fruit", "фрукт", "frukt"]),
        (.cupSoda, ["drink", "beverage", "juice", "soda", "напит", "сок"]),
    ]
}

/// A Lucide icon that takes the surrounding foreground colour and grows with Dynamic Type.
///
///     LarderIcon(.search)                                  // decorative
///     LarderIcon(.trash2, size: .small, label: "Delete")   // read aloud
struct LarderIcon: View {
    /// One size per context, from `components.css`.
    enum Size {
        /// Status chips and field messages.
        case small
        /// The default.
        case medium
        /// Scan and thumbnails.
        case large
        case custom(CGFloat)

        var points: CGFloat {
            switch self {
            case .small: return 14
            case .medium: return 22
            case .large: return 24
            case .custom(let points): return points
            }
        }
    }

    private let name: LarderIconName
    private let label: String?
    @ScaledMetric private var points: CGFloat

    init(_ name: LarderIconName, size: Size = .medium, label: String? = nil) {
        self.name = name
        self.label = label
        _points = ScaledMetric(wrappedValue: size.points, relativeTo: .body)
    }

    var body: some View {
        Image(name.assetName)
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: points, height: points)
            .accessibilityHidden(label == nil)
            .accessibilityLabel(label ?? "")
    }
}

private struct IconGrid: View {
    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 44))], spacing: LarderSpace.s4) {
                ForEach(LarderIconName.allCases, id: \.self) { LarderIcon($0) }
            }
            .padding()
        }
        .foregroundStyle(LarderColor.onSurface)
        .background(LarderColor.background)
    }
}

#Preview("All icons") { IconGrid() }
#Preview("All icons, dark") { IconGrid().preferredColorScheme(.dark) }
