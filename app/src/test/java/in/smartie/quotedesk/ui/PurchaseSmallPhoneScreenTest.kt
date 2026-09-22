package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.ui.purchase.ADD_REQUIREMENT
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.screens.PurchaseBoardScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The narrowest phone the app supports, where a card's controls overflow
 * first.
 *
 * `assertExists()` would pass on a zero-size node — which is how a control
 * once shipped invisible with every test green — so everything here is
 * asserted displayed, at a real size, after scrolling to it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseSmallPhoneScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val records = (1..8).map { number ->
        requirement("pr_$number", name = "Requirement $number", createdAt = number * 1_000L)
    }

    private fun show() {
        compose.setContent {
            SmartieTheme {
                PurchaseBoardScreen(
                    active = PurchaseBoard.active(records),
                    closed = PurchaseBoard.closed(records),
                    capabilities = PurchaseCapabilities.forMember(purchaseAdmin),
                    capabilitiesFor = capabilitiesFor(purchaseAdmin)
                )
            }
        }
    }

    @Test
    fun `adding is a real target at 360dp`() {
        show()
        compose.onNodeWithContentDescription(ADD_REQUIREMENT)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `a card's controls wrap rather than clip, and stay 48dp`() {
        show()
        // Four controls on one card at 360dp, and it says four because it
        // means four: the loop used to stop at three while the comment
        // claimed otherwise, which is part of how B1 went unseen.
        //
        // This still cannot see a control the card clipped away —
        // `assertIsDisplayed()` never asks about ancestors. That is
        // `PurchaseCardClippingScreenTest`'s job.
        val sheets = listOf(
            PurchaseSheet.EDIT,
            PurchaseSheet.URGENCY,
            PurchaseSheet.RECEIVE,
            PurchaseSheet.REMOVE
        )
        for (sheet in sheets) {
            val label = rowActionLabel(sheet, "Requirement 8")
            compose.scrollToDescription(label)
            compose.onNodeWithContentDescription(label)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `the far end of a long board is reachable`() {
        show()
        // The oldest requirement sorts last, so reaching it means the list
        // scrolls rather than ending behind the bottom navigation.
        val label = rowActionLabel(PurchaseSheet.REMOVE, "Requirement 1")
        compose.scrollToDescription(label)
        compose.onNodeWithContentDescription(label)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }
}
