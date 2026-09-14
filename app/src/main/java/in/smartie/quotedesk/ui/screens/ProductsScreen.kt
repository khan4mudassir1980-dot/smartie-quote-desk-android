package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.model.Product
import `in`.smartie.quotedesk.data.model.ProductCategory
import `in`.smartie.quotedesk.ui.AppDataViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ProductsScreen(data: AppDataViewModel) {
    val products by data.products.collectAsStateWithLifecycle()
    val pins by data.pins.collectAsStateWithLifecycle()
    val categories by data.categories.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Product?>(null) }
    var addingProduct by remember { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf(false) }

    val filtered = remember(products, search, categoryFilter) {
        products.filter {
            (categoryFilter.isBlank() || it.categoryId == categoryFilter || it.group == categoryFilter) &&
                (search.isBlank() || it.model.contains(search, true) || it.name.contains(search, true) ||
                    it.specification.contains(search, true) || it.group.contains(search, true))
        }
    }
    val pinned = filtered.filter { it.id in pins }
    val others = filtered.filterNot { it.id in pins }
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }

    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(top = 18.dp, bottom = 4.dp)) {
                Text("Products", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Search products, rates and pinned regular-use items.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (data.profile.isAdmin) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addingProduct = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Add, null); Text("  Add product")
                }
                OutlinedButton(onClick = { addingCategory = true }, modifier = Modifier.weight(1f)) { Text("New category") }
            }
        }
        item {
            OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, label = { Text("Search all products") })
        }
        if (categories.isNotEmpty()) item { CategoryFilter(categories, categoryFilter) { categoryFilter = it } }
        if (pinned.isNotEmpty()) {
            item { SectionHeading("Pinned products", pinned.size) }
            items(pinned, key = { "pin-${it.id}" }) { product ->
                ProductCard(product, true, data.profile.isAdmin,
                    onPin = { data.togglePin(product.id, true) }, onEdit = { editing = product })
            }
        }
        item { SectionHeading(if (categoryFilter.isBlank()) "Product categories" else "Products", others.size) }
        others.groupBy { it.categoryId.ifBlank { it.group } }.forEach { (categoryId, groupProducts) ->
            item(key = "group-$categoryId") {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(categoryNames[categoryId] ?: categoryId.ifBlank { "Uncategorised" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${groupProducts.size}", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            items(groupProducts, key = { it.id }) { product ->
                ProductCard(product, false, data.profile.isAdmin,
                    onPin = { data.togglePin(product.id, false) }, onEdit = { editing = product })
            }
        }
        if (filtered.isEmpty()) item { Text("No products found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Spacer(Modifier.padding(10.dp)) }
    }

    if (addingProduct || editing != null) ProductDialog(product = editing, categories = categories,
        onDismiss = { addingProduct = false; editing = null },
        onSave = { data.saveProduct(it); addingProduct = false; editing = null })
    if (addingCategory) CategoryDialog(onDismiss = { addingCategory = false },
        onSave = { data.saveCategory(it); addingCategory = false })
}

@Composable
private fun CategoryFilter(categories: List<ProductCategory>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(categories.firstOrNull { it.id == selected }?.name ?: "All categories", modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All categories") }, onClick = { open = false; onSelect("") })
            categories.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { open = false; onSelect(c.id) }) }
        }
    }
}

@Composable
private fun SectionHeading(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProductCard(product: Product, pinned: Boolean, canManage: Boolean, onPin: () -> Unit, onEdit: () -> Unit) {
    val currency = remember { NumberFormat.getCurrencyInstance(Locale("en", "IN")) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(product.model, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    if (product.name.isNotBlank()) Text(product.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (product.specification.isNotBlank()) Text(product.specification, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canManage) {
                    IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit product") }
                    IconButton(onClick = onPin) {
                        Icon(if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            if (pinned) "Unpin product" else "Pin product",
                            tint = if (pinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.padding(3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Rate("Dealer", product.dealer, currency); Rate("Contractor", product.contractor, currency); Rate("Client", product.client, currency)
            }
        }
    }
}

@Composable
private fun Rate(label: String, amount: Double?, currency: NumberFormat) {
    Column { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(amount?.let(currency::format) ?: "Not set", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ProductDialog(product: Product?, categories: List<ProductCategory>, onDismiss: () -> Unit, onSave: (Product) -> Unit) {
    var model by remember(product) { mutableStateOf(product?.model.orEmpty()) }
    var name by remember(product) { mutableStateOf(product?.name.orEmpty()) }
    var spec by remember(product) { mutableStateOf(product?.specification.orEmpty()) }
    var unit by remember(product) { mutableStateOf(product?.unit ?: "each") }
    var gst by remember(product) { mutableStateOf(product?.gst?.toCleanString() ?: "18") }
    var dealer by remember(product) { mutableStateOf(product?.dealer?.toCleanString().orEmpty()) }
    var contractor by remember(product) { mutableStateOf(product?.contractor?.toCleanString().orEmpty()) }
    var client by remember(product) { mutableStateOf(product?.client?.toCleanString().orEmpty()) }
    var categoryId by remember(product, categories) { mutableStateOf(product?.categoryId?.ifBlank { product.group } ?: categories.firstOrNull()?.id.orEmpty()) }
    var categoryMenu by remember { mutableStateOf(false) }
    val valid = model.isNotBlank() && categoryId.isNotBlank() && gst.toDoubleOrNull()?.let { it in 0.0..28.0 } == true &&
        listOf(dealer, contractor, client).all { it.isBlank() || it.toDoubleOrNull()?.let { n -> n >= 0 } == true }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (product == null) "Add product" else "Edit product") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Box { OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(categories.firstOrNull { it.id == categoryId }?.name ?: "Select category", modifier = Modifier.weight(1f)); Icon(Icons.Outlined.ArrowDropDown, null) }
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    categories.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { categoryId = c.id; categoryMenu = false }) }
                } } }
            item { OutlinedTextField(model, { model = it }, label = { Text("Model / code") }, singleLine = true) }
            item { OutlinedTextField(name, { name = it }, label = { Text("Product name") }) }
            item { OutlinedTextField(spec, { spec = it }, label = { Text("Description / specification") }, minLines = 2) }
            item { OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, singleLine = true) }
            item { OutlinedTextField(gst, { gst = it }, label = { Text("GST %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true) }
            item { OutlinedTextField(dealer, { dealer = it }, label = { Text("Dealer rate (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true) }
            item { OutlinedTextField(contractor, { contractor = it }, label = { Text("Contractor rate (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true) }
            item { OutlinedTextField(client, { client = it }, label = { Text("Client rate (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true) }
        } },
        confirmButton = { Button(enabled = valid, onClick = {
            val group = product?.group?.ifBlank { categoryId } ?: categoryId
            onSave((product ?: Product()).copy(model = model.trim(), name = name.trim(), specification = spec.trim(), categoryId = categoryId,
                group = group, unit = unit.trim().ifBlank { "each" }, gst = gst.toDouble(), dealer = dealer.toDoubleOrNull(),
                contractor = contractor.toDoubleOrNull(), client = client.toDoubleOrNull()))
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun CategoryDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add category") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Category name") }, singleLine = true) },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun Double.toCleanString(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()
