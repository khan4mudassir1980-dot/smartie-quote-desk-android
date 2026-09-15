package `in`.smartie.quotedesk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Device text-size preference, mirroring the PWA's 100/110/120 setting. */
enum class TextSizePreference(val percent: Int) {
    NORMAL(100), LARGE(110), LARGER(120);

    val multiplier: Float get() = percent / 100f

    companion object {
        fun fromPercent(percent: Int?): TextSizePreference =
            entries.firstOrNull { it.percent == percent } ?: NORMAL
    }
}

/** The largest system font scale the compact layouts are tested against. */
const val MAX_SYSTEM_FONT_SCALE = 1.3f

/** Hard ceiling once the device preference is applied on top of the system scale. */
const val MAX_EFFECTIVE_FONT_SCALE = 1.5f

@Composable
fun SmartieTheme(
    textSize: TextSizePreference = TextSizePreference.NORMAL,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp

    // The beta capped the system font scale at 1.0, which ignored the user's
    // accessibility setting outright. Honour it up to 1.3 (the size the
    // layouts are tested at), then apply the in-app preference on top.
    val effectiveFontScale = (
        density.fontScale.coerceAtMost(MAX_SYSTEM_FONT_SCALE) * textSize.multiplier
        ).coerceIn(0.85f, MAX_EFFECTIVE_FONT_SCALE)

    CompositionLocalProvider(
        LocalDensity provides Density(density.density, effectiveFontScale),
        LocalSmartieDimens provides SmartieDimens().forWidth(widthDp)
    ) {
        MaterialTheme(
            colorScheme = SmartieLightColorScheme,
            typography = SmartieTypography,
            content = content
        )
    }
}
