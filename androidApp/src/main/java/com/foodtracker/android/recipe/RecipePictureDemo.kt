package com.foodtracker.android.recipe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.foodtracker.recipe.PictureBytes
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.RecipeDifficulty
import com.foodtracker.recipe.RecipeIngredient
import com.foodtracker.recipe.RecipeSource
import com.foodtracker.recipe.ReplyPictures
import com.foodtracker.recipe.StepPictureBytes
import java.io.ByteArrayOutputStream

/**
 * Debug demo launches only (MainActivity, `demoRecipePictures`): pictures drawn in code, as JPEG
 * bytes the server would have sent, so a result with pictures can be looked at with nothing sent
 * and no picture file bundled. Never used by the real route.
 */
object RecipePictureDemo {

    /** The recipe shown when no `demoRecipe` file is given: the design's sample. */
    val sampleRecipe = Recipe(
        title = "Spinach and yoghurt eggs on sourdough toast",
        summary = "A warm, quick plate that uses the bread, the spinach and one of the yoghurts today.",
        minutes = 15,
        servings = 2,
        difficulty = RecipeDifficulty.EASY,
        ingredients = listOf(
            RecipeIngredient("4 slices", "Sourdough bread", fromPantry = true),
            RecipeIngredient("100 g", "Baby spinach", fromPantry = true),
            RecipeIngredient("150 g", "Greek yoghurt", fromPantry = true),
            RecipeIngredient("4", "Free-range eggs", fromPantry = true),
            RecipeIngredient("1 clove", "Garlic"),
            RecipeIngredient("1 tbsp", "Olive oil"),
            RecipeIngredient("", "Salt and pepper")
        ),
        steps = listOf(
            "Stir the grated garlic and a pinch of salt into the yoghurt and leave it at room temperature.",
            "Toast the bread. Day-old sourdough toasts better than fresh.",
            "Warm the oil in a pan, add the spinach and turn it for a minute until it wilts. Season.",
            "Fry or poach the eggs to your liking.",
            "Spread the yoghurt on the toast, pile on the spinach, top with the eggs and finish with chilli flakes."
        ),
        tip = "Check the bread before using it: one day past a best-before date is usually fine, but not if there is any mould.",
        source = RecipeSource.SERVER
    )

    /** The dish, and for [steps] pictures of steps 3 and 4 (as a server marks a technique). */
    fun pictures(steps: Boolean): ReplyPictures = ReplyPictures(
        dish = picture(768, 768, null),
        steps = if (steps) listOf(StepPictureBytes(2, picture(768, 576, 2)), StepPictureBytes(3, picture(768, 576, 3))) else emptyList()
    )

    private fun picture(width: Int, height: Int, step: Int?): PictureBytes {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFFB9825A.toInt())
        val cx = width * 0.5f
        val cy = height * 0.52f
        val r = minOf(width, height) * 0.42f
        fun circle(color: Long, radius: Float, x: Float = cx, y: Float = cy) {
            paint.color = color.toInt()
            canvas.drawCircle(x, y, radius, paint)
        }
        circle(0xFFF4EFE6, r)
        circle(0xFFE4DCCD, r * 0.78f)
        if (step == null) {
            circle(0xFF4E7D3A, r * 0.34f, cx - r * 0.22f, cy - r * 0.05f)
            circle(0xFFFFFFFF, r * 0.24f, cx + r * 0.22f, cy + r * 0.1f)
            circle(0xFFF2B233, r * 0.11f, cx + r * 0.22f, cy + r * 0.1f)
        } else {
            circle(if (step == 2) 0xFF6B8E4E else 0xFFF6F1E7, r * 0.5f)
            circle(if (step == 2) 0xFF55733D else 0xFFF2B233, r * 0.2f, cx - r * 0.15f, cy)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        return PictureBytes(out.toByteArray(), width, height)
    }
}
