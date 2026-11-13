import FirebaseCore
import FirebaseFunctions
import Foundation

/// The five callables in `functions/`, region europe-west1, every one with App Check. Errors come
/// out as `RecipeServerError`.
struct FirebaseRecipeBackend: RecipeBackend {
    /// The app whose Auth user makes the calls: the default one, or a second one while signing
    /// in to an account that already exists (see `RecipeAccount`).
    private let appName: String?

    init(appName: String? = nil) {
        self.appName = appName
    }

    func generate(_ request: GenerateRequest) async throws -> GenerateResponse {
        // A recipe takes up to ~40 s and a menu up to ~2 min on the server.
        try await call("generateRecipe", request, timeout: request.kind == .menu ? 190 : 70)
    }

    func balance() async throws -> BalanceResponse {
        try await call("getBalance", EmptyRequest())
    }

    func verifyPurchase(signedTransaction: String) async throws -> VerifyPurchaseResponse {
        try await call("verifyPurchase", VerifyPurchaseRequest(signedTransaction: signedTransaction))
    }

    func mergeBalance(sourceIdToken: String) async throws -> MergeBalanceResponse {
        try await call("mergeBalance", MergeBalanceRequest(sourceIdToken: sourceIdToken))
    }

    func deleteAccount(appleAuthorizationCode: String?) async throws {
        let _: DeleteAccountResponse = try await call(
            "deleteAccount", DeleteAccountRequest(appleAuthorizationCode: appleAuthorizationCode)
        )
    }

    private func call<Request: Encodable, Response: Decodable>(
        _ name: String,
        _ request: Request,
        timeout: TimeInterval = 30
    ) async throws -> Response {
        guard let app = appName.flatMap(FirebaseApp.app(name:)) ?? FirebaseApp.app() else {
            throw RecipeServerError.unauthenticated
        }
        // Limited-use tokens cost nothing while the server doesn't consume them, and are what it
        // will need once it does (CONTRACT.md, "Calling").
        var callable = FirebaseBootstrap.functions(app: app).httpsCallable(
            name,
            options: HTTPSCallableOptions(requireLimitedUseAppCheckTokens: true),
            requestAs: Request.self,
            responseAs: Response.self
        )
        callable.timeoutInterval = timeout
        do {
            return try await callable.call(request)
        } catch {
            throw Self.mapped(error)
        }
    }

    static func mapped(_ error: Error) -> RecipeServerError {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain { return .offline }
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError, underlying.domain == NSURLErrorDomain {
            return .offline
        }
        guard nsError.domain == FunctionsErrorDomain, let code = FunctionsErrorCode(rawValue: nsError.code) else {
            return .server
        }
        let details = nsError.userInfo[FunctionsErrorDetailsKey] as? [String: Any]
        return RecipeServerError.from(
            code: name(of: code),
            reason: details?["reason"] as? String,
            charged: details?["charged"] as? Bool
        )
    }

    private static func name(of code: FunctionsErrorCode) -> String {
        switch code {
        case .invalidArgument: return "invalid-argument"
        case .deadlineExceeded: return "deadline-exceeded"
        case .failedPrecondition: return "failed-precondition"
        case .permissionDenied: return "permission-denied"
        case .resourceExhausted: return "resource-exhausted"
        case .unavailable: return "unavailable"
        case .unauthenticated: return "unauthenticated"
        default: return "internal"
        }
    }
}
