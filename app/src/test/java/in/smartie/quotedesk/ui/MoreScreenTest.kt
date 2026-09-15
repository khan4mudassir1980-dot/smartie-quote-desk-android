package `in`.smartie.quotedesk.ui

import androidx.compose.ui.test.assertIsDisplayed
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
@Config(application = Application::class)
class MoreScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the primary owner is labelled and sees every entry`() {
        val owner = Member(
            uid = "uid_owner", email = "owner@example.invalid", name = "Primary Owner",
            role = Role.OWNER, ownerRank = OwnerRank.PRIMARY,
        )
        compose.setContent {
            SmartieTheme { MoreScreen(member = owner, onOpen = {}, onSignOut = {}) }
        }

        compose.onNodeWithText("Owner / Administrator").assertIsDisplayed()
        compose.onNodeWithText("Primary").assertIsDisplayed()
        compose.onNodeWithText("Team").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsDisplayed()
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

        compose.onNodeWithText("Team").assertIsDisplayed()
        compose.onNodeWithText("About & legal").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Settings").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Parties").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Quotation history").fetchSemanticsNodes().size)
    }
}
