package com.foodtracker.android.ui.navigation

import com.foodtracker.android.camera.CameraScanMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LarderBackTargetTest {
    private fun target(
        route: LarderRoute,
        scanReturn: LarderRoute = LarderRoute.Home,
        recipeReturn: LarderRoute = LarderRoute.Recipe,
        staplesReturn: LarderRoute = LarderRoute.Settings
    ) = larderBackTarget(route, scanReturn, recipeReturn, staplesReturn)

    @Test
    fun rootsLeaveBackToTheSystem() {
        // No root callback here, so the system's back-to-home animation plays.
        assertNull(target(LarderRoute.Home))
        assertNull(target(LarderRoute.Onboarding))
        assertNull(target(LarderRoute.ProductDetail("milk")))
    }

    @Test
    fun otherTabsGoHome() {
        listOf(LarderRoute.Recipe, LarderRoute.Shopping, LarderRoute.Settings).forEach {
            assertEquals(LarderRoute.Home, target(it))
        }
    }

    @Test
    fun scannerAndAddItemGoWhereTheyCameFrom() {
        assertEquals(LarderRoute.Shopping, target(LarderRoute.Scanner(CameraScanMode.BARCODE), scanReturn = LarderRoute.Shopping))
        assertEquals(LarderRoute.Home, target(LarderRoute.AddItem()))
        assertEquals(LarderRoute.Shopping, target(LarderRoute.AddItem(AddItemMode.New), scanReturn = LarderRoute.Shopping))
    }

    @Test
    fun pushedScreensGoToTheirParent() {
        assertEquals(LarderRoute.Settings, target(LarderRoute.Categories))
        assertEquals(LarderRoute.Settings, target(LarderRoute.Staples))
        assertEquals(LarderRoute.Recipe, target(LarderRoute.Staples, staplesReturn = LarderRoute.Recipe))
        assertEquals(LarderRoute.Recipe, target(LarderRoute.SavedRecipes))
        assertEquals(LarderRoute.Recipe, target(LarderRoute.RecipeSettings))
        assertEquals(LarderRoute.RecipeSettings, target(LarderRoute.TasteProfile))
        assertEquals(LarderRoute.SavedRecipes, target(LarderRoute.RecipeResult("r1"), recipeReturn = LarderRoute.SavedRecipes))
        assertEquals(LarderRoute.Recipe, target(LarderRoute.RecipeResult("r1")))
        assertEquals(LarderRoute.RecipeResult("m1"), target(LarderRoute.MenuDish("m1", day = 1, course = 0)))
    }
}
