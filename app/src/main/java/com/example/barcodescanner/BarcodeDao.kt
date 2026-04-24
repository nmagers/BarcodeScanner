package com.example.barcodescanner

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BarcodeDao {
    @Query("SELECT * FROM barcodes ORDER BY scannedAt DESC")
    fun getAll(): Flow<List<BarcodeEntry>>

    @Query("SELECT * FROM barcodes ORDER BY scannedAt ASC")
    suspend fun getAllOnce(): List<BarcodeEntry>

    @Insert
    suspend fun insert(entry: BarcodeEntry): Long

    @Delete
    suspend fun delete(entry: BarcodeEntry)

    @Query("DELETE FROM barcodes")
    suspend fun deleteAll()
}
