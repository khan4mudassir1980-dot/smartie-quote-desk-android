package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.domain.CopyStart
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.QuotationCancel
import `in`.smartie.quotedesk.domain.QuotationCopy
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.quotations.BACK_TO_QUOTATIONS
import `in`.smartie.quotedesk.ui.quotations.CANCELLING
import `in`.smartie.quotedesk.ui.quotations.CANCEL_QUESTION_TAG
import `in`.smartie.quotedesk.ui.quotations.CANCEL_QUOTATION
import `in`.smartie.quotedesk.ui.quotations.CONFIRM_CANCEL
import `in`.smartie.quotedesk.ui.quotations.COPY_WAITS_TAG
import `in`.smartie.quotedesk.ui.quotations.CancelFailure
import `in`.smartie.quotedesk.ui.quotations.DETAIL_ACTIONS_KEY
import `in`.smartie.quotedesk.ui.quotations.DETAIL_END_KEY
import `in`.smartie.quotedesk.ui.quotations.DETAIL_LIST_TAG
import `in`.smartie.quotedesk.ui.quotations.DUPLICATE
import `in`.smartie.quotedesk.ui.quotations.EDITED
import `in`.smartie.quotedesk.ui.quotations.EDIT_QUOTATION
import `in`.smartie.quotedesk.ui.quotations.KEEP_IT
import `in`.smartie.quotedesk.ui.quotations.KEEP_MINE
import `in`.smartie.quotedesk.ui.quotations.QuotationActions
import `in`.smartie.quotedesk.ui.quotations.QuotationDetail
import `in`.smartie.quotedesk.ui.quotations.QuotationListScreen
import `in`.smartie.quotedesk.ui.quotations.REPLACE_IT
import `in`.smartie.quotedesk.ui.quotations.DETAIL_END_TAG
import `in`.smartie.quotedesk.ui.quotations.DOWNLOAD
import `in`.smartie.quotedesk.ui.quotations.PREPARING_PDF
import `in`.smartie.quotedesk.ui.quotations.PRINT
import `in`.smartie.quotedesk.ui.quotations.WHATSAPP
import `in`.smartie.quotedesk.domain.PdfAction
import `in`.smartie.quotedesk.ui.quotations.openQuotationLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Edit and Cancel on the quotation detail (N5.10 commit 7), and the list's
 * "Edited" tag.
 *
 * The three cancel cases the Owner asked the phone rows to cover are here
 * first: **a Manager cancels their own; a Manager is not offered another's;
 * an Owner cancels a Manager's.** The forced case — a Manager's cancel of
 * another's sent anyway — is refused by the rules (`quotation.test.js`) and
 * by `QuotationCancel.plan` inside the transaction.
 *
 * Every absence check scrolls to the last item and finds it there — the
 * sharing note until N5.11, its tag since — so "not offered" never means "not
 * composed yet".
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class QuotationActionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)
    private val otherManager = Member(uid = "u_m2", name = "Other Manager", role = Role.STAFF)
    private val owner = Member(uid = "u_o", name = "Owner Person", role = Role.OWNER)

    private val nine = QuotationRecord(
        id = "qd_9",
        number = "SIE/QD/2025-26/009",
        at = 1_760_000_000_000L,
        by = "Manager Person",
        byUid = "u_m",
        status = "Finalised",
        party = QuotationPartySnapshot(name = "Walk-in Builders"),
        subtotal = 44_400.0,
        total = 52_392.0
    )
    private val ten = nine.copy(id = "qd_10", number = "SIE/QD/2025-26/010", party = QuotationPartySnapshot(name = "Harbour Interiors"))

    private val edited = mutableListOf<QuotationRecord>()
    private val cancelled = mutableListOf<QuotationRecord>()
    private val duplicated = mutableListOf<QuotationRecord>()
    private val outputs = mutableListOf<Pair<QuotationRecord, PdfAction>>()
    private val actions = QuotationActions(
        onEdit = { edited += it },
        onCancel = { cancelled += it },
        onDuplicate = { duplicated += it },
        onOutput = { record, action -> outputs += record to action }
    )

    private fun detail(
        quotation: QuotationRecord = nine,
        viewer: Member = manager,
        cancelling: Boolean = false,
        cancelFailure: String? = null,
        copyStart: CopyStart = CopyStart.Go,
        preparing: Boolean = false,
        pdfFailure: String? = null
    ) {
        compose.setContent {
            SmartieTheme {
                QuotationDetail(
                    quotation = quotation,
                    onBack = {},
                    viewer = viewer,
                    actions = actions,
                    cancelling = cancelling,
                    cancelFailure = cancelFailure,
                    copyStart = copyStart,
                    preparing = preparing,
                    pdfFailure = pdfFailure
                )
            }
        }
    }

    private fun toActions() = compose.onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey(DETAIL_ACTIONS_KEY)

    private fun toEnd() = compose.onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey(DETAIL_END_KEY)

    private fun count(description: String): Int =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Neither control, with the reach proved by the note after them. */
    private fun offersNeitherEditNorCancel() {
        toEnd()
        compose.onNodeWithTag(DETAIL_END_TAG).assertExists()
        assertEquals(0, count(EDIT_QUOTATION))
        assertEquals(0, count(CANCEL_QUOTATION))
    }

    // --- Edit ---------------------------------------------------------------------

    @Test
    fun `a Manager is offered Edit on their own quotation, and Edit hands it over`() {
        detail()
        toActions()
        compose.onNodeWithContentDescription(EDIT_QUOTATION).performClick()

        assertEquals(listOf(nine), edited)
    }

    // --- Cancel: the Owner's three cases ---------------------------------------------

    @Test
    fun `a Manager cancels their own - V8C4's question first, then the cancel`() {
        detail()
        toActions()
        compose.onNodeWithContentDescription(CANCEL_QUOTATION).performClick()

        compose.onNodeWithTag(CANCEL_QUESTION_TAG).assertExists()
        compose.onNodeWithText(QuotationCancel.confirmText(nine.number)).assertExists()
        assertTrue("nothing is sent before the answer", cancelled.isEmpty())

        compose.onNodeWithContentDescription(CONFIRM_CANCEL).performClick()
        assertEquals(listOf(nine), cancelled)
    }

    @Test
    fun `a Manager is not offered Cancel, or Edit, on another's`() {
        detail(viewer = otherManager)
        offersNeitherEditNorCancel()
    }

    @Test
    fun `an Owner is offered Cancel on a Manager's quotation, and cancels it`() {
        detail(viewer = owner)
        toActions()
        compose.onNodeWithContentDescription(CANCEL_QUOTATION).performClick()
        compose.onNodeWithContentDescription(CONFIRM_CANCEL).performClick()

        assertEquals(listOf(nine), cancelled)
    }

    // --- and around them -------------------------------------------------------------

    @Test
    fun `Keep it cancels nothing, and the control comes back`() {
        detail()
        toActions()
        compose.onNodeWithContentDescription(CANCEL_QUOTATION).performClick()
        compose.onNodeWithContentDescription(KEEP_IT).performClick()

        assertTrue(cancelled.isEmpty())
        compose.onNodeWithContentDescription(CANCEL_QUOTATION).assertExists()
    }

    @Test
    fun `a cancelled quotation offers neither Edit nor Cancel`() {
        detail(quotation = nine.copy(status = "Cancelled", cancelledBy = "Owner Person"), viewer = owner)
        offersNeitherEditNorCancel()
    }

    @Test
    fun `a beta record offers neither`() {
        detail(quotation = nine.copy(legacyBetaShape = true), viewer = owner)
        offersNeitherEditNorCancel()
    }

    @Test
    fun `while its cancel is out, the control says so and nothing can be pressed`() {
        detail(cancelling = true)
        toActions()

        compose.onNodeWithContentDescription(CANCELLING).assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription(EDIT_QUOTATION).assertIsNotEnabled().performClick()
        assertTrue(cancelled.isEmpty())
        assertTrue(edited.isEmpty())
    }

    @Test
    fun `a failed cancel is shown on its own quotation, never on another`() {
        val failure = "Not cancelled — Cancelling needs an internet connection."
        compose.setContent {
            SmartieTheme {
                QuotationListScreen(
                    records = listOf(nine, ten),
                    viewer = manager,
                    cancelFailure = CancelFailure("qd_9", failure),
                    actions = actions
                )
            }
        }

        compose.onNodeWithContentDescription(openQuotationLabel(nine.number)).performClick()
        toActions()
        assertTrue(shows(failure))

        // Back is the first item, which the scroll above may have taken off
        // the screen — and a LazyColumn does not compose what is off it.
        compose.onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey("back")
        compose.onNodeWithContentDescription(BACK_TO_QUOTATIONS).performClick()
        compose.onNodeWithContentDescription(openQuotationLabel(ten.number)).performClick()
        toActions()
        compose.onNodeWithContentDescription(CANCEL_QUOTATION).assertExists()
        assertTrue("another quotation's failure", !shows(failure))
    }

    // --- Duplicate (N5.10 commit 8, amendment C) -------------------------------------------

    @Test
    fun `Duplicate is offered on a cancelled quotation too, and with nothing in progress goes straight in`() {
        val gone = nine.copy(status = "Cancelled", cancelledBy = "Owner Person")
        detail(quotation = gone, viewer = owner)
        toActions()
        compose.onNodeWithContentDescription(DUPLICATE).performClick()

        assertEquals(listOf(gone), duplicated)
        assertEquals(0, count(CANCEL_QUOTATION))
    }

    @Test
    fun `with lines in progress, V8C4's question comes first, and Keep mine copies nothing`() {
        detail(copyStart = CopyStart.AskFirst)
        toActions()
        compose.onNodeWithContentDescription(DUPLICATE).performClick()

        compose.onNodeWithText(QuotationCopy.REPLACE_QUESTION).assertExists()
        assertTrue(duplicated.isEmpty())
        compose.onNodeWithContentDescription(KEEP_MINE).performClick()

        assertTrue(duplicated.isEmpty())
        compose.onNodeWithContentDescription(DUPLICATE).assertExists()
    }

    @Test
    fun `and Replace it copies`() {
        detail(copyStart = CopyStart.AskFirst)
        toActions()
        compose.onNodeWithContentDescription(DUPLICATE).performClick()
        compose.onNodeWithContentDescription(REPLACE_IT).performClick()

        assertEquals(listOf(nine), duplicated)
    }

    @Test
    fun `with an edit open, Duplicate is not offered, and the line says why`() {
        detail(copyStart = CopyStart.EditOpen("SIE/QD/2025-26/010"))
        toActions()

        compose.onNodeWithTag(COPY_WAITS_TAG).assertExists()
        compose.onNodeWithText("Finish or discard your changes to SIE/QD/2025-26/010 first").assertExists()
        assertEquals(0, count(DUPLICATE))
        // The reach: Edit sits in the same item.
        compose.onNodeWithContentDescription(EDIT_QUOTATION).assertExists()
    }

    @Test
    fun `Staff are offered nothing at all`() {
        detail(viewer = Member(uid = "u_m", name = "Manager Person", role = Role.WORKER))
        toEnd()
        compose.onNodeWithTag(DETAIL_END_TAG).assertExists()
        assertEquals(0, count(DUPLICATE))
        assertEquals(0, count(EDIT_QUOTATION))
        assertEquals(0, count(CANCEL_QUOTATION))
        assertEquals(0, count(DOWNLOAD))
    }

    // --- the list's tag -----------------------------------------------------------------

    @Test
    fun `a quotation somebody edited is tagged Edited on the list`() {
        compose.setContent {
            SmartieTheme {
                QuotationListScreen(
                    records = listOf(nine.copy(lastEditedAt = 1_760_500_000_000L, lastEditedBy = "Owner Person")),
                    viewer = manager
                )
            }
        }
        assertTrue(shows(EDITED))
    }

    @Test
    fun `one nobody edited is not`() {
        compose.setContent {
            SmartieTheme { QuotationListScreen(records = listOf(nine), viewer = manager) }
        }
        assertTrue(shows(nine.number))
        assertTrue("never edited", !shows(EDITED))
    }

    // --- Download, Print and WhatsApp (N5.11 commit 10) --------------------------

    @Test
    fun `an issued quotation offers Download, Print and WhatsApp at the top of its actions, each handing it over`() {
        detail()
        toActions()
        compose.onNodeWithContentDescription(DOWNLOAD).performClick()
        compose.onNodeWithContentDescription(PRINT).performClick()
        compose.onNodeWithContentDescription(WHATSAPP).performClick()

        assertEquals(listOf(nine to PdfAction.DOWNLOAD, nine to PdfAction.PRINT, nine to PdfAction.SHARE), outputs)
    }

    @Test
    fun `a cancelled quotation is offered them too - it prints marked CANCELLED`() {
        detail(quotation = nine.copy(status = "Cancelled"), viewer = owner)
        toActions()
        assertEquals(1, count(DOWNLOAD))
        assertEquals(1, count(WHATSAPP))
    }

    @Test
    fun `the native beta's records are not offered a PDF`() {
        detail(quotation = nine.copy(legacyBetaShape = true), viewer = owner)
        toEnd()
        compose.onNodeWithTag(DETAIL_END_TAG).assertExists()
        assertEquals(0, count(DOWNLOAD))
        assertEquals(0, count(PRINT))
        assertEquals(0, count(WHATSAPP))
    }

    @Test
    fun `while its PDF is made the row says so, and nothing is pressed`() {
        detail(preparing = true)
        toActions()
        compose.onNodeWithContentDescription(PREPARING_PDF).assertExists()
        assertEquals(0, count(DOWNLOAD))
        assertTrue(outputs.isEmpty())
    }

    @Test
    fun `why the PDF was not made is shown under the row`() {
        detail(pdfFailure = "The PDF was not made — test.")
        toActions()
        compose.onNodeWithText("The PDF was not made — test.").assertExists()
    }

    @Test
    fun `while its cancel is out the PDF buttons do nothing`() {
        detail(viewer = owner, cancelling = true)
        toActions()
        compose.onNodeWithContentDescription(DOWNLOAD).assertIsNotEnabled()
        compose.onNodeWithContentDescription(DOWNLOAD).performClick()
        assertTrue(outputs.isEmpty())
    }

    @Test
    fun `the old note that these were still to come is gone`() {
        detail()
        toEnd()
        assertEquals(
            0,
            compose.onAllNodesWithText("arrive with the rest of the Quotation phase", substring = true)
                .fetchSemanticsNodes().size
        )
    }
}
