package com.meepleradar.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ListingEntity::class,
        GameEntity::class,
        ProductEntity::class,
        SourceEntity::class,
        SyncMetaEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MeepleRadarDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    companion object {
        const val DB_NAME = "meeple_radar_cache.db"

        fun create(context: Context): MeepleRadarDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MeepleRadarDatabase::class.java,
                DB_NAME,
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
