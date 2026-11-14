package com.foodtracker.android.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.backup.BackupDocument
import com.foodtracker.backup.BackupSerializer
import com.foodtracker.review.ReviewPromptRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidReviewPromptStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences("foodtracker_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aFreshInstallHasAnEmptyRecord() {
        assertEquals(ReviewPromptRecord(), AndroidReviewPromptStore(context).load())
    }

    @Test
    fun theRecordSurvivesANewInstance() {
        val record = ReviewPromptRecord(
            firstLaunchMillis = 1L,
            mostItemsSeen = 4,
            usesBeforeDate = 3,
            lastRequestMillis = 9L,
            lastRequestVersion = "2.1.0"
        )
        AndroidReviewPromptStore(context).save(record)
        assertEquals(record, AndroidReviewPromptStore(context).load())
    }

    @Test
    fun brokenJsonReadsAsAFreshRecord() {
        context.getSharedPreferences("foodtracker_settings", Context.MODE_PRIVATE).edit()
            .putString("review_prompt", "{not json").commit()
        assertEquals(ReviewPromptRecord(), AndroidReviewPromptStore(context).load())
    }

    @Test
    fun theRecordIsNotPartOfTheSettingsABackupCarries() {
        AndroidReviewPromptStore(context).save(ReviewPromptRecord(firstLaunchMillis = 1L, usesBeforeDate = 7, lastRequestVersion = "2.1.0"))
        val settings = AndroidSettingsRepository(context).getSettings()
        val backup = BackupSerializer.encode(
            BackupDocument(settings = settings)
        )
        assertFalse(backup.contains("usesBeforeDate"))
        assertFalse(backup.contains("lastRequestVersion"))
    }
}
