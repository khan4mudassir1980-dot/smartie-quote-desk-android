package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.PurchaseDraft
import `in`.smartie.quotedesk.domain.PurchaseWrite
import `in`.smartie.quotedesk.ui.purchase.ADDING
import `in`.smartie.quotedesk.ui.purchase.ADD_REQUIREMENT
import `in`.smartie.quotedesk.ui.purchase.AddRequirementPanel
import `in`.smartie.quotedesk.ui.purchase.CONFIRM_ADD
import `in`.smartie.quotedesk.ui.purchase.NAME_LABEL
import `in`.smartie.quotedesk.ui.purchase.OFFLINE
import `in`.smartie.quotedesk.ui.purchase.PurchaseActions
import `in`.smartie.quotedesk.ui.purchase.QUANTITY_LABEL
import `in`.smartie.quotedesk.ui.purchase.urgencyOptionLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Adding a requirement, on the narrowest phone the app supports.
 *
 * 360dp is where the urgency labels are most likely to lose their ends, and
 * the wording on the control that says how badly something is needed is the
 * Owner's own — so it is asserted whole rather than by substring.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseAddPanelScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        online: Boolean = true,
        saving: Boolean = false,
        actions: PurchaseActions = PurchaseActions()
    ) {
        compose.setContent {
            SmartieTheme {
                AddRequirementPanel(online = online, saving = saving, actions = actions)
            }
        }
    }

    @Test
    fun `all three urgencies are offered, whole, at 360dp`() {
        show()
        for (urgency in UrgencyV2.entries) {
            compose.onNodeWithText(urgency.label).assertIsDisplayed()
        }
        // The Owner's wording, word for word. A substring match would pass on
        // a label that had lost its end to a one-line segmented control.
        compose.onNodeWithText("Needed, but not now").assertIsDisplayed()
        assertEquals("Needed, but not now", UrgencyV2.NORMAL.label)
    }

    @Test
    fun `choosing the green urgency sends the wire value, not the label`() {
        var draft: PurchaseDraft? = null
        show(actions = PurchaseActions(onAdd = { draft = it }))

        compose.field(NAME_LABEL).performTextInput("Remote handsets")
        compose.onNodeWithContentDescription(urgencyOptionLabel(UrgencyV2.NORMAL)).performClick()
        compose.onNodeWithContentDescription(CONFIRM_ADD).performClick()

        assertEquals(UrgencyV2.NORMAL, draft?.urgency)
        // Both halves together, so a label edit can never quietly change what
        // Firestore stores.
        assertEquals("normal", draft?.urgency?.wireValue)
    }

    @Test
    fun `what was typed is what is sent`() {
        var draft: PurchaseDraft? = null
        show(actions = PurchaseActions(onAdd = { draft = it }))

        compose.field(NAME_LABEL).performTextInput("Remote handsets")
        compose.field(QUANTITY_LABEL).performTextClearance()
        compose.field(QUANTITY_LABEL).performTextInput("12")
        compose.onNodeWithContentDescription(urgencyOptionLabel(UrgencyV2.CRITICAL)).performClick()
        compose.onNodeWithContentDescription(CONFIRM_ADD).performClick()

        assertEquals("Remote handsets", draft?.name)
        assertEquals(12.0, draft?.quantity)
        assertEquals(UrgencyV2.CRITICAL, draft?.urgency)
        // `key` ships empty: raising from an out-of-stock row is N4.1.
        assertEquals("", draft?.key)
    }

    @Test
    fun `an unnamed requirement cannot be added`() {
        var draft: PurchaseDraft? = null
        show(actions = PurchaseActions(onAdd = { draft = it }))

        compose.onNodeWithText(ADD_REQUIREMENT).assertIsNotEnabled()
        compose.onNodeWithContentDescription(CONFIRM_ADD).performClick()

        assertNull("an empty form must not reach the repository", draft)
    }

    @Test
    fun `a quantity of zero says so once something has been typed`() {
        var draft: PurchaseDraft? = null
        show(actions = PurchaseActions(onAdd = { draft = it }))

        compose.field(NAME_LABEL).performTextInput("Remote handsets")
        compose.field(QUANTITY_LABEL).performTextClearance()
        compose.field(QUANTITY_LABEL).performTextInput("0")

        // The planner's own sentence, so what is read here cannot drift from
        // what a write would come back with.
        compose.onNodeWithText(PurchaseWrite.NOT_POSITIVE).assertIsDisplayed()
        compose.onNodeWithContentDescription(CONFIRM_ADD).performClick()
        assertNull(draft)
    }

    @Test
    fun `offline it says why and cannot be confirmed`() {
        var draft: PurchaseDraft? = null
        show(online = false, actions = PurchaseActions(onAdd = { draft = it }))

        compose.field(NAME_LABEL).performTextInput("Remote handsets")

        compose.onNodeWithText(OFFLINE).assertIsDisplayed()
        compose.onNodeWithText(ADD_REQUIREMENT).assertIsNotEnabled()
        compose.onNodeWithContentDescription(CONFIRM_ADD).performClick()
        assertNull(draft)
    }

    @Test
    fun `a write already in flight cannot be started twice`() {
        var adds = 0
        show(saving = true, actions = PurchaseActions(onAdd = { adds++ }))

        compose.onNodeWithText(ADDING).assertIsNotEnabled()
        compose.onNodeWithContentDescription(CONFIRM_ADD).performClick()

        assertEquals(0, adds)
    }
}
