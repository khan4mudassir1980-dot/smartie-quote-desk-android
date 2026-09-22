package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The two shared pieces every card in the app is built from, at 360dp, with
 * content long enough to wrap.
 *
 * The Products and Team cards have no footer and no `FlowRow`, and neither
 * carries a handle a test could use to find it, so they are covered here at
 * the level they actually share: `ListRow` **is** the Team card, and
 * `SmartieCard` is what the Products card is drawn in. What those two screens
 * do with them is a phone row (P-B1d), and the checklist says so rather than
 * this class implying otherwise.
 *
 * The point of the class is that N4.4 changed `SmartieCard` for every screen
 * at once, so the component itself is asserted rather than only the one card
 * whose defect was reported.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class SharedCardClippingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val ACTION = "the only control"
        const val LONG_TITLE = "Sliding gate rack 1 m, galvanised, with the long name"
        const val LONG_NOTE =
            "For the Kandivali site, second floor, and it is needed before the " +
                "installers come back on Thursday morning"
    }

    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            SmartieTheme {
                Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Box(Modifier.testTag(CARD_HOST_TAG)) { content() }
                }
            }
        }
    }

    private fun action() {
        SmartieGhostButton(
            text = "Do it",
            onClick = {},
            modifier = Modifier.semantics { contentDescription = ACTION }
        )
    }

    @Test
    fun `a list row with everything on it paints its footer inside the card`() {
        host {
            ListRow(
                title = LONG_TITLE,
                secondary = "10 required · 4 received · 6 remaining",
                note = LONG_NOTE,
                meta = "Added by Mudassir Khan · 22 Sep 2026",
                accent = SmartieColors.Warn,
                tags = { Tag("Needed", TagTone.PURPLE) },
                trailing = { Text("4 in") },
                footer = { action() }
            )
        }

        compose.assertPaintedInsideCard(ACTION)
        compose.assertNoDeadSpaceBelow(ACTION, compose.cardBounds())
    }

    @Test
    fun `and one with no footer still wraps its own content`() {
        // The Team card's shape: title, secondary, tags, no footer. A card
        // that wraps nothing at all must not gain height it does not need.
        host {
            ListRow(
                title = LONG_TITLE,
                secondary = "someone.with.a.long.address@example.com",
                tags = { Tag("Manager", TagTone.NEUTRAL) },
                onClick = {}
            )
        }

        // The tag is the last thing on the card, so the only space under it
        // should be its own padding and the card's.
        compose.assertNoDeadSpaceBelowText("Manager", compose.cardBounds())
    }

    @Test
    fun `a bare card with an accent wraps its content and paints the control`() {
        // The Products card's shape: `SmartieCard` with its own column inside
        // and an accent bar, which is the thing that used to need the
        // intrinsic height.
        host {
            SmartieCard(accent = SmartieColors.Purple) {
                Column(Modifier.fillMaxWidth()) {
                    Text(LONG_TITLE)
                    Text(LONG_NOTE)
                    action()
                }
            }
        }

        compose.assertPaintedInsideCard(ACTION)
        compose.assertNoDeadSpaceBelow(ACTION, compose.cardBounds())
    }
}
