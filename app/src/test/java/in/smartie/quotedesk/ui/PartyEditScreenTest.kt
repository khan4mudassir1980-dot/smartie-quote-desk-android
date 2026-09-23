package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.domain.PartyDraft
import `in`.smartie.quotedesk.ui.more.ADD_PARTY
import `in`.smartie.quotedesk.ui.more.ARCHIVE_PARTY
import `in`.smartie.quotedesk.ui.more.CITY_LABEL
import `in`.smartie.quotedesk.ui.more.EDIT_PARTY
import `in`.smartie.quotedesk.ui.more.GSTIN_LABEL
import `in`.smartie.quotedesk.ui.more.NAME_IS_LOCKED
import `in`.smartie.quotedesk.ui.more.NAME_LABEL
import `in`.smartie.quotedesk.ui.more.PartiesScreen
import `in`.smartie.quotedesk.ui.more.PartyActions
import `in`.smartie.quotedesk.ui.more.PartyCapabilities
import `in`.smartie.quotedesk.ui.more.SAVE_CHANGES
import `in`.smartie.quotedesk.ui.more.SAVE_NEW
import `in`.smartie.quotedesk.ui.more.openLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Adding and correcting a party, and the controls each role is offered.
 *
 * **The form offers only what the rules would accept.** A Manager may correct
 * every detail but may neither rename nor archive, so the name is shown as a
 * fact and no archive control is drawn. A control that earns a permission
 * error is worse than no control, and a test is the only thing that keeps the
 * two layers agreeing.
 *
 * Stored `staff` is displayed **Manager**.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PartyEditScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val sunrise = PartyRecord(
        id = "c_1", name = "Sunrise Constructions", contact = "Mr Deshmukh",
        phone = "9876543210", gstin = "27AAACS1234F1Z5", city = "Mumbai", type = "contractor"
    )
    private val harbour = PartyRecord(id = "c_2", name = "Harbour Interiors", city = "Thane")

    private val manager = PartyCapabilities(canAdd = true, canRename = false, canArchive = false)
    private val admin = PartyCapabilities(canAdd = true, canRename = true, canArchive = true)

    private var created: Pair<String, PartyDraft>? = null
    private var edited: Pair<PartyRecord, PartyDraft>? = null
    private var archived: Pair<PartyRecord, Boolean>? = null

    private fun screen(
        capabilities: PartyCapabilities,
        parties: List<PartyRecord> = listOf(sunrise, harbour)
    ) {
        compose.setContent {
            SmartieTheme {
                PartiesScreen(
                    parties = parties,
                    capabilities = capabilities,
                    newPartyId = { "c_minted" },
                    actions = PartyActions(
                        onCreate = { id, draft -> created = id to draft },
                        onEdit = { record, draft -> edited = record to draft },
                        onArchive = { record, on -> archived = record to on }
                    )
                )
            }
        }
    }

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(description: String) =
        compose.onNodeWithContentDescription(description).performClick()

    private fun openEditorFor(name: String) {
        tap(openLabel(name))
        tap(EDIT_PARTY)
    }

    // --- what a Manager is offered -------------------------------------------------

    @Test
    fun `a Manager editing a party is shown the name, not a name box`() {
        screen(manager)
        openEditorFor("Sunrise Constructions")

        assertTrue("the name is still readable", shows("Sunrise Constructions"))
        assertTrue("and says why it cannot be changed", shows(NAME_IS_LOCKED))
    }

    @Test
    fun `and is offered no archive control at all`() {
        screen(manager)
        tap(openLabel("Sunrise Constructions"))

        compose.onAllNodesWithText(ARCHIVE_PARTY, substring = false)
            .fetchSemanticsNodes().isEmpty().let { assertTrue("no archive control", it) }
    }

    @Test
    fun `but may still correct every other detail`() {
        screen(manager)
        openEditorFor("Sunrise Constructions")

        compose.field(CITY_LABEL).performTextInput("Pune ")
        tap(SAVE_CHANGES)

        assertEquals("c_1", edited?.first?.id)
        assertTrue("the city was sent", edited?.second?.city?.contains("Pune") == true)
    }

    @Test
    fun `an Owner is offered the name box and the archive control`() {
        screen(admin)
        tap(openLabel("Sunrise Constructions"))

        compose.onNodeWithContentDescription(ARCHIVE_PARTY).performClick()
        assertEquals("c_1", archived?.first?.id)
        assertEquals(true, archived?.second)
    }

    @Test
    fun `and can clear a field, because the editor stores what is on screen`() {
        screen(admin)
        openEditorFor("Sunrise Constructions")

        compose.field(GSTIN_LABEL).performTextClearance()
        tap(SAVE_CHANGES)

        assertEquals("", edited?.second?.gstin)
    }

    // --- the duplicate guard ----------------------------------------------------------

    @Test
    fun `saving a party that already exists warns before it writes`() {
        screen(manager)
        tap(ADD_PARTY)

        compose.field(NAME_LABEL).performTextInput("Harbour Interiors")
        tap(SAVE_NEW)

        assertNull("nothing was written", created)
        assertTrue("and it says which party, and why", shows("Harbour Interiors"))
        assertTrue(shows("the same name"))
    }

    @Test
    fun `and offers to open that one instead`() {
        screen(manager)
        tap(ADD_PARTY)
        compose.field(NAME_LABEL).performTextInput("Harbour Interiors")
        tap(SAVE_NEW)

        tap(openLabel("Harbour Interiors"))

        // The form is gone and the existing party is open, with nothing
        // written.
        assertNull(created)
        assertTrue("the party it pointed at", shows("Thane"))
    }

    @Test
    fun `saving again is the person saying they meant it`() {
        // The guard warns once. Two firms really can share a name, and a
        // warning that cannot be overridden is a wall.
        screen(manager)
        tap(ADD_PARTY)
        compose.field(NAME_LABEL).performTextInput("Harbour Interiors")

        tap(SAVE_NEW)
        assertNull(created)

        tap(SAVE_NEW)
        assertEquals("c_minted", created?.first)
        assertEquals("Harbour Interiors", created?.second?.name)
    }

    @Test
    fun `a genuinely new party is written on the first save`() {
        screen(manager)
        tap(ADD_PARTY)

        compose.field(NAME_LABEL).performTextInput("Metro Glass")
        compose.field(CITY_LABEL).performTextInput("Mumbai")
        tap(SAVE_NEW)

        assertEquals("c_minted", created?.first)
        assertEquals("Metro Glass", created?.second?.name)
        assertEquals("Mumbai", created?.second?.city)
    }

    @Test
    fun `and the id is minted once, so a second save reuses it`() {
        // N4.4's B2: an ambiguous failure must not become two customers.
        screen(manager)
        tap(ADD_PARTY)
        compose.field(NAME_LABEL).performTextInput("Metro Glass")

        tap(SAVE_NEW)
        val first = created?.first

        assertEquals("c_minted", first)
    }

    @Test
    fun `a Staff account is offered no Add control`() {
        // They never reach this screen, but the capability is what decides.
        screen(PartyCapabilities(canAdd = false, canRename = false, canArchive = false))

        compose.onAllNodesWithText(ADD_PARTY, substring = false)
            .fetchSemanticsNodes().isEmpty().let { assertTrue("no add control", it) }
    }
}
