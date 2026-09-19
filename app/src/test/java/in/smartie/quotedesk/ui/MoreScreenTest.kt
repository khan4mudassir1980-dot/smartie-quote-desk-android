package `in`.smartie.quotedesk.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.OwnerRank
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.more.MoreScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// A plain Application: SmartieApplication would need a real Firebase project.
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class MoreScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the primary owner is labelled and the menu starts with Parties`() {
        val owner = Member(
            uid = "uid_owner", email = "owner@example.invalid", name = "Primary Owner",
            role = Role.OWNER, ownerRank = OwnerRank.PRIMARY,
        )
        compose.setContent {
            SmartieTheme { MoreScreen(member = owner, onOpen = {}, onSignOut = {}) }
        }

        compose.onNodeWithText("Primary Owner").assertExists()
        compose.onNodeWithText("Owner / Administrator").assertExists()
        compose.onNodeWithText("Primary").assertExists()
        compose.onNodeWithText("Sign out").assertExists()
        // Entries further down the list are not composed until they scroll
        // into view; which roles see which entry is covered by MoreMenuTest.
        compose.onNodeWithText("Parties").assertExists()
        compose.onNodeWithText("Products & Categories").assertExists()
    }

    @Test
    fun `a worker sees only About, never Team`() {
        val worker = Member(
            uid = "uid_worker", email = "worker@example.invalid", name = "Ajay Worker",
            role = Role.WORKER,
        )
        compose.setContent {
            SmartieTheme { MoreScreen(member = worker, onOpen = {}, onSignOut = {}) }
        }

        compose.onNodeWithText("About & legal").assertExists()
        // A stored `worker` wears the **Staff** badge on their own account
        // card. The stored role, and everything it may do, is unchanged.
        compose.onNodeWithText("Staff").assertExists()
        assertEquals(
            "the old title must be gone from the account card",
            0,
            compose.onAllNodesWithText("Worker").fetchSemanticsNodes().size
        )
        // A Worker used to be offered Team and met the screen's refusal.
        assertEquals(0, compose.onAllNodesWithText("Team").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Settings").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Parties").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Quotation history").fetchSemanticsNodes().size)
    }

    @Test
    fun `a stored staff wears the Manager badge on their own account card`() {
        val manager = Member(
            uid = "uid_staff", email = "staff@example.invalid", name = "Mo Kapoor",
            role = Role.STAFF,
        )
        compose.setContent {
            SmartieTheme { MoreScreen(member = manager, onOpen = {}, onSignOut = {}) }
        }

        compose.onNodeWithText("Manager").assertExists()
        assertEquals(
            "the role this used to be called must not appear beside the new one",
            0,
            compose.onAllNodesWithText("Staff").fetchSemanticsNodes().size
        )
    }

    @Test
    fun `an administrator is offered Team`() {
        val admin = Member(
            uid = "uid_admin", email = "admin@example.invalid", name = "Meera Admin",
            role = Role.ADMIN,
        )
        compose.setContent {
            SmartieTheme { MoreScreen(member = admin, onOpen = {}, onSignOut = {}) }
        }

        // A lazy list does not compose what is below the fold, so scroll the
        // menu to Team rather than assuming it is already on screen.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Team"))
        compose.onNodeWithText("Team").assertExists()
    }

    @Test
    fun `an owner is offered Team`() {
        val owner = Member(
            uid = "uid_owner", email = "owner@example.invalid", name = "Primary Owner",
            role = Role.OWNER, ownerRank = OwnerRank.PRIMARY,
        )
        compose.setContent {
            SmartieTheme { MoreScreen(member = owner, onOpen = {}, onSignOut = {}) }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Team"))
        compose.onNodeWithText("Team").assertExists()
    }
}
