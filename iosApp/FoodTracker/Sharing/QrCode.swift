import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Renders `contents` as a QR image, or nil when it won't fit — Core Image's generator returns
/// no output once the payload exceeds what a QR code can hold at this correction level. Callers
/// fall back to sharing as text, which has no such limit.
///
/// Correction level "L" matches Android's `generateQrBitmap`: this is read off a screen held a
/// few inches away rather than off a crumpled label, so capacity is worth more than damage
/// tolerance, and both platforms must cap the shareable list at the same size.
///
/// Generation only. Scanning stays with Vision/AVFoundation in `CameraScannerView`, which
/// already reads QR among its formats.
func generateQrImage(contents: String, sizePx: CGFloat = 640) -> UIImage? {
    guard !contents.isEmpty, let data = contents.data(using: .utf8) else { return nil }

    let filter = CIFilter.qrCodeGenerator()
    filter.message = data
    filter.correctionLevel = "L"
    guard let output = filter.outputImage else { return nil }

    // The generator emits one pixel per module, so it has to be scaled up before it is any use
    // on screen; a nearest-neighbour transform keeps the edges crisp rather than blurring them.
    let scale = sizePx / output.extent.width
    let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

    let context = CIContext()
    guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
    return UIImage(cgImage: cgImage)
}
