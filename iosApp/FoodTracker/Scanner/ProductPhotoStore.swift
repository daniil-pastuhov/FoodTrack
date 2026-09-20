import Foundation

/// Owns the on-disk folder of user-captured product photos and the string reference that
/// `PantryItem.imageUrl` / `LocalProductRecord.imageUrl` hold for them.
///
/// A captured photo is stored as just its file name (e.g. `product_photo_1700000000000.jpg`),
/// never as an absolute path. The app sandbox lives at
/// `/var/mobile/Containers/Data/Application/<UUID>/...` and iOS assigns a fresh UUID on every
/// app update, reinstall and device restore, so an absolute `file://` URL persisted today points
/// at nothing after the next TestFlight/App Store build — which is exactly how photos used to
/// "disappear". Resolving the name against the *current* container at read time keeps them.
///
/// Records written before this existed still carry the absolute `file://` URL; `fileURL(for:)`
/// recognises those and falls back to the same file name inside the current photos folder.
public enum ProductPhotoStore {
    private static let directoryName = "ProductPhotos"

    /// `Library/Application Support/ProductPhotos` inside the current app container. Application
    /// Support (unlike Caches or tmp) is never purged by the system and is included in backups.
    public static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(directoryName, isDirectory: true)
    }

    /// Writes JPEG data to a new file in `directory` and returns its URL.
    public static func save(jpegData: Data) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let fileURL = directory
            .appendingPathComponent("product_photo_\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
        try jpegData.write(to: fileURL)
        return fileURL
    }

    /// The container-independent string to persist for a photo in `directory`.
    public static func reference(for fileURL: URL) -> String {
        fileURL.lastPathComponent
    }

    /// True when `reference` denotes a local photo (either the current bare-file-name form or a
    /// legacy absolute `file://` URL) rather than a remote `http(s)` image.
    public static func isLocalReference(_ reference: String) -> Bool {
        fileURL(for: reference) != nil
    }

    /// Resolves a stored reference to a file in the current container, or `nil` when the
    /// reference is a remote URL. The returned file is not guaranteed to exist.
    public static func fileURL(for reference: String) -> URL? {
        let trimmed = reference.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        if let url = URL(string: trimmed), url.scheme != nil {
            guard url.isFileURL else { return nil }
            // Legacy absolute path: prefer it while the container it names still exists, otherwise
            // the file name is all that survives an app update.
            if FileManager.default.fileExists(atPath: url.path) { return url }
            return directory.appendingPathComponent(url.lastPathComponent)
        }

        // No scheme: a bare file name written by `reference(for:)`. Ignore anything with path
        // separators so a malformed value can never escape the photos folder.
        guard !trimmed.contains("/") else { return nil }
        return directory.appendingPathComponent(trimmed)
    }
}
