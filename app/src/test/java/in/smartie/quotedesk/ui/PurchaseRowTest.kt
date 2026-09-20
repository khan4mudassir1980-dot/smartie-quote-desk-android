package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.ui.components.NOTE_TAG
import `in`.smartie.quotedesk.ui.screens.PurchaseRow
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A Purchase requirement card: its note and its urgency.
 *
 * The first staging pass found notes entered but never shown, and urgency
 * invisible without opening Edit.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun requirement(
        note: String = "",
        urgency: UrgencyV2 = UrgencyV2.CRITICAL
    ) = PurchaseRecord(
        id = "req-1",
        name = "Gate motor bracket",
        quantity = 4.0,
        urgency = urgency,
        note = note,
        by = "Asha",
        createdAt = 1_700_000_000_000L
    )

    private fun show(item: PurchaseRecord) {
        compose.setContent { SmartieTheme { PurchaseRow(item) } }
    }

    @Test
    fun `a saved note is shown on the card`() {
        show(requirement(note = "Two for the Andheri site"))
        compose.onNodeWithTag(NOTE_TAG).assertExists()
        compose.onNodeWithText("Two for the Andheri site").assertExists()
    }

    @Test
    fun `no note means no empty note line`() {
        show(requirement())
        compose.onNodeWithText("Gate motor bracket").assertExists()
        compose.onNodeWithText("4 needed").assertExists()
        // Not an empty line: no line at all.
        compose.onAllNodesWithTag(NOTE_TAG).assertCountEquals(0)
    }

    @Test
    fun `the urgency is on the card in the Owner's words`() {
        show(requirement(urgency = UrgencyV2.CRITICAL))
        compose.onNodeWithText("Very urgent").assertExists()
    }

    @Test
    fun `each urgency carries its own wording`() {
        show(requirement(urgency = UrgencyV2.URGENT))
        compose.onNodeWithText("Can wait 1-2 days").assertExists()
    }

    @Test
    fun `the calmest urgency is named too`() {
        show(requirement(urgency = UrgencyV2.NORMAL))
        compose.onNodeWithText("Needed, but not now").assertExists()
    }
}
