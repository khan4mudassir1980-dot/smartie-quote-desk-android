package `in`.smartie.quotedesk.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
    fun `a worker sees only Team and About`() {
        val worker = Member(
            uid = "uid_worker", email = "worker@example.invalid", name = "Ajay Worker",
            role = Role.WORKER,
        )
        compose.setContent {
            SmartieTheme { MoreScreen(member = worker, onOpen = {}, onSignOut = {}) }
        }

        compose.onNodeWithText("Team").assertExists()
        compose.onNodeWithText("About & legal").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Settings").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Parties").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Quotation history").fetchSemanticsNodes().size)
    }
}
