package com.foodtracker.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PantryItemEntity::class], version = 1, exportSchema = false)
abstract class FoodTrackerDatabase : RoomDatabase() {
    abstract fun pantryDao(): PantryDao

    companion object {
        const val DB_NAME = "foodtracker.db"
    }
}
