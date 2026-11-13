import AVFoundation
import SwiftUI
import UIKit

/// What the result panel is showing.
enum ScanPanel {
    case none, queue, lookingUp, photo, product, notFound, offline, date
}

extension IOSCameraMode {
    /// Batch and receipt fill one shared queue; every other mode produces a single result.
    var usesQueue: Bool { self == .batch || self == .receipt }

    /// The aiming frame: landscape for barcodes, wider and shallower for a printed date, square
    /// for a whole product, tall for a receipt.
    func frameSize(expanded: Bool) -> CGSize {
        switch self {
        case .barcode, .batch: return expanded ? CGSize(width: 360, height: 200) : CGSize(width: 280, height: 160)
        case .dateOcr: return expanded ? CGSize(width: 380, height: 150) : CGSize(width: 300, height: 120)
        case .photo: return expanded ? CGSize(width: 340, height: 340) : CGSize(width: 260, height: 260)
        case .receipt: return expanded ? CGSize(width: 300, height: 480) : CGSize(width: 240, height: 300)
        }
    }
}

/// Where the date on a product result came from.
private enum ScanDateSource {
    case barcode, packaging
}

/// Where to go once a queue has been discarded.
private enum AfterDiscard {
    case leave, typeItIn
}

private enum ScanMetrics {
    /// Text on the live picture only ever sits on this, the lowest round value that keeps
    /// on-surface above 4.5:1 with a white packet behind it.
    static let scrimOpacity = 0.72
    /// How far the picture outside the aiming frame is dimmed.
    static let dimOpacity = 0.45
    /// The tallest the result panel gets on a phone, so some of the camera always shows.
    static let panelMaxHeightFraction = 0.7
    static let expandedPanelWidth: CGFloat = 400
    static let shutterSize: CGFloat = 72
    static let resultThumbSize: CGFloat = 60
    static let queueListMaxHeight: CGFloat = 270
    static let queueRowMinHeight: CGFloat = 60
    static let modeSlotMinHeight: CGFloat = 60
    /// Sits the snackbar below the top controls, clear of the shutter and the result panel.
    static let snackbarTopOffset: CGFloat = 72
    static let cameraOffActionsMaxWidth: CGFloat = 280
}

private var scanScrim: Color { LarderColor.scrim.opacity(ScanMetrics.scrimOpacity) }

public struct ScannerSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var viewModel: NativePantryViewModel

    @State private var cameraAccess = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var mode: IOSCameraMode
    @State private var cameraController: CameraScannerViewController?
    @State private var isCapturingPhoto: Bool = false
    @State private var capturedPhotoURL: URL?
    // A captured photo lives in Application Support and only becomes a saved item's imageUrl if
    // the user continues with it. Every other way out - retaking, switching mode, closing the
    // scanner - has to delete the file, or it stays on disk referenced by nothing. This flag
    // marks the one case that must not delete it.
    @State private var photoHandedOff: Bool = false
    // Receipt capture is a one-shot read rather than a photo that is kept, so it needs its own
    // in-flight flag: `isCapturingPhoto` covers only the shutter, while this also spans the OCR
    // pass and the queue fill that follow it.
    @State private var isReadingReceipt: Bool = false
    @State private var snackbar: LarderSnackbarContent?
    @State private var pendingDiscard: AfterDiscard?
    @State private var askDuplicate: Bool = false
    @State private var torchEnabled: Bool = false
    /// A product's date came off its barcode unless Scan date read it.
    @State private var dateReadOffPack: Bool = false

    var onProceedToAdd: (String?, String?, String?, String?, Date?, String?, ShelfLifeSuggestion?) -> Void
    // Hidden when this is presented from within Add/Edit (see AddEditItemView's scan button):
    // "skip to manual entry" makes no sense there since manual entry is already the screen
    // underneath, and dismissing would otherwise discard fields already typed.
    var showManualEntryShortcut: Bool
    /// Called instead of the product lookup when the camera reads one of our own shared-list QR
    /// codes (see `ShoppingListShare.isPayload`), so scanning a friend's list imports it rather
    /// than looking the payload up as if it were a product barcode. Nil where the host cannot
    /// import one; the scanner then says so rather than silently swallowing the scan.
    var onShoppingListScanned: ((String) -> Void)?
    /// Reports how many items a batch commit added, so the host can confirm it.
    var onBatchCommitted: (Int) -> Void
    /// Which modes this host can actually complete. A mode whose result the host has nowhere to
    /// put does not belong in the mode bar - see AddEditItemView's call.
    var enabledModes: [IOSCameraMode]
    /// Whether to offer "add one to the one you already have" on a re-scan.
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

    /// Slot order for the mode bar: the plain barcode scan first, the two "unpack a whole shop"
    /// modes that extend it next, then the two single-item helpers.
    public static let defaultModes: [IOSCameraMode] = [.barcode, .batch, .receipt, .dateOcr, .photo]

    /// The panel for this moment. A not-found barcode stays quiet in Photo mode, because that is
    /// what Photo mode on the result hands off into. A date is only the whole result with no
    /// barcode held.
    private var panel: ScanPanel {
        if mode.usesQueue { return .queue }
        if viewModel.isLookingUpBarcode { return .lookingUp }
        if capturedPhotoURL != nil { return .photo }
        if viewModel.scannedProduct != nil { return .product }
        if viewModel.productNotFoundBarcode != nil {
            if mode == .photo { return .none }
            if case .offline = viewModel.lookupResult { return .offline }
            return .notFound
        }
        return viewModel.detectedDate != nil ? .date : .none
    }

    /// Whether the aiming hint shows. It goes once there is a result, except while a date is
    /// being read for a product, and on Receipt once the queue has something to check.
    private var showsHint: Bool {
        switch panel {
        case .none: return true
        case .queue: return mode == .batch || (viewModel.batchQueue.isEmpty && !isReadingReceipt)
        case .product: return mode == .dateOcr
        default: return false
        }
    }

    /// duplicateOf is looked up alongside the product, not after it, so it is checked against
    /// this product's barcode rather than trusted to be about the same scan.
    private var inPantry: PantryItem? {
        guard onUseOne != nil, let product = viewModel.scannedProduct else { return nil }
        return viewModel.duplicateOf.flatMap { $0.barcode == product.barcode ? $0 : nil }
    }

    /// Deletes a captured photo the user is not going on with, so it does not sit in Application
    /// Support referenced by nothing.
    private func discardCapturedPhoto() {
        if let url = capturedPhotoURL {
            try? FileManager.default.removeItem(at: url)
        }
        capturedPhotoURL = nil
    }

    private func clearResults() {
        dateReadOffPack = false
        viewModel.clearScanResults()
    }

    /// Picking a mode from the bar starts that mode clean. A result belongs to the mode that
    /// produced it, and leaving a product, an OCR'd date or a captured photo published while the
    /// camera has moved on is how a stale result ends up on screen under a mode that could not
    /// have produced it. The two hand-offs on the result panel (Scan date, Photo mode) assign
    /// `mode` directly instead, precisely because they *do* carry the scan forward. The batch
    /// queue survives either way: it is dropped only on commit, or on close.
    private func selectMode(_ target: IOSCameraMode) {
        guard target != mode else { return }
        discardCapturedPhoto()
        photoHandedOff = false
        clearResults()
        mode = target
    }

    /// Hands the current scan's date and suggestion to Add item, with whatever else is known.
    private func proceed(
        barcode: String? = nil,
        name: String? = nil,
        brand: String? = nil,
        imageUrl: String? = nil,
        category: String? = nil
    ) {
        onProceedToAdd(barcode, name, brand, imageUrl, viewModel.detectedDate, category, viewModel.suggestedExpiry)
        dismiss()
    }

    private func typeItInNow() {
        // Add item would otherwise ask about the product on screen, which isn't what's being added.
        viewModel.dismissDuplicate()
        proceed()
    }

    /// Leaves with nothing carried on, so the next scan doesn't open on this one's result.
    private func close() {
        clearResults()
        dismiss()
    }

    /// An uncommitted queue does not outlive the screen that shows it - otherwise its rows sit
    /// in state nothing displays, to be committed later alongside an unrelated batch.
    /// Discarding a shop's worth of scanning silently would be worse, so closing asks first.
    private func requestClose() {
        if viewModel.batchQueue.isEmpty { close() } else { pendingDiscard = .leave }
    }

    private func requestTypeItIn() {
        if viewModel.batchQueue.isEmpty { typeItInNow() } else { pendingDiscard = .typeItIn }
    }

    private func discardQueueAndClose() {
        viewModel.clearBatchQueue()
        close()
    }

    private func removeQueueRow(_ entry: BatchScanEntry) {
        guard let index = viewModel.batchQueue.firstIndex(of: entry) else { return }
        viewModel.removeBatchEntry(entry.id)
        snackbar = LarderSnackbarContent(
            message: L10n.itemRemovedToast(entry.name),
            actionTitle: L10n.undoAction,
            action: { viewModel.restoreBatchEntry(entry, at: index) }
        )
    }

    private func clearQueue() {
        let entries = viewModel.batchQueue
        viewModel.clearBatchQueue()
        snackbar = LarderSnackbarContent(
            message: L10n.scanQueueCleared,
            actionTitle: L10n.undoAction,
            action: { viewModel.restoreBatchQueue(entries) }
        )
    }

    private func takePhoto() {
        guard let controller = cameraController, !isCapturingPhoto else { return }
        isCapturingPhoto = true
        controller.capturePhoto { url in
            DispatchQueue.main.async {
                isCapturingPhoto = false
                capturedPhotoURL = url
                if url == nil {
                    snackbar = LarderSnackbarContent(message: L10n.photoCaptureFailedMessage)
                }
            }
        }
    }

    private func readReceipt() {
        guard let controller = cameraController, !isReadingReceipt else { return }
        isReadingReceipt = true
        controller.capturePhoto { url in
            guard let url else {
                DispatchQueue.main.async {
                    isReadingReceipt = false
                    snackbar = LarderSnackbarContent(message: L10n.photoCaptureFailedMessage)
                }
                return
            }
            CameraScannerViewController.recognizeReceiptLines(at: url) { lines in
                // The photo has served its purpose, and one of a receipt carries a card number
                // and an address worth not leaving on disk.
                try? FileManager.default.removeItem(at: url)
                let added = viewModel.enqueueReceiptLines(lines)
                snackbar = LarderSnackbarContent(
                    message: added > 0 ? L10n.receiptLinesAdded(added) : L10n.receiptNothingFound
                )
                isReadingReceipt = false
            }
        }
    }

    public var body: some View {
        Group {
            switch cameraAccess {
            case .authorized:
                scanner
            case .notDetermined:
                // Blank behind the system prompt: telling someone the camera is off before they
                // have been asked would be wrong.
                LarderColor.scrim.ignoresSafeArea()
            default:
                CameraOffView(
                    // Changing the permission relaunches the app, so no queue reaches this
                    // state; going through the discard keeps that true without a prompt here.
                    onClose: { discardQueueAndClose() },
                    onOpenSettings: {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    },
                    onAddByHand: {
                        // In Add/Edit the form is what is underneath, so closing is adding by hand.
                        if showManualEntryShortcut { proceed() } else { dismiss() }
                    }
                )
            }
        }
        .statusBarHidden()
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
        // Measured here rather than read from `isExpanded`: inside Add/Edit's sheet on iPad the
        // window is wide but this isn't, and the split needs the room it actually has.
        GeometryReader { proxy in
            let width = proxy.size.width + proxy.safeAreaInsets.leading + proxy.safeAreaInsets.trailing
            let expanded = width >= LarderLayout.expandedMinWidth
            let height = proxy.size.height + proxy.safeAreaInsets.top + proxy.safeAreaInsets.bottom
            Group {
                if expanded {
                    HStack(spacing: 0) {
                        VStack(spacing: 0) {
                            cameraArea(expanded: true, panelMaxHeight: 0)
                            modeBar
                                .padding(.horizontal, (width - ScanMetrics.expandedPanelWidth) * 0.15)
                                .background(LarderColor.background.ignoresSafeArea(edges: .bottom))
                                .environment(\.colorScheme, .dark)
                        }
                        Group {
                            if panel == .none {
                                idlePanel
                            } else {
                                panelContent(expanded: true)
                            }
                        }
                        .frame(width: ScanMetrics.expandedPanelWidth)
                        .frame(maxHeight: .infinity, alignment: .top)
                        .background(LarderColor.surface.ignoresSafeArea())
                    }
                } else {
                    VStack(spacing: 0) {
                        cameraArea(expanded: false, panelMaxHeight: height * ScanMetrics.panelMaxHeightFraction)
                        modeBar
                            .background(LarderColor.background.ignoresSafeArea(edges: .bottom))
                            .environment(\.colorScheme, .dark)
                    }
                }
            }
        }
        .background(LarderColor.scrim.ignoresSafeArea())
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
        // Catches every remaining way out - the close button, the host tearing the scanner
        // down - for a photo that was taken but never continued with.
        .onDisappear {
            if !photoHandedOff, let url = capturedPhotoURL {
                try? FileManager.default.removeItem(at: url)
            }
        }
        // In Add/Edit's sheet a swipe-down would otherwise close this without going through the
        // prompt below, taking the queue out of view but not out of state.
        .interactiveDismissDisabled(!viewModel.batchQueue.isEmpty)
        .larderDialog(
            L10n.batchDiscardTitle,
            isPresented: Binding(
                get: { pendingDiscard != nil },
                set: { if !$0 { pendingDiscard = nil } }
            ),
            message: L10n.batchDiscardMessage(viewModel.batchQueue.count),
            actions: [
                .cancel(L10n.batchDiscardKeep),
                .destructive(L10n.batchDiscardConfirm) {
                    let after = pendingDiscard
                    pendingDiscard = nil
                    if after == .typeItIn {
                        viewModel.clearBatchQueue()
                        typeItInNow()
                    } else {
                        discardQueueAndClose()
                    }
                }
            ]
        )
        .larderDialog(
            L10n.duplicateDialogTitle,
            isPresented: $askDuplicate,
            message: inPantry.map {
                L10n.duplicateDialogMessage($0.quantity > 1 ? "\($0.name) ×\($0.quantity)" : $0.name)
            },
            actions: [
                .init(L10n.duplicateDialogAddOne) {
                    if let item = inPantry, let updated = viewModel.incrementQuantity(item.id) {
                        viewModel.homeConfirmation = .oneMoreAdded(name: updated.name, quantity: updated.quantity)
                    }
                    close()
                },
                .cancel(L10n.duplicateDialogSeparate) {
                    guard let product = viewModel.scannedProduct else { return }
                    // Answered here, so Add item mustn't ask again.
                    viewModel.dismissDuplicate()
                    proceed(
                        barcode: product.barcode, name: product.name, brand: product.brand,
                        imageUrl: product.imageUrl, category: product.category
                    )
                }
            ]
        )
    }

    /// The picture fills this whether or not a panel is up, so it doesn't jump when one appears.
    private func cameraArea(expanded: Bool, panelMaxHeight: CGFloat) -> some View {
        ZStack(alignment: .top) {
            picture
            VStack(spacing: 0) {
                controls
                    .zIndex(1)
                    .environment(\.colorScheme, .dark)
                aimArea(expanded: expanded)
                    .environment(\.colorScheme, .dark)
                // The frame dims everything around it, so all that sits on top is raised above it.
                shutter
                    .zIndex(1)
                    .environment(\.colorScheme, .dark)
                if !expanded, panel != .none {
                    dockedPanel(maxHeight: panelMaxHeight)
                        .zIndex(2)
                }
            }
            LarderSnackbarHost(content: $snackbar)
                .padding(.top, ScanMetrics.snackbarTopOffset)
                .environment(\.colorScheme, .dark)
        }
        .clipped()
    }

    private var picture: some View {
        ZStack {
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
                            snackbar = LarderSnackbarContent(message: L10n.shoppingListScanUnsupported)
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
                    // the card out from under the user. "Scan again" lets the held one go.
                    guard !viewModel.isHoldingBarcodeResult else { return }
                    if let gs1Date = GS1BarcodeParser.extractExpiryDate(from: barcode) {
                        viewModel.detectedDate = gs1Date
                    }
                    viewModel.lookupBarcode(barcode)
                    // Only where the host offers it, and only outside batch: batch collapses
                    // repeats into one row's quantity by itself, and prompting per scan is
                    // exactly the interruption batch mode exists to avoid.
                    if checkDuplicates {
                        viewModel.checkForDuplicate(barcode)
                    }
                },
                onDateFound: { date in
                    if viewModel.scannedProduct != nil {
                        // Scan date: the date lands on the product, which comes back to show it.
                        viewModel.detectedDate = date
                        dateReadOffPack = true
                        mode = .barcode
                    } else if viewModel.detectedDate == nil {
                        // Held like a barcode result, so the date being confirmed can't change
                        // under the thumb as the camera keeps reading. Scan again lets it go.
                        viewModel.detectedDate = date
                    }
                },
                onControllerReady: { controller in
                    cameraController = controller
                }
            )
            if let url = capturedPhotoURL, let image = UIImage(contentsOfFile: url.path) {
                GeometryReader { proxy in
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                }
                .accessibilityLabel(L10n.photoCapturedTitle)
            }
        }
        .ignoresSafeArea(edges: .top)
    }

    private var controls: some View {
        ScanControls(
            onClose: { requestClose() },
            torchAvailable: cameraController?.hasTorch == true,
            torchOn: torchEnabled,
            onToggleTorch: {
                torchEnabled.toggle()
                cameraController?.setTorchEnabled(torchEnabled)
            },
            onTypeItIn: showManualEntryShortcut ? { requestTypeItIn() } : nil
        )
    }

    private func aimArea(expanded: Bool) -> some View {
        VStack(spacing: LarderSpace.s4) {
            Spacer(minLength: 0)
            if panel != .photo {
                let size = mode.frameSize(expanded: expanded)
                AimingFrame()
                    .frame(maxWidth: size.width, maxHeight: size.height)
            }
            if showsHint {
                Text(guideText)
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurface)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, LarderSpace.s3)
                    .padding(.vertical, 6)
                    .background(scanScrim, in: RoundedRectangle(cornerRadius: LarderRadius.sm))
                    .frame(maxWidth: 320)
                    .layoutPriority(1)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, LarderSpace.s4)
        .padding(.vertical, LarderSpace.s2)
    }

    @ViewBuilder private var shutter: some View {
        if mode == .receipt {
            ScanShutter(icon: .receipt, label: L10n.scanReceiptButton, busy: isReadingReceipt) { readReceipt() }
        } else if mode == .photo && capturedPhotoURL == nil {
            ScanShutter(icon: .camera, label: L10n.takePhotoButton, busy: isCapturingPhoto) { takePhoto() }
        }
    }

    private var modeBar: some View {
        ScanModeBar(
            modes: enabledModes,
            selected: mode,
            queued: viewModel.batchQueue.count,
            onSelect: { selectMode($0) }
        )
    }

    private func dockedPanel(maxHeight: CGFloat) -> some View {
        panelContent(expanded: false)
            .frame(maxHeight: maxHeight)
            .frame(maxWidth: .infinity)
            .background(
                LarderColor.surfaceContainerHigh,
                in: UnevenRoundedRectangle(topLeadingRadius: LarderRadius.xl, topTrailingRadius: LarderRadius.xl)
            )
    }

    @ViewBuilder private func panelContent(expanded: Bool) -> some View {
        switch panel {
        case .none:
            EmptyView()
        case .queue:
            if isReadingReceipt {
                ScanResultPanel(expanded: expanded) {
                    ScanBusy(text: L10n.scanReadingReceipt)
                    LarderProgressBar()
                }
            } else {
                ScanQueuePanel(
                    expanded: expanded,
                    entries: viewModel.batchQueue,
                    emptyTitle: mode == .receipt ? L10n.receiptQueueEmpty : L10n.batchQueueEmpty,
                    onRemove: { removeQueueRow($0) },
                    onClear: { clearQueue() },
                    onCommit: {
                        let added = viewModel.commitBatchQueue()
                        if added > 0 {
                            onBatchCommitted(added)
                            close()
                        }
                    }
                )
            }
        case .lookingUp:
            ScanResultPanel(expanded: expanded) { ScanBusy(text: L10n.lookingUpProduct) }
        case .photo:
            photoPanel(expanded: expanded)
        case .product:
            productPanel(expanded: expanded)
        case .notFound:
            notFoundPanel(expanded: expanded)
        case .offline:
            offlinePanel(expanded: expanded)
        case .date:
            datePanel(expanded: expanded)
        }
    }

    @ViewBuilder private func photoPanel(expanded: Bool) -> some View {
        let product = viewModel.scannedProduct
        let barcode = product?.barcode ?? viewModel.productNotFoundBarcode
        let reference = capturedPhotoURL.map(ProductPhotoStore.reference(for:))
        ScanResultPanel(expanded: expanded) {
            ScanResultHeader(
                title: L10n.photoCapturedTitle,
                imageReference: reference,
                icon: .image,
                // Name what this photo is being attached to, when there is something.
                subtitle: product?.name
                    ?? viewModel.productNotFoundBarcode.map(L10n.barcodeFormat)
                    ?? L10n.photoCapturedMessage,
                centred: true
            )
            if viewModel.isLabelingPhoto {
                ScanBusy(text: L10n.photoSuggestionsAnalyzing)
            } else if !viewModel.photoLabelSuggestions.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text(L10n.photoSuggestionsTitle)
                        .larderFont(.label)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                    FlowLayout(spacing: LarderSpace.s2) {
                        ForEach(viewModel.photoLabelSuggestions) { suggestion in
                            if let label = L10n.foodLabelName(suggestion.key) {
                                LarderChip(label) {
                                    // The saved item will point at this file, so it survives
                                    // this screen going away.
                                    photoHandedOff = true
                                    proceed(
                                        barcode: barcode, name: label, brand: product?.brand,
                                        imageUrl: reference, category: suggestion.category
                                    )
                                }
                            }
                        }
                    }
                }
            }
            ScanActionRow {
                LarderButton(L10n.retakePhotoButton, icon: .camera, variant: .outline, fullWidth: true) {
                    discardCapturedPhoto()
                }
                LarderButton(L10n.continueButton, fullWidth: true) {
                    photoHandedOff = true
                    proceed(
                        barcode: barcode, name: product?.name, brand: product?.brand,
                        imageUrl: reference, category: product?.category
                    )
                }
            }
        }
    }

    @ViewBuilder private func productPanel(expanded: Bool) -> some View {
        if let product = viewModel.scannedProduct {
            let forDate = mode == .dateOcr
            let held = inPantry
            ScanResultPanel(expanded: expanded) {
                if !viewModel.allergenWarnings.isEmpty {
                    LarderCallout(
                        .error,
                        icon: .triangleAlert,
                        title: L10n.allergenWarning(viewModel.allergenWarnings.joined(separator: ", ")),
                        message: L10n.scanAllergenNote
                    )
                }
                ScanResultHeader(
                    title: product.name,
                    imageReference: product.imageUrl,
                    icon: .forCategory(product.category),
                    subtitle: product.brand,
                    code: L10n.barcodeFormat(product.barcode),
                    inPantry: held?.quantity
                )
                if let date = viewModel.detectedDate {
                    ScanDateReadout(date: date, source: dateReadOffPack ? .packaging : .barcode)
                }
                if !forDate {
                    ScanActionRow {
                        if let held, let onUseOne {
                            LarderButton(L10n.useOneButton, icon: .circleMinus, variant: .outline, fullWidth: true) {
                                onUseOne(held)
                                close()
                            }
                        }
                        LarderButton(L10n.scanDateButton, icon: .scanText, variant: .outline, fullWidth: true) {
                            // A hand-off, not a mode pick: this deliberately carries the product
                            // forward so the OCR'd date lands on it, which is why it sets `mode`
                            // instead of going through selectMode.
                            mode = .dateOcr
                        }
                    }
                }
                ScanActionRow {
                    LarderButton(L10n.scanAgainButton, variant: .text) {
                        clearResults()
                        mode = .barcode
                    }
                    LarderButton(L10n.continueButton, fullWidth: true) {
                        if held != nil {
                            askDuplicate = true
                        } else {
                            proceed(
                                barcode: product.barcode, name: product.name, brand: product.brand,
                                imageUrl: product.imageUrl, category: product.category
                            )
                        }
                    }
                }
            }
        }
    }

    private func notFoundPanel(expanded: Bool) -> some View {
        let barcode = viewModel.productNotFoundBarcode ?? ""
        return ScanResultPanel(expanded: expanded) {
            ScanResultHeader(
                title: L10n.productNotFoundTitle,
                imageReference: nil,
                icon: .info,
                code: L10n.barcodeFormat(barcode),
                centred: true
            )
            ScanActionRow {
                if enabledModes.contains(.photo) {
                    LarderButton(L10n.switchToPhotoButton, icon: .camera, variant: .outline, fullWidth: true) {
                        // Also a hand-off: the unrecognised barcode has to survive into Photo
                        // mode so the photo is attached to it. Hence not selectMode.
                        mode = .photo
                    }
                }
                LarderButton(L10n.manualEntryButton, fullWidth: true) { proceed(barcode: barcode) }
            }
            LarderButton(L10n.scanAgainButton, variant: .text) { clearResults() }
        }
    }

    private func offlinePanel(expanded: Bool) -> some View {
        let barcode = viewModel.productNotFoundBarcode ?? ""
        return ScanResultPanel(expanded: expanded) {
            LarderCallout(.warning, icon: .wifiOff, title: L10n.scanOfflineTitle, message: L10n.scanOfflineMessage)
            ScanCode(text: L10n.barcodeFormat(barcode))
            ScanActionRow {
                LarderButton(L10n.scanTryAgain, variant: .outline, fullWidth: true) {
                    if let retried = viewModel.retryBarcodeLookup(), checkDuplicates {
                        viewModel.checkForDuplicate(retried)
                    }
                }
                LarderButton(L10n.scanContinueByHand, fullWidth: true) { proceed(barcode: barcode) }
            }
        }
    }

    @ViewBuilder private func datePanel(expanded: Bool) -> some View {
        if let date = viewModel.detectedDate {
            ScanResultPanel(expanded: expanded) {
                HStack(spacing: LarderSpace.s3) {
                    LarderThumb(imageReference: nil, placeholderIcon: .clock, side: ScanMetrics.resultThumbSize)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.dateCapturedTitle)
                            .larderFont(.bodySm)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                        Text(ScanDates.full(date))
                            .larderFont(.headline)
                            .foregroundStyle(LarderColor.onSurface)
                            .accessibilityAddTraits(.isHeader)
                    }
                    Spacer(minLength: 0)
                }
                ScanActionRow {
                    LarderButton(L10n.scanAgainButton, variant: .text) { clearResults() }
                    LarderButton(L10n.scanUseThisDate, fullWidth: true) { proceed() }
                }
            }
        }
    }

    /// Shown on the right from 700pt until there is a result.
    private var idlePanel: some View {
        let copy: (title: String, message: String) = {
            switch mode {
            case .dateOcr: return (L10n.scanIdleDateTitle, L10n.scanIdleDateMessage)
            case .photo: return (L10n.scanIdlePhotoTitle, L10n.scanIdlePhotoMessage)
            default: return (L10n.scanIdleResultsTitle, L10n.scanIdleResultsMessage)
            }
        }()
        return VStack(spacing: LarderSpace.s2) {
            Text(copy.title)
                .larderFont(.title)
                .foregroundStyle(LarderColor.onSurface)
                .accessibilityAddTraits(.isHeader)
            Text(copy.message)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .frame(maxWidth: 280)
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, LarderSpace.s5)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var guideText: String {
        switch mode {
        case .batch: return L10n.reticleHintBatch
        case .receipt: return L10n.reticleHintReceipt
        case .barcode: return L10n.reticleHintBarcode
        case .dateOcr: return L10n.reticleHintDateOcr
        case .photo: return L10n.reticleHintPhoto
        }
    }
}

/// The two ways the scanner writes a date.
private enum ScanDates {
    static func full(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }

    /// Day and month, with the year only when it isn't this one.
    static func estimate(_ date: Date) -> String {
        guard Calendar.current.isDate(date, equalTo: Date(), toGranularity: .year) else { return full(date) }
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("dMMM")
        return formatter.string(from: date)
    }
}

/// Everything outside the aiming frame, for dimming. Reaches well past the frame on purpose; the
/// camera area clips it.
private struct AimSurround: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path(rect.insetBy(dx: -3000, dy: -3000))
        path.addRoundedRect(in: rect, cornerSize: CGSize(width: LarderRadius.lg, height: LarderRadius.lg))
        return path
    }
}

/// Two rings, light then dark, so one of them always clears 3:1 against the picture.
private struct AimingFrame: View {
    var body: some View {
        Color.clear
            .background {
                AimSurround()
                    .fill(LarderColor.scrim.opacity(ScanMetrics.dimOpacity), style: FillStyle(eoFill: true))
            }
            .overlay {
                RoundedRectangle(cornerRadius: LarderRadius.lg + 3)
                    .stroke(LarderColor.scrim, lineWidth: 2)
                    .padding(-3)
            }
            .overlay {
                RoundedRectangle(cornerRadius: LarderRadius.lg + 1)
                    .stroke(LarderColor.onSurface, lineWidth: 2)
                    .padding(-1)
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// Close, the torch and Type it in. `onTypeItIn` is nil where the form is already underneath.
private struct ScanControls: View {
    let onClose: () -> Void
    let torchAvailable: Bool
    let torchOn: Bool
    let onToggleTorch: () -> Void
    let onTypeItIn: (() -> Void)?

    var body: some View {
        HStack(spacing: LarderSpace.s2) {
            LarderIconButton(.x, label: L10n.closeScanner, color: LarderColor.onSurface, action: onClose)
                .background(scanScrim, in: Circle())
            if torchAvailable {
                LarderIconButton(
                    .flashlight,
                    label: L10n.torchToggleButton,
                    color: torchOn ? LarderColor.background : LarderColor.onSurface,
                    action: onToggleTorch
                )
                .background(torchOn ? LarderColor.onSurface : scanScrim, in: Circle())
                .accessibilityValue(torchOn ? L10n.scanTorchOn : L10n.scanTorchOff)
            }
            Spacer(minLength: 0)
            if let onTypeItIn {
                LarderButton(L10n.manualEntryButton, icon: .pencil, variant: .text, action: onTypeItIn)
                    .environment(\.larderTextButtonColor, LarderColor.onSurface)
                    .background(scanScrim, in: Capsule())
            }
        }
        .padding(.horizontal, LarderSpace.s3)
        .padding(.top, LarderSpace.s3)
        .padding(.bottom, LarderSpace.s2)
    }
}

/// All the modes in reach of a thumb, with no swipe. `queued` badges Batch, because a queue
/// outlives a change of mode and is out of sight in the others.
private struct ScanModeBar: View {
    let modes: [IOSCameraMode]
    let selected: IOSCameraMode
    let queued: Int
    let onSelect: (IOSCameraMode) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(modes, id: \.self) { mode in
                ScanModeSlot(
                    mode: mode,
                    selected: mode == selected,
                    queued: mode == .batch ? queued : 0,
                    onTap: { onSelect(mode) }
                )
            }
        }
        .padding(.horizontal, LarderSpace.s1)
        .padding(.vertical, LarderSpace.s2)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L10n.scannerModePickerLabel)
    }
}

private struct ScanModeSlot: View {
    let mode: IOSCameraMode
    let selected: Bool
    let queued: Int
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 2) {
                LarderIcon(icon)
                    .foregroundStyle(selected ? LarderColor.onPrimaryContainer : LarderColor.onSurfaceVariant)
                    .frame(width: 52, height: 32)
                    .background(selected ? LarderColor.primaryContainer : Color.clear, in: Capsule())
                    .overlay(alignment: .topTrailing) {
                        if queued > 0 {
                            LarderBadge(count: queued, isCompact: true)
                                .offset(y: -2)
                        }
                    }
                Text(label)
                    .larderFont(.caption)
                    .foregroundStyle(selected ? LarderColor.onSurface : LarderColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, minHeight: ScanMetrics.modeSlotMinHeight, alignment: .top)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(queued > 0 ? L10n.batchQueueCount(queued) : "")
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
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

    private var icon: LarderIconName {
        switch mode {
        case .barcode: return .scanBarcode
        case .batch: return .layers
        case .receipt: return .receipt
        case .dateOcr: return .scanText
        case .photo: return .camera
        }
    }
}

/// For Photo and Receipt: everywhere else reading is continuous.
private struct ScanShutter: View {
    let icon: LarderIconName
    let label: String
    let busy: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(LarderColor.onSurface)
                if busy {
                    LarderSpinner(size: 28, color: LarderColor.background)
                } else {
                    LarderIcon(icon, size: .custom(30))
                        .foregroundStyle(LarderColor.background)
                }
            }
            .frame(width: ScanMetrics.shutterSize, height: ScanMetrics.shutterSize)
            .background {
                Circle().fill(LarderColor.onSurface).padding(-6)
                Circle().fill(LarderColor.scrim).padding(-3)
            }
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .accessibilityLabel(label)
        .padding(.top, LarderSpace.s2)
        .padding(.bottom, LarderSpace.s6)
    }
}

/// A single result: scrolls by itself if a long name or a large font outgrows the panel.
private struct ScanResultPanel<Content: View>: View {
    let expanded: Bool
    @ViewBuilder let content: Content

    private var stack: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s3) { content }
            .padding(.horizontal, expanded ? LarderSpace.s5 : LarderSpace.s4)
            .padding(.vertical, expanded ? LarderSpace.s6 : LarderSpace.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    var body: some View {
        ViewThatFits(in: .vertical) {
            stack
            ScrollView { stack }
        }
    }
}

/// Two buttons share a row until the wording needs one each, as in Russian.
private struct ScanActionRow<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: LarderSpace.s2) { content }
            VStack(spacing: LarderSpace.s2) { content }
        }
    }
}

private struct ScanResultHeader: View {
    let title: String
    let imageReference: String?
    let icon: LarderIconName
    var subtitle: String? = nil
    var code: String? = nil
    var inPantry: Int? = nil
    var centred: Bool = false

    var body: some View {
        HStack(alignment: centred ? .center : .top, spacing: LarderSpace.s3) {
            LarderThumb(imageReference: imageReference, placeholderIcon: icon, side: ScanMetrics.resultThumbSize)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .larderFont(.title)
                    .foregroundStyle(LarderColor.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let code { ScanCode(text: code) }
                if let inPantry {
                    Text(L10n.inPantryCount(inPantry))
                        .larderFont(.label)
                        .foregroundStyle(LarderColor.primary)
                        .padding(.top, LarderSpace.s1)
                }
            }
            Spacer(minLength: 0)
        }
    }
}

private struct ScanCode: View {
    let text: String

    var body: some View {
        Text(text)
            .larderFont(.caption)
            .foregroundStyle(LarderColor.onSurfaceVariant)
    }
}

private struct ScanBusy: View {
    let text: String

    var body: some View {
        HStack(spacing: LarderSpace.s3) {
            LarderSpinner(size: 24)
            Text(text)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurface)
        }
        .frame(minHeight: LarderSize.control)
    }
}

private struct ScanDateReadout: View {
    let date: Date
    let source: ScanDateSource

    var body: some View {
        HStack(spacing: LarderSpace.s2) {
            LarderIcon(.circleCheck, size: .custom(18))
                .foregroundStyle(LarderColor.primary)
            VStack(alignment: .leading, spacing: 0) {
                Text(L10n.scanBestBefore(ScanDates.full(date)))
                    .larderFont(.label)
                    .foregroundStyle(LarderColor.onSurface)
                Text(source == .barcode ? L10n.scanDateFromBarcode : L10n.scanDateFromPackaging)
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
            }
        }
    }
}

/// The queue Batch and Receipt share. Rows appear the moment a barcode is read and fill in as
/// their lookups land. The list scrolls inside the panel, so the camera stays usable and Add all
/// stays put; a part-cut row shows there is more.
private struct ScanQueuePanel: View {
    let expanded: Bool
    let entries: [BatchScanEntry]
    let emptyTitle: String
    let onRemove: (BatchScanEntry) -> Void
    let onClear: () -> Void
    let onCommit: () -> Void

    private var inset: CGFloat { expanded ? LarderSpace.s5 : LarderSpace.s4 }

    private var rows: some View {
        LazyVStack(spacing: 0) {
            ForEach(entries) { entry in
                ScanQueueRow(entry: entry, inset: inset, onRemove: { onRemove(entry) })
            }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: LarderSpace.s2) {
                Text(entries.isEmpty ? emptyTitle : L10n.batchQueueCount(entries.count))
                    .larderFont(.titleSm)
                    .foregroundStyle(LarderColor.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.isHeader)
                if !entries.isEmpty {
                    LarderButton(L10n.batchClearButton, variant: .text, action: onClear)
                }
            }
            .frame(minHeight: LarderSize.touch)
            .padding(.horizontal, inset)

            if !entries.isEmpty {
                if expanded {
                    ScrollView { rows }
                } else {
                    ViewThatFits(in: .vertical) {
                        VStack(spacing: 0) {
                            ForEach(entries) { entry in
                                ScanQueueRow(entry: entry, inset: inset, onRemove: { onRemove(entry) })
                            }
                        }
                        ScrollView { rows }
                    }
                    .frame(maxHeight: ScanMetrics.queueListMaxHeight)
                }
                LarderButton(L10n.batchCommitButton, fullWidth: true, action: onCommit)
                    .padding(.horizontal, inset)
                    .padding(.top, LarderSpace.s2)
            }
            if expanded { Spacer(minLength: 0) }
        }
        .padding(.vertical, expanded ? LarderSpace.s6 : LarderSpace.s3)
    }
}

private struct ScanQueueRow: View {
    let entry: BatchScanEntry
    let inset: CGFloat
    let onRemove: () -> Void

    private var meta: String {
        if entry.isLookingUp { return L10n.lookingUpProduct }
        let date = entry.showsCheckDate
            ? L10n.batchQueueEstimated(ScanDates.estimate(entry.expiryDate))
            : ScanDates.full(entry.expiryDate)
        return "\(date) · \(L10n.storageLocationName(entry.storageLocation))"
    }

    var body: some View {
        HStack(spacing: LarderSpace.s3) {
            LarderThumb(imageReference: entry.imageUrl, placeholderIcon: .forCategory(entry.category))
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.quantity > 1 ? "\(entry.name) ×\(entry.quantity)" : entry.name)
                    .larderFont(.titleSm)
                    .foregroundStyle(LarderColor.onSurface)
                    .lineLimit(1)
                HStack(spacing: LarderSpace.s2) {
                    if entry.isLookingUp {
                        LarderSpinner(size: 14, lineWidth: 2)
                    }
                    if entry.showsCheckDate {
                        CheckDateMark()
                    }
                    Text(meta)
                        .larderFont(.caption)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            LarderIconButton(.x, label: L10n.batchQueueRemove(entry.name), action: onRemove)
        }
        .frame(minHeight: ScanMetrics.queueRowMinHeight)
        .padding(.leading, inset)
        .padding(.trailing, LarderSpace.s1)
    }
}

private struct CheckDateMark: View {
    var body: some View {
        HStack(spacing: 3) {
            LarderIcon(.circleAlert, size: .small)
            Text(L10n.batchQueueNeedsDate)
                .larderFont(.caption)
                .lineLimit(1)
                .fixedSize()
        }
        .foregroundStyle(LarderColor.onWarningContainer)
        .padding(.leading, LarderSpace.s1)
        .padding(.trailing, LarderSpace.s2)
        .padding(.vertical, 1)
        .background(LarderColor.warningContainer, in: RoundedRectangle(cornerRadius: LarderRadius.sm))
    }
}

/// T5: stands in for the whole scanner when the camera is refused. Every mode needs the camera,
/// so the modes and the torch go, and Close stays.
private struct CameraOffView: View {
    let onClose: () -> Void
    let onOpenSettings: () -> Void
    let onAddByHand: () -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            LarderColor.background.ignoresSafeArea()

            ScrollView {
                ScreenError(.cameraOff, title: L10n.cameraOffTitle, message: L10n.cameraOffBody) {
                    VStack(spacing: LarderSpace.s3) {
                        LarderButton(L10n.cameraOffOpenSettings, fullWidth: true, action: onOpenSettings)
                        LarderButton(L10n.cameraOffAddByHand, variant: .outline, fullWidth: true, action: onAddByHand)
                    }
                    .frame(maxWidth: ScanMetrics.cameraOffActionsMaxWidth)
                    .padding(.top, LarderSpace.s2)
                }
            }
            .defaultScrollAnchor(.center)

            ScanControls(onClose: onClose, torchAvailable: false, torchOn: false, onToggleTorch: {}, onTypeItIn: nil)
        }
        .environment(\.colorScheme, .dark)
    }
}

#Preview("Camera off") {
    CameraOffView(onClose: {}, onOpenSettings: {}, onAddByHand: {})
}

#Preview("Mode bar") {
    ScanModeBar(modes: ScannerSheetView.defaultModes, selected: .barcode, queued: 3, onSelect: { _ in })
        .background(LarderColor.background)
        .environment(\.colorScheme, .dark)
}

#Preview("Queue") {
    ScanQueuePanel(
        expanded: false,
        entries: [
            BatchScanEntry(id: "1", name: "Free-range eggs", expiryDate: Date(), quantity: 6, storageLocation: "Fridge", expirySource: .detected),
            BatchScanEntry(id: "2", name: "Mature cheddar", expiryDate: Date(), storageLocation: "Fridge", expirySource: .fallback),
            BatchScanEntry(id: "3", name: "5012345678917", expiryDate: Date(), storageLocation: "Fridge", expirySource: .fallback, isLookingUp: true)
        ],
        emptyTitle: "",
        onRemove: { _ in },
        onClear: {},
        onCommit: {}
    )
}

#Preview("Product, in pantry") {
    ScanResultPanel(expanded: false) {
        LarderCallout(.error, icon: .triangleAlert, title: "Contains: milk", message: "This matches your allergen list.")
        ScanResultHeader(
            title: "Greek yoghurt",
            imageReference: nil,
            icon: .milk,
            subtitle: "Fage",
            code: "Barcode: 5200435000027",
            inPantry: 2
        )
        ScanDateReadout(date: Date(), source: .barcode)
    }
}
