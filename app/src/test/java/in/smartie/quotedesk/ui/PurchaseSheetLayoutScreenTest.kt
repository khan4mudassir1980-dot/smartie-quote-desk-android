package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.ui.purchase.ADD_REQUIREMENT
import `in`.smartie.quotedesk.ui.purchase.AddRequirementPanel
import `in`.smartie.quotedesk.ui.purchase.CANCEL
import `in`.smartie.quotedesk.ui.purchase.NAME_LABEL
import `in`.smartie.quotedesk.ui.purchase.NOTE_LABEL
import `in`.smartie.quotedesk.ui.purchase.QUANTITY_LABEL
import `in`.smartie.quotedesk.ui.purchase.urgencyChipFace
import `in`.smartie.quotedesk.ui.purchase.urgencyOptionLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Add sheet on the narrowest phone the app supports, **without
 * scrolling**.
 *
 * The Owner's report: the Note field was off the bottom and the buttons went
 * behind the keyboard. Three full-width 48dp urgency rows were a third of the
 * sheet on their own, so the fix is a single row of chips — and the thing
 * worth pinning is not that the chips exist but that what they displaced is
 * now **visible where somebody first looks**, with no `performScrollTo()`
 * anywhere in this class.
 *
 * **What this class cannot check**, and no Robolectric test can: that the
 * fields and buttons clear the *real* keyboard. There is no IME here, so
 * `imePadding()` resolves to zero insets. T-D14 and T-D15 are phone rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseSheetLayoutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val SHEET_HOST = "sheet-host"
    }

    private fun sheet() {
        compose.setContent {
            SmartieTheme {
                Box(Modifier.testTag(SHEET_HOST).fillMaxSize()) {
                    AddRequirementPanel()
                }
            }
        }
    }

    private fun bottomOf(text: String) =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.bottom

    private fun host() = compose.onNodeWithTag(SHEET_HOST).fetchSemanticsNode().boundsInRoot

    @Test
    fun `the note field is visible without scrolling to it`() {
        sheet()

        // No performScrollTo. This is the whole point of the class.
        compose.onNodeWithContentDescription(NOTE_LABEL).assertIsDisplayed()
    }

    @Test
    fun `and so are the name, the quantity and both buttons`() {
        sheet()

        compose.onNodeWithContentDescription(NAME_LABEL).assertIsDisplayed()
        compose.onNodeWithContentDescription(QUANTITY_LABEL).assertIsDisplayed()
        compose.onNodeWithText(CANCEL).assertIsDisplayed()
        compose.onNodeWithText(ADD_REQUIREMENT).assertIsDisplayed()
    }

    @Test
    fun `the whole sheet fits the window it is given`() {
        sheet()

        val panelBottom = bottomOf(ADD_REQUIREMENT)
        assertTrue(
            "the confirm ends at $panelBottom, past the window's ${host().bottom}",
            panelBottom <= host().bottom + 0.5f
        )
    }

    @Test
    fun `the three urgency chips share one row and each is a 48dp target`() {
        sheet()

        val tops = UrgencyV2.entries.map { option ->
            val chip = compose.onNodeWithContentDescription(urgencyOptionLabel(option))
            chip.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            chip.fetchSemanticsNode().boundsInRoot.top
        }

        assertTrue("three chips on one row, not three rows", tops.distinct().size == 1)
    }

    @Test
    fun `each chip's face is legible and the selected wording is shown in full`() {
        sheet()

        for (option in UrgencyV2.entries) {
            compose.onNodeWithText(urgencyChipFace(option)).assertIsDisplayed()
        }
        // Short faces on the chips; the Owner's wording, unabbreviated, below.
        compose.onNodeWithText(UrgencyV2.NORMAL.label).assertIsDisplayed()
    }

    @Test
    fun `the buttons sit below every field, never over one`() {
        sheet()

        val noteBottom = compose.onNodeWithContentDescription(NOTE_LABEL)
            .fetchSemanticsNode().boundsInRoot.bottom
        val cancelTop = compose.onNodeWithText(CANCEL).fetchSemanticsNode().boundsInRoot.top

        assertTrue(
            "the note ends at $noteBottom and Cancel starts at $cancelTop",
            cancelTop >= noteBottom - 0.5f
        )
    }
}
