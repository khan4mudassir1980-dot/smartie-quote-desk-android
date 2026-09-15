package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.ui.AppDataViewModel
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.InDevelopmentBanner
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SummaryTile
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

@Composable
fun StockScreen(data: AppDataViewModel) {
    val stock by data.stock.collectAsStateWithLifecycle()
    val dimens = LocalSmartieDimens.current
    var query by remember { mutableStateOf("") }

    val visible = remember(stock, query) {
        val needle = query.trim().lowercase()
        stock.filter {
            needle.isEmpty() ||
                it.name.lowercase().contains(needle) ||
                it.model.lowercase().contains(needle) ||
                it.key.lowercase().contains(needle)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(
            start = dimens.screenPadding,
            end = dimens.screenPadding,
            top = dimens.gapM,
            bottom = dimens.listBottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS)
    ) {
        item {
            InDevelopmentBanner(
                phase = "N3",
                detail = "Adding and subtracting stock returns with the Our Stock phase, with the " +
                    "pending +/- flow, Done, an optional note and full history."
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
            ) {
                SummaryTile(
                    caption = "Tracked",
                    value = stock.size.toString(),
                    tone = TagTone.PURPLE,
                    modifier = Modifier.weight(1f)
                )
                SummaryTile(
                    caption = "Low",
                    value = stock.count { it.isLow }.toString(),
                    tone = TagTone.WARN,
                    modifier = Modifier.weight(1f)
                )
                SummaryTile(
                    caption = "Out",
                    value = stock.count { it.isOut }.toString(),
                    tone = TagTone.DANGER,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            SmartieField(
                label = "Search",
                value = query,
                onValueChange = { query = it },
                placeholder = "Name, model or category"
            )
        }
        if (visible.isEmpty()) {
            item { EmptyState("Nothing is being tracked yet.") }
        }
        items(visible, key = { it.documentId }) { item -> StockRow(item) }
    }
}

@Composable
private fun StockRow(item: StockRecord) {
    ListRow(
        title = item.name.ifBlank { item.model.ifBlank { item.key } },
        secondary = item.model.takeIf { it.isNotBlank() && it != item.name },
        background = if (item.pinned) SmartieColors.PurpleTint else SmartieColors.Panel,
        accent = when {
            item.isOut -> SmartieColors.Danger
            item.isLow -> SmartieColors.Warn
            else -> null
        },
        tags = {
            when {
                item.isOut -> Tag("Out of stock", TagTone.DANGER)
                item.isLow -> Tag("Low", TagTone.WARN)
                else -> Tag("In stock", TagTone.GREEN)
            }
            if (item.manual) Tag("Manual", TagTone.NEUTRAL)
            if (item.pinned) Tag("Tracked", TagTone.PURPLE)
        },
        trailing = {
            Text(
                "${Money.formatQuantity(item.quantity)} ${item.unit}",
                style = MaterialTheme.typography.titleMedium,
                color = SmartieColors.Ink
            )
        }
    )
}
