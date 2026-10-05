package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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

        // Two nodes read exactly "Received" — this heading and the row's own
        // status tag — so the heading is taken by position rather than by a
        // selector that would match either.
        compose.onAllNodesWithText(RECEIVED_SECTION).onFirst().assertIsDisplayed()
        compose.onNodeWithText("Sliding gate rack").assertIsDisplayed()
        assertTrue(shows("6 in"))
    }

    @Test
    fun `a cancelled requirement says who cancelled it and when — never received, and no figure`() {
        // N5.10b, the advisor's decisions 2 and 3 of 2026-10-05. The receipt
        // here is one V8C4 reversed and left behind; a cancel needs nothing
        // received, so none of it may show.
        val cancelled = requirement("pr_cancelled", name = "Remote handsets", receivedQuantity = 4.0)
            .copy(
                status = "Cancelled",
                byUid = purchaseWorker.uid,
                receivedBy = "Sam",
                cancelledBy = "Asha",
                cancelledByUid = purchaseAdmin.uid,
                cancelledAt = 9_000
            )
        history(listOf(cancelled))

        val line = closingLine(cancelled)!!
        assertTrue(line, line.startsWith("Cancelled by Asha · "))
        compose.onNodeWithText(line).assertIsDisplayed()
        assertTrue("never Received by", !shows("Received by"))
        assertTrue("no figure on a cancelled row", !shows("4 in"))
    }

    @Test
    fun `an old V8C4 cancel with no stamp says Cancelled and nothing it does not know`() {
        val v8c4 = requirement("pr_old", name = "Remote handsets").copy(status = "Cancelled")
        assertEquals("Cancelled", closingLine(v8c4))
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
