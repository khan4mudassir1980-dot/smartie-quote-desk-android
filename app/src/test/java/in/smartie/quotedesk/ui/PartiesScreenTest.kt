package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.ui.more.ACTIVE_SECTION
import `in`.smartie.quotedesk.ui.more.BACK_TO_PARTIES
import `in`.smartie.quotedesk.ui.more.CONTACT_AS_NAME
import `in`.smartie.quotedesk.ui.more.NOT_RECORDED
import `in`.smartie.quotedesk.ui.more.NO_MATCHES
import `in`.smartie.quotedesk.ui.more.PartiesScreen
import `in`.smartie.quotedesk.ui.more.SEARCH_LABEL
import `in`.smartie.quotedesk.ui.more.archivedHeading
import `in`.smartie.quotedesk.ui.more.openLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Parties screen: finding a customer, and the ones somebody archived.
 *
 * The rule this file exists to hold: **an archived party is hidden, never
 * dropped.** Every quotation ever raised against a customer still points at
 * their document, so a list that quietly forgot one would make old quotations
 * look as though they were issued to nobody. It is behind a heading, with a
 * count, and the search still reaches it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PartiesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val sunrise = PartyRecord(
        id = "c_1", name = "Sunrise Constructions", contact = "Mr Deshmukh",
        phone = "9876543210", gstin = "27AAACS1234F1Z5", city = "Mumbai", type = "contractor"
    )
    private val harbour = PartyRecord(id = "c_2", name = "Harbour Interiors", city = "Thane")
    private val old = PartyRecord(id = "c_3", name = "Old Client Pvt Ltd", archived = true)
    private val contactOnly = PartyRecord(id = "c_4", contact = "Mrs Pinto", phone = "9820011223")

    private fun screen(parties: List<PartyRecord> = listOf(sunrise, harbour, old)) {
        compose.setContent { SmartieTheme { PartiesScreen(parties = parties) } }
    }

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Whole-word, for asking whether a control exists.
     *
     * `shows` matches substrings, and the detail view has a field labelled
     * **Archived** — so a substring search for an "Archive" button finds the
     * label and reports a control that is not there.
     */
    private fun showsExactly(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()

    // --- the working list ------------------------------------------------------

    @Test
    fun `the working list shows the parties in use, and counts them`() {
        screen()

        compose.onNodeWithText(ACTIVE_SECTION).assertIsDisplayed()
        assertTrue(shows("Sunrise Constructions"))
        assertTrue(shows("Harbour Interiors"))
    }

    @Test
    fun `an archived party is behind a heading, not on the working list`() {
        screen()

        // Not shown, but its existence is stated and counted.
        assertTrue("archived rows are not on the working list", !shows("Old Client Pvt Ltd"))
        compose.onNodeWithContentDescription(archivedHeading(1, open = false)).assertIsDisplayed()
    }

    @Test
    fun `and opening the heading shows it`() {
        screen()

        compose.onNodeWithContentDescription(archivedHeading(1, open = false)).performClick()

        assertTrue("an archived party is reachable", shows("Old Client Pvt Ltd"))
        compose.onNodeWithContentDescription(archivedHeading(1, open = true)).assertIsDisplayed()
    }

    // --- searching ---------------------------------------------------------------

    @Test
    fun `a search narrows the list`() {
        screen()

        compose.field(SEARCH_LABEL).performTextInput("harbour")

        assertTrue(shows("Harbour Interiors"))
        assertTrue("the others are gone", !shows("Sunrise Constructions"))
    }

    @Test
    fun `a search finds a party by its contact person`() {
        screen()

        compose.field(SEARCH_LABEL).performTextInput("deshmukh")

        assertTrue(shows("Sunrise Constructions"))
    }

    @Test
    fun `a search that matches nothing says so, rather than looking broken`() {
        screen()

        compose.field(SEARCH_LABEL).performTextInput("nobody at all")

        compose.onNodeWithText(NO_MATCHES).assertIsDisplayed()
    }

    @Test
    fun `an archived party is still findable by name`() {
        screen()

        compose.field(SEARCH_LABEL).performTextInput("old client")

        // It stays in its own section rather than joining the working list,
        // and the heading is what says it is there.
        compose.onNodeWithContentDescription(archivedHeading(1, open = false)).assertIsDisplayed()
    }

    // --- a row with nothing to call the firm ------------------------------------------

    @Test
    fun `a party with no firm name shows the contact, and says that is what it is`() {
        screen(listOf(contactOnly))

        assertTrue(shows("Mrs Pinto"))
        assertTrue("the row says why", shows(CONTACT_AS_NAME))
    }

    // --- the detail view ----------------------------------------------------------------

    @Test
    fun `tapping a party opens everything stored about it`() {
        screen()

        compose.onNodeWithContentDescription(openLabel("Sunrise Constructions")).performClick()

        assertTrue(shows("27AAACS1234F1Z5"))
        assertTrue(shows("9876543210"))
        assertTrue(shows("Mr Deshmukh"))
        assertTrue(shows("Mumbai"))
    }

    @Test
    fun `a field nobody filled in says so, rather than leaving a gap`() {
        // "No GSTIN recorded" is an answer somebody came here for; a missing
        // line is not.
        screen(listOf(harbour))

        compose.onNodeWithContentDescription(openLabel("Harbour Interiors")).performClick()

        assertTrue(shows(NOT_RECORDED))
    }

    @Test
    fun `and there is a way back to the list`() {
        screen()

        compose.onNodeWithContentDescription(openLabel("Harbour Interiors")).performClick()
        compose.onNodeWithContentDescription(BACK_TO_PARTIES).performClick()

        compose.onNodeWithText(ACTIVE_SECTION).assertIsDisplayed()
    }

    @Test
    fun `the detail view offers nothing to change, because nothing can be`() {
        // N5.3 is read-only. A control that opened an editor which does not
        // exist would be worse than no control.
        screen(listOf(sunrise))

        compose.onNodeWithContentDescription(openLabel("Sunrise Constructions")).performClick()

        listOf("Edit", "Save", "Delete", "Archive").forEach { control ->
            assertTrue("$control must not be offered yet", !showsExactly(control))
        }
    }
}
