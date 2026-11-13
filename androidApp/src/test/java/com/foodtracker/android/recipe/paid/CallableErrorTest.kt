package com.foodtracker.android.recipe.paid

import com.foodtracker.recipe.ServerError
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
        assertEquals(ServerError.INTERNAL, error(Code.UNKNOWN, "SOMETHING_NEW"))
    }

    @Test
    fun noUserOrNoAppCheckTokenIsUnauthenticated() {
        assertEquals(ServerError.UNAUTHENTICATED, error(Code.UNAUTHENTICATED))
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
