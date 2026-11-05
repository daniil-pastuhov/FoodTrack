import SwiftUI
import UIKit

public struct ItemRowView: View {
    public let item: PantryItem
    public var expiringSoonDays: Int = 3
    public var categoryPreferences: [CategoryPreference] = []
    public var onDecrement: (() -> Void)? = nil

    public var body: some View {
        HStack(spacing: 12) {
            // Product image
            ProductImageView(reference: item.imageUrl) {
                placeholderImage
            }
            .frame(width: 54, height: 54)
            .clipShape(RoundedRectangle(cornerRadius: 10))

            // Info
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(.headline)
                    .lineLimit(2)

                if let brand = item.brand, !brand.isEmpty {
                    Text(brand)
                        .font(.caption)
                        .foregroundColor(LarderColor.onSurfaceVariant)
                        .lineLimit(1)
                }

                // Chips flow onto a new row when they don't fit instead of an HStack
                // squeezing each one into multi-line text.
                FlowLayout(spacing: 6) {
                    StatusChip(item: item, expiringSoonDays: expiringSoonDays)

                    chip(L10n.storageLocationName(item.storageLocation))

                    if let category = resolveCategoryDisplayName(item.category, categoryPreferences), !category.isEmpty {
                        chip(L10n.categoryName(category))
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let onDecrement {
                HStack(spacing: 4) {
                    Button(action: onDecrement) {
                        Image(systemName: "minus.circle")
                            .foregroundColor(LarderColor.onSurfaceVariant)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L10n.useOneButton)
                    Text(L10n.quantityFormat(item.quantity))
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(LarderColor.onSurfaceVariant)
                        .contentTransition(.numericText())
                        .animation(.easeInOut(duration: 0.2), value: item.quantity)
                }
                .fixedSize()
            }
        }
        .padding(.vertical, 4)
        .animation(.easeInOut(duration: 0.2), value: item.daysRemainingFormatted)
    }

    private var placeholderImage: some View {
        ZStack {
            LarderColor.surfaceContainer
            LarderIcon(.forCategory(item.category), size: .large)
                .foregroundColor(LarderColor.onSurfaceVariant)
        }
    }

    private func chip(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .fontWeight(.medium)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(LarderColor.surfaceContainer)
            .cornerRadius(6)
    }
}
