package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.ui.purchase.NOTHING_RECEIVED
import `in`.smartie.quotedesk.ui.purchase.PurchaseHistoryScreen
import `in`.smartie.quotedesk.ui.purchase.RECEIVED_SECTION
import `in`.smartie.quotedesk.ui.purchase.REMOVED_TAG
import `in`.smartie.quotedesk.ui.purchase.closingLine
import `in`.smartie.quotedesk.ui.purchase.removedHeading
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Purchase History on a screen: what is shown, what is folded away, and what
 * a row says about a removal nobody stamped.
 *
 * Its own small class, like the rest of the purchase screen tests:
 * Robolectric's native-object registry is a fixed array per JVM.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseHistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val received = requirement(
        "pr_done",
        name = "Sliding gate rack",
        received = true,
        receivedQuantity = 6.0,
        receivedAt = 9_000
    ).copy(byUid = purchaseWorker.uid, receivedBy = "Sam")

    private val removed = requirement("pr_gone", name = "Duplicate entry", deleted = true)
        .copy(byUid = purchaseWorker.uid, removedBy = "Asha", removedAt = 5_000)

    private fun history(
        records: List<PurchaseRecord>,
        viewer: Member = purchaseAdmin
    ) {
        compose.setContent {
            SmartieTheme { PurchaseHistoryScreen(records = records, viewer = viewer) }
        }
    }

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `received requirements are listed, with what actually arrived`() {
        history(listOf(received))

        compose.onNodeWithText(RECEIVED_SECTION).assertIsDisplayed()
        compose.onNodeWithText("Sliding gate rack").assertIsDisplayed()
        assertTrue(shows("6 in"))
    }

    @Test
    fun `removed requirements are folded away until somebody asks`() {
        history(listOf(received, removed))

        // Collapsed on open: the heading says how many, so tapping it is a
        // decision rather than a guess.
        compose.onNodeWithText(removedHeading(1, open = false)).assertIsDisplayed()
        assertTrue("the row itself is not on screen yet", !shows("Duplicate entry"))

        compose.onNodeWithText(removedHeading(1, open = false)).performClick()

        assertTrue(shows("Duplicate entry"))
        compose.onNodeWithText(removedHeading(1, open = true)).assertIsDisplayed()
    }

    @Test
    fun `no removed requirements means no section to expand`() {
        history(listOf(received))
        assertTrue(!shows("Removed ("))
    }

    @Test
    fun `an empty history says so rather than showing nothing`() {
        history(emptyList())
        compose.onNodeWithText(NOTHING_RECEIVED).assertIsDisplayed()
    }

    @Test
    fun `a Staff account sees only the rows it raised`() {
        val theirs = requirement("pr_theirs", name = "Remote handsets", received = true)
            .copy(byUid = "uid_someone")
        history(listOf(received, theirs, removed), viewer = purchaseWorker)

        compose.onNodeWithText("Sliding gate rack").assertIsDisplayed()
        assertTrue("somebody else's is not theirs to read", !shows("Remote handsets"))
    }

    @Test
    fun `a removal this app made says who and when`() {
        val line = closingLine(removed)
        assertEquals("Removed by Asha on 1 Jan 1970", line)
    }

    @Test
    fun `and a removal nobody stamped still renders, saying only what it knows`() {
        // A PWA removal wrote neither delBy nor delAt. The row appears; the
        // screen does not invent a name or a date for it.
        val legacy = requirement("pr_legacy", name = "Old entry", deleted = true)
            .copy(byUid = purchaseWorker.uid)

        assertEquals(REMOVED_TAG, closingLine(legacy))

        history(listOf(legacy))
        compose.onNodeWithText(removedHeading(1, open = false)).performClick()
        assertTrue(shows("Old entry"))
    }
}
