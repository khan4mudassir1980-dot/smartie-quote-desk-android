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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    /**
     * A shared note somebody typed. Passed only when non-empty — a row must
     * never show an empty note placeholder.
     */
    note: String? = null,
    meta: String? = null,
    tags: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    /**
     * Controls under the row, inside the same card.
     *
     * **Null rather than an empty lambda**, because the slot carries padding:
     * an empty one would add it to every card in the app and thin every
     * board by eight pixels. Null composes nothing at all, so every existing
     * caller lays out exactly as it did.
     *
     * It exists because a requirement's actions belong to its own card, and a
     * second card per item would thin the board anyway — the same reason a
     * stock card's height is treated as a feature.
     */
    footer: (@Composable () -> Unit)? = null,
    background: Color = SmartieColors.Panel,
    accent: Color? = null,
    /**
     * The figure a person came to the card to read, set larger and bolder.
     *
     * Off by default: on most rows the secondary line is an email address or
     * a model code, and emphasising those would just make every card shout.
     * On a requirement it is `10 required · 4 received · 6 remaining`, which
     * is the whole point of the card, so it is on there — and it wraps to two
     * lines rather than ellipsizing, because a figure cut off mid-sentence is
     * worse than a card one line taller.
     */
    emphasiseSecondary: Boolean = false,
    /** A note in its own tinted box, so it reads as somebody's words. */
    tintNote: Boolean = false,
    padding: Dp = LocalSmartieDimens.current.cardPadding,
    onClick: (() -> Unit)? = null
) {
    val dimens = LocalSmartieDimens.current
    SmartieCard(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickableNoRipple(onClick) else Modifier
        ),
        background = background,
        accent = accent,
        padding = padding
    ) {
        // **A Column, and it has to be.** `SmartieCard` puts its content in a
        // `Box`, which stacks its children — so with two of them the footer
        // was drawn on top of the information rather than under it, and the
        // card's `IntrinsicSize.Min` height was the taller of the two rather
        // than their sum, which clipped the lower text. One child again, and
        // a card with no footer lays out exactly as it always did.
        Column(Modifier.fillMaxWidth()) {
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
                            style = if (emphasiseSecondary) {
                                MaterialTheme.typography.titleSmall
                            } else {
                                MaterialTheme.typography.bodySmall
                            },
                            color = if (emphasiseSecondary) {
                                SmartieColors.Ink
                            } else {
                                SmartieColors.Steel
                            },
                            fontWeight = if (emphasiseSecondary) FontWeight.Bold else null,
                            maxLines = if (emphasiseSecondary) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!note.isNullOrBlank()) {
                        val noteText = @Composable {
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                color = SmartieColors.Ink2,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                // A tag, not a content description: a
                                // description would be read out *instead of*
                                // the note.
                                modifier = Modifier.testTag(NOTE_TAG)
                            )
                        }
                        if (tintNote) {
                            Box(
                                Modifier
                                    .padding(top = dimens.gapXs)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(dimens.radiusSmall))
                                    .background(SmartieColors.PurpleTint)
                                    .padding(horizontal = dimens.gapS, vertical = dimens.gapXs)
                            ) {
                                noteText()
                            }
                        } else {
                            Box(Modifier.padding(top = dimens.gapXs)) { noteText() }
                        }
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
            // Its own section under the information, never beside or over it.
            if (footer != null) Box(Modifier.padding(top = dimens.gapS)) { footer() }
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

/** Marks the note line, so a test can prove there is none when there is none. */
const val NOTE_TAG: String = "row-note"
