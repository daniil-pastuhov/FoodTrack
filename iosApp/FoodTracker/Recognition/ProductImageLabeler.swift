import UIKit
import Vision

/// Names a photographed product using Apple's on-device image classifier, so an item with no
/// barcode (produce, bakery, market goods) can be added without typing its name.
///
/// Runs once over the captured photo rather than over the live preview — the user has already
/// chosen the picture, so classifying every frame would burn battery to produce a label that
/// changes under them. Android's `ProductImageLabeler` makes the same call with ML Kit.
///
/// Entirely on-device: no photo, and no part of one, leaves the device.
enum ProductImageLabeler {

    /// Classifies the photo at `url` and returns the grocery suggestions worth offering.
    ///
    /// Returns an empty array on any failure — an unreadable file, a classifier that declines to
    /// run, a picture of something that isn't food. Suggestions are an accelerator, so their
    /// absence has to be silent: the name field is right there either way.
    static func suggestNames(photoURL: URL) async -> [FoodLabelSuggestion] {
        guard let image = UIImage(contentsOfFile: photoURL.path),
              let cgImage = image.cgImage else {
            return []
        }

        let observations: [VNClassificationObservation] = await withCheckedContinuation { continuation in
            let request = VNClassifyImageRequest()
            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
            // Vision's handler is synchronous and CPU-bound, so it must not run on the main
            // actor: this is called right after a capture, while the sheet is still animating.
            DispatchQueue.global(qos: .userInitiated).async {
                try? handler.perform([request])
                continuation.resume(returning: request.results ?? [])
            }
        }

        // Vision's taxonomy uses hyphenated compound identifiers ("ice_cream" style varies by
        // OS version), so normalise to the spaced form the shared vocabulary is keyed on.
        let recognized = observations.map {
            RecognizedLabel(
                text: $0.identifier.replacingOccurrences(of: "_", with: " "),
                confidence: $0.confidence
            )
        }
        return FoodLabels.suggestions(from: recognized)
    }
}
