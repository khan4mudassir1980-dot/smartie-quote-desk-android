package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.ui.AppDataViewModel
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.InDevelopmentBanner
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens

@Composable
fun ProductsScreen(data: AppDataViewModel) {
    val products by data.products.collectAsStateWithLifecycle()
    val dimens = LocalSmartieDimens.current
    var query by remember { mutableStateOf("") }

    val visible = remember(products, query) {
        val needle = query.trim().lowercase()
        products.asSequence()
            .filter { it.active }
            .filter {
                needle.isEmpty() ||
                    it.model.lowercase().contains(needle) ||
                    it.name.lowercase().contains(needle) ||
                    it.spec.lowercase().contains(needle)
            }
            .take(300)
            .toList()
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
                phase = "N2",
                detail = "The full catalogue, pinned shelves, the quantity stepper and the quote " +
                    "bar arrive with the Products phase."
            )
        }
        item {
            SmartieField(
                label = "Search",
                value = query,
                onValueChange = { query = it },
                placeholder = "Model, name or specification"
            )
        }
        if (visible.isEmpty()) {
            item {
                EmptyState(
                    if (products.isEmpty()) {
                        "No products have been shared to this database yet."
                    } else {
                        "No product matches that search."
                    }
                )
            }
        }
        items(visible, key = { it.documentId }) { product -> ProductRow(product) }
    }
}

@Composable
private fun ProductRow(product: ProductRecord) {
    ListRow(
        title = product.model.ifBlank { product.name },
        secondary = product.name.takeIf { it.isNotBlank() && it != product.model },
        meta = product.spec.takeIf { it.isNotBlank() },
        tags = {
            Tag(priceLabel("Dealer", product.dealer), priceTone(product.dealer))
            Tag(priceLabel("Client", product.client), priceTone(product.client))
        },
        trailing = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Tag(product.unit, TagTone.NEUTRAL)
            }
        }
    )
}

/** A missing price is "not set", never zero (audit P2). */
private fun priceLabel(tier: String, price: Double?): String =
    if (price == null) "$tier not set" else "$tier ${Money.formatRupees(price)}"

private fun priceTone(price: Double?): TagTone =
    if (price == null) TagTone.WARN else TagTone.NEUTRAL
