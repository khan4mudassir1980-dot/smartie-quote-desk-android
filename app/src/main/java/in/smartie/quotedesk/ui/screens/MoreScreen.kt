package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.model.MemberRole
import `in`.smartie.quotedesk.data.model.UserProfile
import `in`.smartie.quotedesk.ui.AppDataViewModel

@Composable
fun MoreScreen(data: AppDataViewModel, onSignOut: () -> Unit) {
    val people by data.people.collectAsStateWithLifecycle()
    var inviteInfo by remember { mutableStateOf(false) }
    val visiblePeople = remember(people, data.profile.uid) {
        people.filterNot { it.uid == data.profile.uid }
            .sortedWith(compareByDescending<UserProfile> { it.active }.thenBy { it.name.lowercase() })
    }
    val active = visiblePeople.filter { it.active }
    val disabled = visiblePeople.filterNot { it.active }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("Account & team", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Signed in as ${data.profile.name}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { CurrentUserCard(data.profile, onSignOut) }

        if (data.profile.isOriginalOwner) item { CompactDataSettings() }

        if (data.profile.isAdmin) {
            item {
                Text("People", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                OutlinedButton(onClick = { inviteInfo = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("  Add a teammate")
                }
            }
            if (active.isNotEmpty()) item { GroupLabel("Active people", active.size) }
            items(active, key = { it.uid }) { person -> PeopleCard(data, person) }
            if (disabled.isNotEmpty()) item { GroupLabel("Switched-off people", disabled.size) }
            items(disabled, key = { it.uid }) { person -> PeopleCard(data, person) }
        }
        item { Spacer(Modifier.padding(10.dp)) }
    }

    if (inviteInfo) AlertDialog(
        onDismissRequest = { inviteInfo = false },
        title = { Text("Add a teammate") },
        text = { Text("Ask the teammate to install SMARTIE Quote Desk and continue with their Google account. They enter as a Worker; an Owner or Administrator can then assign the correct role here.") },
        confirmButton = { Button(onClick = { inviteInfo = false }) { Text("Understood") } },
    )
}

@Composable
private fun CurrentUserCard(profile: UserProfile, onSignOut: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                    Text(
                        profile.name.take(1).uppercase().ifBlank { "S" },
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(profile.email, style = MaterialTheme.typography.bodySmall)
                    Text(profile.role.label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            TextButton(onClick = onSignOut) {
                Icon(Icons.Outlined.Logout, contentDescription = null)
                Text("  Sign out")
            }
        }
    }
}

@Composable
private fun CompactDataSettings() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Data & sync", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("Firebase · live team data", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Owner only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GroupLabel(label: String, count: Int) {
    Text("${label.uppercase()} · $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
}

@Composable
private fun PeopleCard(data: AppDataViewModel, person: UserProfile) {
    var roleMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val caller = data.profile
    val protectedOwner = person.isOriginalOwner || person.role == MemberRole.OWNER
    val canManage = when {
        person.uid == caller.uid -> false
        caller.isOriginalOwner -> !person.isOriginalOwner
        caller.role == MemberRole.OWNER -> !protectedOwner
        caller.role == MemberRole.ADMIN -> person.role in setOf(MemberRole.STAFF, MemberRole.WORKER)
        else -> false
    }
    val roleOptions = when {
        caller.isOriginalOwner -> listOf(MemberRole.OWNER, MemberRole.ADMIN, MemberRole.STAFF, MemberRole.WORKER)
        caller.role == MemberRole.OWNER -> listOf(MemberRole.ADMIN, MemberRole.STAFF, MemberRole.WORKER)
        caller.role == MemberRole.ADMIN -> listOf(MemberRole.STAFF, MemberRole.WORKER)
        else -> emptyList()
    }

    Card(colors = CardDefaults.cardColors(containerColor = if (person.active) MaterialTheme.colorScheme.surface else Color(0xFFF4F4F6))) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(person.email, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                    Text(person.role.label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (!person.active) Surface(color = Color(0xFFE8E8EC), shape = RoundedCornerShape(50)) {
                    Text("Disabled", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }

            if (person.role == MemberRole.OWNER && caller.isOriginalOwner && !person.isOriginalOwner) {
                OutlinedButton(
                    onClick = { data.emergencyRevoke(person) },
                    modifier = Modifier.padding(top = 10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) { Text("Emergency revoke", color = MaterialTheme.colorScheme.error) }
            } else if (canManage) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { roleMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(person.role.label, modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                            roleOptions.filterNot { it == person.role }.forEach { role ->
                                DropdownMenuItem(
                                    text = { Text(role.label) },
                                    onClick = {
                                        roleMenu = false
                                        data.changeRole(person, role)
                                    },
                                )
                            }
                        }
                    }
                    OutlinedButton(onClick = { data.setActive(person, !person.active) }) {
                        Text(if (person.active) "Switch off" else "Switch on")
                    }
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.padding(top = 8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .5f)),
                ) {
                    Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text("  Delete permanently", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove ${person.name}?") },
        text = { Text("Their team profile will be deleted. This does not block their Google account; if they sign in again, they return as a Worker.") },
        confirmButton = {
            Button(onClick = { confirmDelete = false; data.deleteProfile(person) }) { Text("Delete permanently") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
