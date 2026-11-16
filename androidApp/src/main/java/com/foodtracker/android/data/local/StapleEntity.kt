package com.foodtracker.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.foodtracker.model.Staple

/** One of the person's staples ([Staple]): a name they always have, apart from the pantry. */
@Entity(tableName = "staples")
data class StapleEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** A preset's id (`StaplePresets`), null for an own entry. */
    val presetId: String?,
    val addedAtEpochMillis: Long
)

fun Staple.toEntity(): StapleEntity = StapleEntity(id, name, presetId, addedAtEpochMillis)

fun StapleEntity.toDomain(): Staple = Staple(id, name, presetId, addedAtEpochMillis)
