package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.model.StockItem
import `in`.smartie.quotedesk.ui.AppDataViewModel
import kotlin.math.max

private enum class StockFilter { ALL, LOW, OUT }

@Composable
fun StockScreen(data: AppDataViewModel) {
    val stock by data.stock.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(StockFilter.ALL) }
    val drafts = remember { mutableStateMapOf<String, Int>() }
    var editing by remember { mutableStateOf<StockItem?>(null) }
    val visible = remember(stock, search, filter) {
        stock.filter { item ->
            val matchesSearch = search.isBlank() || item.key.contains(search, true) || item.note.contains(search, true)
            val matchesFilter = when (filter) {
                StockFilter.ALL -> true
                StockFilter.LOW -> item.isLow
                StockFilter.OUT -> item.isOut
            }
            matchesSearch && matchesFilter
        }
    }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("Our Stock", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Live quantities for the whole team.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StockSummary("Stocked", stock.count { it.quantity > 0 }, Color(0xFFEDE2FF), Modifier.weight(1f)) {
                    filter = StockFilter.ALL
                }
                StockSummary("Low", stock.count { it.isLow }, Color(0xFFFFF1B8), Modifier.weight(1f)) {
                    filter = if (filter == StockFilter.LOW) StockFilter.ALL else StockFilter.LOW
                }
                StockSummary("Out", stock.count { it.isOut }, Color(0xFFFFDCDC), Modifier.weight(1f)) {
                    filter = if (filter == StockFilter.OUT) StockFilter.ALL else StockFilter.OUT
                }
            }
        }
        if (filter != StockFilter.ALL) {
            item {
                val names = visible.take(4).joinToString(" • ") { displayName(it.key) }
                Text(
                    if (names.isBlank()) "No matching products" else names,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                label = { Text("Search stock") },
            )
        }
        items(visible, key = { it.key }) { item ->
            val delta = drafts[item.key] ?: 0
            StockCard(
                item = item,
                delta = delta,
                canWrite = data.profile.canWriteStock,
                canEdit = data.profile.isAdmin,
                onMinus = {
                    if (item.quantity + delta > 0) drafts[item.key] = delta - 1
                },
                onPlus = { drafts[item.key] = delta + 1 },
                onDone = {
                    data.commitStock(item, delta.toDouble())
                    drafts.remove(item.key)
                },
                onEdit = { editing = item },
            )
        }
        item { Spacer(Modifier.padding(10.dp)) }
    }

    editing?.let { item ->
        EditStockDialog(
            item = item,
            onDismiss = { editing = null },
            onSave = { quantity, reorder, note ->
                data.editStock(item, quantity, reorder, note)
                editing = null
            },
        )
    }
}

@Composable
private fun StockSummary(label: String, count: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = color,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.Black.copy(alpha = .08f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text("$count", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun StockCard(
    item: StockItem,
    delta: Int,
    canWrite: Boolean,
    canEdit: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onDone: () -> Unit,
    onEdit: () -> Unit,
) {
    val shownQuantity = max(0.0, item.quantity + delta)
    val status = when {
        shownQuantity <= 0 -> "Out of stock"
        shownQuantity <= item.reorderLevel -> "Low stock"
        else -> "Available"
    }
    val statusColor = when (status) {
        "Out of stock" -> Color(0xFFB42318)
        "Low stock" -> Color(0xFFB54708)
        else -> Color(0xFF087A34)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(displayName(item.key), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Surface(color = statusColor.copy(alpha = .1f), shape = RoundedCornerShape(50)) {
                        Text(status, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = statusColor, style = MaterialTheme.typography.labelMedium)
                    }
                    Text("Reorder at ${formatQty(item.reorderLevel)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatQty(shownQuantity), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                    Text("available", style = MaterialTheme.typography.labelMedium)
                    if (canEdit) TextButton(onClick = onEdit) { Text("Edit", style = MaterialTheme.typography.labelSmall) }
                }
            }
            if (canWrite) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) { IconButton(onClick = onMinus) { Icon(Icons.Outlined.Remove, "Remove one") } }
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) { IconButton(onClick = onPlus) { Icon(Icons.Outlined.Add, "Add one") } }
                }
                if (delta != 0) {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("Done · ${if (delta > 0) "+$delta" else delta.toString()}")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditStockDialog(item: StockItem, onDismiss: () -> Unit, onSave: (Double, Double, String) -> Unit) {
    var quantity by remember(item) { mutableStateOf(formatQty(item.quantity)) }
    var reorder by remember(item) { mutableStateOf(formatQty(item.reorderLevel)) }
    var note by remember(item) { mutableStateOf(item.note) }
    val valid = quantity.toDoubleOrNull()?.let { it >= 0 } == true && reorder.toDoubleOrNull()?.let { it >= 0 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit stock · ${displayName(item.key)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Exact quantity") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(reorder, { reorder = it }, label = { Text("Reorder level") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(note, { note = it }, label = { Text("Stock note (optional)") }, minLines = 2)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { onSave(quantity.toDouble(), reorder.toDouble(), note) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun displayName(key: String): String = key.substringAfterLast('|').ifBlank { key }
private fun formatQty(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)
