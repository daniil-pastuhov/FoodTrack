package com.foodtracker.android.recipe.paid

import com.foodtracker.recipe.Attestation
import com.foodtracker.recipe.BalanceReply
import com.foodtracker.recipe.DeleteReply
import com.foodtracker.recipe.GenerateReply
import com.foodtracker.recipe.GenerateRequest
import com.foodtracker.recipe.MergeReply
import com.foodtracker.recipe.RecipeBackend
import com.foodtracker.recipe.RecipeJson
import com.foodtracker.recipe.RefusedName
import com.foodtracker.recipe.refusedNameOf
import com.foodtracker.recipe.ServerError
import com.foodtracker.recipe.ServerResult
import com.foodtracker.recipe.VerifyReply
import com.foodtracker.recipe.WELCOME_MAX_RECIPES
import com.foodtracker.recipe.WelcomeClaimReply
import com.foodtracker.recipe.WelcomeStatus
import com.foodtracker.recipe.WireKind
import com.foodtracker.recipe.acceptsWelcomeOf
import com.foodtracker.recipe.welcomeOfferOf
import com.foodtracker.recipe.welcomeRecipesFieldOf
import com.foodtracker.recipe.welcomeStatusOf
import com.foodtracker.recipe.acceptsDanishOf
import com.foodtracker.recipe.acceptsMenuDaysOf
import com.foodtracker.recipe.acceptsTasteOf
import com.foodtracker.recipe.acceptsStaplesOf
import com.foodtracker.recipe.tasteQuizVersionOf
import com.foodtracker.recipe.acceptsMultiChoiceConfigOf
import com.foodtracker.recipe.acceptsOptionalIngredientsOf
import com.foodtracker.recipe.purchasesBlockedOf
import com.foodtracker.recipe.recipeCostsOf
import com.foodtracker.recipe.acceptsRecipeImageOf
import com.foodtracker.recipe.acceptsReportsOf
import com.foodtracker.recipe.MAX_PICTURE_BYTES
import com.foodtracker.recipe.MAX_PICTURE_EDGE
import com.foodtracker.recipe.PictureBytes
import com.foodtracker.recipe.PictureLevel
import com.foodtracker.recipe.RecipeReport
import com.foodtracker.recipe.ReplyPictures
import com.foodtracker.recipe.StepPictureBytes
import com.foodtracker.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.appcheck.FirebaseAppCheck
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

/**
 * The callables whose App Check token the server consumes (functions/CONTRACT.md, Calling): each
 * call carries a fresh limited-use token. The rest (`getGeneration`, `getBalance`, `getTasteQuiz`,
 * `getWelcomeOffer`) take an
 * ordinary token, so polling spends no Play Integrity request.
 */
internal val CONSUMING_CALLABLES = setOf("generateRecipe", "verifyPurchase", "mergeBalance", "deleteAccount", "claimWelcome", "reportRecipe")

/** Whether [name] is called with a limited-use App Check token. */
internal fun usesLimitedUseToken(name: String): Boolean = name in CONSUMING_CALLABLES

/** The callables of functions/CONTRACT.md this backend calls, over Firebase Functions in europe-west1. */
class FirebaseRecipeBackend(
    private val firebase: RecipeFirebase,
    override val isAvailable: Boolean
) : RecipeBackend {

    // A limited-use token, as the calls use: asking for it exchanges an attestation with App
    // Check, never with our functions.
    override suspend fun attest(): Attestation = try {
        FirebaseAppCheck.getInstance(firebase.primary).limitedUseAppCheckToken.awaitResult()
        Attestation.OK
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isAttestationRefusal()) Attestation.FAILED else Attestation.UNKNOWN
    }

    override suspend fun generate(request: GenerateRequest): ServerResult<GenerateReply> {
        // A recipe takes up to ~40 s and a menu up to ~2 min on the server; the contract asks
        // for at least 70 s and 190 s. With pictures, up to ~80 s more or less (made after the
        // text, within a reservation 45 s longer): at least 120 s.
        val timeout = when {
            request.kind == WireKind.MENU -> 200L
            request.image == PictureLevel.STEPS -> 190L
            request.image != null -> 130L
            else -> 75L
        }
        return generateReplyOf(callRaw(firebase.functions(), "generateRecipe", RecipeJson.encodeToJsonElement(GenerateRequest.serializer(), request), timeout))
    }

    // A normal App Check token (see CONSUMING_CALLABLES). A recipe with pictures brings them
    // again for as long as the server keeps the generation: a few hundred kilobytes at most.
    override suspend fun getGeneration(requestId: String): ServerResult<GenerateReply> =
        generateReplyOf(callRaw(firebase.functions(), "getGeneration", JsonObject(mapOf("requestId" to JsonPrimitive(requestId))), 45L))

    // A limited-use App Check token (CONSUMING_CALLABLES). No recipe text: only the generation's
    // id, the reason and the person's own note, with the app's platform, version and language.
    override suspend fun reportRecipe(report: RecipeReport): ServerResult<Unit> {
        val data = JsonObject(
            buildMap {
                put("reportId", JsonPrimitive(report.reportId))
                put("requestId", JsonPrimitive(report.requestId))
                put("reason", JsonPrimitive(report.reason.wire))
                report.note?.let { put("note", JsonPrimitive(it)) }
                put(
                    "app",
                    JsonObject(
                        mapOf(
                            "platform" to JsonPrimitive("android"),
                            "version" to JsonPrimitive(BuildConfig.VERSION_NAME.filter { it.isLetterOrDigit() || it in ".+-" }.take(32)),
                            "language" to JsonPrimitive(report.language)
                        )
                    )
                )
            }
        )
        return when (val result = callRaw(firebase.functions(), "reportRecipe", data, 30L)) {
            is ServerResult.Ok -> ServerResult.Ok(Unit)
            is ServerResult.Err -> result
        }
    }

    override suspend fun verifyPurchase(purchaseToken: String): ServerResult<VerifyReply> =
        call(
            firebase.functions(),
            "verifyPurchase",
            JsonObject(mapOf("store" to JsonPrimitive("play"), "purchaseToken" to JsonPrimitive(purchaseToken))),
            60L,
            VerifyReply.serializer()
        )

    override suspend fun getBalance(): ServerResult<BalanceReply> = callBalance(firebase.functions())

    // A normal App Check token, as for the balance (see CONSUMING_CALLABLES). The reply is read
    // leniently by the caller (tasteQuizOf), so it is handed over as JSON.
    override suspend fun getTasteQuiz(language: String): ServerResult<JsonElement> =
        callRaw(firebase.functions(), "getTasteQuiz", JsonObject(mapOf("language" to JsonPrimitive(language))), 30L)

    // Before any account exists: no Auth user is made for it, so the call carries only an ordinary
    // App Check token and no input (functions/CONTRACT.md, getWelcomeOffer). An older server
    // answers not-found, CALLABLE_MISSING, which the caller reads as 0.
    override suspend fun getWelcomeOffer(): ServerResult<Int> =
        when (val result = callRaw(firebase.functions(), "getWelcomeOffer", JsonObject(emptyMap()), 20L)) {
            is ServerResult.Ok -> welcomeOfferOf(result.value)?.let { ServerResult.Ok(it) } ?: ServerResult.Err(ServerError.INTERNAL)
            is ServerResult.Err -> result
        }

    override suspend fun claimWelcome(): ServerResult<WelcomeClaimReply> =
        when (val result = callRaw(firebase.functions(), "claimWelcome", JsonObject(emptyMap()), 30L)) {
            is ServerResult.Ok -> welcomeClaimReplyOf(result.value)?.let { ServerResult.Ok(it) } ?: ServerResult.Err(ServerError.INTERNAL)
            is ServerResult.Err -> result
        }

    override suspend fun deleteAccount(): ServerResult<DeleteReply> =
        // No `appleAuthorizationCode`: Android has no Sign in with Apple to get one from. For an
        // account with Apple attached (added on an iPhone) the server revokes the Apple token it
        // stored at that sign-in, and answers `appleLinkRemoved: false` only when it couldn't.
        when (val result = callRaw(firebase.functions(), "deleteAccount", JsonObject(emptyMap()), 120L)) {
            is ServerResult.Ok -> ServerResult.Ok(deleteReplyOf(result.value))
            is ServerResult.Err -> result
        }
}

/**
 * `generateRecipe` / `getGeneration`'s reply: the pictures taken out of the JSON and decoded off
 * the main thread ([replyPicturesOf]), the rest read as [GenerateReply]. The bytes are never
 * logged; a reply that isn't the contract's shape is `internal`, as for any call.
 */
internal suspend fun generateReplyOf(result: ServerResult<JsonElement>): ServerResult<GenerateReply> = when (result) {
    is ServerResult.Err -> result
    is ServerResult.Ok -> withContext(Dispatchers.Default) {
        val reply = result.value as? JsonObject
        if (reply == null) {
            ServerResult.Err(ServerError.INTERNAL)
        } else {
            try {
                val pictures = replyPicturesOf(reply) { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
                val rest = JsonObject(reply - "image" - "stepImages")
                ServerResult.Ok(RecipeJson.decodeFromJsonElement(GenerateReply.serializer(), rest).copy(pictures = pictures))
            } catch (e: IllegalArgumentException) {
                ServerResult.Err(ServerError.INTERNAL)
            }
        }
    }
}

/**
 * A reply's `image` and `stepImages` (functions/CONTRACT.md "Recipe picture (response)"), or null
 * with neither. A picture is kept only when it is a JPEG (by its `mimeType` and its first bytes),
 * its base64 decodes ([decode]) to at most [MAX_PICTURE_BYTES] bytes, and its sides are whole
 * numbers up to [MAX_PICTURE_EDGE]; anything else is left out, as if it hadn't been made.
 */
internal fun replyPicturesOf(reply: JsonObject, decode: (String) -> ByteArray): ReplyPictures? {
    fun pictureOf(element: JsonElement?): PictureBytes? {
        val picture = element as? JsonObject ?: return null
        if ((picture["mimeType"] as? JsonPrimitive)?.contentOrNull != "image/jpeg") return null
        val data = (picture["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        // Never decoded when it can't be small enough: base64 is 4 characters for 3 bytes.
        if (data.length > (MAX_PICTURE_BYTES / 3 + 1) * 4 + 8) return null
        fun side(key: String) = (picture[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 1..MAX_PICTURE_EDGE }?.toInt()
        val width = side("width") ?: return null
        val height = side("height") ?: return null
        val bytes = runCatching { decode(data) }.getOrNull() ?: return null
        val jpeg = bytes.size in 3..MAX_PICTURE_BYTES && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        return if (jpeg) PictureBytes(bytes, width, height) else null
    }
    val hasDish = "image" in reply
    val steps = (reply["stepImages"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val item = entry as? JsonObject ?: return@mapNotNull null
        val step = (item["step"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..99 }?.toInt() ?: return@mapNotNull null
        pictureOf(item["image"])?.let { StepPictureBytes(step, it) }
    }
    if (!hasDish && steps.isEmpty()) return null
    return ReplyPictures(pictureOf(reply["image"]), steps)
}

/** `deleteAccount`'s reply: `appleLinkRemoved` only when it is a real boolean; anything else reads
 *  as absent, so the manual Apple instruction never shows on a reply that doesn't say so. */
internal fun deleteReplyOf(json: JsonElement): DeleteReply {
    val flag = ((json as? JsonObject)?.get("appleLinkRemoved") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    return DeleteReply(appleLinkRemoved = flag)
}

/**
 * App Check's exchange refusing this build's attestation (HTTP 403, "App attestation failed"):
 * a fork, a sideload, a debug build whose token isn't registered. Anything else, a lost
 * connection or a passing Play Integrity error (quota, server unavailable, Play services
 * updating), isn't a refusal and doesn't hide the paid route.
 */
internal fun Throwable.isAttestationRefusal(): Boolean {
    if (isConnectionFailure()) return false
    val causes = generateSequence(this) { it.cause }.take(8).toList()
    if (causes.any { it.javaClass.name.contains("Integrity") }) return false
    return causes.any { cause ->
        val message = cause.message.orEmpty()
        message.contains("403") || message.contains("attestation failed", ignoreCase = true)
    }
}

/** A failure to reach the network rather than an answer, anywhere in [this]'s causes. */
internal fun Throwable.isConnectionFailure(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is FirebaseNetworkException || it is IOException }

/** `getBalance` as [functions]'s user, for either Firebase app. */
internal suspend fun callBalance(functions: FirebaseFunctions): ServerResult<BalanceReply> =
    when (val result = callRaw(functions, "getBalance", JsonObject(emptyMap()), 30L)) {
        is ServerResult.Ok -> balanceReplyOf(result.value)?.let { ServerResult.Ok(it) } ?: ServerResult.Err(ServerError.INTERNAL)
        is ServerResult.Err -> result
    }

/** `mergeBalance`'s reply: the joined count, what moved (negative for a debt), the costs and the
 *  block. Null without a readable balance. */
internal fun mergeReplyOf(json: JsonElement): MergeReply? {
    val reply = json as? JsonObject ?: return null
    val balance = (reply["balance"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.toInt() ?: return null
    val moved = (reply["moved"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.toInt() ?: 0
    return MergeReply(balance, moved, recipeCostsOf(reply["costs"]), purchasesBlockedOf(reply["purchasesBlocked"]), acceptsOptionalIngredientsOf(reply["acceptsOptionalIngredients"]), acceptsMultiChoiceConfigOf(reply["acceptsMultiChoiceConfig"]), acceptsDanishOf(reply["acceptsDanish"]), acceptsMenuDaysOf(reply["acceptsMenuDays"]), acceptsTasteOf(reply["acceptsTaste"]), tasteQuizVersionOf(reply["tasteQuizVersion"]), acceptsWelcomeOf(reply["acceptsWelcome"]), welcomeRecipesFieldOf(reply["welcomeRecipes"]), acceptsStaplesOf(reply["acceptsStaples"]), acceptsRecipeImageOf(reply["acceptsRecipeImage"]), acceptsReportsOf(reply["acceptsReports"]))
}

/** `getBalance`'s reply: the count, and the costs when they came and could be read. */
internal fun balanceReplyOf(json: JsonElement): BalanceReply? {
    val reply = json as? JsonObject ?: return null
    val balance = (reply["balance"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return null
    return BalanceReply(balance, recipeCostsOf(reply["costs"]), purchasesBlockedOf(reply["purchasesBlocked"]), acceptsOptionalIngredientsOf(reply["acceptsOptionalIngredients"]), acceptsMultiChoiceConfigOf(reply["acceptsMultiChoiceConfig"]), acceptsDanishOf(reply["acceptsDanish"]), acceptsMenuDaysOf(reply["acceptsMenuDays"]), acceptsTasteOf(reply["acceptsTaste"]), tasteQuizVersionOf(reply["tasteQuizVersion"]), acceptsWelcomeOf(reply["acceptsWelcome"]), welcomeRecipesFieldOf(reply["welcomeRecipes"]), acceptsStaplesOf(reply["acceptsStaples"]), acceptsRecipeImageOf(reply["acceptsRecipeImage"]), acceptsReportsOf(reply["acceptsReports"]))
}

/** `claimWelcome`'s reply: its status and `granted`, and everything a balance carries. Null without
 *  a readable status or balance (read as a claim that didn't reach the server; retrying is safe). */
internal fun welcomeClaimReplyOf(json: JsonElement): WelcomeClaimReply? {
    val reply = json as? JsonObject ?: return null
    val status = welcomeStatusOf(reply["status"]) ?: return null
    val balance = balanceReplyOf(reply) ?: return null
    val granted = (reply["granted"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.toInt()?.coerceIn(0, WELCOME_MAX_RECIPES) ?: 0
    return WelcomeClaimReply(status, if (status == WelcomeStatus.GRANTED) granted else 0, balance)
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

/** One call, asked once more if the server says its App Check token was already used: each call
 *  gets its own fresh limited-use token, so the second one carries a new token. */
internal suspend fun callRaw(
    functions: FirebaseFunctions,
    name: String,
    data: JsonElement,
    timeoutSeconds: Long
): ServerResult<JsonElement> = retryingReplayedAppCheck { callOnce(functions, name, data, timeoutSeconds, usesLimitedUseToken(name)) }

internal suspend fun retryingReplayedAppCheck(call: suspend () -> ServerResult<JsonElement>): ServerResult<JsonElement> {
    val first = call()
    return if (first is ServerResult.Err && first.error == ServerError.APP_CHECK_REPLAYED) call() else first
}

private suspend fun callOnce(
    functions: FirebaseFunctions,
    name: String,
    data: JsonElement,
    timeoutSeconds: Long,
    limitedUseAppCheck: Boolean
): ServerResult<JsonElement> = try {
    // A fresh limited-use token per call where the server consumes them (functions/CONTRACT.md);
    // never cached or reused across calls.
    val options = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(limitedUseAppCheck).build()
    val callable = functions.getHttpsCallable(name, options).apply { setTimeout(timeoutSeconds, TimeUnit.SECONDS) }
    val result = callable.call(data.toCallableData()).awaitResult()
    ServerResult.Ok(result.getData().toJsonElement())
} catch (e: CancellationException) {
    throw e
} catch (e: FirebaseFunctionsException) {
    val error = e.toServerError()
    ServerResult.Err(error, if (error == ServerError.INVALID_REQUEST) refusedNameFromDetails(e.details) else null)
} catch (e: FirebaseNetworkException) {
    ServerResult.Err(ServerError.OFFLINE)
} catch (e: IOException) {
    ServerResult.Err(ServerError.OFFLINE)
} catch (e: Exception) {
    ServerResult.Err(ServerError.INTERNAL)
}

/** The refused pantry or allergen name an INVALID_REQUEST's `details` (`field`, `rule`) points at. */
internal fun refusedNameFromDetails(details: Any?): RefusedName? {
    val map = details as? Map<*, *> ?: return null
    return refusedNameOf(map["field"] as? String, map["rule"] as? String)
}

internal fun FirebaseFunctionsException.toServerError(): ServerError =
    serverErrorOf(code, (details as? Map<*, *>)?.get("reason") as? String, cause)

/** The contract's error for a callable failure's code, `details.reason` and cause. */
internal fun serverErrorOf(code: FirebaseFunctionsException.Code, reason: String?, cause: Throwable?): ServerError {
    return when (code) {
        // A replayed token isn't a build that can't attest: asking again with a fresh one works.
        FirebaseFunctionsException.Code.UNAUTHENTICATED ->
            if (reason == ServerError.APP_CHECK_REPLAYED.name) ServerError.APP_CHECK_REPLAYED else ServerError.UNAUTHENTICATED
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> ServerError.OFFLINE
        // Without a reason, the callable itself isn't on this server (an older deployment).
        FirebaseFunctionsException.Code.NOT_FOUND, FirebaseFunctionsException.Code.UNIMPLEMENTED ->
            if (reason.isNullOrEmpty()) ServerError.CALLABLE_MISSING else ServerError.fromReason(reason, ServerError.INTERNAL)
        FirebaseFunctionsException.Code.UNAVAILABLE -> ServerError.fromReason(reason, ServerError.OFFLINE)
        // The SDK reports a dropped connection as `internal` with the I/O error as its cause.
        FirebaseFunctionsException.Code.INTERNAL ->
            if (cause is IOException || cause is FirebaseNetworkException) ServerError.OFFLINE else ServerError.fromReason(reason, ServerError.INTERNAL)
        // Without a reason: every server instance is busy (HTTP 429). Nothing was charged.
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> ServerError.fromReason(reason, ServerError.OVERLOADED)
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
