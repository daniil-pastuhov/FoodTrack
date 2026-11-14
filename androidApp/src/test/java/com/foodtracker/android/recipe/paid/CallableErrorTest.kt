package com.foodtracker.android.recipe.paid

import com.foodtracker.recipe.BalanceReply
import com.foodtracker.recipe.MergeReply
import com.foodtracker.recipe.RecipeCosts
import com.foodtracker.recipe.ServerError
import com.foodtracker.recipe.ServerResult
import com.google.firebase.functions.FirebaseFunctionsException.Code
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** How a callable's failure becomes the contract's error, which decides whether a held request
 *  id is kept and whether the paid route hides (functions/CONTRACT.md, Errors). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallableErrorTest {

    private fun error(code: Code, reason: String? = null, cause: Throwable? = null): ServerError =
        serverErrorOf(code, reason, cause)

    @Test
    fun reasonsAreReadFromTheDetails() {
        assertEquals(ServerError.INSUFFICIENT_CREDITS, error(Code.FAILED_PRECONDITION, "INSUFFICIENT_CREDITS"))
        assertEquals(ServerError.BUSY, error(Code.FAILED_PRECONDITION, "BUSY"))
        assertEquals(ServerError.ACCOUNT_CLOSED, error(Code.FAILED_PRECONDITION, "ACCOUNT_CLOSED"))
        assertEquals(ServerError.RECENT_SIGN_IN_REQUIRED, error(Code.FAILED_PRECONDITION, "RECENT_SIGN_IN_REQUIRED"))
        assertEquals(ServerError.MERGE_NOT_ALLOWED, error(Code.PERMISSION_DENIED, "MERGE_NOT_ALLOWED"))
        assertEquals(ServerError.RATE_LIMITED, error(Code.RESOURCE_EXHAUSTED, "RATE_LIMITED"))
        assertEquals(ServerError.DAILY_CAP, error(Code.RESOURCE_EXHAUSTED, "DAILY_CAP"))
        assertEquals(ServerError.INVALID_REQUEST, error(Code.INVALID_ARGUMENT, "INVALID_REQUEST"))
    }

    @Test
    fun onlyTheKillSwitchReasonMeansPaused() {
        assertEquals(ServerError.GENERATION_DISABLED, error(Code.UNAVAILABLE, "GENERATION_DISABLED"))
        assertEquals(ServerError.OFFLINE, error(Code.UNAVAILABLE), "a plain unavailable is a lost connection")
    }

    @Test
    fun aDroppedConnectionIsOfflineAndAServerBugIsInternal() {
        assertEquals(ServerError.OFFLINE, error(Code.INTERNAL, cause = IOException("reset")))
        assertEquals(ServerError.OFFLINE, error(Code.DEADLINE_EXCEEDED))
        assertEquals(ServerError.INTERNAL, error(Code.INTERNAL))
        assertEquals(ServerError.INTERNAL, error(Code.UNKNOWN))
    }

    @Test
    fun aReasonAddedOnTheServerLaterIsUnknownNotABug() {
        assertEquals(ServerError.UNKNOWN, error(Code.UNKNOWN, "SOMETHING_NEW"))
        assertEquals(ServerError.UNKNOWN, error(Code.RESOURCE_EXHAUSTED, "TOO_MANY_FAILURES_TODAY"))
        assertEquals(ServerError.UNKNOWN, error(Code.FAILED_PRECONDITION, "CONTENT_REFUND_LIMIT"))
    }

    @Test
    fun getBalanceRepliesCarryTheCostsWhenThereAreAny() {
        val withCosts = JsonObject(
            mapOf(
                "balance" to JsonPrimitive(12),
                "costs" to JsonObject(mapOf("recipe" to JsonPrimitive(1), "menu2" to JsonPrimitive(4), "menu3" to JsonPrimitive(5)))
            )
        )
        assertEquals(BalanceReply(12, RecipeCosts(1, 4, 5)), balanceReplyOf(withCosts), "with costs")
        assertEquals(BalanceReply(3, null), balanceReplyOf(JsonObject(mapOf("balance" to JsonPrimitive(3)))), "an older server")
        assertEquals(null, balanceReplyOf(JsonObject(mapOf("costs" to JsonNull))), "no balance is no reply")
    }

    @Test
    fun onlyAConnectionFailureLeavesAttestationUnknown() {
        assertEquals(true, RuntimeException("wrapped", IOException("reset")).isConnectionFailure(), "an I/O cause")
        assertEquals(false, RuntimeException("App attestation failed: 403").isConnectionFailure(), "a refusal")
    }

    private class IntegrityServiceException(message: String) : Exception(message)

    @Test
    fun onlyARealRefusalHidesThePaidRoute() {
        assertEquals(true, RuntimeException("Error returned from API. code: 403 body: App attestation failed.").isAttestationRefusal(), "the exchange refused it")
        assertEquals(false, RuntimeException("wrapped", IntegrityServiceException("-12: TOO_MANY_REQUESTS 403")).isAttestationRefusal(), "a passing Play Integrity error")
        assertEquals(false, RuntimeException("wrapped", IOException("403 reset")).isAttestationRefusal(), "a lost connection")
        assertEquals(false, RuntimeException("Something else went wrong").isAttestationRefusal(), "unknown isn't a refusal")
    }

    private class FakeProfile(var name: String?, var photo: String?, val fails: Boolean = false) : NamedProfile {
        var clears = 0
        override val hasNameOrPhoto: Boolean get() = name != null || photo != null
        override suspend fun clearNameAndPhoto() {
            clears++
            if (fails) throw IllegalStateException("network")
            name = null
            photo = null
        }
    }

    @Test
    fun aSignInsNameAndPhotoAreCleared() = kotlinx.coroutines.runBlocking {
        val google = FakeProfile("A Person", "https://example.invalid/p.jpg")
        assertEquals(true, dropNameAndPhoto(google), "cleared")
        assertEquals(null, google.name, "no name kept")
        assertEquals(null, google.photo, "no photo kept")

        val apple = FakeProfile("A Person", null)
        dropNameAndPhoto(apple)
        assertEquals(null, apple.name, "Apple's first-sign-in name too")

        val clean = FakeProfile(null, null)
        dropNameAndPhoto(clean)
        assertEquals(0, clean.clears, "nothing to clear, no call")

        val failing = FakeProfile("A Person", null, fails = true)
        assertEquals(false, dropNameAndPhoto(failing), "a failure doesn't break sign-in")
    }

    @Test
    fun getBalanceRepliesCarryTheBlock() {
        val blocked = JsonObject(mapOf("balance" to JsonPrimitive(4), "purchasesBlocked" to JsonPrimitive(true)))
        assertEquals(true, balanceReplyOf(blocked)?.purchasesBlocked, "blocked")
        assertEquals(false, balanceReplyOf(JsonObject(mapOf("balance" to JsonPrimitive(4))))?.purchasesBlocked, "absent")
    }

    @Test
    fun noUserOrNoAppCheckTokenIsUnauthenticated() {
        assertEquals(ServerError.UNAUTHENTICATED, error(Code.UNAUTHENTICATED))
    }

    @Test
    fun theFinalContractsReasonsAreRead() {
        assertEquals(ServerError.APP_CHECK_REPLAYED, error(Code.UNAUTHENTICATED, "APP_CHECK_REPLAYED"))
        assertEquals(ServerError.UNAUTHENTICATED, error(Code.UNAUTHENTICATED, "SOMETHING_ELSE"), "only a replay is not a refusal")
        assertEquals(ServerError.USER_DAILY_LIMIT, error(Code.RESOURCE_EXHAUSTED, "USER_DAILY_LIMIT"))
        assertEquals(ServerError.INFRA_FAILURE_LIMIT, error(Code.RESOURCE_EXHAUSTED, "INFRA_FAILURE_LIMIT"))
        assertEquals(ServerError.REQUEST_ID_REUSED, error(Code.FAILED_PRECONDITION, "REQUEST_ID_REUSED"))
    }

    @Test
    fun aMissingCallableIsToldApartFromAnUnknownRequest() {
        assertEquals(ServerError.UNKNOWN_REQUEST, error(Code.NOT_FOUND, "UNKNOWN_REQUEST"))
        assertEquals(ServerError.CALLABLE_MISSING, error(Code.NOT_FOUND), "an older server without getGeneration")
        assertEquals(ServerError.CALLABLE_MISSING, error(Code.UNIMPLEMENTED))
        assertEquals(ServerError.CALLABLE_MISSING, error(Code.NOT_FOUND, ""), "an empty reason is no reason")
        assertEquals(ServerError.UNKNOWN, error(Code.NOT_FOUND, "CALLABLE_MISSING"), "the client's own name isn't a reason")
    }

    @Test
    fun thePurchaseAccountIdIsAVersion5UuidOfTheUid() {
        assertEquals("6f2b3c1e-8d4a-4b7e-9c35-2a1f0e7d5b48", PURCHASE_ACCOUNT_NAMESPACE, "the namespace iOS and the server use")
        // Python: uuid.uuid5(uuid.UUID("6f2b3c1e-8d4a-4b7e-9c35-2a1f0e7d5b48"), "test-uid")
        assertEquals("8e3ec052-71cd-54df-a198-9e3f69fbc9d9", purchaseAccountId("test-uid"), "matches RFC 4122 UUIDv5")
        assertEquals("1e7c2566-647e-5044-9bbb-85fedc8b3c81", purchaseAccountId("abc123"), "a second uid")
        // functions/CONTRACT.md's worked example, asserted in functions/test/hardening.test.ts.
        assertEquals("e0612f8d-d882-5109-b5f4-6e979f3b69ce", purchaseAccountId("test-uid-123"), "the contract's example")
        assertEquals(purchaseAccountId("abc123"), purchaseAccountId("abc123"), "deterministic")
        assertEquals(false, purchaseAccountId("abc124") == purchaseAccountId("abc123"), "per user")
    }

    @Test
    fun aReplayedTokenIsAskedAgainOnceWithAFreshOne() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val ok = retryingReplayedAppCheck {
            calls++
            if (calls == 1) ServerResult.Err(ServerError.APP_CHECK_REPLAYED) else ServerResult.Ok(JsonNull)
        }
        assertEquals(ServerResult.Ok(JsonNull), ok, "the second call's answer")
        assertEquals(2, calls, "asked twice")

        calls = 0
        val twice = retryingReplayedAppCheck {
            calls++
            ServerResult.Err(ServerError.APP_CHECK_REPLAYED)
        }
        assertEquals(ServerResult.Err(ServerError.APP_CHECK_REPLAYED), twice, "not a loop")
        assertEquals(2, calls, "only once more")

        calls = 0
        retryingReplayedAppCheck {
            calls++
            ServerResult.Err(ServerError.UNAUTHENTICATED)
        }
        assertEquals(1, calls, "other refusals aren't asked again")
    }

    @Test
    fun resourceExhaustedWithoutAReasonIsABusyServerNotABug() {
        assertEquals(ServerError.OVERLOADED, error(Code.RESOURCE_EXHAUSTED))
        assertEquals(ServerError.RATE_LIMITED, error(Code.RESOURCE_EXHAUSTED, "RATE_LIMITED"))
        assertEquals(ServerError.UNKNOWN, error(Code.RESOURCE_EXHAUSTED, "OVERLOADED"), "the client's own name isn't a reason")
    }

    @Test
    fun thePurchaseRefusalsAreRead() {
        assertEquals(ServerError.STORE_MISMATCH, error(Code.PERMISSION_DENIED, "STORE_MISMATCH"))
        assertEquals(ServerError.PURCHASE_ACCOUNT_MISMATCH, error(Code.FAILED_PRECONDITION, "PURCHASE_ACCOUNT_MISMATCH"))
    }

    @Test
    fun exactlyTheConsumingCallablesUseLimitedUseTokens() {
        for (name in listOf("generateRecipe", "verifyPurchase", "mergeBalance", "deleteAccount")) {
            assertEquals(true, usesLimitedUseToken(name), name)
        }
        for (name in listOf("getGeneration", "getBalance")) {
            assertEquals(false, usesLimitedUseToken(name), name)
        }
    }

    @Test
    fun mergeRepliesCarryWhatMovedTheCostsAndTheBlock() {
        val debt = JsonObject(
            mapOf(
                "balance" to JsonPrimitive(35),
                "moved" to JsonPrimitive(-3),
                "costs" to JsonObject(mapOf("recipe" to JsonPrimitive(1), "menu2" to JsonPrimitive(3), "menu3" to JsonPrimitive(4))),
                "purchasesBlocked" to JsonPrimitive(true)
            )
        )
        assertEquals(MergeReply(35, -3, RecipeCosts(1, 3, 4), true), mergeReplyOf(debt), "a debt")
        assertEquals(MergeReply(12, 0, null, false), mergeReplyOf(JsonObject(mapOf("balance" to JsonPrimitive(12)))), "nothing else")
        assertEquals(null, mergeReplyOf(JsonObject(mapOf("moved" to JsonPrimitive(3)))), "no balance")
        assertEquals(null, mergeReplyOf(JsonObject(mapOf("balance" to JsonPrimitive("12")))), "a balance as text isn't the contract's")
        assertEquals(
            MergeReply(12, 0, null, false),
            mergeReplyOf(JsonObject(mapOf("balance" to JsonPrimitive(12), "moved" to JsonPrimitive("3")))),
            "a moved as text is read as nothing moved"
        )
    }

    @Test
    fun callableDataKeepsNullsAndIntegers() {
        val data = JsonObject(mapOf("a" to JsonNull, "b" to JsonPrimitive(3), "c" to JsonPrimitive("x"), "d" to JsonPrimitive(true)))
            .toCallableData() as Map<*, *>
        assertEquals(true, data.containsKey("a"))
        assertEquals(null, data["a"])
        assertEquals(3, data["b"])
        assertEquals("x", data["c"])
        assertEquals(true, data["d"])
        assertEquals(JsonPrimitive(9L), 9.0.toJsonElement())
        assertEquals(JsonPrimitive(1.5), 1.5.toJsonElement())
    }

    private fun assertEquals(expected: Any?, actual: Any?, message: String) = org.junit.Assert.assertEquals(message, expected, actual)
}
