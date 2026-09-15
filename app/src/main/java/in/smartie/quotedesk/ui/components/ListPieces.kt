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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/** Summary tile (`.stat`): big number over a small caption, tap to filter. */
@Composable
fun SummaryTile(
    caption: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: TagTone = TagTone.PURPLE,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val dimens = LocalSmartieDimens.current
    Column(
        modifier
            .clip(RoundedCornerShape(dimens.radius))
            .background(if (selected) tone.background else SmartieColors.Panel)
            .border(
                dimens.hairline,
                if (selected) tone.border else SmartieColors.Rule,
                RoundedCornerShape(dimens.radius)
            )
            .then(if (onClick != null) Modifier.clickableNoRipple(onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = SmartieColors.Steel,
            maxLines = 1
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = tone.content,
            maxLines = 1
        )
    }
}

/** Compact list row: title, optional secondary lines, tags and a trailing slot. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    meta: String? = null,
    tags: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    background: Color = SmartieColors.Panel,
    accent: Color? = null,
    onClick: (() -> Unit)? = null
) {
    val dimens = LocalSmartieDimens.current
    SmartieCard(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickableNoRipple(onClick) else Modifier
        ),
        background = background,
        accent = accent
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapM)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = SmartieColors.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (secondary != null) {
                    Text(
                        secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = SmartieColors.Steel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (meta != null) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartieColors.Steel2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(Modifier.padding(top = dimens.gapXs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags() }
                }
            }
            trailing()
        }
    }
}

/** Offline / reconnecting banner, shown above content (audit X11). */
@Composable
fun ConnectivityBanner(online: Boolean, modifier: Modifier = Modifier) {
    if (online) return
    val dimens = LocalSmartieDimens.current
    Row(
        modifier
            .fillMaxWidth()
            .background(SmartieColors.WarnSoft)
            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Offline. You can keep browsing and drafting; saving waits for the connection.",
            style = MaterialTheme.typography.labelMedium,
            color = SmartieColors.Warn
        )
    }
}
