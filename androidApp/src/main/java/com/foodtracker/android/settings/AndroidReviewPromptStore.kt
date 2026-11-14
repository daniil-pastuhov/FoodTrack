package com.foodtracker.android.settings

import android.content.Context
import com.foodtracker.review.ReviewPromptRecord
import com.foodtracker.review.ReviewPromptStore
import kotlinx.serialization.json.Json

/**
 * The rating prompt's counters, under their own key beside `AppSettings` rather than inside it,
 * so the backup format (which serialises `AppSettings`) never carries them and a restore can't
 * reset them. Broken JSON reads as a fresh record.
 */
class AndroidReviewPromptStore(context: Context) : ReviewPromptStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ReviewPromptRecord {
        val json = prefs.getString(KEY_REVIEW_PROMPT, null) ?: return ReviewPromptRecord()
        return runCatching { JSON.decodeFromString(ReviewPromptRecord.serializer(), json) }
            .getOrDefault(ReviewPromptRecord())
    }

    override fun save(record: ReviewPromptRecord) {
        prefs.edit()
            .putString(KEY_REVIEW_PROMPT, JSON.encodeToString(ReviewPromptRecord.serializer(), record))
            .apply()
    }

    companion object {
        /** The same file as [AndroidSettingsRepository], a different key. */
        private const val PREFS_NAME = "foodtracker_settings"
        private const val KEY_REVIEW_PROMPT = "review_prompt"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
