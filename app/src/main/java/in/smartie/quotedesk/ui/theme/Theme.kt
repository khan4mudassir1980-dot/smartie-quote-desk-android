package `in`.smartie.quotedesk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

@Composable
fun SmartieTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
