import AVFoundation
import SwiftUI
import UIKit

public struct ScannerSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var viewModel: NativePantryViewModel

    @State private var cameraAccess = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var mode: IOSCameraMode
    @State private var showManualAdd: Bool = false
    @State private var cameraController: CameraScannerViewController?
    @State private var isCapturingPhoto: Bool = false
    @State private var capturedPhotoURL: URL?
    // A captured photo lives in Application Support and only becomes a saved item's imageUrl if
    // the user continues with it. Every other way out - retaking, switching mode, closing the
    // sheet - has to delete the file, or it stays on disk referenced by nothing. This flag marks
    // the one case that must not delete it.
    @State private var photoHandedOff: Bool = false
    @State private var photoCaptureFailed: Bool = false
    // Receipt capture is a one-shot read rather than a photo that is kept, so it needs its own
    // in-flight flag: `isCapturingPhoto` covers only the shutter, while this also spans the OCR
    // pass and the queue fill that follow it.
    @State private var isReadingReceipt: Bool = false
    @State private var transientMessage: String?
    @State private var confirmDiscardQueue: Bool = false
    @State private var torchEnabled: Bool = false

    var onProceedToAdd: (String?, String?, String?, String?, Date?, String?, ShelfLifeSuggestion?) -> Void
    // Hidden when this sheet is presented from within Add/Edit (see AddEditItemView's scan
    // button): "skip to manual entry" makes no sense there since manual entry is already the
    // screen underneath, and dismissing would otherwise discard fields already typed.
    var showManualEntryShortcut: Bool
    /// Called instead of the product lookup when the camera reads one of our own shared-list QR
    /// codes (see `ShoppingListShare.isPayload`), so scanning a friend's list imports it rather
    /// than looking the payload up as if it were a product barcode. Nil where the host cannot
    /// import one; the sheet then says so rather than silently swallowing the scan.
    var onShoppingListScanned: ((String) -> Void)?
    /// Reports how many items a batch commit added, so the host can surface a toast.
    var onBatchCommitted: (Int) -> Void
    /// Which modes this host can actually complete. A mode whose result the host has nowhere to
    /// put does not belong in the chip row - see AddEditItemView's call.
    var enabledModes: [IOSCameraMode]
    /// Whether to offer "add one to the one you already have" on a re-scan. Only hosts that
    /// render `duplicateOf` should ask for it.
    var checkDuplicates: Bool
    /// Takes one unit of the scanned product out of the pantry - the "I'm using this" answer to
    /// scanning something already there. Needs `checkDuplicates`, since `duplicateOf` is what
    /// says which pantry item it is; nil where the host has no use for it (Add/Edit, or a scan
    /// that is filling in a shopping list entry).
    var onUseOne: ((PantryItem) -> Void)?

    public init(
        viewModel: NativePantryViewModel,
        showManualEntryShortcut: Bool = true,
        enabledModes: [IOSCameraMode] = ScannerSheetView.defaultModes,
        initialMode: IOSCameraMode? = nil,
        checkDuplicates: Bool = true,
        onUseOne: ((PantryItem) -> Void)? = nil,
        onShoppingListScanned: ((String) -> Void)? = nil,
        onBatchCommitted: @escaping (Int) -> Void = { _ in },
        onProceedToAdd: @escaping (String?, String?, String?, String?, Date?, String?, ShelfLifeSuggestion?) -> Void
    ) {
        self.viewModel = viewModel
        self.showManualEntryShortcut = showManualEntryShortcut
        self.enabledModes = enabledModes
        self.checkDuplicates = checkDuplicates
        self.onUseOne = onUseOne
        self.onShoppingListScanned = onShoppingListScanned
        self.onBatchCommitted = onBatchCommitted
        self.onProceedToAdd = onProceedToAdd
        _mode = State(initialValue: initialMode ?? enabledModes.first ?? .barcode)
    }

    /// Chip order for the mode row: the plain barcode scan first, the two "unpack a whole shop"
    /// modes that extend it next, then the two single-item helpers.
    public static let defaultModes: [IOSCameraMode] = [.barcode, .batch, .receipt, .dateOcr, .photo]

    /// Batch and receipt fill one shared queue; every other mode produces a single result.
    private var batchModeActive: Bool { mode == .batch || mode == .receipt }

    /// Lets go of the single-item result on screen, so the camera takes the next barcode again.
    private var scanAgainButton: some View {
        Button(action: { viewModel.clearScanResults() }) {
            Label(L10n.scanAgainButton, systemImage: "arrow.clockwise")
                .font(.subheadline)
        }
    }

    /// A date on its own is a real result. Date OCR is a legitimate opening move - there may be
    /// no barcode to scan, or the user may only want the date - and until this had a card of its
    /// own the date could leave the sheet only through the Manual Entry shortcut, which the
    /// Add/Edit host hides, making the mode a dead end there.
    private var dateOnlyResult: Bool {
        !batchModeActive && !viewModel.isLookingUpBarcode && viewModel.detectedDate != nil
            && viewModel.scannedProduct == nil && viewModel.productNotFoundBarcode == nil
            && capturedPhotoURL == nil
    }

    /// Deletes a captured photo the user is not going on with, so it does not sit in Application
    /// Support referenced by nothing.
    private func discardCapturedPhoto() {
        if let url = capturedPhotoURL {
            try? FileManager.default.removeItem(at: url)
        }
        capturedPhotoURL = nil
    }

    /// Picking a mode from the chip row starts that mode clean. A result belongs to the mode
    /// that produced it, and leaving a product card, an OCR'd date or a captured photo published
    /// while the camera has moved on is how a stale result ends up on screen under a mode that
    /// could not have produced it. The two in-card hand-offs below ("Scan Date", "Try Photo
    /// Mode") assign `mode` directly instead, precisely because they *do* carry the scan
    /// forward. The batch queue survives either way: it is dropped only on commit, or on close.
    private func selectMode(_ target: IOSCameraMode) {
        guard target != mode else { return }
        discardCapturedPhoto()
        photoHandedOff = false
        photoCaptureFailed = false
        viewModel.clearScanResults()
        mode = target
    }

    /// An uncommitted queue does not outlive the sheet that shows it - otherwise its rows sit
    /// in state nothing displays, to be committed later alongside an unrelated batch.
    /// Discarding a shop's worth of scanning silently would be worse, so closing asks first.
    private func requestDismiss() {
        if viewModel.batchQueue.isEmpty {
            dismiss()
        } else {
            confirmDiscardQueue = true
        }
    }

    private func discardQueueAndDismiss() {
        viewModel.clearBatchQueue()
        dismiss()
    }

    public var body: some View {
        Group {
            switch cameraAccess {
            case .authorized:
                scanner
            case .notDetermined:
                // Blank behind the system prompt: telling someone the camera is off before they
                // have been asked would be wrong.
                Color.black.ignoresSafeArea()
            default:
                CameraOffView(
                    // Changing the permission relaunches the app, so no queue reaches this
                    // state; going through the discard keeps that true without a prompt here.
                    onClose: { discardQueueAndDismiss() },
                    onOpenSettings: {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    },
                    onAddByHand: {
                        // In Add/Edit the form is what is underneath, so closing is adding by hand.
                        if showManualEntryShortcut {
                            onProceedToAdd(nil, nil, nil, nil, viewModel.detectedDate, nil, viewModel.suggestedExpiry)
                        }
                        dismiss()
                    }
                )
            }
        }
        .task {
            guard cameraAccess == .notDetermined else { return }
            _ = await AVCaptureDevice.requestAccess(for: .video)
            cameraAccess = AVCaptureDevice.authorizationStatus(for: .video)
        }
        // Catches the way back from "Open settings", should the app survive the change.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                cameraAccess = AVCaptureDevice.authorizationStatus(for: .video)
            }
        }
    }

    private var scanner: some View {
        ZStack {
            // Live Camera View
            CameraScannerView(
                mode: mode,
                onBarcodeFound: { barcode in
                    // A shared shopping list is a QR code, and so is a product's DataMatrix/QR -
                    // they arrive on the same callback. Route ours away before the lookup, or
                    // the whole JSON payload would be sent to Open Food Facts as a barcode.
                    guard !ShoppingListShare.isPayload(barcode) else {
                        guard let onShoppingListScanned else {
                            // Say so rather than drop it: doing nothing is indistinguishable
                            // from the camera having failed to read the code.
                            transientMessage = L10n.shoppingListScanUnsupported
                            return
                        }
                        onShoppingListScanned(barcode)
                        dismiss()
                        return
                    }
                    guard mode != .batch else {
                        viewModel.enqueueBatchScan(barcode)
                        return
                    }
                    // The camera keeps reading barcodes while a result is on screen, so without
                    // this, nudging the phone towards the next product on the shelf would swap
                    // the card out from under the user. "Scan Again" lets the held one go.
                    guard !viewModel.isHoldingBarcodeResult else { return }
                    if let gs1Date = GS1BarcodeParser.extractExpiryDate(from: barcode) {
                        viewModel.detectedDate = gs1Date
                    }
                    viewModel.lookupBarcode(barcode)
                    // Only where the host renders the prompt, and only outside batch: batch
                    // collapses repeats into one row's quantity by itself, and prompting per
                    // scan is exactly the interruption batch mode exists to avoid.
                    if checkDuplicates {
                        viewModel.checkForDuplicate(barcode)
                    }
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
                    Button(action: { requestDismiss() }) {
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

                    // Not in batch or receipt: leaving for the single-item form mid-queue
                    // strands the rows already scanned on a sheet that no longer shows them.
                    if showManualEntryShortcut && !batchModeActive {
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

                // Scanner mode selector. Wraps rather than compressing into a segmented
                // control: every mode has to stay readable, and five segments squeeze
                // "Receipt" and "Date OCR" down to truncated stubs on a narrow phone.
                FlowLayout(spacing: 8) {
                    ForEach(enabledModes, id: \.self) { candidate in
                        ScanModeChip(
                            mode: candidate,
                            selected: candidate == mode,
                            // A queue built in batch outlives a switch to another mode, where
                            // the panel that shows it is hidden. Without this it is invisible.
                            queuedCount: candidate == .batch ? viewModel.batchQueue.count : 0,
                            onTap: { selectMode(candidate) }
                        )
                    }
                }
                .accessibilityLabel(L10n.scannerModePickerLabel)
                .padding(.horizontal, 16)
                .padding(.top, 8)

                // Detected Expiry Date Chip. Suppressed when the date is the whole result: the
                // card at the bottom is then showing the same date, with something to do
                // about it.
                if let detectedDate = viewModel.detectedDate, !dateOnlyResult {
                    let formatter = DateFormatter()
                    let _ = formatter.dateStyle = .medium
                    let dateStr = formatter.string(from: detectedDate)

                    HStack(spacing: 6) {
                        Image(systemName: "checkmark.circle.fill")
                        Text(L10n.expiryDateFound(dateStr))
                            .font(.subheadline)
                            .fontWeight(.bold)
                    }
                    .foregroundColor(LarderColor.onSuccessContainer)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(LarderColor.successContainer)
                    .cornerRadius(20)
                    .padding(.top, 12)
                }

                Spacer()

                // Center Reticle
                VStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(LarderColor.primary, lineWidth: 2.5)
                        .frame(width: reticleSize.width, height: reticleSize.height)

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
                        .foregroundColor(LarderColor.onError)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(LarderColor.error)
                        .cornerRadius(20)
                        .padding(.bottom, 8)
                }

                // Transient banner. Non-blocking: for a receipt the queue below already shows
                // what was added, so this only has to report the count — and, more usefully,
                // the cases where a scan produced nothing this sheet could act on.
                if let transientMessage {
                    Text(transientMessage)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.72))
                        .cornerRadius(20)
                        .padding(.bottom, 8)
                        .task(id: transientMessage) {
                            try? await Task.sleep(for: .seconds(3))
                            self.transientMessage = nil
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
                                transientMessage = added > 0
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
                                .overlay(Circle().stroke(LarderColor.primary, lineWidth: 3))
                            if isReadingReceipt {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: LarderColor.primary))
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
                                .overlay(Circle().stroke(LarderColor.primary, lineWidth: 3))
                            if isCapturingPhoto {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: LarderColor.primary))
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
                if batchModeActive {
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
                } else if viewModel.isLookingUpBarcode || viewModel.scannedProduct != nil || (viewModel.productNotFoundBarcode != nil && mode != .photo) || capturedPhotoURL != nil || dateOnlyResult {
                    VStack(alignment: .leading, spacing: 12) {
                        if viewModel.isLookingUpBarcode {
                            HStack(spacing: 12) {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: LarderColor.primary))
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
                                        LarderColor.surfaceContainer
                                    }
                                }
                                .frame(width: 50, height: 50)
                                .clipShape(RoundedRectangle(cornerRadius: 8))

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(L10n.photoCapturedTitle)
                                        .font(.headline)
                                    // Name the product this photo is being attached to when
                                    // there is one: this branch outranks the product branch
                                    // below, so without it the card would show a photo and
                                    // nothing about what was scanned.
                                    Text(viewModel.scannedProduct?.name
                                        ?? viewModel.productNotFoundBarcode
                                        ?? L10n.photoCapturedMessage)
                                        .font(.caption)
                                        .foregroundColor(LarderColor.onSurfaceVariant)
                                        .lineLimit(2)
                                }
                                Spacer()
                            }

                            HStack(spacing: 10) {
                                Button(action: {
                                    discardCapturedPhoto()
                                }) {
                                    HStack {
                                        Image(systemName: "camera")
                                        Text(L10n.retakePhotoButton)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(LarderColor.surfaceContainer)
                                    .cornerRadius(10)
                                }

                                Button(action: {
                                    // The saved item will point at this file, so it survives
                                    // this sheet going away.
                                    photoHandedOff = true
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
                                        .foregroundColor(LarderColor.onPrimary)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .background(LarderColor.primary)
                                        .cornerRadius(10)
                                }
                            }
                        } else if let product = viewModel.scannedProduct {
                            // duplicateOf is looked up alongside the product, not after it, so
                            // it is checked against this product's barcode rather than trusted
                            // to be about the same scan.
                            let inPantry = viewModel.duplicateOf.flatMap { item in
                                onUseOne != nil && item.barcode == product.barcode ? item : nil
                            }
                            HStack(spacing: 12) {
                                if let imgUrl = product.imageUrl, !imgUrl.isEmpty {
                                    ProductImageView(reference: imgUrl) {
                                        LarderColor.surfaceContainer
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
                                            .foregroundColor(LarderColor.onSurfaceVariant)
                                    }
                                    if let inPantry {
                                        Text(L10n.inPantryCount(inPantry.quantity))
                                            .font(.caption.weight(.semibold))
                                            .foregroundColor(LarderColor.primary)
                                    }
                                }
                                Spacer()
                                scanAgainButton
                            }

                            if let inPantry, let onUseOne {
                                Button(action: {
                                    onUseOne(inPantry)
                                    viewModel.clearScanResults()
                                    dismiss()
                                }) {
                                    HStack {
                                        Image(systemName: "minus.circle")
                                        Text(L10n.useOneButton)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(LarderColor.surfaceContainer)
                                    .cornerRadius(10)
                                }
                            }

                            HStack(spacing: 10) {
                                Button(action: {
                                    // A hand-off, not a mode pick: this deliberately carries the
                                    // product forward so the OCR'd date lands on it, which is
                                    // why it sets `mode` instead of going through selectMode.
                                    mode = .dateOcr
                                }) {
                                    HStack {
                                        Image(systemName: "text.viewfinder")
                                        Text(L10n.scanDateButton)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(LarderColor.surfaceContainer)
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
                                        .foregroundColor(LarderColor.onPrimary)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .background(LarderColor.primary)
                                        .cornerRadius(10)
                                }
                            }
                        } else if let notFoundBarcode = viewModel.productNotFoundBarcode, mode != .photo {
                            // A notice, not an error: an unknown barcode is routine, and the
                            // two buttons below already say what can be done about it.
                            HStack(spacing: 10) {
                                Image(systemName: "info.circle")
                                    .font(.system(size: 17))
                                    .foregroundColor(LarderColor.onSurfaceVariant)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(L10n.productNotFoundTitle)
                                        .font(.subheadline.weight(.semibold))
                                    Text(notFoundBarcode)
                                        .font(.caption2)
                                        .foregroundColor(LarderColor.onSurfaceVariant)
                                }
                                Spacer()
                                scanAgainButton
                            }

                            HStack(spacing: 10) {
                                Button(action: {
                                    // Also a hand-off: the unrecognised barcode has to survive
                                    // into photo mode so the photo is attached to it. Hence not
                                    // selectMode, and hence the `mode != .photo` guard above,
                                    // which hides this card once the hand-off has happened.
                                    mode = .photo
                                }) {
                                    HStack {
                                        Image(systemName: "camera")
                                        Text(L10n.switchToPhotoButton)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(LarderColor.surfaceContainer)
                                    .cornerRadius(10)
                                }

                                Button(action: {
                                    onProceedToAdd(notFoundBarcode, nil, nil, nil, viewModel.detectedDate, nil, viewModel.suggestedExpiry)
                                    dismiss()
                                }) {
                                    Text(L10n.continueButton)
                                        .fontWeight(.bold)
                                        .foregroundColor(LarderColor.onPrimary)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .background(LarderColor.primary)
                                        .cornerRadius(10)
                                }
                            }
                        } else if let date = viewModel.detectedDate {
                            // Date-only: an OCR'd date with no product attached to it. The way
                            // on is the same Add/Edit hand-off every other branch uses,
                            // carrying the date and nothing else.
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: "calendar.badge.checkmark")
                                    .font(.system(size: 22))
                                    .foregroundColor(LarderColor.primary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(L10n.dateCapturedTitle)
                                        .font(.headline)
                                    Text(date, style: .date)
                                        .font(.caption)
                                        .foregroundColor(LarderColor.onSurfaceVariant)
                                }
                                Spacer()
                            }

                            HStack(spacing: 10) {
                                Button(action: {
                                    viewModel.clearScanResults()
                                }) {
                                    HStack {
                                        Image(systemName: "arrow.clockwise")
                                        Text(L10n.scanAgainButton)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(LarderColor.surfaceContainer)
                                    .cornerRadius(10)
                                }

                                Button(action: {
                                    onProceedToAdd(nil, nil, nil, nil, date, nil, viewModel.suggestedExpiry)
                                    dismiss()
                                }) {
                                    Text(L10n.continueButton)
                                        .fontWeight(.bold)
                                        .foregroundColor(LarderColor.onPrimary)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .background(LarderColor.primary)
                                        .cornerRadius(10)
                                }
                            }
                        }
                    }
                    .padding(16)
                    .background(LarderColor.surface)
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
        // Catches every remaining way out - the close button, the swipe-down dismiss, the host
        // tearing the sheet down - for a photo that was taken but never continued with.
        .onDisappear {
            if !photoHandedOff, let url = capturedPhotoURL {
                try? FileManager.default.removeItem(at: url)
            }
        }
        // A swipe-down would otherwise close the sheet without going through the prompt below,
        // taking the queue out of view but not out of state.
        .interactiveDismissDisabled(!viewModel.batchQueue.isEmpty)
        .alert(L10n.batchDiscardTitle, isPresented: $confirmDiscardQueue) {
            Button(L10n.batchDiscardConfirm, role: .destructive) { discardQueueAndDismiss() }
            Button(L10n.batchDiscardKeep, role: .cancel) { confirmDiscardQueue = false }
        } message: {
            Text(L10n.batchDiscardMessage(viewModel.batchQueue.count))
        }
    }

    /// The barcode modes want a landscape box; a receipt is tall and narrow, and framing one
    /// inside the landscape box is not what "fit the whole receipt in frame" is asking for.
    private var reticleSize: CGSize {
        switch mode {
        case .barcode, .batch: return CGSize(width: 280, height: 160)
        case .receipt: return CGSize(width: 240, height: 300)
        case .dateOcr, .photo: return CGSize(width: 300, height: 120)
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


/// Stands in for the whole scanner when the camera is refused: every mode needs the camera, so
/// the mode chips and the torch go with it and only the way out stays.
private struct CameraOffView: View {
    let onClose: () -> Void
    let onOpenSettings: () -> Void
    let onAddByHand: () -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 12) {
                    Image(systemName: "video.slash.fill")
                        .font(.system(size: 40))
                        .accessibilityHidden(true)
                    Text(L10n.cameraOffTitle)
                        .font(.title3)
                        .fontWeight(.bold)
                    Text(L10n.cameraOffBody)
                        .font(.subheadline)
                    Button(action: onOpenSettings) {
                        Text(L10n.cameraOffOpenSettings)
                            .fontWeight(.semibold)
                            .foregroundColor(LarderColor.onPrimary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(LarderColor.primary)
                            .cornerRadius(12)
                    }
                    .padding(.top, 12)
                    Button(action: onAddByHand) {
                        Label(L10n.cameraOffAddByHand, systemImage: "square.and.pencil")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                }
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
                .padding(.vertical, 72)
                .frame(maxWidth: .infinity)
            }
            .defaultScrollAnchor(.center)

            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.white)
                    .padding(10)
                    .background(Color.black.opacity(0.6))
                    .clipShape(Circle())
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
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
                        .foregroundStyle(LarderColor.onSurfaceVariant)
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
                                            entry.needsAttention && !entry.isLookingUp ? LarderColor.error : LarderColor.onSurfaceVariant
                                        )
                                }
                                Spacer()
                                Button {
                                    onRemove(entry.id)
                                } label: {
                                    Image(systemName: "xmark")
                                        .font(.caption)
                                        .foregroundStyle(LarderColor.onSurfaceVariant)
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
                        .foregroundColor(LarderColor.onPrimary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(LarderColor.primary)
                        .cornerRadius(14)
                }
            }
        }
        .padding(16)
        .background(LarderColor.surface)
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

/// One mode chip. `queuedCount` hangs a count off the label when that mode is holding work the
/// user cannot currently see — only batch has any, and only while its panel is hidden behind
/// another mode.
private struct ScanModeChip: View {
    let mode: IOSCameraMode
    let selected: Bool
    let queuedCount: Int
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Image(systemName: symbolName)
                    .font(.system(size: 13, weight: .semibold))
                Text(queuedCount > 0 ? "\(label) (\(queuedCount))" : label)
                    .font(.subheadline)
                    .fontWeight(.semibold)
            }
            .foregroundColor(selected ? LarderColor.onPrimary : .white)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(selected ? LarderColor.primary : Color.black.opacity(0.6))
            .clipShape(Capsule())
            // An unselected chip's translucent black reads as nothing against a dark scene, so
            // outline it - otherwise the other modes look like bare text rather than buttons.
            .overlay(
                Capsule().stroke(Color.white.opacity(selected ? 0 : 0.4), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private var label: String {
        switch mode {
        case .barcode: return L10n.scanModeBarcode
        case .batch: return L10n.scanModeBatch
        case .receipt: return L10n.scanModeReceipt
        case .dateOcr: return L10n.scanModeDateOcr
        case .photo: return L10n.scanModePhoto
        }
    }

    private var symbolName: String {
        switch mode {
        case .barcode: return "qrcode.viewfinder"
        case .batch: return "text.badge.plus"
        case .receipt: return "doc.text.viewfinder"
        case .dateOcr: return "text.viewfinder"
        case .photo: return "camera.fill"
        }
    }
}
