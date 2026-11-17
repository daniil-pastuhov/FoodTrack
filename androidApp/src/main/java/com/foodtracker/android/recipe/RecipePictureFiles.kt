package com.foodtracker.android.recipe

import android.content.Context
import com.foodtracker.recipe.RECIPE_PICTURE_DISH
import com.foodtracker.recipe.RecipePicture
import com.foodtracker.recipe.RecipePictureStore
import com.foodtracker.recipe.RecipeStepPicture
import com.foodtracker.recipe.ReplyPictures
import com.foodtracker.recipe.SavedPictures
import com.foodtracker.recipe.forSteps
import com.foodtracker.recipe.isRecipePictureFile
import com.foodtracker.recipe.recipeStepPictureFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Recipe pictures on this device: `filesDir/recipe_pictures/<savedId>/dish.jpg` and
 * `step-<n>.jpg`, beside the saved_recipes row of that id (whose payload names them). Kept out of
 * Auto Backup and device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml): a backup
 * or a new phone gets the rows, which then show without pictures. Nothing here reads or logs a
 * picture's bytes beyond writing them once.
 */
object RecipePictureFiles {
    const val DIR = "recipe_pictures"

    /** Folder names: the app's own saved ids only, so a payload can never point elsewhere. */
    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")

    fun root(context: Context): File = File(context.filesDir, DIR)

    /** The file of [picture] in saved recipe [savedId]'s folder, when it is there. */
    fun file(context: Context, savedId: String, picture: RecipePicture): File? = file(root(context), savedId, picture.file)

    internal fun file(root: File, savedId: String, name: String): File? {
        if (!SAFE_ID.matches(savedId) || !isRecipePictureFile(name)) return null
        return File(File(root, savedId), name).takeIf { it.isFile }
    }

    internal fun folder(root: File, savedId: String): File? = if (SAFE_ID.matches(savedId)) File(root, savedId) else null
}

/** [RecipePictureStore] on files under [root] ([RecipePictureFiles.root]). [nowMillis] for the
 *  sweep's grace, so a folder being written right now is never taken for an orphan. */
class AndroidRecipePictureStore(
    private val root: File,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : RecipePictureStore {

    constructor(context: Context) : this(RecipePictureFiles.root(context))

    override suspend fun save(savedId: String, pictures: ReplyPictures, steps: Int): SavedPictures = withContext(Dispatchers.IO) {
        val folder = RecipePictureFiles.folder(root, savedId) ?: return@withContext SavedPictures()
        if (!folder.isDirectory && !folder.mkdirs()) return@withContext SavedPictures()
        fun write(name: String, bytes: ByteArray): Boolean = runCatching {
            // Whole or not at all: a half-written picture would show as broken.
            val part = File(folder, "$name.part")
            part.writeBytes(bytes)
            part.renameTo(File(folder, name)) || run { part.delete(); false }
        }.getOrDefault(false)
        val dish = pictures.dish?.takeIf { write(RECIPE_PICTURE_DISH, it.bytes) }?.let { RecipePicture(RECIPE_PICTURE_DISH, it.width, it.height) }
        val kept = pictures.forSteps(steps).mapNotNull { step ->
            val name = recipeStepPictureFile(step.step)
            if (write(name, step.picture.bytes)) RecipeStepPicture(step.step, RecipePicture(name, step.picture.width, step.picture.height)) else null
        }
        SavedPictures(dish, kept)
    }

    override suspend fun sweep(keep: Set<String>) = withContext(Dispatchers.IO) {
        val folders = root.listFiles() ?: return@withContext
        val now = nowMillis()
        for (folder in folders) {
            if (folder.name in keep) continue
            // Written in the last minutes: a recipe being saved right now, its row not there yet.
            val newest = (folder.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L).coerceAtLeast(folder.lastModified())
            if (now - newest < SWEEP_GRACE_MILLIS) continue
            folder.deleteRecursively()
        }
    }

    private companion object {
        const val SWEEP_GRACE_MILLIS = 10 * 60_000L
    }
}
