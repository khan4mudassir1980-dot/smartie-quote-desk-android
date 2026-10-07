package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.ui.screens.INTRO_BYLINE
import `in`.smartie.quotedesk.ui.screens.LOGO_LABEL
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The intro is on screen while the session loads, and only then (N5.12b).
 *
 * "No fixed delay, no minimum time, no 'continue' tap": when the session
 * becomes known, the test holds the clock and lets **two frames** pass — 32ms
 * of the test's time: one for the change to reach the recomposer, one to
 * draw — and the next screen must already be there. A timer, a minimum, an
 * animation out or a tap would each leave the intro behind.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class IntroScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var session by mutableStateOf<SessionState>(SessionState.Loading)

    private fun show() {
        compose.setContent {
            SmartieTheme {
                SessionScreens(
                    session = session,
                    signedOut = { Text(SIGNED_OUT) },
                    ready = { member -> Text("$READY ${member.name}") },
                    onSignOut = {},
                )
            }
        }
    }

    /** The session moves on; the clock is held, and two frames pass, no more. */
    private fun becomes(next: SessionState) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            session = next
            Snapshot.sendApplyNotifications()
        }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun `while the session loads, the intro is the screen - the logo and its line`() {
        show()
        compose.onNodeWithContentDescription(LOGO_LABEL).assertIsDisplayed()
        compose.onNodeWithText(INTRO_BYLINE).assertIsDisplayed()
    }

    @Test
    fun `two frames after the person is known to be signed out, sign-in is there and the intro is gone`() {
        show()
        compose.onNodeWithContentDescription(LOGO_LABEL).assertIsDisplayed()

        becomes(SessionState.SignedOut)

        compose.onNodeWithText(SIGNED_OUT).assertExists()
        compose.onNodeWithContentDescription(LOGO_LABEL).assertDoesNotExist()
        compose.onNodeWithText(INTRO_BYLINE).assertDoesNotExist()
    }

    @Test
    fun `two frames after they are known to be signed in, the app is there and the intro is gone`() {
        show()
        compose.onNodeWithContentDescription(LOGO_LABEL).assertIsDisplayed()

        becomes(SessionState.Ready(Member(uid = "uid-intro", name = "Test Person")))

        compose.onNodeWithText("$READY Test Person").assertExists()
        compose.onNodeWithContentDescription(LOGO_LABEL).assertDoesNotExist()
    }

    @Test
    fun `a session already known never shows the intro`() {
        session = SessionState.SignedOut
        show()
        compose.onNodeWithText(SIGNED_OUT).assertExists()
        compose.onNodeWithContentDescription(LOGO_LABEL).assertDoesNotExist()
    }

    private companion object {
        const val SIGNED_OUT = "the sign-in slot"
        const val READY = "the app slot for"
    }
}
