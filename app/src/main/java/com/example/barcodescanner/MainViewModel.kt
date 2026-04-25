package com.example.barcodescanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BarcodeDatabase.getInstance(app).barcodeDao()

    val entries: StateFlow<List<BarcodeEntry>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(value: String, format: String) = viewModelScope.launch {
        dao.insert(BarcodeEntry(value = value, format = format))
    }

    fun delete(entry: BarcodeEntry) = viewModelScope.launch {
        dao.delete(entry)
    }

    fun clearAll() = viewModelScope.launch {
        dao.deleteAll()
    }

    /**
     * Writes the full barcode list to a CSV in the app cache dir and returns
     * the file (to be shared via FileProvider). Runs off the main thread.
     */
    suspend fun exportCsv(): File = withContext(Dispatchers.IO) {
        val entries = dao.getAllOnce()
        val dir = File(getApplication<Application>().cacheDir, "csv").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "barcodes_$stamp.csv")

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }

        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.appendLine("id,value,format,scanned_at")
            entries.forEach { e ->
                w.append(e.id.toString()).append(',')
                w.append(csvEscape(e.value)).append(',')
                w.append(csvEscape(e.format)).append(',')
                w.append(iso.format(Date(e.scannedAt)))
                w.appendLine()
            }
        }
        file
    }

    /** RFC 4180-style escaping: quote the field if it contains a comma, quote, or newline. */
    private fun csvEscape(raw: String): String {
        val needsQuoting = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"" + raw.replace("\"", "\"\"") + "\"" else raw
    }
}
