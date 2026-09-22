package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchasePeople
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseHistoryScreen
import `in`.smartie.quotedesk.ui.screens.PurchaseRow
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Mudassir Khan · Owner" on the card and in History — where it can be known.
 *
 * C6. The role comes from the uid, so two accounts sharing a display name are
 * told apart; and where the team cannot be read it is absent rather than
 * guessed. A Manager or a Staff account may not read `/users`, so those
 * phones are handed an empty map and every name stands alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseRoleLineScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val namesake = Member(uid = "uid_owner", name = "Mudassir Khan", role = purchaseOwner.role)
    private val team = PurchasePeople.byUid(listOf(namesake, purchaseAdmin, purchaseWorker))

    private val raised = requirement("pr_one", name = "Sliding gate rack")
        .copy(by = "Mudassir Khan", byUid = "uid_owner")

    private fun card(members: Map<String, Member>) {
        compose.setContent {
            SmartieTheme {
                PurchaseRow(
                    item = raised,
                    capabilities = PurchaseCapabilities.forRecord(purchaseAdmin, raised),
                    members = members
                )
            }
        }
    }

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the card names the person and what they are`() {
        card(team)

        assertTrue(shows("Added by Mudassir Khan · Owner"))
    }

    @Test
    fun `and names them alone where the team cannot be read`() {
        // A Manager's or a Staff account's phone. Nothing is broken by the
        // absence — the line is simply shorter.
        card(emptyMap())

        // The card still carries the date after the name, so the thing to
        // assert absent is the title, not the separator.
        assertTrue(shows("Added by Mudassir Khan"))
        assertFalse("no role is invented", shows("Mudassir Khan · Owner"))
    }

    @Test
    fun `History says who received something, and what they are`() {
        val arrived: PurchaseRecord = requirement(
            "pr_done",
            name = "Emergency stop button",
            received = true,
            receivedQuantity = 2.0,
            receivedAt = 9_000
        ).copy(receivedBy = "Asha", receivedByUid = purchaseAdmin.uid)

        compose.setContent {
            SmartieTheme {
                PurchaseHistoryScreen(
                    records = listOf(arrived),
                    viewer = purchaseAdmin,
                    members = team
                )
            }
        }

        assertTrue(shows("Received by Asha · Administrator"))
    }

    @Test
    fun `and who removed one, falling back where the row never said`() {
        // A PWA removal wrote a name and no uid, so there is nothing to
        // resolve and the line stops at the name.
        val gone = requirement("pr_gone", name = "Duplicate entry", deleted = true)
            .copy(removedBy = "Somebody", removedAt = 5_000)

        compose.setContent {
            SmartieTheme {
                PurchaseHistoryScreen(
                    records = listOf(gone),
                    viewer = purchaseAdmin,
                    members = team
                )
            }
        }

        compose.openRemoved(count = 1)
        assertTrue(shows("Removed by Somebody"))
    }
}
