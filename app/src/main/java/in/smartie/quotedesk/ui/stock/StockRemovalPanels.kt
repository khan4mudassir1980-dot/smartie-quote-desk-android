package `in`.smartie.quotedesk.ui.stock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.StoppedStockRecord
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * Removing an item, and what is left behind.
 *
 * Panels rather than dialog bodies, for the reason recorded in
 * `docs/PROJECT-STATUS.md`: a Compose `Dialog` opens its own window with its
 * own recomposer, which the Robolectric test clock does not drive. Each
 * wrapper holds no logic.
 */

/** Everything the removal and history surfaces can do. */
data class StockRemovalActions(
    val onRemove: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onToggleHistory: () -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onConfirmClear: () -> Unit = {}
)

/**
 * The confirmation. It says what is lost, in the words the Owner approved,
 * because "remove" on its own does not convey that a photo goes with it.
 */
@Composable
internal fun StockRemoveConfirmPanel(
    online: Boolean = true,
    saving: Boolean = false,
    actions: StockRemovalActions = StockRemovalActions()
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            REMOVE_WARNING,
            style = MaterialTheme.typography.bodyLarge,
            color = SmartieColors.Ink
        )
        if (!online) {
            Text(
                REMOVE_OFFLINE,
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Warn
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartieGhostButton(text = "Cancel", onClick = actions.onCancel, enabled = !saving)
            SmartiePrimaryButton(
                text = if (saving) "Removing…" else REMOVE_FROM_STOCK,
                onClick = actions.onRemove,
                enabled = online && !saving,
                modifier = Modifier.semantics {
                    contentDescription = when {
                        !online -> REMOVE_OFFLINE
                        saving -> "Removing the item"
                        else -> CONFIRM_REMOVE
                    }
                }
            )
        }
    }
}

/**
 * What has been removed, at the very bottom of the board.
 *
 * Collapsed by default and read-only for everybody. There is deliberately no
 * Restore: the Owner withdrew that design, and a row here is a record of
 * something that is gone, not a thing to act on. Rows carry no click.
 *
 * What is **not** here is as deliberate as what is: no date, no time, no
 * "stopped by", no photo, no price and no note. The timestamps and the
 * author's uid exist in the document — ordering needs one, the rules need the
 * other — and neither reaches this screen.
 */
@Composable
internal fun StoppedHistorySection(
    entries: List<StoppedStockRecord>,
    expanded: Boolean = false,
    canClear: Boolean = false,
    online: Boolean = true,
    actions: StockRemovalActions = StockRemovalActions()
) {
    val dimens = LocalSmartieDimens.current
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(dimens.gapXs)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .clickable(onClick = actions.onToggleHistory)
                .semantics { contentDescription = stoppedHeading(entries.size) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stoppedHeading(entries.size),
                style = MaterialTheme.typography.titleSmall,
                color = SmartieColors.Ink
            )
            Text(
                if (expanded) "Hide" else "Show",
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Purple
            )
        }

        if (!expanded) return@Column

        if (entries.isEmpty()) {
            Text(
                NOTHING_STOPPED,
                style = MaterialTheme.typography.bodySmall,
                color = SmartieColors.Steel
            )
            return@Column
        }

        entries.forEach { StoppedHistoryRow(it) }

        if (canClear) {
            SmartieGhostButton(
                text = CLEAR_HISTORY,
                onClick = actions.onClearHistory,
                enabled = online,
                danger = true,
                modifier = Modifier
                    .padding(top = dimens.gapXs)
                    .semantics {
                        contentDescription = if (online) CLEAR_HISTORY else REMOVE_OFFLINE
                    }
            )
        }
    }
}

/**
 * One removed item: what it was, where it came from, how many there were.
 *
 * Not clickable, and carrying no action — there is nothing to do with it.
 */
@Composable
internal fun StoppedHistoryRow(entry: StoppedStockRecord) {
    SmartieCard(
        background = SmartieColors.Panel2,
        modifier = Modifier.semantics { contentDescription = stoppedRowLabel(entry.label) }
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = SmartieColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    entry.source,
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartieColors.Steel2
                )
            }
            Text(
                lastQuantity(entry),
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Steel
            )
        }
    }
}

/** The confirmation for clearing, which names how much is about to go. */
@Composable
internal fun ClearHistoryConfirmPanel(
    count: Int,
    saving: Boolean = false,
    actions: StockRemovalActions = StockRemovalActions()
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            clearHistoryQuestion(count),
            style = MaterialTheme.typography.bodyLarge,
            color = SmartieColors.Ink
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartieGhostButton(text = "Cancel", onClick = actions.onCancel, enabled = !saving)
            SmartiePrimaryButton(
                text = if (saving) "Clearing…" else CLEAR_HISTORY,
                onClick = actions.onConfirmClear,
                enabled = !saving,
                modifier = Modifier.semantics { contentDescription = CONFIRM_CLEAR }
            )
        }
    }
}

// --- wording -------------------------------------------------------------

internal const val REMOVE_FROM_STOCK: String = "Remove from stock"

internal const val REMOVE_TITLE: String = "Remove this item from stock?"

internal const val REMOVE_WARNING: String =
    "Its quantity, note and photo will be permanently deleted. " +
        "A basic record will remain in stopped-item history."

internal const val REMOVE_OFFLINE: String = "Internet required to remove an item"

internal const val REMOVED: String = "Removed from stock"

internal const val CLEAR_HISTORY: String = "Clear history"

internal const val HISTORY_CLEARED: String = "Stopped-item history cleared."

internal const val NOTHING_STOPPED: String = "Nothing has been removed from stock."

/** Named for the tests, which must find the confirm rather than the opener. */
internal const val CONFIRM_REMOVE: String = "Confirm removing this item from stock"

internal const val CONFIRM_CLEAR: String = "Confirm clearing stopped-item history"

internal fun stoppedHeading(count: Int): String = "Stopped items ($count)"

internal fun stoppedRowLabel(name: String): String = "Stopped item $name"

internal fun lastQuantity(entry: StoppedStockRecord): String =
    "Last: ${Money.formatQuantity(entry.quantity)} ${entry.unit}".trim()

internal fun clearHistoryQuestion(count: Int): String =
    "Permanently clear $count stopped-item history ${if (count == 1) "entry" else "entries"}?"
