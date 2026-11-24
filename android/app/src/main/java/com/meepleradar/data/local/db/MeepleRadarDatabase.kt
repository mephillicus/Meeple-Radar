package com.meepleradar.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ListingEntity::class,
        GameEntity::class,
        ProductEntity::class,
        SourceEntity::class,
        SyncMetaEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class MeepleRadarDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    companion object {
        const val DB_NAME = "meeple_radar_cache.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE listings ADD COLUMN reprint_date TEXT")
                db.execSQL("ALTER TABLE products ADD COLUMN reprint_date TEXT")
            }
        }

        fun create(context: Context): MeepleRadarDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MeepleRadarDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
