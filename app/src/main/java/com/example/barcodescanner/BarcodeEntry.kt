package com.example.barcodescanner

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Canonical list of unit options for the edit dropdown. */
val UNIT_OPTIONS = listOf("Single", "Case", "Block")
const val DEFAULT_UNIT = "Single"

@Entity(
    tableName = "barcodes",
    indices = [Index(value = ["value"], unique = true)]
)
data class BarcodeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val value: String,
    val format: String,
    val productName: String? = null,
    val productSize: String? = null,
    val unit: String = DEFAULT_UNIT,
    val scannedAt: Long = System.currentTimeMillis()
)
