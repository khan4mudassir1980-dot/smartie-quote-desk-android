package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.orderToggleLabel
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.screens.PurchaseRow
import `in`.smartie.quotedesk.ui.screens.orderedLine
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The fullest purchase card since N5.10b, on the narrowest phone, painted whole.
 *
 * The most controls any role sees on one card is now **six** — an Owner or
 * Administrator on an open requirement: Received, Edit, Urgency, the Order
 * toggle, then Close short (part received) or Cancel (nothing received), and
 * Remove. They wrap rather than fold into an overflow (the advisor's decision
 * 7 of 2026-10-05). The Ordered tag sits beside the urgency in a plain `Row`,
 * which clips silently, and the "Ordered by" line is one more line of text —
 * so both are checked for clipping too.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseOrderedClippingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val untouched = requirement("pr_new", name = "Sliding gate rack", quantity = 10.0)

    private val orderedPartly = untouched.copy(
        status = "Ordered",
        receivedQuantity = 4.0,
        receivedBy = "Sam",
        orderedBy = "Asha",
        orderedByUid = purchaseAdmin.uid,
        orderedAt = 1_712_500_000_000L
    )

    private fun card(record: PurchaseRecord, viewer: Member) {
        compose.setContent {
            SmartieTheme {
                Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Box(Modifier.testTag(CARD_HOST_TAG)) {
                        PurchaseRow(
                            item = record,
                            capabilities = PurchaseCapabilities.forRecord(viewer, record)
                        )
                    }
                }
            }
        }
    }

    private fun labels(record: PurchaseRecord, vararg sheets: PurchaseSheet): List<String> =
        sheets.map { rowActionLabel(it, record.name) } + orderToggleLabel(record.name)

    @Test
    fun `an Administrator's six controls on an untouched card are all painted`() {
        card(untouched, purchaseAdmin)

        compose.assertFooterPaintedInsideCard(
            minimumTarget = 48.dp,
            descriptions = labels(
                untouched,
                PurchaseSheet.RECEIVE,
                PurchaseSheet.EDIT,
                PurchaseSheet.URGENCY,
                PurchaseSheet.CANCEL,
                PurchaseSheet.REMOVE
            )
        )
    }

    @Test
    fun `and they take two rows at most, Received first`() {
        card(untouched, purchaseOwner)

        val all = labels(
            untouched,
            PurchaseSheet.RECEIVE,
            PurchaseSheet.EDIT,
            PurchaseSheet.URGENCY,
            PurchaseSheet.CANCEL,
            PurchaseSheet.REMOVE
        ).map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().unclippedBounds() }
        val rows = all.map { it.top }.distinct()
        assertTrue("six controls in ${rows.size} rows", rows.size <= 2)
        val received = all.first()
        assertTrue("Received is on the first row", received.top == all.minOf { it.top })
        assertTrue(
            "Received leads its row",
            all.filter { it.top == received.top }.all { received.left <= it.left }
        )
    }

    @Test
    fun `on an Ordered, part-received card the tag, the line and the six controls are all painted`() {
        card(orderedPartly, purchaseAdmin)

        val card = compose.cardBounds()
        compose.assertFooterPaintedInsideCard(
            minimumTarget = 48.dp,
            descriptions = labels(
                orderedPartly,
                PurchaseSheet.RECEIVE,
                PurchaseSheet.EDIT,
                PurchaseSheet.URGENCY,
                PurchaseSheet.SHORTFALL,
                PurchaseSheet.REMOVE
            )
        )
        val line = orderedLine(orderedPartly)!!
        // The tag is described with the line; it is not a control.
        compose.assertPaintedIn(line, card, minimumTarget = 0.dp, requireClickable = false)
        compose.assertTextPaintedIn(line, card)
    }

    @Test
    fun `a Manager's own untouched card paints its five`() {
        val mine = untouched.copy(byUid = purchaseStaff.uid)
        card(mine, purchaseStaff)

        compose.assertFooterPaintedInsideCard(
            minimumTarget = 48.dp,
            descriptions = listOf(
                PurchaseSheet.RECEIVE,
                PurchaseSheet.EDIT,
                PurchaseSheet.URGENCY,
                PurchaseSheet.CANCEL,
                PurchaseSheet.REMOVE
            ).map { rowActionLabel(it, mine.name) }
        )
    }
}
