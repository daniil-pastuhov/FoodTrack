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

    public init(
        mode: IOSCameraMode,
        onBarcodeFound: @escaping (String) -> Void,
        onDateFound: @escaping (Date) -> Void
    ) {
        self.mode = mode
        self.onBarcodeFound = onBarcodeFound
        self.onDateFound = onDateFound
    }

    public func makeUIViewController(context: Context) -> CameraScannerViewController {
        let vc = CameraScannerViewController()
        vc.mode = mode
        vc.onBarcodeFound = onBarcodeFound
        vc.onDateFound = onDateFound
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

    private var lastBarcode: String?
    private var lastDateString: String?
    private var lastScanTime: Date = Date()

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
