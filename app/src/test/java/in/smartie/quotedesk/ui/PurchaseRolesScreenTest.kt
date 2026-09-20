package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.purchase.PurchaseActions
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseRowActions
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Which controls each role is offered on a requirement's card.
 *
 * A control somebody may not use is **absent**, not disabled — and the one
 * that matters most is Reopen. The v9 rules let any non-Worker update a
 * requirement, and a reopen is an ordinary update, so this gate and
 * `Permissions.canReopenPurchase` behind it are the whole of the enforcement.
 * Nothing on the server will catch a Manager who reaches it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseRolesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val owner = Member(uid = "uid_owner", name = "Omar", role = Role.OWNER)
    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)

    private val open = PurchaseRecord(
        id = "pr_one",
        name = "Sliding gate rack",
        quantity = 6.0,
        urgency = UrgencyV2.URGENT
    )

    private val received = open.copy(status = "Received", received = true)

    private fun show(
        member: Member,
        record: PurchaseRecord = open,
        actions: PurchaseActions = PurchaseActions()
    ) {
        compose.setContent {
            SmartieTheme {
                PurchaseRowActions(
                    record = record,
                    capabilities = PurchaseCapabilities.forMember(member),
                    actions = actions
                )
            }
        }
    }

    private fun exists(sheet: PurchaseSheet, record: PurchaseRecord = open): Boolean =
        compose.onAllNodesWithContentDescription(rowActionLabel(sheet, record.name))
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a Worker gets no row action at all`() {
        show(worker)
        for (sheet in PurchaseSheet.entries) {
            assertTrue("a Worker must not be offered $sheet", !exists(sheet))
        }
    }

    @Test
    fun `the displayed Manager edits, changes urgency and receives`() {
        show(staff)
        assertTrue(exists(PurchaseSheet.EDIT))
        assertTrue(exists(PurchaseSheet.URGENCY))
        assertTrue(exists(PurchaseSheet.RECEIVE))
    }

    @Test
    fun `the displayed Manager is offered no Remove`() {
        show(staff)
        assertTrue("removing is an Administrator's", !exists(PurchaseSheet.REMOVE))
    }

    @Test
    fun `the displayed Manager is offered no Reopen on a received requirement`() {
        show(staff, record = received)
        assertTrue(
            "reopening is the one restriction the rules cannot express",
            !exists(PurchaseSheet.REOPEN, received)
        )
    }

    @Test
    fun `an Administrator is offered Reopen on a received requirement`() {
        // One composition per test: `setContent` may be called once, so the
        // Owner's half of this row is the capability mapping below rather
        // than a second rendering.
        show(admin, record = received)

        compose.onNodeWithContentDescription(rowActionLabel(PurchaseSheet.REOPEN, received.name))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        assertTrue("an Owner may reopen too", PurchaseCapabilities.forMember(owner).reopen)
    }

    @Test
    fun `Reopen is never offered on a requirement that is still open`() {
        show(admin)
        assertTrue(
            "there is nothing to reopen while it is open",
            !exists(PurchaseSheet.REOPEN)
        )
    }

    @Test
    fun `a control is a real target, not just a semantics node`() {
        show(admin)
        // `assertExists` would pass on a zero-size node, which is how a
        // control once shipped invisible with every test green.
        compose.onNodeWithContentDescription(rowActionLabel(PurchaseSheet.EDIT, open.name))
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `opening a panel writes nothing by itself`() {
        var opened: PurchaseSheet? = null
        var removes = 0
        show(
            admin,
            actions = PurchaseActions(
                onOpen = { sheet, _ -> opened = sheet },
                onRemove = { removes++ }
            )
        )

        compose.onNodeWithContentDescription(rowActionLabel(PurchaseSheet.REMOVE, open.name))
            .performClick()

        assertEquals(PurchaseSheet.REMOVE, opened)
        assertEquals("asking is not removing", 0, removes)
    }
}
