package `in`.smartie.quotedesk.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.model.TeamAuditEntry
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.OwnerRank
import `in`.smartie.quotedesk.domain.PeopleFilter
import `in`.smartie.quotedesk.domain.PeopleStatusFilter
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.components.ConfirmDialog
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SectionHeader
import `in`.smartie.quotedesk.ui.components.SegmentedChoice
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A pending action that needs confirming before it is carried out. */
private sealed interface PendingAction {
    val target: Member

    data class SwitchOff(override val target: Member) : PendingAction
    data class Remove(override val target: Member) : PendingAction
    data class Revoke(override val target: Member) : PendingAction
    data class Appoint(override val target: Member) : PendingAction
    data class Demote(override val target: Member, val role: Role) : PendingAction
}

@Composable
fun TeamScreen(
    viewer: Member,
    viewModel: TeamViewModel,
    onShareInvite: () -> Unit,
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val audit by viewModel.audit.collectAsStateWithLifecycle()
    val dimens = LocalSmartieDimens.current
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var showActivity by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(
            start = dimens.screenPadding,
            end = dimens.screenPadding,
            top = dimens.gapM,
            bottom = dimens.listBottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS),
    ) {
        if (!Permissions.canViewTeam(viewer)) {
            item {
                EmptyState("Only an Owner or Administrator can manage team roles.")
            }
            return@LazyColumn
        }

        item {
            SmartieCard {
                Column {
                    Text(
                        "Invite a teammate",
                        style = MaterialTheme.typography.titleMedium,
                        color = SmartieColors.Ink,
                    )
                    Text(
                        "Share the app with them. They choose Continue with Google and enter as a " +
                            "Worker; an Owner or Administrator then sets their role.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SmartieColors.Steel,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    SmartieGhostButton("Share app link", onShareInvite)
                }
            }
        }

        item {
            SmartieField(
                label = "Search people",
                value = filter.query,
                onValueChange = viewModel::setQuery,
                placeholder = "Name or email",
            )
        }

        item {
            SegmentedChoice(
                options = PeopleStatusFilter.entries,
                selected = filter.status,
                label = { it.label },
                onSelect = viewModel::setStatus,
            )
        }

        if (groups.isEmpty) {
            item { EmptyState("No other people match.") }
        }

        if (groups.active.isNotEmpty()) {
            item { SectionHeader("Active people", trailing = groups.active.size.toString()) }
            items(groups.active, key = { it.uid }) { person ->
                PersonRow(viewer, person, viewModel) { pending = it }
            }
        }

        if (groups.switchedOff.isNotEmpty()) {
            item { SectionHeader("Switched off", trailing = groups.switchedOff.size.toString()) }
            items(groups.switchedOff, key = { it.uid }) { person ->
                PersonRow(viewer, person, viewModel) { pending = it }
            }
        }

        if (Permissions.canViewTeamActivity(viewer)) {
            item {
                SmartieGhostButton(
                    text = if (showActivity) "Hide team activity" else "Team activity",
                    onClick = { showActivity = !showActivity },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showActivity) {
                if (audit.isEmpty()) {
                    item { EmptyState("No activity yet.") }
                } else {
                    items(audit, key = { it.id }) { entry -> AuditRow(entry) }
                }
            }
        }
    }

    pending?.let { action ->
        ConfirmationFor(
            action = action,
            onDismiss = { pending = null },
            onConfirm = {
                when (action) {
                    is PendingAction.SwitchOff -> viewModel.setActive(action.target, false)
                    is PendingAction.Remove -> viewModel.remove(action.target)
                    is PendingAction.Revoke -> viewModel.emergencyRevoke(action.target)
                    is PendingAction.Appoint -> viewModel.changeRole(action.target, Role.OWNER)
                    is PendingAction.Demote -> viewModel.changeRole(action.target, action.role)
                }
                pending = null
            },
        )
    }
}

@Composable
private fun ConfirmationFor(
    action: PendingAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = action.target.name.ifBlank { action.target.email }
    when (action) {
        is PendingAction.SwitchOff -> ConfirmDialog(
            title = "Switch off account?",
            message = "$name will be signed out and cannot enter until an administrator " +
                "switches the account on again.",
            confirmText = "Switch off",
            danger = true,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )

        is PendingAction.Remove -> ConfirmDialog(
            title = "Remove member?",
            message = "Remove $name from this team.",
            warning = "If they sign in again later, a fresh Worker profile will be created.",
            confirmText = "Remove member",
            requireTypedText = name,
            danger = true,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )

        is PendingAction.Revoke -> ConfirmDialog(
            title = "Emergency revoke Owner?",
            message = "Remove Owner access from $name.",
            warning = "This immediately changes the account to Worker and switches it off. " +
                "They cannot enter again until switched on.",
            confirmText = "Revoke access",
            requireTypedText = name,
            danger = true,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )

        is PendingAction.Appoint -> ConfirmDialog(
            title = "Appoint second Owner?",
            message = "$name will receive full administrative control.",
            warning = "Only the primary Owner can later demote or emergency-revoke this account.",
            confirmText = "Appoint Owner",
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )

        is PendingAction.Demote -> ConfirmDialog(
            title = "Change protected Owner role?",
            message = "$name will become ${roleLabel(action.role)}.",
            warning = "They will lose Owner-level control immediately.",
            confirmText = "Change to ${roleLabel(action.role)}",
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun PersonRow(
    viewer: Member,
    person: Member,
    viewModel: TeamViewModel,
    onPending: (PendingAction) -> Unit,
) {
    val options = viewModel.roleOptionsFor(person)
    val canToggle = Permissions.canToggleActive(viewer, person)
    val canRemove = Permissions.canRemove(viewer, person)
    val canRevoke = Permissions.canEmergencyRevoke(viewer, person)

    // One lazy item emits one node: the row and its controls share a Column.
    Column(Modifier.fillMaxWidth()) {
        ListRow(
            title = person.name.ifBlank { person.email },
            secondary = person.email.takeIf { it.isNotBlank() },
            background = if (person.active) SmartieColors.Panel else SmartieColors.Panel2,
            tags = {
                Tag(person.roleLabel, if (person.isOwner) TagTone.PURPLE else TagTone.NEUTRAL)
                person.ownerSubLabel?.let { Tag(it, TagTone.PURPLE) }
                if (!person.active) Tag("Switched off", TagTone.WARN)
            },
            trailing = {},
        )

        if (options.isNotEmpty() || canToggle || canRemove || canRevoke) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (options.isNotEmpty()) {
                    RoleMenu(
                        person = person,
                        options = options,
                        onSelect = { role ->
                            when {
                                role == Role.OWNER -> onPending(PendingAction.Appoint(person))
                                person.ownerRank == OwnerRank.ADDITIONAL ->
                                    onPending(PendingAction.Demote(person, role))
                                else -> viewModel.changeRole(person, role)
                            }
                        },
                    )
                }
                if (canToggle) {
                    SmartieGhostButton(
                        text = if (person.active) "Switch off" else "Switch on",
                        onClick = {
                            if (person.active) onPending(PendingAction.SwitchOff(person))
                            else viewModel.setActive(person, true)
                        },
                    )
                }
                if (canRevoke) {
                    SmartieGhostButton(
                        text = "Emergency revoke",
                        danger = true,
                        onClick = { onPending(PendingAction.Revoke(person)) },
                    )
                } else if (canRemove) {
                    SmartieGhostButton(
                        text = "Remove",
                        danger = true,
                        onClick = { onPending(PendingAction.Remove(person)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleMenu(person: Member, options: List<Role>, onSelect: (Role) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        SmartieGhostButton(
            text = "Role: ${roleLabel(person.role)}",
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { role ->
                DropdownMenuItem(
                    text = { Text(roleLabel(role)) },
                    onClick = {
                        expanded = false
                        if (role != person.role) onSelect(role)
                    },
                )
            }
        }
    }
}

@Composable
private fun AuditRow(entry: TeamAuditEntry) {
    val detail = if (entry.detailFrom.isNotBlank() || entry.detailTo.isNotBlank()) {
        " (${entry.detailFrom} to ${entry.detailTo})"
    } else {
        ""
    }
    SmartieCard {
        Column {
            Text(
                entry.readableAction + detail,
                style = MaterialTheme.typography.titleSmall,
                color = SmartieColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.targetName.ifBlank { entry.targetEmail.ifBlank { "team" } }} · " +
                    "by ${entry.by.ifBlank { "Administrator" }} · ${formatMoment(entry.at)}",
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Steel,
            )
        }
    }
}

private fun roleLabel(role: Role): String = when (role) {
    Role.OWNER -> "Owner / Administrator"
    Role.ADMIN -> "Administrator"
    Role.STAFF -> "Staff"
    Role.WORKER -> "Worker"
}

private fun formatMoment(millis: Long): String =
    if (millis <= 0) "" else SimpleDateFormat("d MMM yyyy, h:mm a", Locale.forLanguageTag("en-IN"))
        .format(Date(millis))
