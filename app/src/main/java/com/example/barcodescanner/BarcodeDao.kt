package com.example.barcodescanner

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BarcodeDao {
    @Query("SELECT * FROM barcodes ORDER BY scannedAt DESC, id DESC")
    fun getAll(): Flow<List<BarcodeEntry>>

    @Query("SELECT * FROM barcodes ORDER BY scannedAt ASC, id ASC")
    suspend fun getAllOnce(): List<BarcodeEntry>

    @Query("SELECT * FROM barcodes WHERE value = :value LIMIT 1")
    suspend fun findByValue(value: String): BarcodeEntry?

    /** Returns -1 if a row with the same `value` already exists (unique index conflict). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: BarcodeEntry): Long

    @Query("UPDATE barcodes SET productName = :name, productSize = :size WHERE id = :id")
    suspend fun updateProductInfo(id: Long, name: String?, size: String?)

    @Query("UPDATE barcodes SET productName = :name, productSize = :size, unit = :unit WHERE id = :id")
    suspend fun updateUserFields(id: Long, name: String?, size: String?, unit: String)

    @Delete
    suspend fun delete(entry: BarcodeEntry)

    @Query("DELETE FROM barcodes")
    suspend fun deleteAll()
}
