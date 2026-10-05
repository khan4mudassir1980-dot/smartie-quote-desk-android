package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.domain.PurchaseAccess
import `in`.smartie.quotedesk.ui.purchase.EditRequirementPanel
import `in`.smartie.quotedesk.ui.purchase.NAME_LABEL
import `in`.smartie.quotedesk.ui.purchase.NOTE_LABEL
import `in`.smartie.quotedesk.ui.purchase.QUANTITY_LABEL
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Edit sheet on an Ordered requirement (the Owner's QD4): for a Manager or
 * the Staff creator, what and how many are shown and locked, with the reason;
 * the note and the urgency stay theirs. The view model decides the lock
 * (`PurchaseViewModelTest`); this is what the sheet does with it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseEditLockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val ordered = requirement("pr_one", name = "Sliding gate rack", quantity = 10.0)
        .copy(status = "Ordered")

    @Test
    fun `locked, what and how many are disabled and the sheet says why`() {
        compose.setContent {
            SmartieTheme { EditRequirementPanel(record = ordered, whatAndHowManyLocked = true) }
        }

        compose.field(NAME_LABEL).assertIsNotEnabled()
        compose.field(QUANTITY_LABEL).assertIsNotEnabled()
        compose.field(NOTE_LABEL).assertIsEnabled()
        compose.onNodeWithText(PurchaseAccess.ORDERED_WHAT_AND_HOW_MANY).assertIsDisplayed()
    }

    @Test
    fun `unlocked, an Owner or Administrator edits them as before`() {
        compose.setContent {
            SmartieTheme { EditRequirementPanel(record = ordered, whatAndHowManyLocked = false) }
        }

        compose.field(NAME_LABEL).assertIsEnabled()
        compose.field(QUANTITY_LABEL).assertIsEnabled()
        assertTrue(
            compose.onAllNodesWithText(PurchaseAccess.ORDERED_WHAT_AND_HOW_MANY)
                .fetchSemanticsNodes().isEmpty()
        )
    }
}
