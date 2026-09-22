import SwiftUI
import UIKit

public struct ScannerSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var viewModel: NativePantryViewModel

    @State private var mode: IOSCameraMode = .barcode
    @State private var showManualAdd: Bool = false
    @State private var cameraController: CameraScannerViewController?
    @State private var isCapturingPhoto: Bool = false
    @State private var capturedPhotoURL: URL?
    @State private var photoCaptureFailed: Bool = false
    @State private var torchEnabled: Bool = false

    var onProceedToAdd: (String?, String?, String?, String?, Date?, String?, ShelfLifeSuggestion?) -> Void
    // Hidden when this sheet is presented from within Add/Edit (see AddEditItemView's scan
    // button): "skip to manual entry" makes no sense there since manual entry is already the
    // screen underneath, and dismissing would otherwise discard fields already typed.
    var showManualEntryShortcut: Bool

    public init(
        viewModel: NativePantryViewModel,
        showManualEntryShortcut: Bool = true,
        onProceedToAdd: @escaping (String?, String?, String?, String?, Date?, String?, ShelfLifeSuggestion?) -> Void
    ) {
        self.viewModel = viewModel
        self.showManualEntryShortcut = showManualEntryShortcut
        self.onProceedToAdd = onProceedToAdd
    }

    public var body: some View {
        ZStack {
            // Live Camera View
            CameraScannerView(
                mode: mode,
                onBarcodeFound: { barcode in
                    if let gs1Date = GS1BarcodeParser.extractExpiryDate(from: barcode) {
                        viewModel.detectedDate = gs1Date
                    }
                    viewModel.lookupBarcode(barcode)
                },
                onDateFound: { date in
                    viewModel.detectedDate = date
                },
                onControllerReady: { controller in
                    cameraController = controller
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

                    if cameraController?.hasTorch == true {
                        Button(action: {
                            torchEnabled.toggle()
                            cameraController?.setTorchEnabled(torchEnabled)
                        }) {
                            Image(systemName: torchEnabled ? "bolt.fill" : "bolt.slash.fill")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.white)
                                .padding(10)
                                .background(Color.black.opacity(0.6))
                                .clipShape(Circle())
                        }
                        .accessibilityLabel(L10n.torchToggleButton)
                    }

                    if showManualEntryShortcut {
                        Button(action: {
                            onProceedToAdd(nil, nil, nil, nil, viewModel.detectedDate, nil, viewModel.suggestedExpiry)
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
                .colorScheme(.dark) // always sits over the live (dark) camera feed, independent of app theme
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

                if mode == .photo && photoCaptureFailed {
                    Text(L10n.photoCaptureFailedMessage)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.red.opacity(0.85))
                        .cornerRadius(20)
                        .padding(.bottom, 8)
                }

                // Shutter Button (Photo mode, before a photo has been taken)
                if mode == .photo && capturedPhotoURL == nil {
                    Button(action: {
                        guard let controller = cameraController, !isCapturingPhoto else { return }
                        isCapturingPhoto = true
                        photoCaptureFailed = false
                        controller.capturePhoto { url in
                            DispatchQueue.main.async {
                                isCapturingPhoto = false
                                capturedPhotoURL = url
                                photoCaptureFailed = url == nil
                            }
                        }
                    }) {
                        ZStack {
                            Circle()
                                .fill(Color.white)
                                .frame(width: 72, height: 72)
                                .overlay(Circle().stroke(Color.accentColor, lineWidth: 3))
                            if isCapturingPhoto {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .accentColor))
                            } else {
                                Image(systemName: "camera.fill")
                                    .font(.system(size: 26))
                                    .foregroundColor(.black)
                            }
                        }
                    }
                    .disabled(isCapturingPhoto)
                    .padding(.bottom, 24)
                }

                // Bottom Product Result Card
                if viewModel.isLookingUpBarcode || viewModel.scannedProduct != nil || (viewModel.productNotFoundBarcode != nil && mode != .photo) || capturedPhotoURL != nil {
                    VStack(alignment: .leading, spacing: 12) {
                        if viewModel.isLookingUpBarcode {
                            HStack(spacing: 12) {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .accentColor))
                                Text(L10n.lookingUpProduct)
                                    .font(.subheadline)
                            }
                            .padding(.vertical, 8)
                        } else if let photoURL = capturedPhotoURL {
                            HStack(spacing: 12) {
                                Group {
                                    if let uiImage = UIImage(contentsOfFile: photoURL.path) {
                                        Image(uiImage: uiImage)
                                            .resizable()
                                            .aspectRatio(contentMode: .fill)
                                    } else {
                                        Color.gray.opacity(0.3)
                                    }
                                }
                                .frame(width: 50, height: 50)
                                .clipShape(RoundedRectangle(cornerRadius: 8))

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(L10n.photoCapturedTitle)
                                        .font(.headline)
                                    Text(L10n.photoCapturedMessage)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                            }

                            HStack(spacing: 10) {
                                Button(action: {
                                    capturedPhotoURL = nil
                                }) {
                                    HStack {
                                        Image(systemName: "camera")
                                        Text(L10n.retakePhotoButton)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(Color(.secondarySystemFill))
                                    .cornerRadius(10)
                                }

                                Button(action: {
                                    onProceedToAdd(
                                        viewModel.scannedProduct?.barcode ?? viewModel.productNotFoundBarcode,
                                        viewModel.scannedProduct?.name,
                                        viewModel.scannedProduct?.brand,
                                        ProductPhotoStore.reference(for: photoURL),
                                        viewModel.detectedDate,
                                        viewModel.scannedProduct?.category,
                                        viewModel.suggestedExpiry
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
                        } else if let product = viewModel.scannedProduct {
                            HStack(spacing: 12) {
                                if let imgUrl = product.imageUrl, !imgUrl.isEmpty {
                                    ProductImageView(reference: imgUrl) {
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
                                        viewModel.detectedDate,
                                        product.category,
                                        viewModel.suggestedExpiry
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
                        } else if let notFoundBarcode = viewModel.productNotFoundBarcode, mode != .photo {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: "questionmark.circle")
                                    .font(.system(size: 22))
                                    .foregroundColor(.secondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(L10n.productNotFoundTitle)
                                        .font(.headline)
                                    Text(L10n.productNotFoundMessage)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }

                            HStack(spacing: 10) {
                                Button(action: {
                                    mode = .photo
                                }) {
                                    HStack {
                                        Image(systemName: "camera")
                                        Text(L10n.switchToPhotoButton)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(Color(.secondarySystemFill))
                                    .cornerRadius(10)
                                }

                                Button(action: {
                                    onProceedToAdd(notFoundBarcode, nil, nil, nil, viewModel.detectedDate, nil, viewModel.suggestedExpiry)
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
