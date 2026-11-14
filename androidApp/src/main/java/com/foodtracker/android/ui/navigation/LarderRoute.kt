package com.foodtracker.android.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.foodtracker.android.camera.CameraScanMode
import com.foodtracker.android.ui.design.LarderTab
import com.foodtracker.model.ShelfLifeSuggestion
import com.foodtracker.review.ReviewPrompter
import kotlinx.datetime.LocalDate

/** Everywhere the app can go. The same list as iOS; see docs/design/IMPLEMENTATION.md. */
sealed interface LarderRoute {
    data object Home : LarderRoute
    data object Recipe : LarderRoute
    data object Shopping : LarderRoute
    data object Settings : LarderRoute

    /** Opens over the current screen, so it never becomes the current route itself. */
    data class ProductDetail(val itemId: String) : LarderRoute
    data class AddItem(val mode: AddItemMode = AddItemMode.New) : LarderRoute
    data class Scanner(val mode: CameraScanMode = CameraScanMode.BARCODE) : LarderRoute
    data object Categories : LarderRoute

    /** A saved recipe or menu, by its saved row's id. */
    data class RecipeResult(val savedId: String) : LarderRoute

    /** One dish of a saved menu: its day and course by position. */
    data class MenuDish(val savedId: String, val day: Int, val course: Int) : LarderRoute
    data object SavedRecipes : LarderRoute
    data object RecipeSettings : LarderRoute
    data object Onboarding : LarderRoute
}

sealed interface AddItemMode {
    data object New : AddItemMode
    data class Prefilled(val prefill: ItemPrefill) : AddItemMode
    data class Edit(val itemId: String) : AddItemMode
}

/** What a scan or a shopping list row already knows about the item being added. */
data class ItemPrefill(
    val barcode: String? = null,
    val name: String? = null,
    val brand: String? = null,
    val imageUrl: String? = null,
    val expiryDate: LocalDate? = null,
    val category: String? = null,
    /** Expiry to start from when the scan had no date of its own: the shelf life this barcode
     *  was last given, or its category's default. */
    val suggestion: ShelfLifeSuggestion? = null
)

/** The tab this route belongs to, or null for a pushed or modal route. */
val LarderRoute.tab: LarderTab?
    get() = when (this) {
        LarderRoute.Home -> LarderTab.Home
        LarderRoute.Recipe -> LarderTab.Recipe
        LarderRoute.Shopping -> LarderTab.Shopping
        LarderRoute.Settings -> LarderTab.Settings
        else -> null
    }

val LarderTab.route: LarderRoute
    get() = when (this) {
        LarderTab.Home -> LarderRoute.Home
        LarderTab.Recipe -> LarderRoute.Recipe
        LarderTab.Shopping -> LarderRoute.Shopping
        LarderTab.Settings -> LarderRoute.Settings
    }

/** How a screen moves somewhere else without the root having to pass it a callback. */
fun interface LarderNavigator {
    fun navigate(route: LarderRoute)
}

/** Provided by the root around every screen. */
/** Shows a snackbar through the Root's host, so a screen can offer Undo or Try again itself. */
fun interface LarderSnackbars {
    fun show(message: String, actionLabel: String?, onAction: (() -> Unit)?)
}

val LocalLarderSnackbars = staticCompositionLocalOf { LarderSnackbars { _, _, _ -> } }

val LocalLarderNavigator = staticCompositionLocalOf { LarderNavigator { } }

/** The rating prompt's hooks for screens that report a moment or a problem (see
 *  `ReviewPrompter`). Null outside the root, where nothing is reported. */
val LocalReviewPrompter = staticCompositionLocalOf<ReviewPrompter?> { null }
