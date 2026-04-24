package com.example.barcodescanner

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BarcodeEntry::class], version = 1, exportSchema = false)
abstract class BarcodeDatabase : RoomDatabase() {
    abstract fun barcodeDao(): BarcodeDao

    companion object {
        @Volatile private var INSTANCE: BarcodeDatabase? = null

        fun getInstance(context: Context): BarcodeDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BarcodeDatabase::class.java,
                    "barcodes.db"
                ).build().also { INSTANCE = it }
            }
    }
}
