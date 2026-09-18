package `in`.smartie.quotedesk.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import `in`.smartie.quotedesk.ui.theme.SmartieTagTextStyle

/**
 * The PWA's `.tag` family: neutral, `g` success, `w` warning, `r` danger and
 * `o` purple/info (`index.html:258-261, 711-713, 761`).
 */
enum class TagTone(val background: Color, val content: Color, val border: Color) {
    NEUTRAL(SmartieColors.Panel2, SmartieColors.Steel, SmartieColors.Rule),
    GREEN(SmartieColors.SuccessSoft, SmartieColors.SuccessDeep, SmartieColors.SuccessLine),
    WARN(SmartieColors.WarnSoft, SmartieColors.Warn, SmartieColors.WarnLine),
    DANGER(SmartieColors.DangerSoft, SmartieColors.Danger, SmartieColors.DangerLine),
    PURPLE(SmartieColors.PurpleLight, SmartieColors.PurpleDark, SmartieColors.PurpleLine)
}

@Composable
fun Tag(text: String, tone: TagTone = TagTone.NEUTRAL, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SmartieTagTextStyle,
        color = tone.content,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(tone.background)
            .border(1.dp, tone.border, RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** Status chip with a leading dot, as `.chip` with `ok/busy/off/bad`. */
enum class ChipState(val tone: TagTone, val dot: Color) {
    OK(TagTone.GREEN, SmartieColors.Success),
    BUSY(TagTone.PURPLE, SmartieColors.Purple),
    OFF(TagTone.WARN, SmartieColors.Warn),
    BAD(TagTone.DANGER, SmartieColors.Danger)
}

@Composable
fun StatusChip(text: String, state: ChipState, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(state.tone.background)
            .border(BorderStroke(1.dp, state.tone.border), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(state.dot))
        Text(text, style = SmartieTagTextStyle, color = state.tone.content, maxLines = 1)
    }
}

/**
 * A **filled** chip in one solid colour, for something that has to read at a
 * glance without being looked for.
 *
 * Purchase urgency uses it: the 3dp accent bar down the side of a card was
 * technically present but nobody saw it, so the first staging pass reported
 * the colours as missing.
 */
@Composable
fun FilledTag(text: String, colour: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SmartieTagTextStyle,
        color = SmartieColors.Panel,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(LocalSmartieDimens.current.pillRadius))
            .background(colour)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}
