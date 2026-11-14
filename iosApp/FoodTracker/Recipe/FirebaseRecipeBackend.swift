import FirebaseCore
import FirebaseFunctions
import Foundation

/// The six callables in `functions/`, region europe-west1, every one with App Check. Errors come
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

    /// Not consumed on the server, so a normal (cached) App Check token rather than a limited-use
    /// one: polling doesn't use up tokens.
    func generation(requestId: String) async throws -> GenerateResponse {
        try await call("getGeneration", GetGenerationRequest(requestId: requestId), limitedUseToken: false)
    }

    /// Not consumed either: an ordinary token, as `getGeneration`.
    func balance() async throws -> BalanceResponse {
        try await call("getBalance", EmptyRequest(), limitedUseToken: false)
    }

    func verifyPurchase(signedTransaction: String, consumptionConsent: Bool) async throws -> VerifyPurchaseResponse {
        try await call(
            "verifyPurchase",
            VerifyPurchaseRequest(signedTransaction: signedTransaction, consumptionConsent: consumptionConsent)
        )
    }

    func mergeBalance(sourceIdToken: String) async throws -> MergeBalanceResponse {
        try await call("mergeBalance", MergeBalanceRequest(sourceIdToken: sourceIdToken))
    }

    func deleteAccount(appleAuthorizationCode: String?) async throws {
        // The server's own limit is 120 s (it revokes the Apple token and deletes the user).
        let _: DeleteAccountResponse = try await call(
            "deleteAccount", DeleteAccountRequest(appleAuthorizationCode: appleAuthorizationCode), timeout: 120
        )
    }

    private func call<Request: Encodable, Response: Decodable>(
        _ name: String,
        _ request: Request,
        timeout: TimeInterval = 30,
        limitedUseToken: Bool = true
    ) async throws -> Response {
        guard let app = appName.flatMap(FirebaseApp.app(name:)) ?? FirebaseApp.app() else {
            throw RecipeServerError.unauthenticated
        }
        // A limited-use App Check token for `generateRecipe`, `verifyPurchase`, `mergeBalance`
        // and `deleteAccount`, which consume theirs (CONTRACT.md, "Calling"), so none may be
        // reused: the SDK asks App Check for a new one on every `call`, which covers each
        // fallback to `generateRecipe` and each purchase resubmission, and nothing here keeps a
        // token. `getGeneration` and `getBalance` take an ordinary one.
        var callable = FirebaseBootstrap.functions(app: app).httpsCallable(
            name,
            options: HTTPSCallableOptions(requireLimitedUseAppCheckTokens: limitedUseToken),
            requestAs: Request.self,
            responseAs: Response.self
        )
        callable.timeoutInterval = timeout
        var attempt = 0
        while true {
            do {
                return try await callable.call(request)
            } catch {
                let mapped = Self.mapped(error)
                // A replayed token: once more, with the new token the next call fetches.
                guard RecipeServerError.retriesImmediately(mapped, attempt: attempt) else { throw mapped }
                attempt += 1
            }
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
            charged: details?["charged"] as? Bool,
            field: details?["field"] as? String
        )
    }

    private static func name(of code: FunctionsErrorCode) -> String {
        switch code {
        case .invalidArgument: return "invalid-argument"
        case .deadlineExceeded: return "deadline-exceeded"
        case .notFound: return "not-found"
        case .unimplemented: return "unimplemented"
        case .failedPrecondition: return "failed-precondition"
        case .permissionDenied: return "permission-denied"
        case .resourceExhausted: return "resource-exhausted"
        case .unavailable: return "unavailable"
        case .unauthenticated: return "unauthenticated"
        default: return "internal"
        }
    }
}
