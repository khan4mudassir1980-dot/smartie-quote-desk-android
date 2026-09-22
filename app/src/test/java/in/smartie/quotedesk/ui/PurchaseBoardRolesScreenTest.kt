package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.ui.purchase.ADD_REQUIREMENT
import `in`.smartie.quotedesk.ui.purchase.PurchaseActions
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.screens.PurchaseBoardScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Which controls each role is offered **on the board**, not just on a panel.
 *
 * `PurchaseRolesScreenTest` holds the control row on its own. This holds the
 * same rules once the row is inside a `LazyColumn`, which is where a control
 * can be absent and merely-below-the-fold at the same time — so every check
 * here scrolls before it concludes anything is missing.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseBoardRolesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val open = requirement("pr_open", name = "Sliding gate rack")
    private val done = requirement(
        "pr_done",
        name = "Emergency stop button",
        received = true,
        receivedQuantity = 2.0
    )

    private fun show(
        member: Member,
        records: List<PurchaseRecord> = listOf(open, done),
        saving: Set<String> = emptySet(),
        actions: PurchaseActions = PurchaseActions()
    ) {
        compose.setContent {
            SmartieTheme {
                PurchaseBoardScreen(
                    active = PurchaseBoard.active(records),
                    received = PurchaseBoard.closed(records),
                    saving = saving,
                    capabilities = PurchaseCapabilities.forMember(member),
                    capabilitiesFor = capabilitiesFor(member),
                    actions = actions
                )
            }
        }
        // The closed row lives in History now, and every assertion in this
        // class is about what a role is offered — so it is opened here rather
        // than letting the fold answer for the role.
        compose.openHistoryFor(records)
    }

    private fun offered(sheet: PurchaseSheet, record: PurchaseRecord): Boolean =
        compose.boardHas(rowActionLabel(sheet, record.name))

    /**
     * The five a card can offer.
     *
     * [PurchaseSheet.ADD] is deliberately absent: it is the board's own
     * control, not a row's, and it carries the same description — so
     * including it here would make "a Worker is offered nothing" fail on the
     * one thing a Worker may do.
     */
    private val ROW_SHEETS = listOf(
        PurchaseSheet.EDIT,
        PurchaseSheet.URGENCY,
        PurchaseSheet.RECEIVE,
        PurchaseSheet.REOPEN,
        PurchaseSheet.REMOVE
    )

    @Test
    fun `a Worker may add and may do nothing else`() {
        show(purchaseWorker)

        compose.onNodeWithContentDescription(ADD_REQUIREMENT).assertIsDisplayed()
        for (sheet in ROW_SHEETS) {
            assertFalse("a Worker must not be offered $sheet", offered(sheet, open))
            assertFalse("a Worker must not be offered $sheet", offered(sheet, done))
        }
    }

    @Test
    fun `the displayed Manager edits, changes urgency and receives`() {
        show(purchaseStaff)

        assertTrue(offered(PurchaseSheet.EDIT, open))
        assertTrue(offered(PurchaseSheet.URGENCY, open))
        assertTrue(offered(PurchaseSheet.RECEIVE, open))
    }

    @Test
    fun `the displayed Manager has no path to reopen or remove, hidden or otherwise`() {
        show(purchaseStaff)

        // On the closed row, where Reopen would appear for somebody who may.
        assertFalse(
            "reopening is the one restriction the rules cannot express",
            offered(PurchaseSheet.REOPEN, done)
        )
        assertFalse(offered(PurchaseSheet.REMOVE, open))
        assertFalse(offered(PurchaseSheet.REMOVE, done))
        // And the capability behind the controls agrees, so there is no second
        // route into the same operation.
        assertFalse(PurchaseCapabilities.forRecord(purchaseStaff, done).reopen)
        assertFalse(PurchaseCapabilities.forRecord(purchaseWorker, done).reopen)
    }

    @Test
    fun `an Administrator reopens a received requirement and removes any of them`() {
        var asked: Pair<PurchaseSheet, String?>? = null
        show(
            purchaseAdmin,
            actions = PurchaseActions(onOpen = { sheet, record -> asked = sheet to record?.id })
        )

        assertTrue(offered(PurchaseSheet.REOPEN, done))
        assertTrue(offered(PurchaseSheet.REMOVE, open))

        compose.scrollToDescription(rowActionLabel(PurchaseSheet.REOPEN, done.name))
        compose.onNodeWithContentDescription(rowActionLabel(PurchaseSheet.REOPEN, done.name))
            .performClick()

        // The right sheet, for the right requirement, and nothing written by
        // asking for it.
        assertEquals(PurchaseSheet.REOPEN to "pr_done", asked)
    }

    @Test
    fun `an Owner is offered the same as an Administrator`() {
        show(purchaseOwner)

        assertTrue(offered(PurchaseSheet.REOPEN, done))
        assertTrue(offered(PurchaseSheet.REMOVE, open))
        assertTrue(offered(PurchaseSheet.RECEIVE, open))
    }

    @Test
    fun `a requirement with a write in flight cannot be acted on again`() {
        var opens = 0
        show(
            purchaseAdmin,
            saving = setOf("pr_open"),
            actions = PurchaseActions(onOpen = { _, _ -> opens++ })
        )

        val label = rowActionLabel(PurchaseSheet.RECEIVE, open.name)
        compose.scrollToDescription(label)
        compose.onNodeWithContentDescription(label).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label).performClick()

        assertEquals("a second tap must not open a second sheet", 0, opens)
    }
}
