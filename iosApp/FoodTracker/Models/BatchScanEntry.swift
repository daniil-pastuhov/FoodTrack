import Foundation

/// One product waiting in the scanner's batch queue: scanned and looked up, but not yet saved.
///
/// Batch mode exists because unpacking a weekly shop is fifteen to thirty items, and the normal
/// flow costs a full round trip through Add/Edit for each one. Here each scan is looked up and
/// parked, and the whole queue is reviewed and committed at the end.
///
/// Mirrors `shared`'s `com.foodtracker.viewmodel.BatchScanEntry`.
public struct BatchScanEntry: Identifiable, Equatable {
    /// Row identity, independent of `barcode` — a receipt-scanned row has no barcode at all
    /// (see `ReceiptParser`), so the queue can't key on one.
    public var id: String
    public var barcode: String?
    public var name: String
    public var brand: String?
    public var imageUrl: String?
    public var category: String?
    /// Starts from whatever the scan could work out — a date read off the packaging, or the
    /// shelf life this barcode was last given — so a queue of familiar products is often
    /// correct without the user touching any of it.
    public var expiryDate: Date
    public var quantity: Int
    public var storageLocation: String
    /// Where `expiryDate` came from, so the review list can flag the rows that are guesses.
    public var expirySource: ShelfLifeSource
    /// True while the product lookup is still in flight, so a row can appear immediately on the
    /// scan rather than after the network answers.
    public var isLookingUp: Bool

    /// True when nothing better than the fallback was known, so the user should look at it.
    public var needsAttention: Bool { expirySource == .fallback }

    /// The queue's Check date mark: a guess nothing better than the fallback backs up, and every
    /// receipt row, because a receipt prints no dates. Only a date read off a pack is exempt.
    public var showsCheckDate: Bool {
        !isLookingUp && (needsAttention || (id.hasPrefix("receipt_") && expirySource != .detected))
    }

    public init(
        id: String,
        barcode: String? = nil,
        name: String,
        brand: String? = nil,
        imageUrl: String? = nil,
        category: String? = nil,
        expiryDate: Date,
        quantity: Int = 1,
        storageLocation: String,
        expirySource: ShelfLifeSource,
        isLookingUp: Bool = false
    ) {
        self.id = id
        self.barcode = barcode
        self.name = name
        self.brand = brand
        self.imageUrl = imageUrl
        self.category = category
        self.expiryDate = expiryDate
        self.quantity = quantity
        self.storageLocation = storageLocation
        self.expirySource = expirySource
        self.isLookingUp = isLookingUp
    }
}
