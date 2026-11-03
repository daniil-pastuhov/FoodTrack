import SwiftUI
import UIKit

/// Renders a product image from an `imageUrl` reference — a locally captured photo (see
/// `ProductPhotoStore`) or a remote Open Food Facts URL — with `placeholder` shown until it is
/// available (or when it isn't).
///
/// Replaces per-view `UIImage(contentsOfFile:)` + `AsyncImage` code. `AsyncImage` is unreliable
/// inside `List`: a cell scrolled off-screen mid-download settles into `.failure(cancelled)` and
/// stays on the placeholder forever. Loading via `.task(id:)` re-runs on every appearance, so a
/// cancelled load simply restarts, and the decoded result is cached so the retry is free.
public struct ProductImageView<Placeholder: View>: View {
    private let reference: String?
    private let placeholder: Placeholder

    @State private var image: UIImage?

    public init(reference: String?, @ViewBuilder placeholder: () -> Placeholder) {
        self.reference = reference
        self.placeholder = placeholder()
    }

    public var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .transition(.opacity)
            } else {
                placeholder
            }
        }
        .animation(.easeInOut(duration: 0.25), value: image != nil)
        .task(id: reference) {
            guard let reference, !reference.isEmpty else {
                image = nil
                return
            }
            if let cached = ProductImageLoader.shared.cachedImage(for: reference) {
                image = cached
                return
            }
            image = nil
            let loaded = await ProductImageLoader.shared.image(for: reference)
            guard !Task.isCancelled else { return }
            image = loaded
        }
    }
}

/// Loads product images off the main thread and memoises the decoded `UIImage` per reference.
public final class ProductImageLoader: @unchecked Sendable {
    public static let shared = ProductImageLoader()

    private let memoryCache = NSCache<NSString, UIImage>()

    /// Remote images go through a session whose `URLCache` is large enough to keep every product
    /// picture the user has scanned, so the list still shows them offline and after the shared
    /// session's 10 MB default cache would have evicted them.
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.urlCache = URLCache(
            memoryCapacity: 8 * 1024 * 1024,
            diskCapacity: 100 * 1024 * 1024,
            diskPath: "ProductImageCache"
        )
        configuration.requestCachePolicy = .returnCacheDataElseLoad
        return URLSession(configuration: configuration)
    }()

    private init() {
        memoryCache.countLimit = 300
    }

    public func cachedImage(for reference: String) -> UIImage? {
        memoryCache.object(forKey: reference as NSString)
    }

    public func image(for reference: String) async -> UIImage? {
        if let cached = cachedImage(for: reference) { return cached }

        let loaded: UIImage?
        if let fileURL = ProductPhotoStore.fileURL(for: reference) {
            loaded = await Task.detached(priority: .userInitiated) {
                UIImage(contentsOfFile: fileURL.path)
            }.value
        } else if let url = URL(string: reference), let scheme = url.scheme, scheme.hasPrefix("http") {
            loaded = await fetchRemote(url)
        } else {
            loaded = nil
        }

        if let loaded {
            memoryCache.setObject(loaded, forKey: reference as NSString)
        }
        return loaded
    }

    private func fetchRemote(_ url: URL) async -> UIImage? {
        var request = URLRequest(url: url)
        request.setValue("FoodTracker-iOS/1.0 (https://github.com/daniil-pastuhov/FoodTrack)", forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await session.data(for: request) else { return nil }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            return nil
        }
        return await Task.detached(priority: .userInitiated) {
            // Decode off the main thread; the list draws this at 54pt, so force the bitmap to
            // exist now instead of on first draw.
            UIImage(data: data)?.preparingForDisplay()
        }.value
    }
}
