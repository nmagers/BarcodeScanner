package com.example.barcodescanner

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppScreen(
                    viewModel = viewModel,
                    onScan = ::startScan,
                    onExport = ::shareCsv
                )
            }
        }
    }

    private fun startScan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()

        GmsBarcodeScanning.getClient(this, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue
                if (value.isNullOrEmpty()) {
                    toast("Couldn't read that barcode")
                    return@addOnSuccessListener
                }
                viewModel.add(value, formatName(barcode.format))
            }
            .addOnCanceledListener { /* user backed out */ }
            .addOnFailureListener { e ->
                toast("Scan failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
    }

    private fun shareCsv() {
        lifecycleScope.launch {
            if (viewModel.entries.value.isEmpty()) {
                toast("Nothing to export yet")
                return@launch
            }
            runCatching { viewModel.exportCsv() }
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, file.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Export CSV"))
                }
                .onFailure { toast("Export failed: ${it.localizedMessage}") }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(
    viewModel: MainViewModel,
    onScan: () -> Unit,
    onExport: () -> Unit
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val filteredEntries by viewModel.filteredEntries.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var editing by remember { mutableStateOf<BarcodeEntry?>(null) }
    var duplicatePrompt by remember { mutableStateOf<ScanEvent.DuplicatePrompt?>(null) }
    var unknownPrompt by remember { mutableStateOf<ScanEvent.UnknownBarcode?>(null) }
    var addVariantFor by remember { mutableStateOf<BarcodeEntry?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ScanEvent.Saved ->
                    Toast.makeText(context, "Saved: ${event.value}", Toast.LENGTH_SHORT).show()
                is ScanEvent.UnknownBarcode ->
                    unknownPrompt = event
                is ScanEvent.DuplicatePrompt ->
                    duplicatePrompt = event
                is ScanEvent.Error ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Barcode Scanner") },
                actions = {
                    IconButton(onClick = onExport, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Export CSV")
                    }
                    IconButton(
                        onClick = { scope.launch { viewModel.clearAll() } },
                        enabled = entries.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                text = { Text("Scan") }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(padding)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearch(it) },
                    placeholder = { Text("Search name or barcode") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearch("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                if (filteredEntries.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No results for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredEntries, key = { it.id }) { entry ->
                            EntryCard(
                                entry = entry,
                                onClick = { editing = entry },
                                onAddVariant = { addVariantFor = entry },
                                onDelete = { viewModel.delete(entry) }
                            )
                        }
                    }
                }
            }
        }

        editing?.let { entry ->
            // Keep the dialog bound to the latest version of this entry from the
            // flow (so a concurrent product lookup updating the row doesn't get
            // silently reverted when the user hits Save).
            val current = entries.firstOrNull { it.id == entry.id } ?: entry
            EditEntryDialog(
                entry = current,
                onDismiss = { editing = null },
                onSave = { name, size, unit, quantity ->
                    viewModel.updateEntry(current.id, name, size, unit, quantity)
                    editing = null
                }
            )
        }

        duplicatePrompt?.let { prompt ->
            DuplicateDialog(
                prompt = prompt,
                onKeep = { duplicatePrompt = null },
                onReplace = {
                    viewModel.replaceDuplicate(prompt.existing, prompt.replacement)
                    duplicatePrompt = null
                }
            )
        }

        unknownPrompt?.let { prompt ->
            ProductDetailDialog(
                title = "Unknown barcode",
                subtitle = "Not in price list — enter details or save blank to flag for review.",
                onDismiss = { unknownPrompt = null },
                onSave = { name, price, unit, quantity ->
                    viewModel.saveUnknown(prompt.value, prompt.format, name, price, unit, quantity)
                    unknownPrompt = null
                }
            )
        }

        addVariantFor?.let { source ->
            ProductDetailDialog(
                title = "Add variant",
                subtitle = "Same product, different type or quantity.",
                initialName = source.productName.orEmpty(),
                initialPrice = source.price.orEmpty(),
                onDismiss = { addVariantFor = null },
                onSave = { name, price, unit, quantity ->
                    viewModel.addVariant(source, name, price, unit, quantity)
                    addVariantFor = null
                }
            )
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(16.dp))
            Text(
                "Tap Scan to capture your first barcode",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun EntryCard(
    entry: BarcodeEntry,
    onClick: () -> Unit,
    onAddVariant: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.value,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    if (entry.needsReview) {
                        Spacer(Modifier.size(6.dp))
                        Icon(
                            Icons.Filled.Flag,
                            contentDescription = "Needs review",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFF59E0B)
                        )
                    }
                }
                ProductLine(entry)
                Spacer(Modifier.size(2.dp))
                Text(
                    text = metaLine(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onAddVariant) {
                Icon(Icons.Filled.Add, contentDescription = "Add variant")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun ProductLine(entry: BarcodeEntry) {
    val label = listOfNotNull(
        entry.productName?.takeIf { it.isNotBlank() },
        entry.productSize?.takeIf { it.isNotBlank() }?.let { "($it)" },
        entry.price?.takeIf { it.isNotBlank() }
    ).joinToString("  ")
    if (label.isEmpty()) return
    Spacer(Modifier.size(4.dp))
    Text(text = label, style = MaterialTheme.typography.bodyMedium)
}

/** Bottom metadata line: "FORMAT · 6-pack ×3 · timestamp" */
private fun metaLine(entry: BarcodeEntry): String {
    val unitPart = when {
        entry.quantity > 1 -> "${entry.unit} ×${entry.quantity}"
        entry.unit != DEFAULT_UNIT -> entry.unit
        else -> null
    }
    return listOfNotNull(entry.format, unitPart, tsFormat.format(Date(entry.scannedAt))).joinToString(" · ")
}

// ---------------------------------------------------------------------------
// Edit dialog
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditEntryDialog(
    entry: BarcodeEntry,
    onDismiss: () -> Unit,
    onSave: (name: String, size: String, unit: String, quantity: Int) -> Unit
) {
    var name by remember(entry.id) { mutableStateOf(entry.productName.orEmpty()) }
    var size by remember(entry.id) { mutableStateOf(entry.productSize.orEmpty()) }
    var unit by remember(entry.id) { mutableStateOf(entry.unit) }
    var quantityText by remember(entry.id) { mutableStateOf(entry.quantity.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit product") },
        text = {
            Column {
                Text(
                    text = entry.value,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = size,
                    onValueChange = { size = it },
                    label = { Text("Size") },
                    singleLine = true,
                    placeholder = { Text("e.g. 330 mL, 500 g") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.filter { c -> c.isDigit() } },
                    label = { Text("Total amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                UnitDropdown(selected = unit, onChange = { unit = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val qty = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                onSave(name, size, unit, qty)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductDetailDialog(
    title: String,
    subtitle: String,
    initialName: String = "",
    initialPrice: String = "",
    initialUnit: String = DEFAULT_UNIT,
    initialQuantity: Int = 1,
    onDismiss: () -> Unit,
    onSave: (name: String, price: String, unit: String, quantity: Int) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var price by remember { mutableStateOf(initialPrice) }
    var unit by remember { mutableStateOf(initialUnit) }
    var quantityText by remember { mutableStateOf(initialQuantity.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.filter { c -> c.isDigit() } },
                    label = { Text("Total amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                UnitDropdown(selected = unit, onChange = { unit = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val qty = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                onSave(name, price, unit, qty)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DuplicateDialog(
    prompt: ScanEvent.DuplicatePrompt,
    onKeep: () -> Unit,
    onReplace: () -> Unit
) {
    val existing = prompt.existing
    val productLine = listOfNotNull(
        existing.productName?.takeIf { it.isNotBlank() },
        existing.productSize?.takeIf { it.isNotBlank() }?.let { "($it)" },
        existing.price?.takeIf { it.isNotBlank() }
    ).joinToString("  ").takeIf { it.isNotBlank() }

    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Already recorded") },
        text = {
            Column {
                Text(
                    text = existing.value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                if (productLine != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(productLine, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
                val meta = metaLine(existing)
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Keep the existing record, or replace it?",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onKeep) { Text("Keep existing") }
        },
        dismissButton = {
            TextButton(onClick = onReplace) { Text("Replace") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdown(selected: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            UNIT_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private val tsFormat = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault())

/** Map ML Kit's format constant to a human-readable name stored in the CSV. */
internal fun formatName(format: Int): String = when (format) {
    Barcode.FORMAT_CODE_128 -> "CODE_128"
    Barcode.FORMAT_CODE_39 -> "CODE_39"
    Barcode.FORMAT_CODE_93 -> "CODE_93"
    Barcode.FORMAT_CODABAR -> "CODABAR"
    Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
    Barcode.FORMAT_EAN_13 -> "EAN_13"
    Barcode.FORMAT_EAN_8 -> "EAN_8"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_QR_CODE -> "QR_CODE"
    Barcode.FORMAT_UPC_A -> "UPC_A"
    Barcode.FORMAT_UPC_E -> "UPC_E"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_AZTEC -> "AZTEC"
    else -> "UNKNOWN"
}
