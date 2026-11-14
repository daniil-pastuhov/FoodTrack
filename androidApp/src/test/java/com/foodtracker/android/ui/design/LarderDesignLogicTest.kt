package com.foodtracker.android.ui.design

import android.content.res.Resources
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.ui.navigation.AddItemMode
import com.foodtracker.android.ui.navigation.LarderRoute
import com.foodtracker.android.ui.navigation.route
import com.foodtracker.android.ui.navigation.tab
import com.foodtracker.model.ExpiryStatus
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LarderDesignLogicTest {

    private val resources: Resources
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private val today = LocalDate(2026, 11, 4)

    private fun label(status: ExpiryStatus, days: Int, date: LocalDate = LocalDate.fromEpochDays(today.toEpochDays() + days)) =
        expiryStatusLabel(resources, Locale.UK, status, days, date, today)

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun testStatusWordsStayFactual() {
        assertEquals("1 day over", label(ExpiryStatus.EXPIRED, -1))
        assertEquals("3 days over", label(ExpiryStatus.EXPIRED, -3))
        assertEquals("Today", label(ExpiryStatus.EXPIRING_TODAY, 0))
        assertEquals("Tomorrow", label(ExpiryStatus.EXPIRING_SOON, 1))
        assertEquals("In 2 days", label(ExpiryStatus.EXPIRING_SOON, 2))
        assertEquals("In 7 days", label(ExpiryStatus.FRESH, 7))
    }

    @Test
    fun testAFreshItemTurnsFromDaysToADateAfterAWeek() {
        assertTrue(showsDaysLeft(ExpiryStatus.FRESH, 7))
        assertFalse(showsDaysLeft(ExpiryStatus.FRESH, 8))
        assertEquals("19 Nov", label(ExpiryStatus.FRESH, 15))
    }

    @Test
    fun testADateInAnotherYearShowsTheYear() {
        assertEquals("14 Mar 2027", label(ExpiryStatus.FRESH, 130))
    }

    @Test
    fun testAnItemInsideALongSoonThresholdStaysRelative() {
        assertTrue(showsDaysLeft(ExpiryStatus.EXPIRING_SOON, 12))
    }

    @Test
    fun testExpandedStartsAt700() {
        assertFalse(isExpandedWidth(699.dp))
        assertTrue(isExpandedWidth(700.dp))
    }

    @Test
    fun testPhoneShellStacksSnackbarAboveScanAboveThePill() {
        val clearance = shellClearance(0.dp, pillHeight = 72.dp, scanHeight = 56.dp, expanded = false)

        assertEquals(ShellClearance(content = 172.dp, scan = 96.dp, snackbar = 164.dp), clearance)
    }

    @Test
    fun testPhoneShellWithoutScanClearsOnlyThePill() {
        val clearance = shellClearance(0.dp, pillHeight = 72.dp, scanHeight = 0.dp, expanded = false)

        assertEquals(104.dp, clearance.content)
        assertEquals(96.dp, clearance.snackbar)
    }

    @Test
    fun testExpandedShellPutsScanLevelWithThePill() {
        val clearance = shellClearance(0.dp, pillHeight = 72.dp, scanHeight = 56.dp, expanded = true)

        assertEquals(ShellClearance(content = 104.dp, scan = 12.dp, snackbar = 96.dp), clearance)
    }

    @Test
    fun testALevelActionTallerThanThePillIsClearedByItsOwnHeight() {
        val clearance = shellClearance(0.dp, pillHeight = 72.dp, scanHeight = 90.dp, expanded = true)

        assertEquals(122.dp, clearance.content)
        assertEquals(12.dp, clearance.scan)
    }

    @Test
    fun testAPinnedActionSitsBesideThePillOnlyWhereItFits() {
        // A 1280dp landscape tablet: a 440dp action beside a 260dp pill.
        assertTrue(pinnedActionFitsBesidePill(1280.dp, pillWidth = 260.dp, actionWidth = 440.dp))
        // Just past 700dp the half-width action would run into the pill.
        assertFalse(pinnedActionFitsBesidePill(720.dp, pillWidth = 260.dp, actionWidth = 330.dp))
        // (800 - 260) / 2 = 270 = 234 + 24 (end gap) + 12 (gap): exactly fits.
        assertTrue(pinnedActionFitsBesidePill(800.dp, pillWidth = 260.dp, actionWidth = 234.dp))
        assertFalse(pinnedActionFitsBesidePill(800.dp, pillWidth = 260.dp, actionWidth = 235.dp))
        assertFalse("not measured yet", pinnedActionFitsBesidePill(1280.dp, pillWidth = 260.dp, actionWidth = 0.dp))
    }

    @Test
    fun testTheNavigationBarLiftsEverything() {
        val clearance = shellClearance(24.dp, pillHeight = 72.dp, scanHeight = 56.dp, expanded = true)

        assertEquals(ShellClearance(content = 128.dp, scan = 36.dp, snackbar = 120.dp), clearance)
    }

    @Test
    fun testGroupPositions() {
        assertEquals(LarderGroupPosition.Single, LarderGroupPosition.of(0, 1))
        assertEquals(LarderGroupPosition.First, LarderGroupPosition.of(0, 3))
        assertEquals(LarderGroupPosition.Middle, LarderGroupPosition.of(1, 3))
        assertEquals(LarderGroupPosition.Last, LarderGroupPosition.of(2, 3))
    }

    @Test
    fun testCategoryIconsFollowTheCategoryInAnyLanguage() {
        assertEquals(LarderIcons.Milk, categoryIcon("Dairy"))
        assertEquals(LarderIcons.Milk, categoryIcon("Молочное"))
        assertEquals(LarderIcons.Croissant, categoryIcon("Bakery"))
        assertEquals(LarderIcons.Leaf, categoryIcon("Grønnsaker"))
        assertEquals(LarderIcons.Package, categoryIcon("Something else"))
        assertEquals(LarderIcons.Package, categoryIcon(null))
    }

    @Test
    fun testEveryTabRoundTripsAndPushedRoutesHaveNoTab() {
        LarderTab.entries.forEach { assertEquals(it, it.route.tab) }
        assertNull(LarderRoute.AddItem(AddItemMode.New).tab)
        assertNull(LarderRoute.Scanner().tab)
        assertNull(LarderRoute.Onboarding.tab)
    }

    @Test
    fun testShellChromeHoldsAreCounted() {
        val chrome = ShellChrome()
        chrome.hold(nav = true, scan = false)
        chrome.hold(nav = true, scan = true)

        chrome.release(nav = true, scan = false)

        assertTrue(chrome.navHidden)
        assertTrue(chrome.scanHidden)

        chrome.release(nav = true, scan = true)

        assertFalse(chrome.navHidden)
        assertFalse(chrome.scanHidden)
    }
}
