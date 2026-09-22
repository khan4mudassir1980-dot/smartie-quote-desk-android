package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.screens.PurchaseRow
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The longest footer a purchase card can show, on the narrowest phone, painted
 * whole.
 *
 * This is B1. An Owner looking at a partly received requirement is offered
 * five controls; at 360dp they cannot fit on one line, and the two that wrap
 * are always the last two — Close short and Remove, which is exactly the pair
 * the phone pass found missing. Every existing test passed through this
 * because `assertIsDisplayed()` does not detect ancestor clipping; see
 * `CardClipping.kt`.
 *
 * The card is wrapped in a `Box` and nothing else, so the host's bounds are
 * the card's own and "inside the card" means it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseCardClippingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** Ten asked for, four arrived: the state that offers every control. */
    private val partlyReceived = requirement(
        "pr_part",
        name = "Sliding gate rack",
        quantity = 10.0,
        receivedQuantity = 4.0
    )

    private val untouched = requirement("pr_new", name = "Sliding gate rack")

    private fun card(record: PurchaseRecord, viewer: Member) {
        compose.setContent {
            SmartieTheme {
                // The board's own horizontal padding, so the card is the width
                // it really gets rather than the whole window.
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

    private fun labels(record: PurchaseRecord, vararg sheets: PurchaseSheet) =
        sheets.map { rowActionLabel(it, record.name) }

    @Test
    fun `an Owner's five controls on a partly received card are all painted`() {
        card(partlyReceived, purchaseOwner)

        compose.assertFooterPaintedInsideCard(
            labels(
                partlyReceived,
                PurchaseSheet.EDIT,
                PurchaseSheet.URGENCY,
                PurchaseSheet.RECEIVE,
                PurchaseSheet.SHORTFALL,
                PurchaseSheet.REMOVE
            )
        )
    }

    @Test
    fun `and the Owner's four on an untouched one, Remove included`() {
        card(untouched, purchaseOwner)

        compose.assertFooterPaintedInsideCard(
            labels(
                untouched,
                PurchaseSheet.EDIT,
                PurchaseSheet.URGENCY,
                PurchaseSheet.RECEIVE,
                PurchaseSheet.REMOVE
            )
        )
    }

    @Test
    fun `a creator's own untouched card paints its Remove too`() {
        // The second half of the phone report: a Staff account, its own row.
        val mine = untouched.copy(byUid = purchaseWorker.uid)
        card(mine, purchaseWorker)

        compose.assertFooterPaintedInsideCard(
            labels(
                mine,
                PurchaseSheet.EDIT,
                PurchaseSheet.URGENCY,
                PurchaseSheet.RECEIVE,
                PurchaseSheet.REMOVE
            )
        )
    }

    @Test
    fun `and there is no hole under the last of them`() {
        card(partlyReceived, purchaseOwner)

        compose.assertNoDeadSpaceBelow(
            rowActionLabel(PurchaseSheet.REMOVE, partlyReceived.name)
        )
    }
}
