import AuthenticationServices
import CryptoKit
import FirebaseAuth
import FirebaseCore
import Foundation
import GoogleSignIn
import UIKit

// Who holds the recipes: nobody yet, this install (an anonymous Firebase user, made only when a
// purchase starts), or an account signed in with Apple or Google. Signing in from an install
// that holds recipes links the sign-in to the same user where it can, so the recipes stay put,
// and otherwise moves them over with `mergeBalance`.

enum RecipeSignInProvider: String, CaseIterable, Sendable {
    case apple = "apple.com"
    case google = "google.com"
}

enum RecipeAccountState: Equatable, Sendable {
    case none
    case anonymous(uid: String)
    case signedIn(uid: String, providers: [RecipeSignInProvider], email: String?)

    var uid: String? {
        switch self {
        case .none: return nil
        case .anonymous(let uid), .signedIn(let uid, _, _): return uid
        }
    }

    var isSignedIn: Bool {
        if case .signedIn = self { return true }
        return false
    }

    var providers: [RecipeSignInProvider] {
        if case .signedIn(_, let providers, _) = self { return providers }
        return []
    }
}

enum RecipeAccountError: Error, Equatable {
    case cancelled
    case failed
    /// Adding a sign-in that already belongs to another account, which can't be joined to this
    /// one: only an install's recipes can be moved into an account.
    case providerInUse
    case notConfigured
}

/// A sign-in from Apple or Google, ready for Firebase.
struct RecipeSignInCredential {
    let provider: RecipeSignInProvider
    let credential: AuthCredential
    /// Sign in with Apple's one-time code, which the server exchanges to revoke Apple's tokens
    /// when the account is deleted.
    let appleAuthorizationCode: String?
}

/// An account that already exists, signed in on a second Firebase app so this install's user is
/// still the current one until the person decides.
struct RecipePendingMerge: Identifiable {
    let id = UUID()
    let accountBalance: Int
    let installBalance: Int
    fileprivate let appName: String
    fileprivate let user: User
}

/// What a sign-in came to.
enum RecipeSignInOutcome {
    /// Same user as before (linked) or a fresh sign-in on an empty install.
    case signedIn
    /// The account exists and this install holds recipes: ask before moving them.
    case needsMerge(RecipePendingMerge)
}

@MainActor
final class RecipeAccount {
    private static let mergeAppName = "recipeMerge"

    private var auth: Auth? {
        FirebaseApp.app().map { FirebaseBootstrap.auth(app: $0) }
    }

    var state: RecipeAccountState { Self.state(of: auth?.currentUser) }

    static func state(of user: User?) -> RecipeAccountState {
        guard let user else { return .none }
        if user.isAnonymous { return .anonymous(uid: user.uid) }
        let providers = RecipeSignInProvider.allCases.filter { provider in
            user.providerData.contains { $0.providerID == provider.rawValue }
        }
        let email = user.email ?? user.providerData.compactMap(\.email).first
        return .signedIn(uid: user.uid, providers: providers, email: email)
    }

    /// The user calls are made as, created anonymously if there is none: only ever on the way to
    /// a purchase or a purchase's confirmation.
    func ensureUser() async throws {
        guard let auth else { throw RecipeAccountError.notConfigured }
        if auth.currentUser == nil {
            do {
                _ = try await auth.signInAnonymously()
            } catch {
                throw Self.isNetwork(error) ? RecipeServerError.offline : RecipeAccountError.failed
            }
        }
    }

    /// Signs in with `credential`: links it to this install's user where possible, signs in
    /// directly when there is no user, and adds it as a second sign-in to an account.
    func signIn(with credential: RecipeSignInCredential, installBalance: Int) async throws -> RecipeSignInOutcome {
        guard let auth else { throw RecipeAccountError.notConfigured }
        guard let user = auth.currentUser else {
            do {
                _ = try await auth.signIn(with: credential.credential)
                return .signedIn
            } catch {
                throw Self.mapped(error)
            }
        }
        do {
            _ = try await user.link(with: credential.credential)
            return .signedIn
        } catch {
            let nsError = error as NSError
            guard nsError.domain == AuthErrors.domain,
                  nsError.code == AuthErrorCode.credentialAlreadyInUse.rawValue
                    || nsError.code == AuthErrorCode.emailAlreadyInUse.rawValue else {
                throw Self.mapped(error)
            }
            guard user.isAnonymous else { throw RecipeAccountError.providerInUse }
            // Apple won't take the same credential twice; Firebase hands back a fresh one.
            let existing = nsError.userInfo[AuthErrors.userInfoUpdatedCredentialKey] as? AuthCredential
                ?? credential.credential
            return try await prepareMerge(credential: existing, installBalance: installBalance)
        }
    }

    private func prepareMerge(credential: AuthCredential, installBalance: Int) async throws -> RecipeSignInOutcome {
        guard let options = FirebaseApp.app()?.options else { throw RecipeAccountError.notConfigured }
        await deleteMergeApp()
        FirebaseApp.configure(name: Self.mergeAppName, options: options)
        guard let mergeApp = FirebaseApp.app(name: Self.mergeAppName) else { throw RecipeAccountError.failed }
        let mergeAuth = FirebaseBootstrap.auth(app: mergeApp)
        let signedIn: User
        do {
            signedIn = try await mergeAuth.signIn(with: credential).user
        } catch {
            await deleteMergeApp()
            throw Self.mapped(error)
        }
        if installBalance <= 0 {
            // Nothing to move: the account simply takes over.
            try await adopt(signedIn)
            return .signedIn
        }
        let accountBalance: Int
        do {
            accountBalance = try await FirebaseRecipeBackend(appName: Self.mergeAppName).balance().balance
        } catch {
            await deleteMergeApp()
            throw error
        }
        return .needsMerge(RecipePendingMerge(
            accountBalance: accountBalance,
            installBalance: installBalance,
            appName: Self.mergeAppName,
            user: signedIn
        ))
    }

    /// "Add and sign in": moves this install's recipes into the account, then makes the account
    /// the current user. Returns the server's answer, with the account's new balance.
    func completeMerge(_ pending: RecipePendingMerge) async throws -> MergeBalanceResponse {
        guard let source = auth?.currentUser, source.isAnonymous else { throw RecipeAccountError.failed }
        let token = try await source.getIDToken()
        let response = try await FirebaseRecipeBackend(appName: pending.appName).mergeBalance(sourceIdToken: token)
        try await adopt(pending.user)
        return response
    }

    func cancelMerge() async {
        await deleteMergeApp()
    }

    private func adopt(_ user: User) async throws {
        defer { Task { await self.deleteMergeApp() } }
        do {
            try await auth?.updateCurrentUser(user)
        } catch {
            throw Self.mapped(error)
        }
    }

    private func deleteMergeApp() async {
        guard let app = FirebaseApp.app(name: Self.mergeAppName) else { return }
        try? FirebaseBootstrap.auth(app: app).signOut()
        _ = await app.delete()
    }

    func signOut() {
        try? auth?.signOut()
        GIDSignIn.sharedInstance.signOut()
    }

    /// The fresh sign-in `deleteAccount` needs, then the deletion. Returns once the server has
    /// deleted the account and this phone has signed out.
    func deleteAccount(with credential: RecipeSignInCredential, backend: RecipeBackend) async throws {
        guard let user = auth?.currentUser else { throw RecipeAccountError.failed }
        do {
            _ = try await user.reauthenticate(with: credential.credential)
        } catch {
            throw Self.mapped(error)
        }
        try await backend.deleteAccount(appleAuthorizationCode: credential.appleAuthorizationCode)
        signOut()
    }

    static func isNetwork(_ error: Error) -> Bool {
        let nsError = error as NSError
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError, underlying.domain == NSURLErrorDomain {
            return true
        }
        return nsError.domain == NSURLErrorDomain
            || (nsError.domain == AuthErrors.domain && nsError.code == AuthErrorCode.networkError.rawValue)
    }

    static func mapped(_ error: Error) -> Error {
        if error is RecipeAccountError || error is RecipeServerError { return error }
        if isNetwork(error) { return RecipeServerError.offline }
        let nsError = error as NSError
        if nsError.domain == AuthErrors.domain, nsError.code == AuthErrorCode.webContextCancelled.rawValue {
            return RecipeAccountError.cancelled
        }
        return RecipeAccountError.failed
    }
}

// MARK: Sign in with Apple

/// One Sign in with Apple request: the nonce Firebase checks, hashed for Apple.
@MainActor
final class AppleSignInRequest: NSObject {
    private var nonce = AppleSignInRequest.randomNonce()
    private var continuation: CheckedContinuation<RecipeSignInCredential, Error>?

    /// For `SignInWithAppleButton`'s `onRequest`.
    func configure(_ request: ASAuthorizationAppleIDRequest) {
        nonce = Self.randomNonce()
        request.requestedScopes = [.email]
        request.nonce = Self.sha256(nonce)
    }

    /// For `SignInWithAppleButton`'s `onCompletion`.
    func credential(from result: Result<ASAuthorization, Error>) throws -> RecipeSignInCredential {
        switch result {
        case .success(let authorization):
            return try credential(from: authorization)
        case .failure(let error):
            throw Self.mapped(error)
        }
    }

    /// The same without a button, for the fresh sign-in before deleting an account.
    func perform() async throws -> RecipeSignInCredential {
        let request = ASAuthorizationAppleIDProvider().createRequest()
        configure(request)
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            controller.performRequests()
        }
    }

    private func credential(from authorization: ASAuthorization) throws -> RecipeSignInCredential {
        guard let apple = authorization.credential as? ASAuthorizationAppleIDCredential,
              let tokenData = apple.identityToken,
              let idToken = String(data: tokenData, encoding: .utf8) else {
            throw RecipeAccountError.failed
        }
        let credential = OAuthProvider.appleCredential(withIDToken: idToken, rawNonce: nonce, fullName: nil)
        let code = apple.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
        return RecipeSignInCredential(provider: .apple, credential: credential, appleAuthorizationCode: code)
    }

    private static func mapped(_ error: Error) -> Error {
        if let error = error as? ASAuthorizationError, error.code == .canceled { return RecipeAccountError.cancelled }
        return RecipeAccountError.failed
    }

    static func randomNonce(length: Int = 32) -> String {
        let charset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var generator = SystemRandomNumberGenerator()
        return String((0..<length).map { _ in charset.randomElement(using: &generator)! })
    }

    static func sha256(_ input: String) -> String {
        SHA256.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

extension AppleSignInRequest: ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        MainActor.assumeIsolated {
            let result = Result { try self.credential(from: authorization) }
            continuation?.resume(with: result)
            continuation = nil
        }
    }

    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        MainActor.assumeIsolated {
            continuation?.resume(throwing: Self.mapped(error))
            continuation = nil
        }
    }

    nonisolated func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        MainActor.assumeIsolated { RecipePresenter.window ?? ASPresentationAnchor() }
    }
}

// MARK: Sign in with Google

enum GoogleSignInSupport {
    /// The OAuth client id from GoogleService-Info.plist, present once Google sign-in is enabled
    /// for the iOS app in the Firebase console and the plist is downloaded again.
    static var clientID: String? { FirebaseApp.app()?.options.clientID }

    /// Google sign-in works only with a client id and its reversed form registered as a URL
    /// scheme; without both the button is hidden rather than crashing the SDK.
    static var isConfigured: Bool {
        isConfigured(clientID: clientID, urlSchemes: registeredURLSchemes())
    }

    static func isConfigured(clientID: String?, urlSchemes: [String]) -> Bool {
        guard let clientID, !clientID.isEmpty else { return false }
        return urlSchemes.contains(reversed(clientID: clientID))
    }

    /// "123-abc.apps.googleusercontent.com" → "com.googleusercontent.apps.123-abc".
    static func reversed(clientID: String) -> String {
        clientID.split(separator: ".").reversed().joined(separator: ".")
    }

    static func registeredURLSchemes(bundle: Bundle = .main) -> [String] {
        let types = bundle.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []
        return types.flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] }
    }

    @MainActor
    static func signIn() async throws -> RecipeSignInCredential {
        guard isConfigured, let clientID, let presenter = RecipePresenter.topViewController else {
            throw RecipeAccountError.notConfigured
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        let result: GIDSignInResult
        do {
            result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
        } catch {
            if let error = error as? GIDSignInError, error.code == .canceled {
                throw RecipeAccountError.cancelled
            }
            throw RecipeAccount.isNetwork(error) ? RecipeServerError.offline : RecipeAccountError.failed
        }
        guard let idToken = result.user.idToken?.tokenString else { throw RecipeAccountError.failed }
        let credential = GoogleAuthProvider.credential(
            withIDToken: idToken,
            accessToken: result.user.accessToken.tokenString
        )
        return RecipeSignInCredential(provider: .google, credential: credential, appleAuthorizationCode: nil)
    }
}

/// Where the sign-in sheets present from.
@MainActor
enum RecipePresenter {
    static var window: UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
    }

    static var topViewController: UIViewController? {
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
