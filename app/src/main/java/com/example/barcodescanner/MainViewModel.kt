package com.example.barcodescanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** Barcode not found in the price list — UI should prompt for manual details. */
    data class UnknownBarcode(val value: String, val format: String) : ScanEvent()
    /** A (value, unit) pair already exists. UI asks Keep or Replace. */
    data class DuplicatePrompt(
        val existing: BarcodeEntry,
        val replacement: BarcodeEntry
    ) : ScanEvent()
    data class Error(val message: String) : ScanEvent()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BarcodeDatabase.getInstance(app).barcodeDao()
    private val pricelistRepo = PricelistRepository(app)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { pricelistRepo.seedIfNeeded() }
        }
    }

    val entries: StateFlow<List<BarcodeEntry>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredEntries: StateFlow<List<BarcodeEntry>> = combine(entries, _searchQuery) { list, query ->
        if (query.isBlank()) list
        else {
            val q = query.trim().lowercase()
            list.filter { e ->
                e.value.lowercase().contains(q) ||
                e.productName?.lowercase()?.contains(q) == true ||
                e.productSize?.lowercase()?.contains(q) == true
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearch(query: String) { _searchQuery.value = query }

    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    fun add(value: String, format: String) = viewModelScope.launch {
        val match = pricelistRepo.findByBarcode(value)
        if (match != null) {
            val newEntry = BarcodeEntry(value = value, format = format, productName = match.name, price = match.price)
            val existing = dao.findByValueAndUnit(value, DEFAULT_UNIT)
            if (existing != null) {
                _events.emit(ScanEvent.DuplicatePrompt(existing, newEntry))
            } else {
                if (dao.insert(newEntry) != -1L) _events.emit(ScanEvent.Saved(value))
            }
        } else {
            _events.emit(ScanEvent.UnknownBarcode(value, format))
        }
    }

    /** User chose "Replace" on the duplicate prompt — deletes old row and inserts the replacement. */
    fun replaceDuplicate(existing: BarcodeEntry, replacement: BarcodeEntry) =
        viewModelScope.launch {
            dao.delete(existing)
            val rowId = dao.insert(replacement.copy(id = 0, scannedAt = System.currentTimeMillis()))
            if (rowId != -1L) _events.emit(ScanEvent.Saved(replacement.value))
            else _events.emit(ScanEvent.Error("Could not save"))
        }

    /** Called when the user submits the unknown-barcode detail dialog. */
    fun saveUnknown(value: String, format: String, name: String, price: String, unit: String, quantity: Int) =
        viewModelScope.launch {
            val newEntry = BarcodeEntry(
                value = value,
                format = format,
                productName = name.trim().ifBlank { null },
                price = price.trim().ifBlank { null },
                unit = unit.ifBlank { DEFAULT_UNIT },
                quantity = quantity.coerceAtLeast(1),
                needsReview = true
            )
            val existing = dao.findByValueAndUnit(value, newEntry.unit)
            if (existing != null) {
                _events.emit(ScanEvent.DuplicatePrompt(existing, newEntry))
            } else {
                val rowId = dao.insert(newEntry)
                if (rowId == -1L) _events.emit(ScanEvent.Error("Could not save: already exists"))
                else _events.emit(ScanEvent.Saved(value))
            }
        }

    /** Adds a new unit/quantity variant of an already-known product. */
    fun addVariant(source: BarcodeEntry, name: String, price: String, unit: String, quantity: Int) =
        viewModelScope.launch {
            val newEntry = BarcodeEntry(
                value = source.value,
                format = source.format,
                productName = name.trim().ifBlank { null },
                price = price.trim().ifBlank { null },
                unit = unit.ifBlank { DEFAULT_UNIT },
                quantity = quantity.coerceAtLeast(1),
                needsReview = source.needsReview
            )
            val existing = dao.findByValueAndUnit(source.value, newEntry.unit)
            if (existing != null) {
                _events.emit(ScanEvent.DuplicatePrompt(existing, newEntry))
            } else {
                val rowId = dao.insert(newEntry)
                if (rowId == -1L) _events.emit(ScanEvent.Error("Could not save: already exists"))
                else _events.emit(ScanEvent.Saved(source.value))
            }
        }

    fun delete(entry: BarcodeEntry) = viewModelScope.launch {
        dao.delete(entry)
    }

    /** Saves user edits from the tap-to-edit dialog. Blank name/size become null. */
    fun updateEntry(id: Long, name: String, size: String, unit: String, quantity: Int) = viewModelScope.launch {
        val trimmedName = name.trim().ifBlank { null }
        dao.updateUserFields(
            id = id,
            name = trimmedName,
            size = size.trim().ifBlank { null },
            unit = unit.ifBlank { DEFAULT_UNIT },
            quantity = quantity.coerceAtLeast(1),
            needsReview = trimmedName == null
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
            w.appendLine("id,value,format,product,size,price,unit,quantity,needs_review,scanned_at")
            rows.forEach { e ->
                w.append(e.id.toString()).append(',')
                w.append(csvEscape(e.value)).append(',')
                w.append(csvEscape(e.format)).append(',')
                w.append(csvEscape(e.productName.orEmpty())).append(',')
                w.append(csvEscape(e.productSize.orEmpty())).append(',')
                w.append(csvEscape(e.price.orEmpty())).append(',')
                w.append(csvEscape(e.unit)).append(',')
                w.append(e.quantity.toString()).append(',')
                w.append(if (e.needsReview) "1" else "0").append(',')
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
