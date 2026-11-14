package com.foodtracker.android.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.foodtracker.recipe.RecipeSource
import com.foodtracker.recipe.SavedRecipe
import com.foodtracker.recipe.SavedRecipeKind
import com.foodtracker.recipe.payloadJson
import com.foodtracker.recipe.savedRecipeFromPayload

/**
 * A recipe or menu written in the app. One table holds both: [kind] says which, and [payload]
 * is the recipe or menu as JSON (`RecipeJson`), so a menu's dishes need no tables of their own.
 * [title] and [source] are copied out of the payload for the saved list.
 */
@Entity(tableName = "saved_recipes", indices = [Index("createdAtEpochMillis")])
data class SavedRecipeEntity(
    @PrimaryKey val id: String,
    /** "recipe" or "menu". */
    val kind: String,
    val title: String,
    val payload: String,
    val createdAtEpochMillis: Long,
    /** "server" or "device". */
    val source: String
)

fun SavedRecipe.toEntity(): SavedRecipeEntity = SavedRecipeEntity(
    id = id,
    kind = kind.wireName,
    title = title,
    payload = payloadJson(),
    createdAtEpochMillis = createdAtEpochMillis,
    source = source.wireName
)

/** Null for a row this version can't read, so one bad row can't hide the rest. */
fun SavedRecipeEntity.toDomain(): SavedRecipe? {
    val kind = SavedRecipeKind.entries.firstOrNull { it.wireName == kind } ?: return null
    val source = RecipeSource.entries.firstOrNull { it.wireName == source } ?: RecipeSource.DEVICE
    return savedRecipeFromPayload(id, kind, title, createdAtEpochMillis, source, payload)
}

private val SavedRecipeKind.wireName: String
    get() = when (this) {
        SavedRecipeKind.RECIPE -> "recipe"
        SavedRecipeKind.MENU -> "menu"
    }

private val RecipeSource.wireName: String
    get() = when (this) {
        RecipeSource.SERVER -> "server"
        RecipeSource.DEVICE -> "device"
    }
