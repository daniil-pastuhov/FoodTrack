import SwiftUI
import AVFoundation
import Vision

public enum IOSCameraMode {
    case barcode
    case dateOcr
    case photo
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

        captureSession.commitConfiguration()

        let preview = AVCaptureVideoPreviewLayer(session: captureSession)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        self.previewLayer = preview

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.captureSession.startRunning()
        }
    }

    // MARK: - Barcode Delegate
    public func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard mode == .barcode else { return }

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

    private func extractDate(from lines: [String]) -> Date? {
        let keywordRegex = try? NSRegularExpression(pattern: "(?i)\\b(exp|bb|bbd|best before|use by|expires|val)\\b")

        let formats = [
            "dd.MM.yyyy", "dd/MM/yyyy", "dd-MM-yyyy",
            "yyyy-MM-dd",
            "dd.MM.yy", "dd/MM/yy",
            "dd MMM yyyy", "dd MMM yy",
            "MM/yy", "MM/yyyy"
        ]

        let fullText = lines.joined(separator: " ")
        for line in lines {
            // Check for date pattern
            let datePatterns = [
                "\\b(\\d{1,2})[\\.\\/\\-](\\d{1,2})[\\.\\/\\-](\\d{2,4})\\b",
                "\\b(20\\d{2})[\\.\\/\\-](\\d{1,2})[\\.\\/\\-](\\d{1,2})\\b",
                "\\b(\\d{1,2})\\s+([A-Za-z]{3})\\.?\\s+(\\d{2,4})\\b"
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
                        if let parsed = formatter.date(from: matchedStr) {
                            return parsed
                        }
                    }
                }
            }
        }

        return nil
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

        let photosDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ProductPhotos", isDirectory: true)

        do {
            try FileManager.default.createDirectory(at: photosDir, withIntermediateDirectories: true)
            let fileURL = photosDir
                .appendingPathComponent("product_photo_\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
            try thumbnailData.write(to: fileURL)
            photoCaptureCompletion?(fileURL)
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
