package `in`.smartie.quotedesk.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens copied from the approved PWA (V8C4 `index.html:22-42`).
 *
 * The beta theme used `#7125DC`; the approved purple is `#6D28D9`. Every value
 * here is the PWA's, so a native screen and a PWA screen can be compared side
 * by side without a colour difference.
 */
object SmartieColors {
    val Purple = Color(0xFF6D28D9)
    val PurpleDark = Color(0xFF4C1D95)
    val PurpleLight = Color(0xFFEDE9FE)
    val PurpleLine = Color(0xFFD8CCF5)

    /** Row/card wash used for pinned items. */
    val PurpleTint = Color(0xFFF5F2FE)

    val Ink = Color(0xFF1F2937)
    val Ink2 = Color(0xFF374151)
    val Steel = Color(0xFF6B7280)
    val Steel2 = Color(0xFF9099A8)

    val Rule = Color(0xFFE3DEF2)
    val Rule2 = Color(0xFFEFECF8)

    val Paper = Color(0xFFF8F7FC)
    val Panel = Color(0xFFFFFFFF)
    val Panel2 = Color(0xFFF6F4FC)

    val Success = Color(0xFF15803D)
    val SuccessSoft = Color(0xFFE7F6EC)
    val SuccessDeep = Color(0xFF166534)
    val SuccessLine = Color(0xFFBFE2CD)

    val Warn = Color(0xFFB45309)
    val WarnSoft = Color(0xFFFEF3C7)
    val WarnLine = Color(0xFFF0D9A0)

    val Danger = Color(0xFFB91C1C)
    val DangerSoft = Color(0xFFFEE2E2)
    val DangerLine = Color(0xFFF3C9C4)

    /** Urgency colours: very urgent red, urgent yellow/amber, normal green. */
    val UrgencyCritical = Danger
    val UrgencyUrgent = Color(0xFFEAB308)
    val UrgencyNormal = Success

    /** The one deliberately non-purple detail: the 3dp tricolour strip. */
    val TricolourSaffron = Color(0xFFFF9933)
    val TricolourWhite = Color(0xFFFFFFFF)
    val TricolourGreen = Color(0xFF138808)
}

val SmartieLightColorScheme = lightColorScheme(
    primary = SmartieColors.Purple,
    onPrimary = Color.White,
    primaryContainer = SmartieColors.PurpleLight,
    onPrimaryContainer = SmartieColors.PurpleDark,
    secondary = SmartieColors.Success,
    onSecondary = Color.White,
    secondaryContainer = SmartieColors.SuccessSoft,
    onSecondaryContainer = SmartieColors.SuccessDeep,
    tertiary = SmartieColors.Warn,
    onTertiary = Color.White,
    tertiaryContainer = SmartieColors.WarnSoft,
    onTertiaryContainer = SmartieColors.Warn,
    background = SmartieColors.Paper,
    onBackground = SmartieColors.Ink,
    surface = SmartieColors.Panel,
    onSurface = SmartieColors.Ink,
    surfaceVariant = SmartieColors.Panel2,
    onSurfaceVariant = SmartieColors.Steel,
    outline = SmartieColors.Rule,
    outlineVariant = SmartieColors.Rule2,
    error = SmartieColors.Danger,
    onError = Color.White,
    errorContainer = SmartieColors.DangerSoft,
    onErrorContainer = SmartieColors.Danger
)
