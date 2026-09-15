package `in`.smartie.quotedesk.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import `in`.smartie.quotedesk.R

/** Inter, bundled at the five weights the PWA loads (400/500/600/700/800). */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold)
)

/**
 * The PWA's compact scale: body 15, section heading 17, card title 15 bold,
 * secondary labels 12.5-13, bottom navigation labels 11.
 */
val SmartieTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.ExtraBold,
        fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.ExtraBold,
        fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = 14.5.sp, lineHeight = 19.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 19.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 12.5.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 14.sp
    )
)

/** Tag and chip text: 11.5sp semi-bold, as `.tag` in the PWA. */
val SmartieTagTextStyle = TextStyle(
    fontFamily = Inter, fontWeight = FontWeight.SemiBold,
    fontSize = 11.5.sp, lineHeight = 15.sp, textAlign = TextAlign.Center
)
