package `in`.smartie.quotedesk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/** A `.card`: white panel, 1dp rule border, 10dp radius. */
@Composable
fun SmartieCard(
    modifier: Modifier = Modifier,
    background: Color = SmartieColors.Panel,
    borderColor: Color = SmartieColors.Rule,
    accent: Color? = null,
    content: @Composable () -> Unit
) {
    val dimens = LocalSmartieDimens.current
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(dimens.radius))
            .background(background)
            .border(dimens.hairline, borderColor, RoundedCornerShape(dimens.radius))
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .width(dimens.accentBarWidth)
                    .fillMaxHeight()
                    .background(accent)
            )
        }
        Box(Modifier.fillMaxWidth().padding(dimens.cardPadding)) { content() }
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
