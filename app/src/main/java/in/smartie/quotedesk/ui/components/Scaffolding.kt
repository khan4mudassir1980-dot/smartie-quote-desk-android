package `in`.smartie.quotedesk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * The rule under the screen header: **one solid purple line**, full width, the
 * same on every screen.
 *
 * It replaced a saffron/white/green tricolour copied from the PWA masthead.
 * On a white header its white middle third read as a gap rather than a line,
 * and neither the saffron nor the green appeared anywhere else in the app.
 */
@Composable
fun HeaderRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(LocalSmartieDimens.current.headerRuleHeight)
            .background(SmartieColors.HeaderRule)
            .semantics { contentDescription = HEADER_RULE_LABEL }
    )
}

/** Names the rule for tests and for a screen reader. */
const val HEADER_RULE_LABEL: String = "Header rule"

/**
 * Page titles live in the app bar, not as an oversized heading inside the
 * content (audit U1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartieTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    Column(modifier) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartieColors.Steel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = { actions() },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = SmartieColors.Panel,
                titleContentColor = SmartieColors.Ink,
                navigationIconContentColor = SmartieColors.Ink2,
                actionIconContentColor = SmartieColors.Ink2
            )
        )
        HeaderRule()
    }
}

/**
 * A `.card`: white panel, 1dp rule border, 10dp radius, optional accent bar.
 *
 * **The accent bar is painted, not laid out**, and that is the whole point.
 * It used to be a sibling `Box` with `fillMaxHeight()`, which only works
 * inside a `Row` whose height is already known — so the card carried
 * `height(IntrinsicSize.Min)` for no other reason than to give that bar
 * something to fill.
 *
 * That cost far more than it bought. An intrinsic measurement asks a child
 * how tall it would be, and a `FlowRow` answers for the width it is asked
 * about rather than the width it finally gets. When the two disagree, the
 * card's height is fixed to the wrong one: too short and the clip below
 * erases whatever wrapped past it — which is how N4.4's B1 lost the last two
 * controls on every purchase card — too tall and the surplus shows as a hole
 * under the content, which the same phone pass reported and
 * `PurchaseCardClippingScreenTest` measured at 67px.
 *
 * `drawBehind` needs no intrinsic pass at all: the card wraps its content, and
 * the bar is drawn down the left edge of whatever height that turns out to be.
 * The clip stays — it rounds the bar's corners with the card's — but nothing
 * can overflow it any more.
 */
@Composable
fun SmartieCard(
    modifier: Modifier = Modifier,
    background: Color = SmartieColors.Panel,
    borderColor: Color = SmartieColors.Rule,
    accent: Color? = null,
    content: @Composable () -> Unit
) {
    val dimens = LocalSmartieDimens.current
    val shape = RoundedCornerShape(dimens.radius)
    val barWidth = dimens.accentBarWidth

    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(
                if (accent == null) {
                    Modifier
                } else {
                    Modifier.drawBehind {
                        drawRect(color = accent, size = Size(barWidth.toPx(), size.height))
                    }
                }
            )
            .border(dimens.hairline, borderColor, shape)
            // The bar's width first, so the content clears it exactly as it
            // did when the bar was a sibling taking up that space.
            .padding(start = if (accent == null) 0.dp else barWidth)
            .padding(dimens.cardPadding)
    ) {
        content()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = SmartieColors.Ink)
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = SmartieColors.Steel)
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = SmartieColors.Steel
        )
    }
}
