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
            if let imageUrl = item.imageUrl, let url = URL(string: imageUrl), url.isFileURL {
                Group {
                    if let uiImage = UIImage(contentsOfFile: url.path) {
                        Image(uiImage: uiImage)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    } else {
                        placeholderImage
                    }
                }
                .frame(width: 54, height: 54)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            } else if let imageUrl = item.imageUrl, let url = URL(string: imageUrl) {
                AsyncImage(url: url, transaction: Transaction(animation: .easeInOut(duration: 0.25))) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable()
                            .aspectRatio(contentMode: .fill)
                            .transition(.opacity)
                    default:
                        placeholderImage
                    }
                }
                .frame(width: 54, height: 54)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            } else {
                placeholderImage
                    .frame(width: 54, height: 54)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }

            // Info
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(.headline)
                    .lineLimit(2)

                if let brand = item.brand, !brand.isEmpty {
                    Text(brand)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                // Chips flow onto a new row when they don't fit instead of an HStack
                // squeezing each one into multi-line text.
                FlowLayout(spacing: 6) {
                    badgeView

                    chip(item.storageLocation)

                    if let category = resolveCategoryDisplayName(item.category, categoryPreferences), !category.isEmpty {
                        chip(category)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let onDecrement {
                HStack(spacing: 4) {
                    Button(action: onDecrement) {
                        Image(systemName: "minus.circle")
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L10n.useOneButton)
                    Text(L10n.quantityFormat(item.quantity))
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.secondary)
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
            Color(.secondarySystemFill)
            Image(systemName: "takeoutbag.and.cup.and.straw.fill")
                .foregroundColor(.accentColor)
                .font(.system(size: 24))
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
            .background(Color(.secondarySystemFill))
            .cornerRadius(6)
    }

    private var badgeView: some View {
        let (bg, fg, icon) = statusColorsAndIcon(for: item.expiryStatus(expiringSoonDays: expiringSoonDays))
        return HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 10, weight: .bold))
            Text(item.daysRemainingFormatted)
                .font(.caption2)
                .fontWeight(.semibold)
                .lineLimit(1)
        }
        .fixedSize()
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(bg)
        .foregroundColor(fg)
        .cornerRadius(6)
    }

    private func statusColorsAndIcon(for status: ExpiryStatus) -> (Color, Color, String) {
        switch status {
        case .expired:
            return (Color.red.opacity(0.15), Color.red, "exclamationmark.triangle.fill")
        case .expiringToday, .expiringSoon:
            return (Color.orange.opacity(0.15), Color.orange, "clock.fill")
        case .fresh:
            return (Color.green.opacity(0.15), Color.green, "checkmark.circle.fill")
        }
    }
}
