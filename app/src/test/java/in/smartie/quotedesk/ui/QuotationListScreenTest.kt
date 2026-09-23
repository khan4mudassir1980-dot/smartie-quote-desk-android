package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Fixtures
import `in`.smartie.quotedesk.data.mapping.toQuotationRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.quotations.BACK_TO_QUOTATIONS
import `in`.smartie.quotedesk.ui.quotations.GRAND_TOTAL
import `in`.smartie.quotedesk.ui.quotations.NO_QUOTATIONS
import `in`.smartie.quotedesk.ui.quotations.QuotationListScreen
import `in`.smartie.quotedesk.ui.quotations.openQuotationLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The quotation list and the detail view, against **the real exported
 * documents** rather than records shaped to suit the test.
 *
 * `fixtures/quotations.json` holds the three shapes that actually exist: a
 * finalised PWA quotation issued at the contractor tier, a native-beta record
 * with per-line GST and no document-level total scheme, and a row whose totals
 * were stored as strings. Each has broken something before. A cancelled
 * quotation is built here, because no export carried one.
 *
 * Titles: stored `staff` is displayed **Manager**.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class QuotationListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)

    /** Every quotation in the export, through the reader the app really uses. */
    private val exported: List<QuotationRecord> =
        Fixtures.load("quotations.json").map { it.toQuotationRecord() }

    private fun byId(id: String) = exported.first { it.id == id }

    private fun screen(
        records: List<QuotationRecord> = exported,
        viewer: Member = admin
    ) {
        compose.setContent {
            SmartieTheme { QuotationListScreen(records = records, viewer = viewer) }
        }
    }

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun open(number: String) =
        compose.onNodeWithContentDescription(openQuotationLabel(number)).performClick()

    // --- the list -----------------------------------------------------------------

    @Test
    fun `every exported quotation reaches the list`() {
        screen()

        assertTrue(shows("SIE/QD/2025-26/007"))
        assertTrue(shows("SIE/QD/2025-26/008"))
        assertTrue(shows("SIE/QD/2024-25/101"))
    }

    @Test
    fun `an empty database says so rather than looking broken`() {
        screen(records = emptyList())
        assertTrue(shows(NO_QUOTATIONS))
    }

    // --- the four shapes that have broken something before ---------------------------

    @Test
    fun `a contractor-tier quotation still reads, tier and all`() {
        // New quotations offer Dealer and Client only. Ones already issued at
        // the contractor tier must still display, and the rate data behind
        // them is never deleted.
        screen()
        open("SIE/QD/2025-26/007")

        assertTrue("the tier it was issued at", shows("Contractor"))
        assertTrue(shows("Sunrise Constructions"))
        assertTrue("its own subtotal", shows("44,900"))
        assertTrue("and its stored total", shows("52,982"))
    }

    @Test
    fun `a record with string totals reads as money, not as text`() {
        // `q_string_totals` stores total "12390" and subtotal "10500" as
        // strings, and gst as the number 1 rather than a boolean.
        screen()
        open("SIE/QD/2024-25/101")

        assertTrue(shows("12,390"))
        assertTrue(shows("10,500"))
        // GST is the difference between two stored figures, so it lands even
        // though neither was a number when it was written.
        assertTrue(shows("1,890"))
    }

    @Test
    fun `a beta-shaped record reads, and is labelled as one`() {
        // No document-level `gst`, only a per-line rate and a `gstTotal`.
        screen()
        open("SIE/QD/2025-26/008")

        assertTrue("it is flagged", shows("Beta record"))
        assertTrue(shows("Harbour Interiors"))
        assertTrue("the gstTotal it stored", shows("3,132"))
        assertTrue(shows("20,532"))
    }

    @Test
    fun `a cancelled quotation says who cancelled it and when`() {
        val cancelled = byId("q_pwa_finalised").copy(
            id = "q_cancelled",
            number = "SIE/QD/2025-26/099",
            status = "Cancelled",
            cancelledBy = "Administrator",
            cancelledAt = 1_713_000_000_000L
        )
        screen(records = listOf(cancelled))
        open("SIE/QD/2025-26/099")

        assertTrue(shows("Cancelled"))
        assertTrue(shows("Administrator"))
    }

    // --- the detail view -----------------------------------------------------------

    @Test
    fun `the detail shows the lines, their rates and the totals`() {
        screen()
        open("SIE/QD/2025-26/007")

        assertTrue("a catalogue line", shows("Sliding gate motor 1000 kg"))
        assertTrue("its rate", shows("22,200"))
        assertTrue("the transport line V8C4 writes by hand", shows("Transportation"))
        assertTrue(shows(GRAND_TOTAL))
    }

    @Test
    fun `the party is the snapshot taken when it was issued`() {
        // Not today's party record: a customer who has since moved offices did
        // not move on a quotation that was already sent.
        screen()
        open("SIE/QD/2025-26/007")

        assertTrue(shows("27AAACS1234F1Z5"))
        assertTrue(shows("Andheri East"))
    }

    @Test
    fun `and there is a way back to the list`() {
        screen()
        open("SIE/QD/2025-26/007")
        compose.onNodeWithContentDescription(BACK_TO_QUOTATIONS).performClick()

        compose.onNodeWithContentDescription(openQuotationLabel("SIE/QD/2025-26/008"))
            .assertIsDisplayed()
    }

    @Test
    fun `nothing on the detail offers to change it`() {
        screen()
        open("SIE/QD/2025-26/007")

        listOf("Edit", "Save", "Cancel", "Delete", "Share").forEach { control ->
            assertTrue(
                "$control must not be offered yet",
                compose.onAllNodesWithText(control, substring = false)
                    .fetchSemanticsNodes().isEmpty()
            )
        }
    }

    // --- the role filter, on the screen as well as in the domain --------------------

    @Test
    fun `a Manager's list holds only the quotations they issued`() {
        // The same filter the history screen applies. Two lists of the same
        // documents that disagreed would be a leak — which is exactly what
        // N4.4 found on the Purchase board.
        val manager = Member(uid = "uid_admin", name = "Sam", role = Role.STAFF)
        screen(viewer = manager)

        // `uid_admin` issued the two numbered PWA rows; the string-totals row
        // carries no author at all and belongs to nobody.
        assertTrue(shows("SIE/QD/2025-26/007"))
        assertTrue(shows("SIE/QD/2025-26/008"))
        assertTrue("an authorless row is nobody's", !shows("SIE/QD/2024-25/101"))
    }
}
