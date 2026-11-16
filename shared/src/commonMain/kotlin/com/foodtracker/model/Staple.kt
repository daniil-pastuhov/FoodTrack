package com.foodtracker.model

import kotlinx.serialization.Serializable

/**
 * A name the person always has at home (salt, oil, eggs): no date, no quantity and no status, so
 * it never notifies anyone and is never counted in an expiry number or a widget. Kept in its own
 * table, never in the pantry, and offered to every recipe route as something the recipe may use
 * (`shared/.../staples/StapleRules.kt`). iOS: `Staple` in `Models/Staple.swift`.
 *
 * [presetId] is one of [com.foodtracker.staples.StaplePresets] for a ticked preset, shown and sent
 * in the app's current language; [name] is then the preset's name in the language it was ticked
 * in, the fallback for a reader that doesn't know the id. Null for the person's own entries.
 * Part of the backup file (`staples`), so the field names are a cross-platform contract.
 */
@Serializable
data class Staple(
    val id: String,
    val name: String,
    val presetId: String? = null,
    val addedAtEpochMillis: Long = 0L
)
