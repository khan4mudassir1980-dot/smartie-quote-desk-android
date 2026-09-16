package `in`.smartie.quotedesk.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * One category shelf's header, standing in for the PWA's `<details class="cat">`
 * summary: the shelf name, how many products are on it, and whether it is open.
 *
 * The products themselves are separate list items rather than children, so a
 * closed shelf costs nothing and 403 products never compose at once.
 */
@Composable
fun ShelfHeader(
    name: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = LocalSmartieDimens.current
    SmartieCard(
        modifier = modifier
            .clickableNoRipple(onClick)
            .semantics { stateDescription = if (expanded) "Open" else "Closed" },
        background = if (expanded) SmartieColors.PurpleTint else SmartieColors.Panel
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = SmartieColors.Purple
            )
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = SmartieColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Tag(count.toString(), if (count == 0) TagTone.NEUTRAL else TagTone.PURPLE)
        }
    }
}

/**
 * The bar above the bottom navigation showing what the quotation holds, as the
 * PWA's `#qbar` does: `Empty` and `₹0` until something is added.
 *
 * N2 fills the draft; turning it into a numbered, priced quotation is N5's.
 */
@Composable
fun QuoteBar(
    lineCount: Int,
    total: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    needsRate: Int = 0
) {
    val dimens = LocalSmartieDimens.current
    SmartieCard(modifier = modifier, accent = SmartieColors.Purple) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapM)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (lineCount == 0) "Empty" else "$lineCount in the quotation",
                    style = MaterialTheme.typography.titleSmall,
                    color = SmartieColors.Ink
                )
                Text(
                    if (needsRate > 0) "$total · $needsRate needs a rate" else total,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (needsRate > 0) SmartieColors.Warn else SmartieColors.Steel
                )
            }
            SmartiePrimaryButton(
                text = "View quote",
                onClick = onOpen,
                enabled = lineCount > 0
            )
        }
    }
}
