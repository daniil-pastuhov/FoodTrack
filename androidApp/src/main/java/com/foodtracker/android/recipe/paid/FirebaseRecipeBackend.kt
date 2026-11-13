package com.foodtracker.android.recipe.paid

import com.foodtracker.recipe.GenerateReply
import com.foodtracker.recipe.GenerateRequest
import com.foodtracker.recipe.RecipeBackend
import com.foodtracker.recipe.RecipeJson
import com.foodtracker.recipe.ServerError
import com.foodtracker.recipe.ServerResult
import com.foodtracker.recipe.VerifyReply
import com.foodtracker.recipe.WireKind
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The five callables of functions/CONTRACT.md, over Firebase Functions in europe-west1. */
class FirebaseRecipeBackend(
    private val firebase: RecipeFirebase,
    override val isAvailable: Boolean
) : RecipeBackend {

    override suspend fun generate(request: GenerateRequest): ServerResult<GenerateReply> {
        // A recipe takes up to ~40 s and a menu up to ~2 min on the server; the contract asks
        // for at least 70 s and 190 s.
        val timeout = if (request.kind == WireKind.MENU) 200L else 75L
        return call(firebase.functions(), "generateRecipe", RecipeJson.encodeToJsonElement(GenerateRequest.serializer(), request), timeout, GenerateReply.serializer())
    }

    override suspend fun verifyPurchase(purchaseToken: String): ServerResult<VerifyReply> =
        call(
            firebase.functions(),
            "verifyPurchase",
            JsonObject(mapOf("store" to JsonPrimitive("play"), "purchaseToken" to JsonPrimitive(purchaseToken))),
            60L,
            VerifyReply.serializer()
        )

    override suspend fun getBalance(): ServerResult<Int> = callBalance(firebase.functions())

    override suspend fun deleteAccount(): ServerResult<Unit> =
        // Apple's token is revoked on the phone before this (see FirebaseRecipeAccount), so no
        // authorization code is sent.
        when (val result = callRaw(firebase.functions(), "deleteAccount", JsonObject(emptyMap()), 60L)) {
            is ServerResult.Ok -> ServerResult.Ok(Unit)
            is ServerResult.Err -> result
        }
}

/** `getBalance` as [functions]'s user, for either Firebase app. */
internal suspend fun callBalance(functions: FirebaseFunctions): ServerResult<Int> =
    when (val result = callRaw(functions, "getBalance", JsonObject(emptyMap()), 30L)) {
        is ServerResult.Ok -> (result.value as? JsonObject)?.get("balance")?.let { (it as? JsonPrimitive)?.longOrNull?.toInt() }
            ?.let { ServerResult.Ok(it) } ?: ServerResult.Err(ServerError.INTERNAL)
        is ServerResult.Err -> result
    }

internal suspend fun <T> call(
    functions: FirebaseFunctions,
    name: String,
    data: JsonElement,
    timeoutSeconds: Long,
    serializer: KSerializer<T>
): ServerResult<T> = when (val result = callRaw(functions, name, data, timeoutSeconds)) {
    is ServerResult.Ok -> try {
        ServerResult.Ok(RecipeJson.decodeFromJsonElement(serializer, result.value))
    } catch (e: IllegalArgumentException) {
        // Not the contract's shape: a server bug, as `internal` is.
        ServerResult.Err(ServerError.INTERNAL)
    }
    is ServerResult.Err -> result
}

internal suspend fun callRaw(
    functions: FirebaseFunctions,
    name: String,
    data: JsonElement,
    timeoutSeconds: Long
): ServerResult<JsonElement> = try {
    // Limited-use tokens, so these calls keep working when the server starts consuming them.
    val options = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()
    val callable = functions.getHttpsCallable(name, options).apply { setTimeout(timeoutSeconds, TimeUnit.SECONDS) }
    val result = callable.call(data.toCallableData()).awaitResult()
    ServerResult.Ok(result.getData().toJsonElement())
} catch (e: CancellationException) {
    throw e
} catch (e: FirebaseFunctionsException) {
    ServerResult.Err(e.toServerError())
} catch (e: FirebaseNetworkException) {
    ServerResult.Err(ServerError.OFFLINE)
} catch (e: IOException) {
    ServerResult.Err(ServerError.OFFLINE)
} catch (e: Exception) {
    ServerResult.Err(ServerError.INTERNAL)
}

internal fun FirebaseFunctionsException.toServerError(): ServerError =
    serverErrorOf(code, (details as? Map<*, *>)?.get("reason") as? String, cause)

/** The contract's error for a callable failure's code, `details.reason` and cause. */
internal fun serverErrorOf(code: FirebaseFunctionsException.Code, reason: String?, cause: Throwable?): ServerError {
    return when (code) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> ServerError.UNAUTHENTICATED
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> ServerError.OFFLINE
        FirebaseFunctionsException.Code.UNAVAILABLE -> ServerError.fromReason(reason, ServerError.OFFLINE)
        // The SDK reports a dropped connection as `internal` with the I/O error as its cause.
        FirebaseFunctionsException.Code.INTERNAL ->
            if (cause is IOException || cause is FirebaseNetworkException) ServerError.OFFLINE else ServerError.fromReason(reason, ServerError.INTERNAL)
        else -> ServerError.fromReason(reason, ServerError.INTERNAL)
    }
}

/** JSON as the callable SDK sends it: maps, lists and plain values, with nulls kept. */
internal fun JsonElement.toCallableData(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { it.value.toCallableData() }
    is JsonArray -> map { it.toCallableData() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull!!.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
        else -> doubleOrNull
    }
}

/** What the callable SDK hands back, as JSON. */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
    is List<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    is Boolean -> JsonPrimitive(this)
    is Int, is Long, is Short, is Byte -> JsonPrimitive((this as Number).toLong())
    // JSON numbers can come back as doubles; whole ones are read as ints by the decoder.
    is Number -> toDouble().let { if (it % 1.0 == 0.0 && it in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) JsonPrimitive(it.toLong()) else JsonPrimitive(it) }
    is org.json.JSONObject -> JsonObject(keys().asSequence().associateWith { opt(it).toJsonElement() })
    is org.json.JSONArray -> JsonArray((0 until length()).map { opt(it).toJsonElement() })
    org.json.JSONObject.NULL -> JsonNull
    else -> JsonPrimitive(toString())
}
