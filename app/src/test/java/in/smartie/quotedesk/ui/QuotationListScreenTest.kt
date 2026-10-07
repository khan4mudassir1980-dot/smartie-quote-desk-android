package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Fixtures
import `in`.smartie.quotedesk.data.mapping.toQuotationRecord
import `in`.smartie.quotedesk.data.model.QuotationDiscountRecord
import `in`.smartie.quotedesk.data.model.QuotationInstallationRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.quotations.BACK_TO_QUOTATIONS
import `in`.smartie.quotedesk.ui.quotations.CANCEL_QUOTATION
import `in`.smartie.quotedesk.ui.quotations.DETAIL_ACTIONS_KEY
import `in`.smartie.quotedesk.ui.quotations.DETAIL_LIST_TAG
import `in`.smartie.quotedesk.ui.quotations.DISCOUNT_ROW
import `in`.smartie.quotedesk.ui.quotations.EDIT_QUOTATION
import `in`.smartie.quotedesk.ui.quotations.GRAND_TOTAL
import `in`.smartie.quotedesk.ui.quotations.INSTALLATION_ROW
import `in`.smartie.quotedesk.ui.quotations.LAST_EDITED
import `in`.smartie.quotedesk.ui.quotations.NO_QUOTATIONS
import `in`.smartie.quotedesk.ui.quotations.QuotationListScreen
import `in`.smartie.quotedesk.ui.quotations.TOTALS_KEY
import `in`.smartie.quotedesk.ui.quotations.formatDate
import `in`.smartie.quotedesk.ui.quotations.lastEditedText
import `in`.smartie.quotedesk.ui.quotations.openQuotationLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The quotation list and the detail view, against **the synthetic fixtures
 * modelled on what V8C4 stores** rather than records shaped to suit the test.
 *
 * `fixtures/quotations.json` holds the three shapes that actually exist: a
 * finalised PWA quotation issued at the contractor tier, a native-beta record
 * with per-line GST and no document-level total scheme, and a row whose totals
 * were stored as strings. Each has broken something before. A cancelled
 * quotation is built here, because no fixture carries one.
 *
 * Titles: stored `staff` is displayed **Manager**.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class QuotationListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)

    /** Every quotation in the fixtures, through the reader the app really uses. */
    private val stored: List<QuotationRecord> =
        Fixtures.load("quotations.json").map { it.toQuotationRecord() }

    private fun byId(id: String) = stored.first { it.id == id }

    private fun screen(
        records: List<QuotationRecord> = stored,
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

    /**
     * Bring the totals card into view.
     *
     * A `LazyColumn` never composes what is off screen, so on a quotation
     * with a full party snapshot and a couple of lines the totals are not
     * merely invisible — they are absent from the semantics tree, and
     * `onNodeWithText` cannot find what was never composed. Scrolling by key
     * is what makes the assertion about the screen rather than about the
     * viewport it happened to be measured in.
     */
    private fun scrollToTotals() =
        compose.onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey(TOTALS_KEY)

    // --- the list -----------------------------------------------------------------

    @Test
    fun `every stored quotation reaches the list`() {
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

        scrollToTotals()
        assertTrue("its own subtotal", shows("34,500"))
        assertTrue("and its stored total", shows("40,710"))
    }

    @Test
    fun `a record with string totals reads as money, not as text`() {
        // `q_string_totals` stores total "17700" and subtotal "15000" as
        // strings, and gst as the number 1 rather than a boolean.
        screen()
        open("SIE/QD/2024-25/101")

        scrollToTotals()
        assertTrue(shows("17,700"))
        assertTrue(shows("15,000"))
        // GST is the difference between two stored figures, so it lands even
        // though neither was a number when it was written.
        assertTrue(shows("2,700"))
    }

    @Test
    fun `a beta-shaped record reads, and is labelled as one`() {
        // No document-level `gst`, only a per-line rate and a `gstTotal`.
        screen()
        open("SIE/QD/2025-26/008")

        assertTrue("it is flagged", shows("Beta record"))
        assertTrue(shows("Harbour Interiors"))

        scrollToTotals()
        assertTrue("the gstTotal it stored", shows("2,160"))
        assertTrue(shows("14,160"))
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

    @Test
    fun `a quotation somebody edited says who and when, right after the issue date`() {
        // N5.10: the stamp goes on the card now; N5.11 prints it in the same
        // place, directly after the date.
        val edited = byId("q_pwa_finalised").copy(
            lastEditedBy = "Ravi Kulkarni",
            lastEditedByUid = "uid_manager",
            lastEditedAt = 1_760_000_000_000L,
            revision = 1
        )
        screen(records = listOf(edited))
        open("SIE/QD/2025-26/007")

        assertTrue(shows(LAST_EDITED))
        assertTrue(shows(lastEditedText(edited)))
        assertTrue("the name is in it", lastEditedText(edited).startsWith("Ravi Kulkarni, "))
    }

    @Test
    fun `the issue date shown is the server's when the quotation has one, on the row and the detail`() {
        // N5.11, the Owner's decision 1.1: the date the PDF prints. A phone
        // whose clock was out would otherwise show one date and print another.
        val phone = 1_735_732_800_000L // 1 Jan 2025, midday UTC
        val server = 1_742_040_000_000L // 15 Mar 2025, midday UTC
        val issued = byId("q_pwa_finalised").copy(at = phone, serverAt = server)
        screen(records = listOf(issued))

        assertTrue("the row", shows(formatDate(server)))
        assertFalse(shows(formatDate(phone)))

        open("SIE/QD/2025-26/007")
        assertTrue("the detail", shows(formatDate(server)))
        assertFalse(shows(formatDate(phone)))
    }

    @Test
    fun `a quotation nobody edited says nothing about editing`() {
        screen()
        open("SIE/QD/2025-26/007")

        // The reach: the issue date's own label is there.
        assertTrue(shows("Issued"))
        assertTrue(compose.onAllNodesWithText(LAST_EDITED).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `installation and discount are shown as they were stored`() {
        // Until N5.10 commit 1 the reader dropped both, and a discounted
        // quotation's totals did not visibly add up.
        val native = byId("q_pwa_finalised").copy(
            installation = QuotationInstallationRecord(mode = "door", rate = 500.0, amount = 2_000.0, basis = 4.0),
            discount = QuotationDiscountRecord(kind = "pct", value = 10.0, amount = 3_400.0),
            discountBase = 34_000.0
        )
        screen(records = listOf(native))
        open("SIE/QD/2025-26/007")
        scrollToTotals()

        assertTrue(shows(INSTALLATION_ROW))
        assertTrue(shows("2,000"))
        assertTrue(shows(DISCOUNT_ROW))
        assertTrue("a deduction, as the builder shows one", shows("- ₹3,400"))
    }

    @Test
    fun `a quotation with neither shows neither row`() {
        screen()
        open("SIE/QD/2025-26/007")
        scrollToTotals()

        // The reach: the grand total is on the same card.
        assertTrue(shows(GRAND_TOTAL))
        assertTrue(compose.onAllNodesWithText(INSTALLATION_ROW).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(DISCOUNT_ROW).fetchSemanticsNodes().isEmpty())
    }

    // --- the detail view -----------------------------------------------------------

    @Test
    fun `the detail shows the lines, their rates and the totals`() {
        screen()
        open("SIE/QD/2025-26/007")

        assertTrue("a catalogue line", shows("Sliding gate motor 1000 kg"))
        assertTrue("its rate", shows("17,000"))
        assertTrue("the Transportation line V8C4 writes", shows("Transportation"))

        scrollToTotals()
        assertTrue("the grand total", shows(GRAND_TOTAL))
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
    fun `the detail offers Edit and Cancel, and nothing else that changes it`() {
        // Until N5.10 this was "nothing on the detail offers to change it".
        // Edit and Cancel are now offered to the creator and to an Owner or
        // Administrator (`QuotationActionsScreenTest` has who); Delete and
        // Share are still not — nothing deletes a quotation, and sharing is
        // N5.11's.
        screen()
        open("SIE/QD/2025-26/007")
        compose.onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey(DETAIL_ACTIONS_KEY)

        compose.onNodeWithContentDescription(EDIT_QUOTATION).assertExists()
        compose.onNodeWithContentDescription(CANCEL_QUOTATION).assertExists()
        listOf("Save", "Delete", "Share").forEach { control ->
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
