package com.example.barcodescanner

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PricelistRepository(private val app: Application) {

    private val dao = BarcodeDatabase.getInstance(app).pricelistDao()

    suspend fun seedIfNeeded() = withContext(Dispatchers.IO) {
        if (dao.count() > 0) return@withContext
        dao.insertAll(parseCsv())
    }

    suspend fun findByBarcode(barcode: String): PricelistEntry? =
        dao.findByBarcode(barcode)
            ?: if (barcode.startsWith('0')) dao.findByBarcode(barcode.removePrefix("0")) else null
            ?: dao.findByBarcode("0$barcode")

    private fun parseCsv(): List<PricelistEntry> {
        val entries = mutableListOf<PricelistEntry>()
        app.assets.open("pricelist.csv").bufferedReader().forEachLine { line ->
            val cols = parseLine(line)
            if (cols.size < 5) return@forEachLine
            val barcode = cols[4].trim()
            if (barcode.isBlank()) return@forEachLine
            entries.add(
                PricelistEntry(
                    barcode = barcode,
                    plu = cols[1].trim(),
                    name = cols[2].trim(),
                    price = cols[3].trim(),
                    category = cols[0].trim()
                )
            )
        }
        return entries
    }

    /** Minimal RFC-4180 CSV line parser that handles quoted fields containing commas. */
    private fun parseLine(line: String): List<String> {
        val cols = mutableListOf<String>()
        val buf = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { cols.add(buf.toString()); buf.clear() }
                else -> buf.append(ch)
            }
        }
        cols.add(buf.toString())
        return cols
    }
}
