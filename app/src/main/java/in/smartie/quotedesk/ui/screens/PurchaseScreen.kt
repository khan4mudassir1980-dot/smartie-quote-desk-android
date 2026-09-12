package in.smartie.quotedesk.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import in.smartie.quotedesk.data.model.PurchaseRequirement
import in.smartie.quotedesk.data.model.Urgency
import in.smartie.quotedesk.ui.AppDataViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun PurchaseScreen(data: AppDataViewModel) {
    val requirements by data.requirements.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("Purchase requirements", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Newest requirements stay at the top.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("  Add a requirement")
            }
        }
        items(requirements, key = { it.id }) { requirement -> RequirementCard(requirement) }
        item { Spacer(Modifier.padding(10.dp)) }
    }

    if (adding) AddRequirementDialog(
        onDismiss = { adding = false },
        onSave = { name, qty, urgency, note ->
            data.addRequirement(name, qty, urgency, note)
            adding = false
        },
    )
}

@Composable
private fun RequirementCard(requirement: PurchaseRequirement) {
    val color = when (requirement.urgency) {
        Urgency.CRITICAL -> Color(0xFFC62828)
        Urgency.URGENT -> Color(0xFFF57C00)
        Urgency.NORMAL -> Color(0xFF087A34)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth()) {
            Surface(color = color, modifier = Modifier.align(Alignment.CenterVertically)) { Spacer(Modifier.padding(horizontal = 3.dp, vertical = 54.dp)) }
            Column(Modifier.padding(14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(requirement.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(50)) {
                        Text(requirement.urgency.label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = color, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text("Quantity needed: ${formatPurchaseQty(requirement.quantity)}", fontWeight = FontWeight.SemiBold)
                if (requirement.note.isNotBlank()) Text(requirement.note)
                Text(
                    "${requirement.status} · Added by ${requirement.addedBy.ifBlank { "Team member" }} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(requirement.updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddRequirementDialog(onDismiss: () -> Unit, onSave: (String, Double, Urgency, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var urgency by remember { mutableStateOf(Urgency.NORMAL) }
    var note by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && quantity.toDoubleOrNull()?.let { it > 0 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a requirement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Product / item") }, singleLine = true)
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Urgency", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Urgency.entries.forEach { option ->
                        OutlinedButton(onClick = { urgency = option }, enabled = urgency != option) { Text(option.label) }
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, minLines = 2)
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onSave(name, quantity.toDouble(), urgency, note) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatPurchaseQty(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)
