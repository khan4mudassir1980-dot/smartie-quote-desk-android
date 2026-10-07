package `in`.smartie.quotedesk.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens, copied from the approved PWA (V8C4 `index.html:22-42`) —
 * except the brand colours the Owner set in N5.12b.
 *
 * Since N5.12b the purples are the brand's, not the PWA's: [Purple] is the
 * primary purple `#581FEB` (the PWA's was `#6D28D9`, the beta's `#7125DC`);
 * [PurpleDark] is the deep indigo `#3212BD`, the pressed colour, and takes the
 * PWA's dark purple's other roles too; [Highlight] is the light violet
 * `#A138FC`. [Navy] and [IconBackground] are the other two. Everything else is
 * still the PWA's. `BrandContrastTest` checks every pair they are drawn in.
 */
object SmartieColors {
    val Purple = Color(0xFF581FEB)
    val PurpleDark = Color(0xFF3212BD)

    /**
     * The light violet: what marks a **highlighted** thing — the field being
     * typed in, a stepper holding a change, a card being dragged. A line,
     * never text: on the page it is 4.39:1, short of AA for text.
     */
    val Highlight = Color(0xFFA138FC)

    /** The primary button's fill: the deep indigo while it is pressed. */
    fun primaryButton(pressed: Boolean): Color = if (pressed) PurpleDark else Purple

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

    /**
     * **Ordered** (N5.10b). The app had no blue — V8C4's palette is purple,
     * green, amber and red — and the Owner chose blue on 2026-10-05 because
     * green reads as "received". The same four roles as the green set; the
     * tag's text, [BlueDeep] on [BlueSoft], is checked against WCAG AA in
     * `TagContrastTest`.
     */
    val Blue = Color(0xFF1D4ED8)
    val BlueSoft = Color(0xFFE8EFFD)
    val BlueDeep = Color(0xFF1E40AF)
    val BlueLine = Color(0xFFBFD3F6)

    /**
     * **Brand colours** (the Owner, N5.12b). [IconBackground] is behind the
     * launcher icon, the Android 12 splash, the intro and sign-in — always,
     * dark mode included. [Navy] is the logo's "Quote", and the intro's line.
     * Their XML twin, for the icon and the window, is
     * `@color/ic_launcher_background`.
     */
    val IconBackground = Color(0xFFF7F4FF)
    val Navy = Color(0xFF08162C)

    /** Urgency colours: very urgent red, urgent yellow/amber, normal green. */
    val UrgencyCritical = Danger
    val UrgencyUrgent = Color(0xFFEAB308)
    val UrgencyNormal = Success

    /**
     * The rule under every screen header: one solid brand purple, full width.
     * It replaced a saffron/white/green tricolour, whose white middle third
     * read as a gap on a white header and whose segments belonged to no other
     * part of the app.
     */
    val HeaderRule = Purple
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
