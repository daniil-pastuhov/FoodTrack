import ImageIO
import SwiftUI
import UIKit
import UniformTypeIdentifiers

// Recipe pictures and "Report this recipe" (owner's decisions 2026-11-17; design
// docs/design/src/pages/pictures.html): the form's Pictures row and its sheet, the pictures on a
// result, and the report action and sheet. The rules are in RecipeWire.swift (`RecipePictures`,
// `RecipeReporting`); Android: `RecipePictures.kt`.

// MARK: Picture bytes

/// The Saved row's thumbnail: the dish picture (4:3 from the server, any shape read) cropped to a
/// centred square of `edge` pixels, scaled down while it is decoded (ImageIO's thumbnail, so the
/// full picture is never decoded) and drawn with ImageIO only, so it runs off the main actor.
/// Never logs anything about the picture.
enum RecipePictureThumbnail {
    /// The square's pixels: the row's 44 pt tile at 3x with room to spare.
    static let edge = 240

    nonisolated static func make(from data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let size = RecipePictureDecoder.pixelSize(of: source), size.width > 0, size.height > 0 else { return nil }
        // The short side scaled to `edge` (never up), so the square crop keeps all of it.
        let long = max(size.width, size.height), short = min(size.width, size.height)
        let maxPixel = min(long, (CGFloat(edge) * long / short).rounded(.up))
        guard let scaled = RecipePictureDecoder.thumbnail(source, maxPixel: maxPixel) else { return nil }
        let side = min(scaled.width, scaled.height)
        let square = CGRect(x: (scaled.width - side) / 2, y: (scaled.height - side) / 2, width: side, height: side)
        guard let cropped = scaled.cropping(to: square) else { return nil }
        return jpeg(cropped, quality: 0.8)
    }

    nonisolated static func jpeg(_ image: CGImage, quality: Double) -> Data? {
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return out as Data
    }
}

/// Decoding a picture at the size it is shown, never larger: ImageIO reads the JPEG straight to a
/// bitmap of at most `maxPixel` on its longer side (applying any orientation it carries), so a
/// 1200 px picture shown at 390 pt never sits in memory at full size. Off the main actor.
enum RecipePictureDecoder {
    nonisolated static func pixelSize(of source: CGImageSource) -> CGSize? {
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int else { return nil }
        // Orientations 5–8 turn the picture a quarter: its shown width is the stored height.
        let orientation = properties[kCGImagePropertyOrientation] as? Int ?? 1
        return orientation >= 5 ? CGSize(width: height, height: width) : CGSize(width: width, height: height)
    }

    nonisolated static func thumbnail(_ source: CGImageSource, maxPixel: CGFloat) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, Int(maxPixel.rounded(.up))),
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// The picture decoded to fill a frame of `frame` pixels (cropped to fill, so the scale is the
    /// larger of the two sides' ratios): a 4:3 picture in the 4:3 frame needs the frame's width,
    /// a 3:2 one a little more for the height. Never scaled up.
    nonisolated static func decode(_ data: Data, filling frame: CGSize) -> CGImage? {
        guard frame.width > 0, frame.height > 0,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let size = pixelSize(of: source), size.width > 0, size.height > 0 else { return nil }
        let scale = min(1, max(frame.width / size.width, frame.height / size.height))
        return thumbnail(source, maxPixel: max(size.width, size.height) * scale)
    }
}

#if DEBUG
/// A generated stand-in picture for demo launches (`-demoRecipePictures`): a plate on a table,
/// tinted by `seed`. Nothing is fetched.
enum RecipePictureSample {
    /// The server's usual shape: 4:3 landscape, 1200 × 896 (CONTRACT.md "Recipe picture").
    static let width = 1200
    static let height = 896

    static func make(seed: Int) -> Data? {
        guard let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        let hues: [(CGFloat, CGFloat, CGFloat)] = [(0.86, 0.45, 0.30), (0.35, 0.62, 0.32), (0.93, 0.76, 0.35), (0.55, 0.40, 0.65)]
        let tint = hues[abs(seed) % hues.count]
        let w = CGFloat(width), h = CGFloat(height)
        // The plate is sized by the short side and centred, so it stays round in the wide frame.
        let s = h, cx = w / 2, cy = h / 2
        func circle(_ diameter: CGFloat) -> CGRect { CGRect(x: cx - diameter / 2, y: cy - diameter / 2, width: diameter, height: diameter) }
        // Table.
        context.setFillColor(red: 0.55, green: 0.42, blue: 0.32, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: w, height: h))
        // Plate and rim.
        context.setFillColor(red: 0.96, green: 0.95, blue: 0.92, alpha: 1)
        context.fillEllipse(in: circle(s * 0.76))
        context.setFillColor(red: 0.90, green: 0.89, blue: 0.86, alpha: 1)
        context.fillEllipse(in: circle(s * 0.6))
        // Food.
        for i in 0..<7 {
            let angle = CGFloat(i + seed) * 0.9
            let r = s * 0.13
            let x = cx + cos(angle) * s * 0.12 - r / 2
            let y = cy + sin(angle) * s * 0.12 - r / 2
            let shade = 0.75 + 0.05 * CGFloat(i % 3)
            context.setFillColor(red: tint.0 * shade, green: tint.1 * shade, blue: tint.2 * shade, alpha: 1)
            context.fillEllipse(in: CGRect(x: x, y: y, width: r * 1.6, height: r))
        }
        context.setFillColor(red: 0.30, green: 0.55, blue: 0.25, alpha: 1)
        context.fillEllipse(in: CGRect(x: cx - s * 0.04, y: cy - s * 0.06, width: s * 0.09, height: s * 0.05))
        guard let image = context.makeImage() else { return nil }
        return RecipePictureThumbnail.jpeg(image, quality: 0.8)
    }
}
#endif

// MARK: Pictures on a result

/// One picture of a result (the dish's or a step's) at the text's width in the design's 4:3 frame,
/// with the card radius. The frame's height comes from its width alone, so it is reserved before
/// the bytes are read and nothing moves when the picture appears (a cross-fade; none with reduced
/// motion). The picture fills it, cropped and centred: a 4:3 one exactly, a 3:2 or a square one
/// trimmed at the sides or top, never letterboxed or stretched. It is decoded off the main actor at
/// the frame's pixel size (`RecipePictureDecoder`), never as the full bitmap. Not tappable. Read
/// aloud as `label` ("Picture of …, made by AI").
struct RecipePictureFrame: View {
    let data: Data?
    let label: String

    /// The frame's shape (docs/design/src/pages/pictures.html, `.pic-img`).
    static let aspect: CGFloat = 4.0 / 3.0

    @State private var image: UIImage?
    @State private var width: CGFloat = 0
    @Environment(\.displayScale) private var displayScale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// What the picture is decoded for: the data and the frame's pixel width (whole points, so
    /// a sub-point layout change doesn't decode it again).
    private struct Target: Equatable {
        let data: Data?
        let pixelWidth: Int
    }

    var body: some View {
        Rectangle()
            .fill(LarderColor.surfaceContainer)
            .aspectRatio(Self.aspect, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: LarderRadius.lg))
            .accessibilityElement()
            .accessibilityLabel(label)
            .accessibilityAddTraits(.isImage)
            .task(id: Target(data: data, pixelWidth: Int((width * displayScale).rounded(.up)))) {
                guard let data else {
                    image = nil
                    return
                }
                let pixels = CGSize(width: width * displayScale, height: width * displayScale / Self.aspect)
                guard pixels.width >= 1 else { return }
                // A wider frame (rotation, iPad split view) decodes again; the old one stays meanwhile.
                guard let decoded = await Self.decode(data, filling: pixels), !Task.isCancelled else { return }
                if image == nil {
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.25)) { image = decoded }
                } else {
                    image = decoded
                }
            }
    }

    /// Decoded at the frame's size off the main actor, so scrolling never waits on a JPEG.
    nonisolated static func decode(_ data: Data, filling pixels: CGSize) async -> UIImage? {
        await Task.detached(priority: .userInitiated) {
            RecipePictureDecoder.decode(data, filling: pixels).map { UIImage(cgImage: $0) }
        }.value
    }
}

/// A quiet line under a result's facts or its Method heading: a caption, or a picture that
/// couldn't be made this time. No error colour, no promise.
struct RecipePictureLine: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .larderFont(.caption)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: The form's Pictures row

/// "Pictures", drawn like More options: the value is what will really be sent (No pictures, or the
/// level and its cost), and when the pack holds it lower, the reason as a second line. One button
/// for VoiceOver: title, value and cost, then that line. Opens `RecipePicturesSheet`.
struct RecipePicturesRow: View {
    let choice: RecipePictureChoice
    let costs: RecipeCosts
    let balance: Int
    let action: () -> Void

    var body: some View {
        let value = L10n.recipeImageValue(choice.sent, cost: costs.picture(choice.sent))
        let short = choice.short.map { L10n.recipeImageShort($0, balance: balance) }
        Button(action: action) {
            HStack(spacing: LarderSpace.s3) {
                LarderIcon(.image).foregroundStyle(LarderColor.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 0) {
                    Text(L10n.recipeImageRow)
                        .larderFont(.label)
                        .foregroundStyle(LarderColor.onSurface)
                    Text(value)
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    if let short {
                        Text(short)
                            .larderFont(.bodySm)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                LarderIcon(.chevronRight).foregroundStyle(LarderColor.onSurfaceVariant)
            }
            .padding(.vertical, LarderSpace.s2)
            .frame(minHeight: LarderSize.touch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([L10n.recipeImageRow, value, short].compactMap { $0 }.joined(separator: ", "))
        .accessibilityAddTraits(.isButton)
    }
}

/// "Pictures, made by AI": the priced levels with each one's cost from the server's table, as a
/// picker (a tap chooses and closes; Cancel or a swipe leaves it unchanged). A level the pack
/// doesn't cover is dimmed, can't be chosen, and the row's line says why.
struct RecipePicturesSheet: View {
    let choice: RecipePictureChoice
    let costs: RecipeCosts
    let balance: Int
    let onChoose: (RecipePictureLevel) -> Void
    let onCancel: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        LarderSheet(L10n.recipeImageTitle) {
            LarderGroup {
                ForEach(choice.levels, id: \.self) { level in
                    row(level)
                }
            }
            .accessibilityElement(children: .contain)
            if let short = choice.short {
                Text(L10n.recipeImageShort(short, balance: balance))
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            LarderButton(L10n.cancelButton, variant: .text, fullWidth: true, action: onCancel)
                .padding(.top, LarderSpace.s1)
        }
    }

    private func row(_ level: RecipePictureLevel) -> some View {
        let enabled = choice.covered.contains(level)
        let selected = level == choice.sent
        let cost = costs.picture(level).map(L10n.recipeCost)
        return Button { onChoose(level) } label: {
            HStack(alignment: .center, spacing: LarderSpace.s3) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? LarderColor.primary : LarderColor.onSurfaceVariant)
                    .font(.title3)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.recipeImageLevel(level))
                        .larderFont(.body)
                        .foregroundStyle(LarderColor.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                    if let description = L10n.recipeImageLevelDescription(level) {
                        Text(description)
                            .larderFont(.bodySm)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    // At accessibility sizes the cost goes under the text, which keeps the width.
                    if let cost, dynamicTypeSize.isAccessibilitySize {
                        Text(cost)
                            .larderFont(.label)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let cost, !dynamicTypeSize.isAccessibilitySize {
                    Text(cost)
                        .larderFont(.label)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .fixedSize()
                }
            }
            .padding(.vertical, LarderSpace.s3)
            .padding(.horizontal, LarderSpace.s4)
            .frame(minHeight: LarderSize.touch)
            .contentShape(Rectangle())
            .opacity(enabled ? 1 : 0.38)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([L10n.recipeImageLevel(level), L10n.recipeImageLevelDescription(level), cost].compactMap { $0 }.joined(separator: ", "))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

// MARK: Report this recipe

/// The very end of a result our server wrote: a quiet "Report this recipe / menu" with a flag, or
/// once sent from here "You've reported this", not tappable. Nothing where `foot` is hidden.
struct RecipeReportFootView: View {
    let foot: RecipeReportFoot
    let isMenu: Bool
    let action: () -> Void

    var body: some View {
        switch foot {
        case .hidden:
            EmptyView()
        case .available:
            LarderButton(L10n.recipeReportAction(isMenu: isMenu), icon: .flag, variant: .text, action: action)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, LarderSpace.s6)
        case .reported:
            HStack(spacing: LarderSpace.s2) {
                LarderIcon(.check, size: .custom(16))
                Text(L10n.recipeReportDone).larderFont(.label)
            }
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .frame(maxWidth: .infinity, minHeight: LarderSize.touch, alignment: .leading)
            .padding(.top, LarderSpace.s6)
            .accessibilityElement(children: .combine)
        }
    }
}

/// The foot for a saved result, following the model (a report sent elsewhere, the account, the
/// server's flag) as it changes.
struct RecipeReportFootHost: View {
    @ObservedObject var model: RecipeViewModel
    let entry: SavedRecipe
    let isMenu: Bool

    var body: some View {
        RecipeReportFootView(foot: model.reportFoot(for: entry), isMenu: isMenu) {
            model.openReport(for: entry, isMenu: isMenu)
        }
    }
}

/// "Report this recipe / menu": the reasons (the picture one only with pictures), an optional
/// note of up to 500 characters with its count, what is sent, Send report / Cancel. Opening it
/// sends nothing; sending never touches the balance and promises nothing.
struct RecipeReportSheetView: View {
    @ObservedObject var model: RecipeViewModel
    @AccessibilityFocusState private var errorFocused: Bool

    var body: some View {
        if let sheet = model.reportSheet {
            let busy = sheet.status == .sending
            LarderSheet(L10n.recipeReportAction(isMenu: sheet.isMenu)) {
                Text(L10n.recipeReportLede)
                    .larderFont(.body)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                LarderGroup {
                    ForEach(sheet.reasons, id: \.self) { reason in
                        reasonRow(reason, selected: sheet.reason == reason)
                    }
                }
                .disabled(busy || sheet.status == .limit || sheet.status == .unknown)
                if sheet.reasonMissing {
                    Text(L10n.recipeReportReasonRequired)
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.error)
                        .accessibilityFocused($errorFocused)
                        .onAppear { errorFocused = true }
                }
                LarderTextField(
                    L10n.recipeReportNoteLabel,
                    text: Binding(get: { model.reportSheet?.note ?? "" }, set: { model.setReportNote($0) }),
                    count: L10n.recipeReportNoteCount(RecipeReporting.noteLength(sheet.note), of: RecipeReporting.noteMax),
                    axis: .vertical
                )
                .disabled(busy || sheet.status == .limit || sheet.status == .unknown)
                HStack(alignment: .top, spacing: LarderSpace.s2) {
                    LarderIcon(.info, size: .custom(16))
                    Text(L10n.recipeReportSends(isMenu: sheet.isMenu))
                        .larderFont(.bodySm)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .accessibilityElement(children: .combine)
                switch sheet.status {
                case .failed:
                    LarderCallout(.error, message: L10n.recipeReportFailed)
                case .limit:
                    LarderCallout(.info, message: L10n.recipeReportLimit)
                case .unknown:
                    LarderCallout(.info, message: L10n.recipeReportUnknown)
                case .editing, .sending:
                    EmptyView()
                }
                VStack(spacing: LarderSpace.s1) {
                    if sheet.status != .limit && sheet.status != .unknown {
                        LarderButton(
                            sheet.status == .failed ? L10n.recipeReportTryAgain : L10n.recipeReportSend,
                            isLoading: busy,
                            fullWidth: true
                        ) { model.sendReport() }
                        .accessibilityLabel(busy ? L10n.recipeReportSending : (sheet.status == .failed ? L10n.recipeReportTryAgain : L10n.recipeReportSend))
                    }
                    LarderButton(L10n.recipeReportCancel, variant: .text, fullWidth: true) { model.dismissReport() }
                        .disabled(busy)
                }
                .padding(.top, LarderSpace.s2)
            }
            .interactiveDismissDisabled(busy)
        }
    }

    private func reasonRow(_ reason: RecipeReportReason, selected: Bool) -> some View {
        Button { model.chooseReportReason(reason) } label: {
            HStack(spacing: LarderSpace.s3) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? LarderColor.primary : LarderColor.onSurfaceVariant)
                    .font(.title3)
                    .accessibilityHidden(true)
                Text(L10n.recipeReportReason(reason))
                    .larderFont(.body)
                    .foregroundStyle(LarderColor.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, LarderSpace.s3)
            .padding(.horizontal, LarderSpace.s4)
            .frame(minHeight: LarderSize.touch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}
