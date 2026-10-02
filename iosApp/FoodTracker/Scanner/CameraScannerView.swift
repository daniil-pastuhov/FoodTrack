import SwiftUI
import AVFoundation
import UIKit
import Vision

/// Vision works in `CGImagePropertyOrientation`, which `UIImage` does not expose directly — a
/// photo taken in any orientation but the default would otherwise be OCR'd sideways.
extension UIImage.Orientation {
    var cgOrientation: CGImagePropertyOrientation {
        switch self {
        case .up: return .up
        case .upMirrored: return .upMirrored
        case .down: return .down
        case .downMirrored: return .downMirrored
        case .left: return .left
        case .leftMirrored: return .leftMirrored
        case .right: return .right
        case .rightMirrored: return .rightMirrored
        @unknown default: return .up
        }
    }
}

public enum IOSCameraMode {
    case barcode
    case dateOcr
    case photo
    /// Keeps the camera live and queues each scanned product instead of stopping to fill in a
    /// form, for unpacking a whole shop. Uses the same detection path as `barcode`; only what
    /// the sheet does with each result differs.
    case batch

    /// Photographs a till receipt and reads every product line off it in one pass, filling the
    /// same queue `batch` does.
    ///
    /// Runs no live detection, like `photo`: a receipt has to be read from one stable,
    /// fully-framed still rather than from the stream, which would produce a different partial
    /// reading every frame with no way to tell which was complete. See
    /// `CameraScannerViewController.recognizeReceiptLines`.
    case receipt
}

public struct CameraScannerView: UIViewControllerRepresentable {
    public var mode: IOSCameraMode
    public var onBarcodeFound: (String) -> Void
    public var onDateFound: (Date) -> Void
    public var onControllerReady: ((CameraScannerViewController) -> Void)?

    public init(
        mode: IOSCameraMode,
        onBarcodeFound: @escaping (String) -> Void,
        onDateFound: @escaping (Date) -> Void,
        onControllerReady: ((CameraScannerViewController) -> Void)? = nil
    ) {
        self.mode = mode
        self.onBarcodeFound = onBarcodeFound
        self.onDateFound = onDateFound
        self.onControllerReady = onControllerReady
    }

    public func makeUIViewController(context: Context) -> CameraScannerViewController {
        let vc = CameraScannerViewController()
        vc.mode = mode
        vc.onBarcodeFound = onBarcodeFound
        vc.onDateFound = onDateFound
        DispatchQueue.main.async {
            onControllerReady?(vc)
        }
        return vc
    }

    public func updateUIViewController(_ uiViewController: CameraScannerViewController, context: Context) {
        uiViewController.mode = mode
    }
}

public class CameraScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate, AVCaptureVideoDataOutputSampleBufferDelegate {
    public var mode: IOSCameraMode = .barcode
    public var onBarcodeFound: ((String) -> Void)?
    public var onDateFound: ((Date) -> Void)?

    private let captureSession = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let videoOutput = AVCaptureVideoDataOutput()
    private let metadataOutput = AVCaptureMetadataOutput()
    private let photoOutput = AVCapturePhotoOutput()
    private var videoDevice: AVCaptureDevice?

    /// Keeps the preview, the frames handed to Vision and captured photos upright however the
    /// device is held. Without it they come out 90° off whenever the device isn't in the
    /// camera's native orientation: on iPad held in landscape (the usual way), the preview is
    /// sideways and date OCR reads rotated text.
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var rotationObservations: [NSKeyValueObservation] = []

    public var hasTorch: Bool { videoDevice?.hasTorch ?? false }

    public func setTorchEnabled(_ enabled: Bool) {
        guard let device = videoDevice, device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = enabled ? .on : .off
            device.unlockForConfiguration()
        } catch {
            print("Failed to toggle torch: \(error.localizedDescription)")
        }
    }

    private var lastBarcode: String?
    private var lastDateString: String?
    private var lastScanTime: Date = Date()
    private var photoCaptureCompletion: ((URL?) -> Void)?
    private var isPhotoOutputConfigured = false

    public func capturePhoto(completion: @escaping (URL?) -> Void) {
        guard isPhotoOutputConfigured else {
            print("capturePhoto called but AVCapturePhotoOutput was never added to the session")
            completion(nil)
            return
        }
        photoCaptureCompletion = completion
        let settings = AVCapturePhotoSettings()
        photoOutput.capturePhoto(with: settings, delegate: self)
    }

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupCamera()
    }

    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private func setupCamera() {
        captureSession.beginConfiguration()
        captureSession.sessionPreset = .high

        guard let videoDevice = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let videoInput = try? AVCaptureDeviceInput(device: videoDevice) else {
            captureSession.commitConfiguration()
            return
        }

        if captureSession.canAddInput(videoInput) {
            captureSession.addInput(videoInput)
        }
        self.videoDevice = videoDevice

        // 1. Metadata Output for Barcodes
        if captureSession.canAddOutput(metadataOutput) {
            captureSession.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            metadataOutput.metadataObjectTypes = [
                .ean13, .ean8, .upce, .code128, .qr, .dataMatrix
            ]
        }

        // 2. Video Output for Live Vision OCR
        if captureSession.canAddOutput(videoOutput) {
            videoOutput.setSampleBufferDelegate(self, queue: DispatchQueue(label: "ocrQueue"))
            captureSession.addOutput(videoOutput)
        }

        // 3. Photo Output for manual "Photo" mode captures
        if captureSession.canAddOutput(photoOutput) {
            captureSession.addOutput(photoOutput)
            isPhotoOutputConfigured = true
        } else {
            print("AVCaptureSession.canAddOutput(photoOutput) returned false — photo capture will be unavailable")
        }

        // On iPad the app can run in Split View, Slide Over or a Stage Manager window, where the
        // camera is otherwise cut off (the session is interrupted and the preview goes black).
        if captureSession.isMultitaskingCameraAccessSupported {
            captureSession.isMultitaskingCameraAccessEnabled = true
        }

        captureSession.commitConfiguration()

        let preview = AVCaptureVideoPreviewLayer(session: captureSession)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        self.previewLayer = preview

        let coordinator = AVCaptureDevice.RotationCoordinator(device: videoDevice, previewLayer: preview)
        rotationCoordinator = coordinator
        applyRotation(from: coordinator)
        rotationObservations = [
            coordinator.observe(\.videoRotationAngleForHorizonLevelPreview) { [weak self] coordinator, _ in
                DispatchQueue.main.async { self?.applyRotation(from: coordinator) }
            },
            coordinator.observe(\.videoRotationAngleForHorizonLevelCapture) { [weak self] coordinator, _ in
                DispatchQueue.main.async { self?.applyRotation(from: coordinator) }
            },
        ]

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.captureSession.startRunning()
        }
    }

    /// The preview follows the interface. Photos follow the preview too, so they come out the
    /// way they were framed: following the horizon instead would let a receipt shot flat on a
    /// table (where the horizon is ambiguous) be saved sideways on a portrait-locked iPhone.
    /// The live date-OCR frames follow the horizon, so the text is upright for Vision however
    /// the device is turned; they are re-read many times a second, so an ambiguous moment costs
    /// nothing. Barcode detection doesn't care about orientation.
    private func applyRotation(from coordinator: AVCaptureDevice.RotationCoordinator) {
        let previewAngle = coordinator.videoRotationAngleForHorizonLevelPreview
        if let connection = previewLayer?.connection, connection.isVideoRotationAngleSupported(previewAngle) {
            connection.videoRotationAngle = previewAngle
        }
        if let connection = photoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(previewAngle) {
            connection.videoRotationAngle = previewAngle
        }
        let captureAngle = coordinator.videoRotationAngleForHorizonLevelCapture
        if let connection = videoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(captureAngle) {
            connection.videoRotationAngle = captureAngle
        }
    }

    // MARK: - Barcode Delegate
    public func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard mode == .barcode || mode == .batch else { return }

        for metadata in metadataObjects {
            guard let readableObject = metadata as? AVMetadataMachineReadableCodeObject,
                  let stringValue = readableObject.stringValue else { continue }

            let now = Date()
            if stringValue != lastBarcode || now.timeIntervalSince(lastScanTime) > 2.0 {
                lastBarcode = stringValue
                lastScanTime = now
                AudioServicesPlaySystemSound(SystemSoundID(kSystemSoundID_Vibrate))
                onBarcodeFound?(stringValue)
                break
            }
        }
    }

    // MARK: - Video Data Delegate for Apple Vision Text OCR
    public func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard mode == .dateOcr else { return }

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let request = VNRecognizeTextRequest { [weak self] request, error in
            guard let observations = request.results as? [VNRecognizedTextObservation], error == nil else { return }

            var recognizedLines: [String] = []
            for observation in observations {
                if let candidate = observation.topCandidates(1).first {
                    recognizedLines.append(candidate.string)
                }
            }

            if let date = self?.extractDate(from: recognizedLines) {
                let formatter = DateFormatter()
                formatter.dateFormat = "yyyy-MM-dd"
                let dateStr = formatter.string(from: date)

                let now = Date()
                if dateStr != self?.lastDateString || now.timeIntervalSince(self?.lastScanTime ?? Date()) > 2.0 {
                    self?.lastDateString = dateStr
                    self?.lastScanTime = now
                    DispatchQueue.main.async {
                        AudioServicesPlaySystemSound(SystemSoundID(1519)) // subtle haptic
                        self?.onDateFound?(date)
                    }
                }
            }
        }
        request.recognitionLevel = .accurate

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        try? handler.perform([request])
    }

    /// Reads the text off a photographed till receipt, in reading order.
    ///
    /// A one-shot pass over a captured still rather than the live `captureOutput` path above:
    /// that one re-reads a small date stamp until it sees the same answer twice, which suits a
    /// few characters. A receipt is thirty lines that must all be read from a single
    /// fully-framed image.
    ///
    /// Runs entirely on-device; no image, and no part of one, leaves the phone. Parsing the
    /// lines into products is `ReceiptParser`'s job, which mirrors the same rules on Android.
    public static func recognizeReceiptLines(at url: URL, completion: @escaping ([String]) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            guard let data = try? Data(contentsOf: url), let image = UIImage(data: data),
                  let cgImage = image.cgImage else {
                DispatchQueue.main.async { completion([]) }
                return
            }

            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            // A receipt's own language, not the phone's — it is printed by the shop.
            request.recognitionLanguages = ["en-US", "nb-NO", "ru-RU"]
            request.usesLanguageCorrection = false

            let handler = VNImageRequestHandler(cgImage: cgImage, orientation: image.imageOrientation.cgOrientation, options: [:])
            try? handler.perform([request])

            let observations = (request.results as? [VNRecognizedTextObservation]) ?? []
            // Vision returns observations in no particular order, so sort top-to-bottom: the
            // parser's one-line lookahead for weighed goods depends on reading order.
            let lines = observations
                .sorted { $0.boundingBox.origin.y > $1.boundingBox.origin.y }
                .compactMap { $0.topCandidates(1).first?.string }

            DispatchQueue.main.async { completion(lines) }
        }
    }

    private func extractDate(from lines: [String]) -> Date? {
        let keywordRegex = try? NSRegularExpression(pattern: "(?i)\\b(exp|bb|bbd|best before|use by|expires|val)\\b")

        let formats = [
            "dd.MM.yyyy", "dd/MM/yyyy", "dd-MM-yyyy",
            "yyyy-MM-dd",
            "dd.MM.yy", "dd/MM/yy",
            "dd MMM yyyy", "dd MMM yy",
            "MM/yy", "MM/yyyy", "MM.yy", "MM.yyyy", "MM-yy", "MM-yyyy"
        ]
        // Month/year-only formats have no day component, so DateFormatter defaults to the
        // 1st of the month; expiry dates are conventionally the last day of the printed month.
        let monthYearOnlyFormats: Set<String> = ["MM/yy", "MM/yyyy", "MM.yy", "MM.yyyy", "MM-yy", "MM-yyyy"]

        for line in lines {
            // Check for date pattern
            let datePatterns = [
                "\\b(\\d{1,2})[\\.\\/\\-](\\d{1,2})[\\.\\/\\-](\\d{2,4})\\b",
                "\\b(20\\d{2})[\\.\\/\\-](\\d{1,2})[\\.\\/\\-](\\d{1,2})\\b",
                "\\b(\\d{1,2})\\s+([A-Za-z]{3})\\.?\\s+(\\d{2,4})\\b",
                "\\b(0[1-9]|1[0-2])[\\.\\/\\-](20\\d{2}|\\d{2})\\b"
            ]

            for pattern in datePatterns {
                guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
                let nsString = line as NSString
                let matches = regex.matches(in: line, range: NSRange(location: 0, length: nsString.length))

                for match in matches {
                    let matchedStr = nsString.substring(with: match.range)
                    for format in formats {
                        let formatter = DateFormatter()
                        formatter.dateFormat = format
                        formatter.locale = Locale(identifier: "en_US_POSIX")
                        if format.contains("yy") && !format.contains("yyyy") {
                            // en_US_POSIX parses two-digit years literally (e.g. "26" -> year 26 AD)
                            // instead of inferring a century, unlike locale-aware formatters. Pin the
                            // pivot so every 00-99 value maps to 2000-2099, the only plausible range
                            // for a scanned grocery expiry date.
                            formatter.twoDigitStartDate = Calendar(identifier: .gregorian)
                                .date(from: DateComponents(year: 2000, month: 1, day: 1))
                        }
                        // ICU is lenient about digit count, so a "yyyy" format happily parses
                        // "29.07.27" as year 27 AD (and it is tried before the "yy" variant that
                        // applies the century pivot). Reject implausible years so the match
                        // falls through to the next format instead of storing a bogus date.
                        if let parsed = formatter.date(from: matchedStr), ExpiryDatePlausibility.isPlausible(parsed) {
                            if monthYearOnlyFormats.contains(format) {
                                return lastDayOfMonth(containing: parsed)
                            }
                            return parsed
                        }
                    }
                }
            }
        }

        return nil
    }

    private func lastDayOfMonth(containing date: Date) -> Date {
        let calendar = Calendar(identifier: .gregorian)
        guard let range = calendar.range(of: .day, in: .month, for: date),
              let firstOfMonth = calendar.date(from: calendar.dateComponents([.year, .month], from: date)) else {
            return date
        }
        return calendar.date(byAdding: .day, value: range.count - 1, to: firstOfMonth) ?? date
    }

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if captureSession.isRunning {
            captureSession.stopRunning()
        }
    }
}

// MARK: - Photo Capture Delegate
extension CameraScannerViewController: AVCapturePhotoCaptureDelegate {
    public func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        defer { photoCaptureCompletion = nil }

        if let error = error {
            print("Photo capture failed: \(error.localizedDescription)")
            photoCaptureCompletion?(nil)
            return
        }

        guard let data = photo.fileDataRepresentation() else {
            print("Photo capture produced no file data representation")
            photoCaptureCompletion?(nil)
            return
        }

        guard let thumbnailData = Self.thumbnailJPEGData(from: data) else {
            print("Failed to decode/resize captured photo data (\(data.count) bytes)")
            photoCaptureCompletion?(nil)
            return
        }

        do {
            photoCaptureCompletion?(try ProductPhotoStore.save(jpegData: thumbnailData))
        } catch {
            print("Failed to write captured photo to disk: \(error.localizedDescription)")
            photoCaptureCompletion?(nil)
        }
    }

    /// Downscales the full-resolution capture (several MB as HEIC/JPEG straight off the sensor)
    /// to a thumbnail suitable for a 96x96 preview and a 54x54 list row, decoded off the main
    /// thread here rather than re-decoding the full image on every SwiftUI re-render.
    private static func thumbnailJPEGData(from data: Data, maxDimension: CGFloat = 600) -> Data? {
        guard let image = UIImage(data: data) else { return nil }

        let scale = min(1, maxDimension / max(image.size.width, image.size.height))
        let targetSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)

        let renderer = UIGraphicsImageRenderer(size: targetSize)
        let thumbnail = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }

        return thumbnail.jpegData(compressionQuality: 0.7)
    }
}
