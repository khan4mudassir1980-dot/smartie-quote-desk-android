package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.screens.PurchaseBoardScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The window a person has to correct the requirement they raised.
 *
 * Two things are asserted here that nothing else can assert: that the
 * controls are decided **per card** rather than per screen — a Staff account
 * sees them on their own requirement and not on the one beside it — and that
 * they **disappear on the snapshot** that first reports a delivery, with no
 * restart and nothing invalidating anything.
 *
 * Its own small class, like the rest of the purchase screen tests:
 * Robolectric's native-object registry is a fixed array per JVM.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseCreatorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val mine = requirement(
        "pr_mine",
        name = "Sliding gate rack",
        quantity = 10.0,
        createdAt = 5_000
    ).copy(by = "Ravi", byUid = purchaseWorker.uid)

    private val theirs = requirement(
        "pr_theirs",
        name = "Remote handsets",
        createdAt = 1_000
    ).copy(by = "Sam", byUid = purchaseStaff.uid)

    /** The board over a list the test can change, as the listener would. */
    private fun showing(
        member: Member,
        initial: List<PurchaseRecord>
    ): (List<PurchaseRecord>) -> Unit {
        var records by mutableStateOf(initial)
        compose.setContent {
            SmartieTheme {
                PurchaseBoardScreen(
                    active = PurchaseBoard.active(records),
                    closed = PurchaseBoard.closed(records),
                    capabilities = PurchaseCapabilities.forMember(member),
                    capabilitiesFor = capabilitiesFor(member)
                )
            }
        }
        return { next -> compose.runOnIdle { records = next } }
    }

    private fun offered(sheet: PurchaseSheet, record: PurchaseRecord): Boolean =
        compose.boardHas(rowActionLabel(sheet, record.name))

    // --- the creator's own card ------------------------------------------------

    @Test
    fun `a Staff account may correct the requirement they raised`() {
        showing(purchaseWorker, listOf(mine))

        assertTrue(offered(PurchaseSheet.EDIT, mine))
        assertTrue(offered(PurchaseSheet.URGENCY, mine))
        assertTrue(offered(PurchaseSheet.REMOVE, mine))
    }

    @Test
    fun `and is offered nothing at all on the card beside it`() {
        // Per card, which is the whole point: one board, two requirements,
        // two different answers.
        showing(purchaseWorker, listOf(mine, theirs))

        assertTrue(offered(PurchaseSheet.EDIT, mine))
        assertFalse(offered(PurchaseSheet.EDIT, theirs))
        assertFalse(offered(PurchaseSheet.URGENCY, theirs))
        assertFalse(offered(PurchaseSheet.REMOVE, theirs))
    }

    @Test
    fun `a Staff account records what arrives against their own requirement`() {
        showing(purchaseWorker, listOf(mine))

        assertTrue(offered(PurchaseSheet.RECEIVE, mine))
        // Nothing has arrived yet, so there is no shortfall to write off.
        assertFalse(offered(PurchaseSheet.SHORTFALL, mine))
    }

    @Test
    fun `and is offered Close short once part of it has arrived`() {
        val partly = mine.copy(receivedQuantity = 4.0, receivedBy = "Sam", receivedAt = 9_000)
        showing(purchaseWorker, listOf(partly))

        assertTrue(offered(PurchaseSheet.RECEIVE, partly))
        assertTrue(offered(PurchaseSheet.SHORTFALL, partly))
        // And the lock still took the corrections away.
        assertFalse(offered(PurchaseSheet.EDIT, partly))
        assertFalse(offered(PurchaseSheet.REMOVE, partly))
    }

    @Test
    fun `a Staff account is offered no delivery control on somebody else's row`() {
        val partly = theirs.copy(quantity = 10.0, receivedQuantity = 4.0, receivedBy = "Sam")
        showing(purchaseWorker, listOf(partly))

        assertFalse(offered(PurchaseSheet.RECEIVE, partly))
        assertFalse(offered(PurchaseSheet.SHORTFALL, partly))
    }

    @Test
    fun `nor on a row with no recorded creator`() {
        val orphan = requirement("pr_orphan", name = "Emergency stop button", quantity = 10.0)
            .copy(receivedQuantity = 4.0, receivedBy = "Sam")
        showing(purchaseWorker, listOf(orphan))

        assertFalse(offered(PurchaseSheet.RECEIVE, orphan))
        assertFalse(offered(PurchaseSheet.SHORTFALL, orphan))
    }

    @Test
    fun `a requirement with no recorded creator offers a Staff account nothing`() {
        // The PWA wrote rows without a byUid, and a row whose author cannot
        // be proved belongs to nobody.
        val legacy = requirement("pr_legacy", name = "Emergency stop button")
        showing(purchaseWorker, listOf(legacy))

        assertFalse(offered(PurchaseSheet.EDIT, legacy))
        assertFalse(offered(PurchaseSheet.REMOVE, legacy))
    }

    // --- the snapshot that closes the window ------------------------------------

    @Test
    fun `the controls go the moment a partial receipt arrives`() {
        val listener = showing(purchaseWorker, listOf(mine))
        assertTrue("before the delivery", offered(PurchaseSheet.EDIT, mine))

        // Exactly what the listener would deliver after somebody else
        // received four of the ten: nothing here restarts and nothing
        // invalidates a cache.
        listener(listOf(mine.copy(receivedQuantity = 4.0, receivedBy = "Sam", receivedAt = 9_000)))

        assertFalse("edit is gone", offered(PurchaseSheet.EDIT, mine))
        assertFalse("so is urgency", offered(PurchaseSheet.URGENCY, mine))
        assertFalse("and so is remove", offered(PurchaseSheet.REMOVE, mine))
        // But not the delivery: a requirement you raised is one you should be
        // able to finish.
        assertTrue("Received survives the lock", offered(PurchaseSheet.RECEIVE, mine))
    }

    @Test
    fun `and come back when an Administrator reopens it`() {
        val received = mine.copy(
            status = "Received",
            received = true,
            receivedQuantity = 10.0,
            receivedBy = "Sam",
            receivedAt = 9_000
        )
        val listener = showing(purchaseWorker, listOf(received))
        assertFalse("while it is closed", offered(PurchaseSheet.EDIT, mine))

        // A reopen removes all four receipt fields, so the row is untouched
        // again — the Owner's decision, recorded in docs/N4.2-plan.md.
        listener(listOf(mine))

        assertTrue(offered(PurchaseSheet.EDIT, mine))
        assertTrue(offered(PurchaseSheet.REMOVE, mine))
    }

    // --- the Manager's side ------------------------------------------------------

    @Test
    fun `a Manager keeps Received and gains Close short once part of it arrives`() {
        val partly = theirs.copy(quantity = 10.0, receivedQuantity = 4.0, receivedBy = "Sam")
        showing(purchaseStaff, listOf(partly))

        assertTrue(offered(PurchaseSheet.RECEIVE, partly))
        assertTrue(offered(PurchaseSheet.SHORTFALL, partly))
        // And loses the three the lock takes away, on their own requirement.
        assertFalse(offered(PurchaseSheet.EDIT, partly))
        assertFalse(offered(PurchaseSheet.URGENCY, partly))
        assertFalse(offered(PurchaseSheet.REMOVE, partly))
    }

    @Test
    fun `a Manager removes their own untouched requirement and not another's`() {
        showing(purchaseStaff, listOf(theirs, mine))

        assertTrue(offered(PurchaseSheet.REMOVE, theirs))
        assertFalse(offered(PurchaseSheet.REMOVE, mine))
        // Editing anybody's untouched requirement is the permission they
        // already had, and it is not narrowed by this batch.
        assertTrue(offered(PurchaseSheet.EDIT, mine))
    }

    @Test
    fun `Close short is not offered before a delivery or after the last one`() {
        // A different document: a LazyColumn is keyed by id, and two rows
        // sharing one key is a crash rather than a failed assertion.
        val whole = theirs.copy(
            id = "pr_whole",
            name = "Gate motor bracket",
            quantity = 10.0,
            receivedQuantity = 10.0,
            receivedBy = "Sam"
        )
        showing(purchaseStaff, listOf(theirs, whole))

        assertFalse("nothing arrived: remove it instead", offered(PurchaseSheet.SHORTFALL, theirs))
        assertFalse("nothing is outstanding", offered(PurchaseSheet.SHORTFALL, whole))
    }

    // --- an Owner and an Administrator keep everything ----------------------------

    @Test
    fun `an Administrator still corrects a partly received requirement`() {
        val partly = mine.copy(receivedQuantity = 4.0, receivedBy = "Sam")
        showing(purchaseAdmin, listOf(partly))

        assertTrue(offered(PurchaseSheet.EDIT, partly))
        assertTrue(offered(PurchaseSheet.URGENCY, partly))
        assertTrue(offered(PurchaseSheet.REMOVE, partly))
    }
}
