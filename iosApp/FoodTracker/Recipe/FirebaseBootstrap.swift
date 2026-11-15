import DeviceCheck
import FirebaseAppCheck
import FirebaseAuth
import FirebaseCore
import FirebaseFunctions

/// Starts Firebase, in every build. Crashlytics collection stays off in Debug through the
/// `FirebaseCrashlyticsCollectionEnabled` Info.plist key (`CRASHLYTICS_COLLECTION_ENABLED`), never
/// through `setCrashlyticsCollectionEnabled`, whose value would persist on the device.
///
/// Configuring creates no Auth user, App Check token or server call: App Check's auto refresh
/// is off in Info.plist, and the paid route asks for a user and a token only when a purchase
/// starts.
enum FirebaseBootstrap {
    static let region = "europe-west1"

    static func configure() {
        // Before `configure`, as App Check requires.
        AppCheck.setAppCheckProviderFactory(RecipeAppCheckProviderFactory())
        #if DEBUG
        if let host = emulatorHost, let options = FirebaseOptions.defaultOptions() {
            // The emulator suite runs as the `demo-` project, which never reaches a real one.
            options.projectID = emulatorProjectID
            FirebaseApp.configure(options: options)
            Auth.auth().useEmulator(withHost: host, port: 9099)
            return
        }
        #endif
        FirebaseApp.configure()
    }

    /// Whether this build can prove it is genuine at all. Debug builds use the debug provider,
    /// whose token is registered by hand in the console; release builds need App Attest, which
    /// some devices (and the simulator) don't have. Without it the paid route stays hidden.
    static var canAttest: Bool {
        #if DEBUG
        return true
        #else
        return DCAppAttestService.shared.isSupported
        #endif
    }

    static func functions(app: FirebaseApp) -> Functions {
        let functions = Functions.functions(app: app, region: region)
        #if DEBUG
        if let host = emulatorHost {
            functions.useEmulator(withHost: host, port: 5001)
        }
        #endif
        return functions
    }

    static func auth(app: FirebaseApp) -> Auth {
        let auth = Auth.auth(app: app)
        #if DEBUG
        if let host = emulatorHost, app.name != FirebaseApp.app()?.name {
            auth.useEmulator(withHost: host, port: 9099)
        }
        #endif
        return auth
    }

    #if DEBUG
    /// `-recipeEmulatorHost 127.0.0.1`: talk to the Firebase emulator suite (functions/README.md)
    /// instead of the real project. Debug builds only.
    static var emulatorHost: String? {
        UserDefaults.standard.string(forKey: "recipeEmulatorHost").flatMap { $0.isEmpty ? nil : $0 }
    }

    static let emulatorProjectID = "demo-foodtracker"
    #endif
}

/// App Attest in release builds, the debug provider in debug ones. The debug token is printed
/// to the console on first use and registered in the Firebase console by hand; it is never
/// committed. Against the emulator suite, which decodes App Check tokens without verifying
/// them, a local stand-in token is used so nothing reaches App Check's real service.
final class RecipeAppCheckProviderFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> AppCheckProvider? {
        #if DEBUG
        if FirebaseBootstrap.emulatorHost != nil {
            return EmulatorAppCheckProvider(appID: app.options.googleAppID)
        }
        return AppCheckDebugProvider(app: app)
        #else
        return AppAttestProvider(app: app)
        #endif
    }
}

#if DEBUG
/// A well-formed, unsigned App Check token for the Functions emulator only.
final class EmulatorAppCheckProvider: NSObject, AppCheckProvider {
    private let appID: String

    init(appID: String) {
        self.appID = appID
    }

    func getToken(completion handler: @escaping (AppCheckToken?, Error?) -> Void) {
        handler(Self.token(appID: appID), nil)
    }

    func getLimitedUseToken(completion handler: @escaping (AppCheckToken?, Error?) -> Void) {
        handler(Self.token(appID: appID), nil)
    }

    static func token(appID: String, now: Date = Date()) -> AppCheckToken {
        let expiry = now.addingTimeInterval(3600)
        func part(_ object: [String: Any]) -> String {
            let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
            return data.base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        let header = part(["alg": "RS256", "typ": "JWT", "kid": "emulator"])
        let payload = part([
            "sub": appID,
            "aud": ["projects/\(FirebaseBootstrap.emulatorProjectID)"],
            "iss": "https://firebaseappcheck.googleapis.com/emulator",
            "iat": Int(now.timeIntervalSince1970),
            "exp": Int(expiry.timeIntervalSince1970),
        ])
        return AppCheckToken(token: "\(header).\(payload).emulator", expirationDate: expiry)
    }
}
#endif
