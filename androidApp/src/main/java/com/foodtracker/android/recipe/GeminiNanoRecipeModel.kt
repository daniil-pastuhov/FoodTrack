package com.foodtracker.android.recipe

import android.os.Build
import androidx.annotation.RequiresApi
import com.foodtracker.recipe.OnDeviceAvailability
import com.foodtracker.recipe.OnDeviceRecipeModel
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.first

/**
 * Gemini Nano through ML Kit's GenAI Prompt API (AICore). The library needs Android 8.0 (API 26)
 * and a supported phone with a locked bootloader (recent Pixel, Galaxy and others); everywhere
 * else this reports [OnDeviceAvailability.UNSUPPORTED] and the shared prompt is the free route.
 *
 * Every ML Kit call lives in [Client], which is only created on API 26 and up, so nothing from
 * the library is loaded on older phones (the manifest overrides its minSdk to allow that).
 */
class GeminiNanoRecipeModel : OnDeviceRecipeModel {

    private val client: Client? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { Client() }.getOrNull() else null
    }

    override suspend fun availability(): OnDeviceAvailability =
        client?.availability() ?: OnDeviceAvailability.UNSUPPORTED

    override fun supportsLanguage(languageCode: String): Boolean = languageCode in SUPPORTED_LANGUAGES

    override suspend fun download(): Boolean = client?.download() ?: false

    override suspend fun generate(prompt: String): String =
        client?.generate(prompt) ?: error("No on-device model on this phone")

    @RequiresApi(Build.VERSION_CODES.O)
    private class Client {
        private val model: GenerativeModel = Generation.getClient()

        suspend fun availability(): OnDeviceAvailability = runCatching {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> OnDeviceAvailability.AVAILABLE
                FeatureStatus.DOWNLOADING -> OnDeviceAvailability.DOWNLOADING
                FeatureStatus.DOWNLOADABLE -> OnDeviceAvailability.DOWNLOADABLE
                else -> OnDeviceAvailability.UNSUPPORTED
            }
        }.getOrDefault(OnDeviceAvailability.UNSUPPORTED)

        /** Waits for the download to finish or fail. */
        suspend fun download(): Boolean = runCatching {
            model.download()
                .first { it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed }
                .let { it is DownloadStatus.DownloadCompleted }
        }.getOrDefault(false)

        suspend fun generate(prompt: String): String {
            val response = model.generateContent(
                generateContentRequest(TextPart(prompt)) {
                    // Low, so the reply keeps to the JSON shape; still above zero so a retry
                    // after a bad reply can come out differently.
                    temperature = 0.3f
                    topK = 16
                    maxOutputTokens = MAX_OUTPUT_TOKENS
                }
            )
            return response.candidates.firstOrNull()?.text ?: error("The model returned nothing")
        }
    }

    companion object {
        /**
         * The recipe languages Gemini Nano is offered for. ML Kit validates the Prompt API in
         * English (and Korean, which the app doesn't offer) and says other languages depend on
         * the device and its downloaded models, with no way to ask. Rather than show a route
         * that writes poorly, it is offered for English only; another language can be added
         * here once it has been tried on real phones.
         */
        val SUPPORTED_LANGUAGES: Set<String> = setOf("en")

        /** A recipe of the size [com.foodtracker.recipe.buildOnDeviceRecipePrompt] asks for
         *  fits easily; the API's own guidance is to stay well under 4,000. */
        private const val MAX_OUTPUT_TOKENS = 1_024
    }
}
