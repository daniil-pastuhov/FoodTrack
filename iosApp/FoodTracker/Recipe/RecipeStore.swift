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
    /// Whether it was bought from the sheet with the consumption line shown; `verifyPurchase`
    /// sends `true` on every resubmission while it is, and nothing otherwise (never `false`,
    /// which would withdraw it). False for records an older build saved.
    var consumptionConsent: Bool
    /// The server said the purchase belongs to another account (`PURCHASE_ACCOUNT_MISMATCH`):
    /// kept unfinished for that account, sent again only at launch and when the account
    /// changes, and not counted as a payment on its way for this one.
    var boughtByOtherAccount: Bool

    var id: UInt64 { transactionID }

    init(transactionID: UInt64, productID: String, credits: Int, consumptionConsent: Bool = false, boughtByOtherAccount: Bool = false) {
        self.transactionID = transactionID
        self.productID = productID
        self.credits = credits
        self.consumptionConsent = consumptionConsent
        self.boughtByOtherAccount = boughtByOtherAccount
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        transactionID = try container.decode(UInt64.self, forKey: .transactionID)
        productID = try container.decode(String.self, forKey: .productID)
        credits = try container.decode(Int.self, forKey: .credits)
        consumptionConsent = (try? container.decodeIfPresent(Bool.self, forKey: .consumptionConsent)) ?? false
        boughtByOtherAccount = (try? container.decodeIfPresent(Bool.self, forKey: .boughtByOtherAccount)) ?? false
    }
}

/// The SwiftUI `purchase` environment action, passed in so the store sheet presents over the
/// right scene, with the purchase's options (the `appAccountToken`).
typealias RecipePurchaseAction = @MainActor (Product, Set<Product.PurchaseOption>) async throws -> Product.PurchaseResult

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

    static func unconfirmed(_ transaction: Transaction, consumptionConsent: Bool = false) -> UnconfirmedPurchase {
        UnconfirmedPurchase(
            transactionID: transaction.id,
            productID: transaction.productID,
            credits: (RecipeWire.credits(forProductID: transaction.productID) ?? 0) * max(transaction.purchasedQuantity, 1),
            consumptionConsent: consumptionConsent
        )
    }

    /// Whether a transaction's buyer consented to consumption information: yes once its own
    /// record says so, or when the sheet started a purchase of that product, with the line
    /// shown, no later than the transaction (an Ask to Buy approval arrives after, and a
    /// restart finds only the start). Only ever turns a "don't know" into a yes, never a yes
    /// into a no: a false here sends nothing (`RecipeWire.consentToSend`), so a resubmission
    /// can't withdraw a consent the server already has. A transaction from before this build,
    /// or bought on another device, has neither and reads false.
    static func consumptionConsent(record: UnconfirmedPurchase?, consentStartedAt: Date?, purchaseDate: Date) -> Bool {
        if record?.consumptionConsent == true { return true }
        guard let consentStartedAt else { return false }
        return consentStartedAt <= purchaseDate
    }
}
