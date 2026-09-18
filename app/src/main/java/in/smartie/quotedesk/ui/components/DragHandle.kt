package `in`.smartie.quotedesk.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * The six-dot grip that replaced the pinned shelf's `↑` and `↓` buttons.
 *
 * Long-press and drag is the gesture; the buttons it replaced were two more
 * taps on a card that already carries three. [accessibilityActions] keep the
 * same two moves reachable for anyone who cannot drag — as named actions on
 * this handle rather than as visible arrows.
 */
@Composable
fun DragHandle(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = SmartieColors.Steel,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList()
) {
    Canvas(
        modifier
            .size(width = 32.dp, height = 36.dp)
            .semantics {
                contentDescription = label
                if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
            }
    ) {
        val radius = 1.7.dp.toPx()
        val columnGap = 8.dp.toPx()
        val rowGap = 6.dp.toPx()
        val centreX = size.width / 2f
        val centreY = size.height / 2f
        for (column in -1..1 step 2) {
            for (row in -1..1) {
                drawCircle(
                    color = tint,
                    radius = radius,
                    center = Offset(centreX + column * columnGap / 2f, centreY + row * rowGap)
                )
            }
        }
    }
}
