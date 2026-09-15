package `in`.smartie.quotedesk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

val SmartiePurple = Color(0xFF7125DC)
val SmartiePurpleDark = Color(0xFF4F168F)
val SmartieGreen = Color(0xFF087A34)
val SmartieOrange = Color(0xFFF4A13A)
val SmartieInk = Color(0xFF1D2735)
val SmartieSurface = Color(0xFFF8F7FC)

private val Colors = lightColorScheme(
    primary = SmartiePurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE2FF),
    onPrimaryContainer = SmartiePurpleDark,
    secondary = SmartieGreen,
    tertiary = SmartieOrange,
    background = SmartieSurface,
    surface = Color.White,
    onSurface = SmartieInk,
    error = Color(0xFFB42318),
)

private val CompactTypography = Typography(
    displayLarge = Typography().displayLarge.copy(fontSize = 42.sp, lineHeight = 48.sp),
    displayMedium = Typography().displayMedium.copy(fontSize = 36.sp, lineHeight = 42.sp),
    displaySmall = Typography().displaySmall.copy(fontSize = 31.sp, lineHeight = 37.sp),
    headlineLarge = Typography().headlineLarge.copy(fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = Typography().headlineMedium.copy(fontSize = 25.sp, lineHeight = 31.sp),
    headlineSmall = Typography().headlineSmall.copy(fontSize = 21.sp, lineHeight = 27.sp),
    titleLarge = Typography().titleLarge.copy(fontSize = 19.sp, lineHeight = 25.sp),
    titleMedium = Typography().titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = Typography().titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = Typography().labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = Typography().labelMedium.copy(fontSize = 11.sp, lineHeight = 16.sp),
    labelSmall = Typography().labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
)

@Composable
fun SmartieTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1f)),
    ) {
        MaterialTheme(colorScheme = Colors, typography = CompactTypography, content = content)
    }
}
