package `in`.smartie.quotedesk.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * The one place an urgency becomes a colour: very urgent red, can-wait
 * yellow, not-needed-now green.
 *
 * It is a plain function rather than a `when` inside a composable so the three
 * colours can be asserted in an ordinary unit test — a rendered colour is not
 * something the Compose semantics tree reports.
 */
fun urgencyColour(urgency: UrgencyV2): Color = when (urgency) {
    UrgencyV2.CRITICAL -> SmartieColors.UrgencyCritical
    UrgencyV2.URGENT -> SmartieColors.UrgencyUrgent
    UrgencyV2.NORMAL -> SmartieColors.UrgencyNormal
}

/**
 * The urgency, filled in its own colour so it reads at a glance on the card.
 *
 * The 3dp accent bar down the side of the card was technically present but
 * nobody saw it, so the first staging pass reported the colours as missing.
 */
@Composable
fun UrgencyTag(urgency: UrgencyV2, modifier: Modifier = Modifier) {
    FilledTag(urgency.label, urgencyColour(urgency), modifier)
}
