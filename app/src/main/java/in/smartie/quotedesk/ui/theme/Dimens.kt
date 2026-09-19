package `in`.smartie.quotedesk.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Control metrics from the approved PWA CSS. The beta used Material defaults
 * (56dp inputs, 80dp navigation bar, 48dp icon buttons), which is what made
 * every screen feel oversized next to the PWA.
 */
data class SmartieDimens(
    val buttonHeight: Dp = 42.dp,
    val buttonHeightCompact: Dp = 44.dp,
    val inputHeight: Dp = 46.dp,
    val stepperButtonWidth: Dp = 40.dp,
    val stepperHeight: Dp = 42.dp,
    val stepperButtonWidthNarrow: Dp = 38.dp,
    val stepperHeightNarrow: Dp = 40.dp,
    val bottomNavHeight: Dp = 56.dp,
    val topBarHeight: Dp = 56.dp,
    val headerRuleHeight: Dp = 3.dp,
    val accentBarWidth: Dp = 3.dp,
    val cardPadding: Dp = 15.dp,
    val screenPadding: Dp = 12.dp,
    val radius: Dp = 10.dp,
    val radiusSmall: Dp = 8.dp,
    val pillRadius: Dp = 20.dp,
    val hairline: Dp = 1.dp,
    val gapXs: Dp = 4.dp,
    val gapS: Dp = 8.dp,
    val gapM: Dp = 12.dp,
    val gapL: Dp = 16.dp,
    /** Keeps list content clear of the bottom bar, snackbars and the quote bar. */
    val listBottomInset: Dp = 88.dp,
    /** The back-to-top control: its size **is** its touch target. */
    val backToTopSize: Dp = 48.dp
)

/** Narrow-screen (<= 360dp) variant: only the stepper shrinks, as in the PWA. */
fun SmartieDimens.forWidth(widthDp: Int): SmartieDimens =
    if (widthDp <= 360) copy(
        stepperButtonWidth = stepperButtonWidthNarrow,
        stepperHeight = stepperHeightNarrow
    ) else this

/**
 * The **least** tall the bottom navigation's own content may be, at a given
 * font scale. Not a cap: an icon, its indicator and a label need more than
 * the compact height, and pinning the bar to it clips the label off.
 *
 * The system navigation's inset is **not** part of this. It is padding
 * *under* the bar, never height taken out of it — confusing the two is what
 * put the icons and labels behind the system navigation on a second phone.
 * See `SmartieBottomBar`.
 *
 * It rises with the effective font scale, as the label does, and never falls
 * below the compact height the PWA uses. Nothing here is measured per device.
 */
fun bottomNavMinHeight(base: Dp, fontScale: Float): Dp =
    base * fontScale.coerceIn(1f, MAX_EFFECTIVE_FONT_SCALE)

val LocalSmartieDimens = staticCompositionLocalOf { SmartieDimens() }
