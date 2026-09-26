package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.domain.PartyDraft
import `in`.smartie.quotedesk.ui.more.ADD_KEY
import `in`.smartie.quotedesk.ui.more.ADD_PARTY
import `in`.smartie.quotedesk.ui.more.ARCHIVE_KEY
import `in`.smartie.quotedesk.ui.more.ARCHIVE_PARTY
import `in`.smartie.quotedesk.ui.more.CITY_LABEL
import `in`.smartie.quotedesk.ui.more.DUPLICATE_KEY
import `in`.smartie.quotedesk.ui.more.EDIT_KEY
import `in`.smartie.quotedesk.ui.more.EDIT_PARTY
import `in`.smartie.quotedesk.ui.more.GSTIN_LABEL
import `in`.smartie.quotedesk.ui.more.NAME_IS_LOCKED
import `in`.smartie.quotedesk.ui.more.NAME_LABEL
import `in`.smartie.quotedesk.ui.more.PARTIES_LIST_TAG
import `in`.smartie.quotedesk.ui.more.PARTY_DETAIL_TAG
import `in`.smartie.quotedesk.ui.more.PARTY_EDITOR_TAG
import `in`.smartie.quotedesk.ui.more.PartiesScreen
import `in`.smartie.quotedesk.ui.more.PartyActions
import `in`.smartie.quotedesk.ui.more.PartyCapabilities
import `in`.smartie.quotedesk.ui.more.READ_ONLY_KEY
import `in`.smartie.quotedesk.ui.more.SAVE_CHANGES
import `in`.smartie.quotedesk.ui.more.SAVE_KEY
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
 * **Every assertion here scrolls first, and that is not tidiness.** A
 * `LazyColumn` does not merely hide what is off screen, it never composes it,
 * so an un-scrolled `onNodeWithContentDescription` cannot tell a missing
 * control from one below the fold — and with nine fields above the Save
 * button, the fold is where most of this screen lives. The first version of
 * this file asserted against the viewport and reported the screen broken when
 * it was not. The absence checks are written to prove their own reach: each
 * one also asserts that a *neighbouring* control was found, so "no archive
 * button" cannot quietly mean "nothing was composed at all".
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

    // --- looking at the whole screen rather than the viewport ---------------------

    /** Composes the item with [key], so the assertion after it is about the screen. */
    private fun scrollTo(tag: String, key: String) =
        compose.onNodeWithTag(tag).performScrollToKey(key)

    private fun tap(description: String) =
        compose.onNodeWithContentDescription(description).performClick()

    private fun tapInDetail(key: String, description: String) {
        scrollTo(PARTY_DETAIL_TAG, key)
        tap(description)
    }

    private fun tapInEditor(key: String, description: String) {
        scrollTo(PARTY_EDITOR_TAG, key)
        tap(description)
    }

    /** A box in the editor. Its list key is its label, so one name does both. */
    private fun box(label: String): SemanticsNodeInteraction {
        scrollTo(PARTY_EDITOR_TAG, label)
        return compose.field(label)
    }

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun offers(description: String): Boolean =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun openParty(party: PartyRecord) {
        scrollTo(PARTIES_LIST_TAG, party.id)
        tap(openLabel(party.name))
    }

    private fun openEditorFor(party: PartyRecord) {
        openParty(party)
        tapInDetail(EDIT_KEY, EDIT_PARTY)
    }

    private fun startAdding() {
        scrollTo(PARTIES_LIST_TAG, ADD_KEY)
        tap(ADD_PARTY)
    }

    private fun save(description: String) = tapInEditor(SAVE_KEY, description)

    // --- what a Manager is offered -------------------------------------------------

    @Test
    fun `a Manager editing a party is shown the name, not a name box`() {
        screen(manager)
        openEditorFor(sunrise)
        scrollTo(PARTY_EDITOR_TAG, NAME_LABEL)

        assertTrue("the name is still readable", shows("Sunrise Constructions"))
        assertTrue("and says why it cannot be changed", shows(NAME_IS_LOCKED))
    }

    @Test
    fun `and is offered no archive control at all`() {
        screen(manager)
        openParty(sunrise)
        // The bottom of the detail, which composes the action row above it —
        // so Edit being found is what makes Archive's absence mean something.
        scrollTo(PARTY_DETAIL_TAG, READ_ONLY_KEY)

        assertTrue("the Edit control is there", offers(EDIT_PARTY))
        assertTrue("but no archive control", !offers(ARCHIVE_PARTY))
    }

    @Test
    fun `but may still correct every other detail`() {
        screen(manager)
        openEditorFor(sunrise)

        box(CITY_LABEL).performTextInput("Pune ")
        save(SAVE_CHANGES)

        assertEquals("c_1", edited?.first?.id)
        assertTrue("the city was sent", edited?.second?.city?.contains("Pune") == true)
    }

    @Test
    fun `an Owner is offered the name box and the archive control`() {
        screen(admin)
        openParty(sunrise)

        tapInDetail(ARCHIVE_KEY, ARCHIVE_PARTY)

        assertEquals("c_1", archived?.first?.id)
        assertEquals(true, archived?.second)
    }

    @Test
    fun `and can clear a field, because the editor stores what is on screen`() {
        screen(admin)
        openEditorFor(sunrise)

        box(GSTIN_LABEL).performTextClearance()
        save(SAVE_CHANGES)

        assertEquals("", edited?.second?.gstin)
    }

    // --- the duplicate guard ----------------------------------------------------------

    @Test
    fun `saving a party that already exists warns before it writes`() {
        screen(manager)
        startAdding()

        box(NAME_LABEL).performTextInput("Harbour Interiors")
        save(SAVE_NEW)

        assertNull("nothing was written", created)
        scrollTo(PARTY_EDITOR_TAG, DUPLICATE_KEY)
        assertTrue("and it says which party, and why", shows("Harbour Interiors"))
        // V8C4's `matchReason` wording since N5.9a commit 8b.
        assertTrue(shows("the same company name"))
    }

    @Test
    fun `and offers to open that one instead`() {
        screen(manager)
        startAdding()
        box(NAME_LABEL).performTextInput("Harbour Interiors")
        save(SAVE_NEW)

        tapInEditor(DUPLICATE_KEY, openLabel("Harbour Interiors"))

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
        startAdding()
        box(NAME_LABEL).performTextInput("Harbour Interiors")

        save(SAVE_NEW)
        assertNull(created)

        save(SAVE_NEW)
        assertEquals("c_minted", created?.first)
        assertEquals("Harbour Interiors", created?.second?.name)
    }

    @Test
    fun `a genuinely new party is written on the first save`() {
        screen(manager)
        startAdding()

        box(NAME_LABEL).performTextInput("Metro Glass")
        box(CITY_LABEL).performTextInput("Mumbai")
        save(SAVE_NEW)

        assertEquals("c_minted", created?.first)
        assertEquals("Metro Glass", created?.second?.name)
        assertEquals("Mumbai", created?.second?.city)
    }

    @Test
    fun `and the id is minted once, so a second save reuses it`() {
        // N4.4's B2: an ambiguous failure must not become two customers.
        screen(manager)
        startAdding()
        box(NAME_LABEL).performTextInput("Metro Glass")

        save(SAVE_NEW)

        assertEquals("c_minted", created?.first)
    }

    @Test
    fun `a Staff account is offered no Add control`() {
        // They never reach this screen, but the capability is what decides.
        screen(PartyCapabilities(canAdd = false, canRename = false, canArchive = false))

        // The rows sit *below* where the Add control would be, so finding one
        // proves that part of the list composed and the control really is
        // absent rather than merely off screen.
        assertTrue("the rows are there", offers(openLabel("Sunrise Constructions")))
        assertTrue("but no add control", !offers(ADD_PARTY))
    }
}
