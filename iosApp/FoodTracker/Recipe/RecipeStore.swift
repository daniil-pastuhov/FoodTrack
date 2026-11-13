import Foundation
import StoreKit

/// One pack as the sheet lists it, priced by the store in the person's currency.
struct RecipePack: Identifiable, Equatable {
    let product: Product
    let credits: Int

    var id: String { product.id }
    var displayPrice: String { product.displayPrice }

    /// "£0.20": worked out from the store's price, never written down anywhere.
    var pricePerRecipe: String {
        RecipePack.pricePerRecipe(price: product.price, credits: credits, format: product.priceFormatStyle)
    }

    static func pricePerRecipe(price: Decimal, credits: Int, format: Decimal.FormatStyle.Currency) -> String {
        guard credits > 0 else { return price.formatted(format) }
        return (price / Decimal(credits)).formatted(format)
    }

    /// Smallest first, as the sheet lists them; products the app doesn't know are left out.
    static func packs(from products: [Product]) -> [RecipePack] {
        products
            .compactMap { product in
                guard product.type == .consumable, let credits = RecipeWire.credits(forProductID: product.id) else { return nil }
                return RecipePack(product: product, credits: credits)
            }
            .sorted { $0.credits < $1.credits }
    }
}

/// What the store says about the packs.
enum RecipePacksState: Equatable {
    case idle
    case loading
    case loaded([RecipePack])
    /// The store couldn't be reached, or purchases are off on this device.
    case unreachable
    /// The store answered and has no packs for this build, as in a fork with another bundle id:
    /// the paid route is hidden.
    case notOffered
}

/// A purchase the store has taken payment for and our server hasn't added yet. Kept on the
/// phone so the callout survives a restart; StoreKit keeps the transaction itself unfinished.
struct UnconfirmedPurchase: Codable, Equatable, Identifiable, Sendable {
    let transactionID: UInt64
    let productID: String
    let credits: Int

    var id: UInt64 { transactionID }
}

/// The SwiftUI `purchase` environment action, passed in so the store sheet presents over the
/// right scene.
typealias RecipePurchaseAction = @MainActor (Product) async throws -> Product.PurchaseResult

enum RecipeStoreKit {
    static func loadPacks() async -> RecipePacksState {
        guard AppStore.canMakePayments else { return .unreachable }
        do {
            let products = try await Product.products(for: RecipeWire.productIDs)
            let packs = RecipePack.packs(from: products)
            return packs.isEmpty ? .notOffered : .loaded(packs)
        } catch {
            return .unreachable
        }
    }

    /// Ours and a consumable: the only transactions the paid route sends to the server.
    static func isRecipePack(_ transaction: Transaction) -> Bool {
        transaction.productType == .consumable && RecipeWire.credits(forProductID: transaction.productID) != nil
    }

    static func unconfirmed(_ transaction: Transaction) -> UnconfirmedPurchase {
        UnconfirmedPurchase(
            transactionID: transaction.id,
            productID: transaction.productID,
            credits: (RecipeWire.credits(forProductID: transaction.productID) ?? 0) * max(transaction.purchasedQuantity, 1)
        )
    }
}
