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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.ui.text.font.FontStyle
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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Which entry is currently being edited, if any.
    var editing by remember { mutableStateOf<BarcodeEntry?>(null) }
    // A pending duplicate confirmation, if any.
    var duplicatePrompt by remember { mutableStateOf<ScanEvent.DuplicatePrompt?>(null) }

    // Surface ScanEvents from the VM. Toasts for saved/error, a dialog for duplicates.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ScanEvent.Saved ->
                    Toast.makeText(context, "Saved: ${event.value}", Toast.LENGTH_SHORT).show()
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    EntryCard(
                        entry = entry,
                        onClick = { editing = entry },
                        onDelete = { viewModel.delete(entry) }
                    )
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
                onSave = { name, size, unit ->
                    viewModel.updateEntry(current.id, name, size, unit)
                    editing = null
                }
            )
        }

        duplicatePrompt?.let { prompt ->
            DuplicateDialog(
                prompt = prompt,
                onKeep = { duplicatePrompt = null },
                onReplace = {
                    viewModel.replaceDuplicate(prompt.existing, prompt.value, prompt.format)
                    duplicatePrompt = null
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
                Text(
                    text = entry.value,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                ProductLine(entry)
                Spacer(Modifier.size(2.dp))
                Text(
                    text = metaLine(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

/** Shows product name + size, a "Looking up…" placeholder while lookup is in flight, or nothing. */
@Composable
private fun ProductLine(entry: BarcodeEntry) {
    val name = entry.productName
    val size = entry.productSize
    val hasInfo = !name.isNullOrBlank() || !size.isNullOrBlank()
    val canLookup = ProductLookup.supportsLookup(entry.format)

    when {
        hasInfo -> {
            Spacer(Modifier.size(4.dp))
            val label = listOfNotNull(
                name?.takeIf { it.isNotBlank() },
                size?.takeIf { it.isNotBlank() }?.let { "($it)" }
            ).joinToString(" ")
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        canLookup -> {
            Spacer(Modifier.size(4.dp))
            Text(
                text = "Looking up product…",
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Bottom metadata line: "FORMAT · Unit · timestamp" (unit omitted if default). */
private fun metaLine(entry: BarcodeEntry): String {
    val parts = listOfNotNull(
        entry.format,
        entry.unit.takeIf { it != DEFAULT_UNIT },
        tsFormat.format(Date(entry.scannedAt))
    )
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------------------
// Edit dialog
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditEntryDialog(
    entry: BarcodeEntry,
    onDismiss: () -> Unit,
    onSave: (name: String, size: String, unit: String) -> Unit
) {
    var name by remember(entry.id) { mutableStateOf(entry.productName.orEmpty()) }
    var size by remember(entry.id) { mutableStateOf(entry.productSize.orEmpty()) }
    var unit by remember(entry.id) { mutableStateOf(entry.unit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit product") },
        text = {
            Column {
                Text(
                    text = entry.value,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
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
                UnitDropdown(selected = unit, onChange = { unit = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, size, unit) }) { Text("Save") }
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
        existing.productSize?.takeIf { it.isNotBlank() }?.let { "($it)" }
    ).joinToString(" ").takeIf { it.isNotBlank() }

    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Already scanned") },
        text = {
            Column {
                Text(
                    text = prompt.value,
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
                val meta = listOfNotNull(
                    existing.unit.takeIf { it != DEFAULT_UNIT },
                    "scanned ${tsFormat.format(Date(existing.scannedAt))}"
                ).joinToString(" · ")
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Keep the existing record, or replace it with a fresh scan?",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        // Keep existing is the safe default — put it on the right as the confirm button.
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
