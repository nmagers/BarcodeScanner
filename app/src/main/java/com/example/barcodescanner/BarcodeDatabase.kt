package com.example.barcodescanner

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BarcodeEntry::class], version = 4, exportSchema = false)
abstract class BarcodeDatabase : RoomDatabase() {
    abstract fun barcodeDao(): BarcodeDao

    companion object {
        /**
         * v1 -> v2:
         *  - adds `productName` column
         *  - removes any existing duplicates (keeps the earliest row per value)
         *  - adds a UNIQUE index on `value` so the DAO's IGNORE strategy works
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE barcodes ADD COLUMN productName TEXT")
                db.execSQL(
                    """
                    DELETE FROM barcodes
                    WHERE id NOT IN (SELECT MIN(id) FROM barcodes GROUP BY value)
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_barcodes_value ON barcodes(value)"
                )
            }
        }

        /** v2 -> v3: adds `productSize` column. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE barcodes ADD COLUMN productSize TEXT")
            }
        }

        /** v3 -> v4: adds `unit` column, defaulting existing rows to "Single". */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE barcodes ADD COLUMN unit TEXT NOT NULL DEFAULT 'Single'")
            }
        }

        @Volatile private var INSTANCE: BarcodeDatabase? = null

        fun getInstance(context: Context): BarcodeDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BarcodeDatabase::class.java,
                    "barcodes.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
