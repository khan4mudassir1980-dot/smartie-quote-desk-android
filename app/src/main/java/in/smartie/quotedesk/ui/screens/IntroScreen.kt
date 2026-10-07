package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.R
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/** What a screen reader says for the logo — the words on it. */
const val LOGO_LABEL: String = "Quote Desk"

/** The intro's one line, at its foot (the Owner left it to this side; N5.12b). */
const val INTRO_BYLINE: String = "by Smart India Enterprises"

/** The logo's size on the intro, and on sign-in above the card. */
val INTRO_LOGO_SIZE: Dp = 200.dp
val SIGN_IN_LOGO_SIZE: Dp = 140.dp

/**
 * The colour a brand screen paints behind itself, as a semantics property, so
 * a test can read what was painted without a screenshot.
 */
val BrandBackgroundKey = SemanticsPropertyKey<Color>("BrandBackground")
private var SemanticsPropertyReceiver.paintedBackground by BrandBackgroundKey

/**
 * The icon background, `#F7F4FF`, behind the intro and sign-in.
 *
 * Always this colour, in dark mode too: it is a fixed brand colour, never a
 * theme colour, so nothing about the phone's theme can change it.
 */
fun Modifier.brandBackground(color: Color = SmartieColors.IconBackground): Modifier =
    background(color).semantics { paintedBackground = color }

/** The Quote Desk logo — `QD_full_colour` from `branding/` — at [size] square. */
@Composable
fun QuoteDeskLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.qd_logo),
        contentDescription = LOGO_LABEL,
        modifier = modifier.size(size),
    )
}

/**
 * The opening intro (N5.12b): the logo, centred on the icon background, with
 * one line at the foot.
 *
 * It is the loading screen and nothing more. There is no timer, no minimum
 * time and nothing to tap: it is on screen exactly while the session is
 * loading, and once the session is known the next screen is composed in its
 * place straight away — `IntroScreenTest` holds that to two frames of the
 * test's clock. It follows Android 12's splash, which shows the icon on the
 * same colour.
 */
@Composable
fun IntroScreen() {
    Box(
        modifier = Modifier.fillMaxSize().brandBackground(),
        contentAlignment = Alignment.Center,
    ) {
        QuoteDeskLogo(INTRO_LOGO_SIZE)
        Text(
            INTRO_BYLINE,
            style = MaterialTheme.typography.labelMedium,
            color = SmartieColors.Navy,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        )
    }
}
