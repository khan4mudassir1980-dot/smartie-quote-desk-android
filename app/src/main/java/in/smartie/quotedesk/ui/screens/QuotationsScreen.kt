package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.model.Customer
import `in`.smartie.quotedesk.data.model.Product
import `in`.smartie.quotedesk.data.model.QuotationLine
import `in`.smartie.quotedesk.data.model.RateTier
import `in`.smartie.quotedesk.ui.AppDataViewModel
import `in`.smartie.quotedesk.util.QuotationPdf
import java.text.NumberFormat
import java.util.Locale

@Composable
fun QuotationsScreen(data: AppDataViewModel) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) QuotationEditor(data = data, onClose = { creating = false })
    else QuotationHistory(data = data, onCreate = { creating = true })
}

@Composable
private fun QuotationHistory(data: AppDataViewModel, onCreate: () -> Unit) {
    val quotations by data.quotations.collectAsStateWithLifecycle()
    val currency = remember { NumberFormat.getCurrencyInstance(Locale("en", "IN")) }
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("Quotations", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Create and share a professional quotation in one flow.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("  Create quotation")
            }
        }
        items(quotations, key = { it.id }) { quote ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(quote.number.ifBlank { "Quotation" }, fontWeight = FontWeight.Bold)
                        Text(quote.partyName.ifBlank { "Customer not set" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(quote.status, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(currency.format(quote.total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                }
            }
        }
        if (quotations.isEmpty()) item { Text("No quotations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun QuotationEditor(data: AppDataViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val products by data.products.collectAsStateWithLifecycle()
    val customers by data.customers.collectAsStateWithLifecycle()
    val currency = remember { NumberFormat.getCurrencyInstance(Locale("en", "IN")) }
    var tier by remember { mutableStateOf(RateTier.CLIENT) }
    var customerName by rememberSaveable { mutableStateOf("") }
    var company by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var gstin by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var customerId by rememberSaveable { mutableStateOf("") }
    var saveCustomer by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf("") }
    var additionalLabel by rememberSaveable { mutableStateOf("") }
    var additionalAmount by rememberSaveable { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<QuotationLine>() }
    val visibleProducts = products.filter {
        it.active && (search.isBlank() || listOf(it.model, it.name, it.specification, it.group).any { value -> value.contains(search, true) })
    }.take(12)
    val subtotal = lines.sumOf { it.amount }
    val gst = lines.sumOf { it.gstAmount }
    val additional = additionalAmount.toDoubleOrNull() ?: 0.0
    val total = subtotal + gst + additional

    fun selectCustomer(customer: Customer) {
        customerId = customer.id
        customerName = customer.name
        company = customer.company
        phone = customer.phone
        email = customer.email
        gstin = customer.gstin
        city = customer.city
        address = customer.address
    }

    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, "Back") }
                Column {
                    Text("Create quotation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("Save and share PDF directly", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionTitle("1. Rate type") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RateTier.entries.forEach { option ->
                    FilterChip(selected = tier == option, onClick = {
                        tier = option
                        lines.indices.forEach { index ->
                            products.firstOrNull { it.id == lines[index].productId }?.let { product ->
                                lines[index] = lines[index].copy(rate = product.rateFor(option))
                            }
                        }
                    }, label = { Text(option.label) })
                }
            }
        }
        if (customers.isNotEmpty()) {
            item {
                Column {
                    Text("Saved customers", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        customers.take(20).forEach { customer ->
                            AssistChip(onClick = { selectCustomer(customer) }, label = { Text(customer.company.ifBlank { customer.name }) })
                        }
                    }
                }
            }
        }
        item { SectionTitle("2. Customer details") }
        item { Field(customerName, { customerName = it }, "Contact person *") }
        item { Field(company, { company = it }, "Company / party name") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(phone, { phone = it }, "Phone", Modifier.weight(1f), KeyboardType.Phone)
                Field(email, { email = it }, "Email", Modifier.weight(1f), KeyboardType.Email)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(gstin, { gstin = it.uppercase() }, "GSTIN", Modifier.weight(1f))
                Field(city, { city = it }, "City / site", Modifier.weight(1f))
            }
        }
        item { Field(address, { address = it }, "Address") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = saveCustomer, onCheckedChange = { saveCustomer = it })
                Text("  Save customer for next time")
            }
        }
        item { SectionTitle("3. Add products") }
        item { Field(search, { search = it }, "Search model, product or category") }
        items(visibleProducts, key = { it.documentId.ifBlank { it.id } }) { product ->
            ProductChoice(product, product.rateFor(tier)) {
                val existing = lines.indexOfFirst { it.productId == product.id }
                if (existing >= 0) lines[existing] = lines[existing].copy(quantity = lines[existing].quantity + 1)
                else lines += QuotationLine(
                    productId = product.id,
                    model = product.model.ifBlank { product.name },
                    description = product.specification.ifBlank { product.name },
                    unit = product.unit,
                    rate = product.rateFor(tier),
                    gst = product.gst,
                )
            }
        }
        item { OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Add manual item") } }
        if (lines.isNotEmpty()) item { SectionTitle("4. Quotation items") }
        itemsIndexed(lines, key = { index, item -> "${item.productId}-${item.model}-$index" }) { index, line ->
            LineEditor(line, currency, { lines[index] = it }, { lines.removeAt(index) })
        }
        item { SectionTitle("5. Charges and total") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(additionalLabel, { additionalLabel = it }, "Additional charge", Modifier.weight(1.3f))
                Field(additionalAmount, { additionalAmount = numeric(it) }, "Amount", Modifier.weight(0.7f), KeyboardType.Decimal)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    TotalRow("Subtotal", currency.format(subtotal))
                    TotalRow("GST", currency.format(gst))
                    if (additional > 0) TotalRow(additionalLabel.ifBlank { "Additional" }, currency.format(additional))
                    TotalRow("Grand total", currency.format(total), true)
                }
            }
        }
        item {
            Button(
                onClick = {
                    data.createQuotation(
                        Customer(customerId, customerName.trim(), company.trim(), phone.trim(), email.trim(), gstin.trim(), city.trim(), address.trim()),
                        tier, lines.toList(), additionalLabel, additional, saveCustomer,
                    ) { created ->
                        runCatching { QuotationPdf.createAndShare(context, created) }
                            .onFailure { data.messages.tryEmit(it.message ?: "PDF could not be shared") }
                        onClose()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = customerName.isNotBlank() && lines.isNotEmpty(),
            ) { Text("Create & share PDF") }
        }
        item { Text("The quotation number is assigned only when you create it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (showManual) ManualItemDialog({ showManual = false }) { lines += it; showManual = false }
}

@Composable
private fun ProductChoice(product: Product, rate: Double, onAdd: () -> Unit) {
    val currency = remember { NumberFormat.getCurrencyInstance(Locale("en", "IN")) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(product.model.ifBlank { product.name }, fontWeight = FontWeight.Bold)
                Text(product.specification.ifBlank { product.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(currency.format(rate), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Button(onClick = onAdd) { Text("Add") }
        }
    }
}

@Composable
private fun LineEditor(line: QuotationLine, currency: NumberFormat, onChange: (QuotationLine) -> Unit, onRemove: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(line.model, fontWeight = FontWeight.Bold)
                    Text(line.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Remove") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = { if (line.quantity > 1) onChange(line.copy(quantity = line.quantity - 1)) }) { Icon(Icons.Outlined.Remove, "Decrease") }
                Text(formatNumber(line.quantity), fontWeight = FontWeight.Bold)
                IconButton(onClick = { onChange(line.copy(quantity = line.quantity + 1)) }) { Icon(Icons.Outlined.Add, "Increase") }
                OutlinedTextField(
                    value = formatEditable(line.rate),
                    onValueChange = { onChange(line.copy(rate = numeric(it).toDoubleOrNull() ?: 0.0)) },
                    label = { Text("Rate") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = formatEditable(line.gst),
                    onValueChange = { onChange(line.copy(gst = (numeric(it).toDoubleOrNull() ?: 0.0).coerceIn(0.0, 28.0))) },
                    label = { Text("GST %") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(0.45f),
                )
                Text(currency.format(line.amount + line.gstAmount), modifier = Modifier.weight(0.55f), fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun ManualItemDialog(onDismiss: () -> Unit, onAdd: (QuotationLine) -> Unit) {
    var model by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("each") }
    var rate by remember { mutableStateOf("") }
    var gst by remember { mutableStateOf("18") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add manual item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(model, { model = it }, "Model / item *")
                Field(description, { description = it }, "Description")
                Field(unit, { unit = it }, "Unit")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(rate, { rate = numeric(it) }, "Rate", Modifier.weight(1f), KeyboardType.Decimal)
                    Field(gst, { gst = numeric(it) }, "GST %", Modifier.weight(1f), KeyboardType.Decimal)
                }
            }
        },
        confirmButton = { TextButton(enabled = model.isNotBlank(), onClick = {
            onAdd(QuotationLine(model = model.trim(), description = description.trim(), unit = unit.trim().ifBlank { "each" }, rate = rate.toDoubleOrNull() ?: 0.0, gst = (gst.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 28.0)))
        }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), keyboardType: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = modifier, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboardType))
}

@Composable private fun SectionTitle(value: String) = Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)

@Composable
private fun TotalRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (strong) FontWeight.Black else FontWeight.Normal)
        Text(value, fontWeight = if (strong) FontWeight.Black else FontWeight.SemiBold)
    }
}

private fun Product.rateFor(tier: RateTier): Double = when (tier) {
    RateTier.DEALER -> dealer
    RateTier.CONTRACTOR -> contractor
    RateTier.CLIENT -> client
} ?: 0.0

private fun numeric(value: String) = value.filterIndexed { index, char -> char.isDigit() || (char == '.' && value.indexOf('.') == index) }
private fun formatEditable(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
private fun formatNumber(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(Locale.ENGLISH, value)
