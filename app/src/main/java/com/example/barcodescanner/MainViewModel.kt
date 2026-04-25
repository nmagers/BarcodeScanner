package com.example.barcodescanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One-shot UI events consumed by the Activity/Composable layer. */
sealed class ScanEvent {
    data class Saved(val value: String) : ScanEvent()
    /** Emitted when a scanned code is already in the DB. UI should prompt the user. */
    data class DuplicatePrompt(
        val value: String,
        val format: String,
        val existing: BarcodeEntry
    ) : ScanEvent()
    data class Error(val message: String) : ScanEvent()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BarcodeDatabase.getInstance(app).barcodeDao()

    val entries: StateFlow<List<BarcodeEntry>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    /**
     * Inserts a scan. If the barcode is already recorded, emits DuplicatePrompt so the
     * UI can ask whether to keep the existing entry or replace it. Product lookup runs
     * asynchronously after a successful insert so the UI doesn't block on network.
     */
    fun add(value: String, format: String) = viewModelScope.launch {
        val existing = dao.findByValue(value)
        if (existing != null) {
            _events.emit(ScanEvent.DuplicatePrompt(value, format, existing))
            return@launch
        }
        insertFresh(value, format)
    }

    /** User chose "Replace" on the duplicate prompt. Delete old row, insert fresh, re-lookup. */
    fun replaceDuplicate(existing: BarcodeEntry, value: String, format: String) =
        viewModelScope.launch {
            dao.delete(existing)
            insertFresh(value, format)
        }

    private suspend fun insertFresh(value: String, format: String) {
        val rowId = dao.insert(BarcodeEntry(value = value, format = format))
        if (rowId == -1L) {
            // Shouldn't happen (we just deleted/checked), but surface cleanly if it does.
            _events.emit(ScanEvent.Error("Could not save: already exists"))
            return
        }
        _events.emit(ScanEvent.Saved(value))

        // Kick off product lookup off the main coroutine. Don't fail the scan
        // if the network is down — just leave product fields null.
        if (ProductLookup.supportsLookup(format)) {
            viewModelScope.launch {
                val info = runCatching { ProductLookup.lookup(value, format) }.getOrNull()
                if (info != null && info.hasAnything) {
                    dao.updateProductInfo(rowId, info.name, info.size)
                }
            }
        }
    }

    fun delete(entry: BarcodeEntry) = viewModelScope.launch {
        dao.delete(entry)
    }

    /** Saves user edits from the tap-to-edit dialog. Blank name/size become null. */
    fun updateEntry(id: Long, name: String, size: String, unit: String) = viewModelScope.launch {
        dao.updateUserFields(
            id = id,
            name = name.trim().ifBlank { null },
            size = size.trim().ifBlank { null },
            unit = unit.ifBlank { DEFAULT_UNIT }
        )
    }

    fun clearAll() = viewModelScope.launch {
        dao.deleteAll()
    }

    /**
     * Writes the full barcode list to a CSV in the app cache dir and returns
     * the file (to be shared via FileProvider). Runs off the main thread.
     */
    suspend fun exportCsv(): File = withContext(Dispatchers.IO) {
        val rows = dao.getAllOnce()
        val dir = File(getApplication<Application>().cacheDir, "csv").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "barcodes_$stamp.csv")

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }

        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.appendLine("id,value,format,product,size,unit,scanned_at")
            rows.forEach { e ->
                w.append(e.id.toString()).append(',')
                w.append(csvEscape(e.value)).append(',')
                w.append(csvEscape(e.format)).append(',')
                w.append(csvEscape(e.productName.orEmpty())).append(',')
                w.append(csvEscape(e.productSize.orEmpty())).append(',')
                w.append(csvEscape(e.unit)).append(',')
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
