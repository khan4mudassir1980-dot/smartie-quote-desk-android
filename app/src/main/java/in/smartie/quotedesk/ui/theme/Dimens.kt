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
    val tricolourHeight: Dp = 3.dp,
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
    val listBottomInset: Dp = 88.dp
)

/** Narrow-screen (<= 360dp) variant: only the stepper shrinks, as in the PWA. */
fun SmartieDimens.forWidth(widthDp: Int): SmartieDimens =
    if (widthDp <= 360) copy(
        stepperButtonWidth = stepperButtonWidthNarrow,
        stepperHeight = stepperHeightNarrow
    ) else this

val LocalSmartieDimens = staticCompositionLocalOf { SmartieDimens() }
