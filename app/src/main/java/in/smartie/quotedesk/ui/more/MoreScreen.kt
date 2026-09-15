package `in`.smartie.quotedesk.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

@Composable
fun MoreScreen(
    member: Member,
    onOpen: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val dimens = LocalSmartieDimens.current
    val entries = MoreMenu.visibleTo(member)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = dimens.screenPadding,
            end = dimens.screenPadding,
            top = dimens.gapM,
            bottom = dimens.listBottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS),
    ) {
        item { AccountCard(member, onSignOut) }

        items(entries, key = { it.route }) { entry ->
            ListRow(
                title = entry.label,
                secondary = entry.description,
                onClick = { onOpen(entry.route) },
                tags = {
                    entry.phase?.let { Tag("In development · $it", TagTone.WARN) }
                },
            )
        }
    }
}

@Composable
private fun AccountCard(member: Member, onSignOut: () -> Unit) {
    SmartieCard {
        Column(Modifier.fillMaxWidth()) {
            Text(
                member.name.ifBlank { member.email },
                style = MaterialTheme.typography.titleMedium,
                color = SmartieColors.Ink,
            )
            Text(
                member.email,
                style = MaterialTheme.typography.bodySmall,
                color = SmartieColors.Steel,
            )
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tag(member.roleLabel, if (member.isOwner) TagTone.PURPLE else TagTone.NEUTRAL)
                member.ownerSubLabel?.let { Tag(it, TagTone.PURPLE) }
            }
            SmartieGhostButton(
                text = "Sign out",
                onClick = onSignOut,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
