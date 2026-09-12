package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.model.Product
import `in`.smartie.quotedesk.ui.AppDataViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ProductsScreen(data: AppDataViewModel) {
    val products by data.products.collectAsStateWithLifecycle()
    val pins by data.pins.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val filtered = remember(products, search) {
        if (search.isBlank()) products else products.filter {
            it.model.contains(search, true) || it.name.contains(search, true) || it.group.contains(search, true)
        }
    }
    val pinned = filtered.filter { it.id in pins }
    val others = filtered.filterNot { it.id in pins }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 18.dp, bottom = 4.dp)) {
                Text("Products", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Search products, rates and pinned regular-use items.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                label = { Text("Search all products") },
            )
        }
        if (pinned.isNotEmpty()) {
            item { SectionHeading("Pinned products", pinned.size) }
            items(pinned, key = { "pin-${it.id}" }) { product ->
                ProductCard(product, true, data.profile.isAdmin) { data.togglePin(product.id, true) }
            }
        }
        item { SectionHeading("Product categories", others.size) }
        val groups = others.groupBy { it.group }
        groups.forEach { (group, groupProducts) ->
            item(key = "group-$group") {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.ifBlank { "Uncategorised" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${groupProducts.size}", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            items(groupProducts, key = { it.id }) { product ->
                ProductCard(product, false, data.profile.isAdmin) { data.togglePin(product.id, false) }
            }
        }
        item { Spacer(Modifier.padding(10.dp)) }
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
private fun ProductCard(product: Product, pinned: Boolean, canPin: Boolean, onPin: () -> Unit) {
    val currency = remember { NumberFormat.getCurrencyInstance(Locale("en", "IN")) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(product.model, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    if (product.name.isNotBlank()) Text(product.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canPin) IconButton(onClick = onPin) {
                    Icon(
                        if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (pinned) "Unpin product" else "Pin product",
                        tint = if (pinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.padding(3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Rate("Dealer", product.dealer, currency)
                Rate("Contractor", product.contractor, currency)
                Rate("Client", product.client, currency)
            }
        }
    }
}

@Composable
private fun Rate(label: String, amount: Double?, currency: NumberFormat) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(amount?.let(currency::format) ?: "Not set", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}
