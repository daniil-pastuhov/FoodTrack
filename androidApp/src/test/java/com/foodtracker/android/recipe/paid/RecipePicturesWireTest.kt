package com.foodtracker.android.recipe.paid

import com.foodtracker.android.recipe.AndroidRecipePictureStore
import com.foodtracker.android.recipe.RecipePictureFiles
import com.foodtracker.recipe.PictureBytes
import com.foodtracker.recipe.RECIPE_PICTURE_DISH
import com.foodtracker.recipe.ReplyPictures
import com.foodtracker.recipe.StepPictureBytes
import com.foodtracker.recipe.recipeStepPictureFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** Reading a reply's pictures (functions/CONTRACT.md "Recipe picture (response)") and keeping them as files. */
class RecipePicturesWireTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    private val decode: (String) -> ByteArray = { Base64.getDecoder().decode(it) }

    private fun picture(bytes: ByteArray = jpeg, mime: String = "image/jpeg", width: Int = 768, height: Int = 768) = JsonObject(
        mapOf(
            "mimeType" to JsonPrimitive(mime),
            "data" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
            "width" to JsonPrimitive(width),
            "height" to JsonPrimitive(height)
        )
    )

    @Test
    fun theDishAndTheStepsAreRead() {
        val reply = JsonObject(
            mapOf(
                "status" to JsonPrimitive("DONE"),
                "image" to picture(),
                "stepImages" to JsonArray(listOf(JsonObject(mapOf("step" to JsonPrimitive(2), "image" to picture(width = 768, height = 576)))))
            )
        )
        val pictures = replyPicturesOf(reply, decode)!!
        assertArrayEquals(jpeg, pictures.dish!!.bytes)
        assertEquals(listOf(2), pictures.steps.map { it.step })
        assertEquals(576, pictures.steps.single().picture.height)
    }

    @Test
    fun noPicturesNoValue() {
        assertNull(replyPicturesOf(JsonObject(mapOf("status" to JsonPrimitive("DONE"))), decode))
    }

    @Test
    fun anythingButASmallJpegIsLeftOut() {
        listOf(
            picture(mime = "image/png"),
            picture(bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
            picture(width = 0),
            picture(height = 10_000),
            picture(bytes = ByteArray(700_000) { if (it == 0) 0xFF.toByte() else if (it == 1) 0xD8.toByte() else 0 })
        ).forEach { bad ->
            val pictures = replyPicturesOf(JsonObject(mapOf("image" to bad)), decode)
            assertNotNull(pictures)
            assertNull(pictures!!.dish)
        }
        // Undecodable base64 is no picture either, and never an exception.
        val broken = JsonObject(mapOf("mimeType" to JsonPrimitive("image/jpeg"), "data" to JsonPrimitive("%%%"), "width" to JsonPrimitive(1), "height" to JsonPrimitive(1)))
        assertNull(replyPicturesOf(JsonObject(mapOf("image" to broken)), decode)!!.dish)
    }

    @Test
    fun theReportCallableSpendsALimitedUseToken() {
        assertTrue(usesLimitedUseToken("reportRecipe"))
        assertFalse(usesLimitedUseToken("getGeneration"))
    }

    private fun tempRoot(): File = Files.createTempDirectory("pictures").toFile()

    @Test
    fun picturesAreKeptAsFilesInTheSavedRecipesFolder() = runBlocking {
        val root = tempRoot()
        val store = AndroidRecipePictureStore(root)
        val kept = store.save(
            "recipe_1",
            ReplyPictures(PictureBytes(jpeg, 768, 768), listOf(StepPictureBytes(1, PictureBytes(jpeg, 768, 576)), StepPictureBytes(7, PictureBytes(jpeg, 1, 1)))),
            steps = 3
        )
        assertEquals(RECIPE_PICTURE_DISH, kept.dish!!.file)
        assertEquals(listOf(recipeStepPictureFile(1)), kept.steps.map { it.picture.file })
        assertArrayEquals(jpeg, File(root, "recipe_1/dish.jpg").readBytes())
        assertNotNull(RecipePictureFiles.file(root, "recipe_1", RECIPE_PICTURE_DISH))
        // Never outside its folder.
        assertNull(RecipePictureFiles.file(root, "../recipe_1", RECIPE_PICTURE_DISH))
        assertNull(RecipePictureFiles.file(root, "recipe_1", "../recipe_1/dish.jpg"))
        root.deleteRecursively()
        Unit
    }

    @Test
    fun theSweepRemovesOnlyOldFoldersOfRowsThatAreGone() = runBlocking {
        val root = tempRoot()
        var now = System.currentTimeMillis()
        val store = AndroidRecipePictureStore(root) { now }
        store.save("kept", ReplyPictures(PictureBytes(jpeg, 1, 1)), 0)
        store.save("gone", ReplyPictures(PictureBytes(jpeg, 1, 1)), 0)
        // Just written: a row may be on its way, so it stays.
        store.sweep(setOf("kept"))
        assertTrue(File(root, "gone").exists())
        now += 11 * 60_000L
        store.sweep(setOf("kept"))
        assertFalse(File(root, "gone").exists())
        assertTrue(File(root, "kept/dish.jpg").exists())
        root.deleteRecursively()
        Unit
    }
}
