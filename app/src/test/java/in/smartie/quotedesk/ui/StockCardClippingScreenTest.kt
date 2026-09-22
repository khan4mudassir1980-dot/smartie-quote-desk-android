package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.stock.stockItemLabel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The stock card's controls, painted whole, on the narrowest phone.
 *
 * The same shared `SmartieCard` as the purchase card, and the same shape of
 * risk: its controls are a `FlowRow` too, and the comment above that one
 * already records that the stepper alone is 130–134dp and each button 58–83dp,
 * so three controls overflow 360dp. Until N4.4 the card's height came from an
 * intrinsic measurement that a wrapping `FlowRow` answers for a different
 * width than it finally gets — which cost the purchase card its last two
 * controls, and which nothing here would have noticed either.
 *
 * The card names itself, so no test host is needed: its `contentDescription`
 * is the handle, and "inside the card" is measured against the real thing.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class StockCardClippingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val name = "Sliding gate motor"
    private val card = stockItemLabel(name)

    private fun shelf(pending: Map<String, Double> = emptyMap()) {
        compose.showStock(
            stock = listOf(stockRecord("SIE1000", name, quantity = 9.0)),
            capabilities = adminCaps,
            pending = pending
        )
    }

    @Test
    fun `an Administrator's controls are all painted inside the card`() {
        shelf()

        compose.assertFooterPaintedInsideNamedCard(
            listOf("Pin $name", "Edit $name", "History for $name"),
            card
        )
        // The stepper is a container — its minus and plus are what click — so
        // it is checked for paint and bounds and not for a click action.
        compose.assertPaintedIn(
            "Change $name by one",
            compose.cardBoundsNamed(card),
            // `stepperHeightNarrow`. At 360dp the stepper is deliberately
            // 40dp — `SmartieDimens.forWidth` shrinks it and nothing else —
            // so asking for 44 here would be asking the card to break a rule
            // the theme sets on purpose.
            minimumTarget = 40.dp,
            requireClickable = false
        )
    }

    @Test
    fun `and the save row a pending change adds is painted too`() {
        // The longest this card ever gets: every control above, plus the Done
        // and Discard row that only appears once something is pending.
        shelf(pending = mapOf("gateMotors|SIE1000" to -4.0))

        compose.assertFooterPaintedInsideNamedCard(
            listOf("Done, save $name", "Edit $name", "History for $name"),
            card
        )
    }

    @Test
    fun `and there is no hole under the last control`() {
        shelf()

        compose.assertNoDeadSpaceBelow("History for $name", compose.cardBoundsNamed(card))
    }
}
