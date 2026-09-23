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
    // Receipt capture is a one-shot read rather than a photo that is kept, so it needs its own
    // in-flight flag: `isCapturingPhoto` covers only the shutter, while this also spans the OCR
    // pass and the queue fill that follow it.
    @State private var isReadingReceipt: Bool = false
    @State private var receiptMessage: String?
    @State private var torchEnabled: Bool = false

    var onProceedToAdd: (String?, String?, String?, String?, Date?, String?, ShelfLifeSuggestion?) -> Void
    // Hidden when this sheet is presented from within Add/Edit (see AddEditItemView's scan
    // button): "skip to manual entry" makes no sense there since manual entry is already the
    // screen underneath, and dismissing would otherwise discard fields already typed.
    var showManualEntryShortcut: Bool
    /// Called instead of the product lookup when the camera reads one of our own shared-list QR
    /// codes (see `ShoppingListShare.isPayload`), so scanning a friend's list imports it rather
    /// than looking the payload up as if it were a product barcode.
    var onShoppingListScanned: (String) -> Void
    /// Reports how many items a batch commit added, so the host can surface a toast.
    var onBatchCommitted: (Int) -> Void

    public init(
        viewModel: NativePantryViewModel,
        showManualEntryShortcut: Bool = true,
        onShoppingListScanned: @escaping (String) -> Void = { _ in },
        onBatchCommitted: @escaping (Int) -> Void = { _ in },
        onProceedToAdd: @escaping (String?, String?, String?, String?, Date?, String?, ShelfLifeSuggestion?) -> Void
    ) {
        self.viewModel = viewModel
        self.showManualEntryShortcut = showManualEntryShortcut
        self.onShoppingListScanned = onShoppingListScanned
        self.onBatchCommitted = onBatchCommitted
        self.onProceedToAdd = onProceedToAdd
    }

    public var body: some View {
        ZStack {
            // Live Camera View
            CameraScannerView(
                mode: mode,
                onBarcodeFound: { barcode in
                    // A shared shopping list is a QR code, and so is a product's DataMatrix/QR -
                    // they arrive on the same callback. Route ours away before the lookup, or
                    // the whole JSON payload would be sent to Open Food Facts as a barcode.
                    guard !ShoppingListShare.isPayload(barcode) else {
                        onShoppingListScanned(barcode)
                        dismiss()
                        return
                    }
                    guard mode != .batch else {
                        viewModel.enqueueBatchScan(barcode)
                        return
                    }
                    if let gs1Date = GS1BarcodeParser.extractExpiryDate(from: barcode) {
                        viewModel.detectedDate = gs1Date
                    }
                    viewModel.lookupBarcode(barcode)
                    // Only the single-item flow asks: batch collapses repeats into one row's
                    // quantity by itself, and prompting per scan is exactly the interruption
                    // batch mode exists to avoid.
                    viewModel.checkForDuplicate(barcode)
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
                    Text(L10n.scanModeBatch).tag(IOSCameraMode.batch)
                    Text(L10n.scanModeReceipt).tag(IOSCameraMode.receipt)
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
                            width: (mode == .barcode || mode == .batch) ? 280 : 300,
                            height: (mode == .barcode || mode == .batch) ? 160 : 120
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

                // Receipt result banner. Transient and non-blocking: the queue below already
                // shows what was added, so this only has to report the count — and, more
                // usefully, the case where a photo yielded nothing at all.
                if let receiptMessage {
                    Text(receiptMessage)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.72))
                        .cornerRadius(20)
                        .padding(.bottom, 8)
                        .task(id: receiptMessage) {
                            try? await Task.sleep(for: .seconds(3))
                            self.receiptMessage = nil
                        }
                }

                // Shutter Button (Receipt mode). Separate from the photo shutter below: nothing
                // is kept afterwards — the still is read once, turned into queue rows and
                // deleted — so there is no captured-photo state to show or retake.
                if mode == .receipt {
                    Button(action: {
                        guard let controller = cameraController, !isReadingReceipt else { return }
                        isReadingReceipt = true
                        controller.capturePhoto { url in
                            guard let url else {
                                DispatchQueue.main.async { isReadingReceipt = false }
                                return
                            }
                            CameraScannerViewController.recognizeReceiptLines(at: url) { lines in
                                // The photo has served its purpose, and one of a receipt carries
                                // a card number and an address worth not leaving on disk.
                                try? FileManager.default.removeItem(at: url)
                                let added = viewModel.enqueueReceiptLines(lines)
                                receiptMessage = added > 0
                                    ? L10n.receiptLinesAdded(added)
                                    : L10n.receiptNothingFound
                                isReadingReceipt = false
                            }
                        }
                    }) {
                        ZStack {
                            Circle()
                                .fill(Color.white)
                                .frame(width: 72, height: 72)
                                .overlay(Circle().stroke(Color.accentColor, lineWidth: 3))
                            if isReadingReceipt {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .accentColor))
                            } else {
                                Image(systemName: "doc.text.viewfinder")
                                    .font(.system(size: 26))
                                    .foregroundColor(.black)
                            }
                        }
                    }
                    .disabled(isReadingReceipt)
                    .accessibilityLabel(L10n.scanReceiptButton)
                    .padding(.bottom, 24)
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
                if mode == .batch || mode == .receipt {
                    BatchQueuePanel(
                        entries: viewModel.batchQueue,
                        onRemove: { viewModel.removeBatchEntry($0) },
                        onClear: { viewModel.clearBatchQueue() },
                        onCommit: {
                            let added = viewModel.commitBatchQueue()
                            if added > 0 {
                                onBatchCommitted(added)
                                dismiss()
                            }
                        }
                    )
                } else if viewModel.isLookingUpBarcode || viewModel.scannedProduct != nil || (viewModel.productNotFoundBarcode != nil && mode != .photo) || capturedPhotoURL != nil {
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
        // Classify the captured photo once, so a barcode-less product (produce, bakery) can
        // suggest its own name. Keyed on the URL so retaking a photo reclassifies and
        // cancelling one drops the previous suggestions.
        .task(id: capturedPhotoURL) {
            guard let url = capturedPhotoURL else {
                viewModel.clearPhotoLabels()
                return
            }
            viewModel.onPhotoLabelingStarted()
            viewModel.onPhotoLabelsDetected(await ProductImageLabeler.suggestNames(photoURL: url))
        }
    }

    private var guideText: String {
        switch mode {
        case .batch:
            return L10n.reticleHintBatch
        case .receipt:
            return L10n.reticleHintReceipt
        case .barcode:
            return L10n.reticleHintBarcode
        case .dateOcr:
            return L10n.reticleHintDateOcr
        case .photo:
            return L10n.reticleHintPhoto
        }
    }
}


/// The batch queue, docked over the live camera so scanning and reviewing happen on one screen.
///
/// Rows appear the instant a barcode is read (see `NativePantryViewModel.enqueueBatchScan`) and
/// fill in as their lookups land, so the list visibly keeps up with the scanning rather than
/// lagging behind the network. Anything the app could not work out a date for is flagged rather
/// than silently defaulted — that is the one thing worth stopping to check before committing.
private struct BatchQueuePanel: View {
    let entries: [BatchScanEntry]
    let onRemove: (String) -> Void
    let onClear: () -> Void
    let onCommit: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text(entries.isEmpty ? L10n.batchQueueEmpty : L10n.batchQueueCount(entries.count))
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if !entries.isEmpty {
                    Button(L10n.batchClearButton, action: onClear)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            if !entries.isEmpty {
                // Capped height so a long queue scrolls inside the panel rather than swallowing
                // the camera preview it is docked over.
                ScrollView {
                    VStack(spacing: 8) {
                        ForEach(entries) { entry in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(entry.quantity > 1 ? "\(entry.name) ×\(entry.quantity)" : entry.name)
                                        .lineLimit(1)
                                    Text(subtitle(for: entry))
                                        .font(.caption)
                                        .foregroundStyle(
                                            entry.needsAttention && !entry.isLookingUp ? Color.red : Color.secondary
                                        )
                                }
                                Spacer()
                                Button {
                                    onRemove(entry.id)
                                } label: {
                                    Image(systemName: "xmark")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(L10n.batchQueueRemoveContentDescription)
                            }
                        }
                    }
                }
                .frame(maxHeight: 220)

                Button(action: onCommit) {
                    Text(L10n.batchCommitButton)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color.accentColor)
                        .cornerRadius(14)
                }
            }
        }
        .padding(16)
        .background(Color(.systemBackground))
        .cornerRadius(20)
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    private func subtitle(for entry: BatchScanEntry) -> String {
        if entry.isLookingUp { return L10n.lookingUpProduct }
        if entry.needsAttention { return L10n.batchQueueNeedsDate }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: entry.expiryDate)
    }
}
