package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.ui.AppDataViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun QuotationsScreen(data: AppDataViewModel) {
    val quotations by data.quotations.collectAsStateWithLifecycle()
    val currency = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("Quotations", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Saved team quotations and their current status.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Button(onClick = { data.messages.tryEmit("Native quotation editor is the next migration milestone.") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("  Create quotation")
            }
        }
        items(quotations, key = { it.id }) { quote ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(quote.number.ifBlank { "Draft quotation" }, fontWeight = FontWeight.Bold)
                        Text(quote.partyName.ifBlank { "Customer not set" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(quote.status, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(currency.format(quote.total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                }
            }
        }
        if (quotations.isEmpty()) item { Text("No saved quotations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Spacer(Modifier.padding(10.dp)) }
    }
}
