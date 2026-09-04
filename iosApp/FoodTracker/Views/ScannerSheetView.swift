import SwiftUI

public struct ScannerSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var viewModel: NativePantryViewModel

    @State private var mode: IOSCameraMode = .barcode
    @State private var showManualAdd: Bool = false

    var onProceedToAdd: (String?, String?, String?, String?, Date?) -> Void

    public init(
        viewModel: NativePantryViewModel,
        onProceedToAdd: @escaping (String?, String?, String?, String?, Date?) -> Void
    ) {
        self.viewModel = viewModel
        self.onProceedToAdd = onProceedToAdd
    }

    public var body: some View {
        ZStack {
            // Live Camera View
            CameraScannerView(
                mode: mode,
                onBarcodeFound: { barcode in
                    viewModel.lookupBarcode(barcode)
                },
                onDateFound: { date in
                    viewModel.detectedDate = date
                }
            )
            .edgesIgnoringSafeArea(.all)

            // Overlays
            VStack {
                // Top bar
                HStack {
                    Button(action: { dismiss() }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.white)
                            .padding(10)
                            .background(Color.black.opacity(0.6))
                            .clipShape(Circle())
                    }

                    Spacer()

                    Button(action: {
                        onProceedToAdd(nil, nil, nil, nil, viewModel.detectedDate)
                        dismiss()
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "square.and.pencil")
                            Text(L10n.manualEntryButton)
                        }
                        .font(.footnote)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.6))
                        .cornerRadius(20)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)

                // Mode Picker
                Picker(L10n.scannerModePickerLabel, selection: $mode) {
                    Text(L10n.scanModeBarcode).tag(IOSCameraMode.barcode)
                    Text(L10n.scanModeDateOcr).tag(IOSCameraMode.dateOcr)
                    Text(L10n.scanModePhoto).tag(IOSCameraMode.photo)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 40)
                .padding(.top, 8)

                // Detected Expiry Date Chip
                if let detectedDate = viewModel.detectedDate {
                    let formatter = DateFormatter()
                    let _ = formatter.dateStyle = .medium
                    let dateStr = formatter.string(from: detectedDate)

                    HStack(spacing: 6) {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.white)
                        Text(L10n.expiryDateFound(dateStr))
                            .font(.subheadline)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Color.green)
                    .cornerRadius(20)
                    .padding(.top, 12)
                }

                Spacer()

                // Center Reticle
                VStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(Color.accentColor, lineWidth: 2.5)
                        .frame(
                            width: mode == .barcode ? 280 : 300,
                            height: mode == .barcode ? 160 : 120
                        )

                    Text(guideText)
                        .font(.caption)
                        .foregroundColor(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.black.opacity(0.65))
                        .cornerRadius(8)
                }

                Spacer()

                // Bottom Product Result Card
                if viewModel.isLookingUpBarcode || viewModel.scannedProduct != nil {
                    VStack(alignment: .leading, spacing: 12) {
                        if viewModel.isLookingUpBarcode {
                            HStack(spacing: 12) {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .accentColor))
                                Text(L10n.lookingUpProduct)
                                    .font(.subheadline)
                            }
                            .padding(.vertical, 8)
                        } else if let product = viewModel.scannedProduct {
                            HStack(spacing: 12) {
                                if let imgUrl = product.imageUrl, let url = URL(string: imgUrl) {
                                    AsyncImage(url: url) { image in
                                        image.resizable().aspectRatio(contentMode: .fill)
                                    } placeholder: {
                                        Color.gray.opacity(0.3)
                                    }
                                    .frame(width: 50, height: 50)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                }

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(product.name)
                                        .font(.headline)
                                        .lineLimit(1)
                                    if let brand = product.brand {
                                        Text(brand)
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                }
                                Spacer()
                            }

                            HStack(spacing: 10) {
                                Button(action: {
                                    mode = .dateOcr
                                }) {
                                    HStack {
                                        Image(systemName: "text.viewfinder")
                                        Text(L10n.scanDateButton)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(Color(.secondarySystemFill))
                                    .cornerRadius(10)
                                }

                                Button(action: {
                                    onProceedToAdd(
                                        product.barcode,
                                        product.name,
                                        product.brand,
                                        product.imageUrl,
                                        viewModel.detectedDate
                                    )
                                    dismiss()
                                }) {
                                    Text(L10n.continueButton)
                                        .fontWeight(.bold)
                                        .foregroundColor(.white)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .background(Color.accentColor)
                                        .cornerRadius(10)
                                }
                            }
                        }
                    }
                    .padding(16)
                    .background(Color(.systemBackground))
                    .cornerRadius(20)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                }
            }
        }
    }

    private var guideText: String {
        switch mode {
        case .barcode:
            return L10n.reticleHintBarcode
        case .dateOcr:
            return L10n.reticleHintDateOcr
        case .photo:
            return L10n.reticleHintPhoto
        }
    }
}
