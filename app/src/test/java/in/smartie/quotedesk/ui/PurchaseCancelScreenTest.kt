package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseRowActions
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.screens.PurchaseRow
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Who is offered Cancel, on which card — N5.10b, the Owner's decision of
 * 2026-10-05, which replaced the N4 design that had no cancel at all.
 *
 * Only while nothing has arrived. An Owner or Administrator on any, Ordered
 * included; a Manager on anybody's that is not Ordered; Staff never — they
 * remove their own.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseCancelScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val needed = requirement("pr_one", name = "Sliding gate rack", quantity = 10.0)
    private val ordered = needed.copy(status = "Ordered")
    private val partly = needed.copy(receivedQuantity = 4.0, receivedBy = "Sam")

    private fun controls(record: PurchaseRecord, viewer: Member) {
        compose.setContent {
            SmartieTheme {
                PurchaseRowActions(
                    record = record,
                    capabilities = PurchaseCapabilities.forRecord(viewer, record)
                )
            }
        }
    }

    private fun offers(sheet: PurchaseSheet, record: PurchaseRecord): Boolean =
        compose.onAllNodesWithContentDescription(rowActionLabel(sheet, record.name))
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a Manager is offered Cancel on somebody's requirement nothing has arrived against`() {
        controls(needed, purchaseStaff)
        assertTrue(offers(PurchaseSheet.CANCEL, needed))
    }

    @Test
    fun `but not on an Ordered one`() {
        controls(ordered, purchaseStaff)
        assertTrue(!offers(PurchaseSheet.CANCEL, ordered))
    }

    @Test
    fun `and nobody on a part-received one, which is closed short instead`() {
        controls(partly, purchaseAdmin)
        assertTrue(!offers(PurchaseSheet.CANCEL, partly))
        assertTrue(offers(PurchaseSheet.SHORTFALL, partly))
    }

    @Test
    fun `an Administrator is offered Cancel on an Ordered one`() {
        controls(ordered, purchaseAdmin)
        assertTrue(offers(PurchaseSheet.CANCEL, ordered))
    }

    @Test
    fun `Staff are offered Remove on their own and never Cancel`() {
        val mine = needed.copy(byUid = purchaseWorker.uid)
        controls(mine, purchaseWorker)
        assertTrue(offers(PurchaseSheet.REMOVE, mine))
        assertTrue(!offers(PurchaseSheet.CANCEL, mine))
    }

    @Test
    fun `a cancelled card shows no received figure, whatever V8C4 left on it`() {
        // The advisor's decision 2 of 2026-10-05: a cancel needs nothing
        // received, so a figure on a cancelled row is a reversed receipt.
        val cancelled = needed.copy(status = "Cancelled", receivedQuantity = 4.0, receivedBy = "Sam")
        compose.setContent { SmartieTheme { PurchaseRow(item = cancelled) } }
        assertTrue(
            compose.onAllNodesWithText("4 in", substring = true).fetchSemanticsNodes().isEmpty()
        )
    }
}
