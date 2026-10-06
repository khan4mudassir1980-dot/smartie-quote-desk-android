package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
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
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.Catalogue
import `in`.smartie.quotedesk.domain.CopyOrigin
import `in`.smartie.quotedesk.domain.Discount
import `in`.smartie.quotedesk.domain.DiscountKind
import `in`.smartie.quotedesk.domain.EditOrigin
import `in`.smartie.quotedesk.domain.QuoteDiscount
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.ui.products.BUILDER_DISCARD_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_DISCOUNT_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_HEADER_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_LOCK_TAG
import `in`.smartie.quotedesk.ui.products.BUILDER_SAVE_EDIT_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_TAIL_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_TIER_KEY
import `in`.smartie.quotedesk.ui.products.CATALOGUE_FINALISE_STATUS_TAG
import `in`.smartie.quotedesk.ui.products.DISCARD_CHANGES
import `in`.smartie.quotedesk.ui.products.DISCARD_CONFIRM
import `in`.smartie.quotedesk.ui.products.FINALISE
import `in`.smartie.quotedesk.ui.products.GatePhase
import `in`.smartie.quotedesk.ui.products.ISSUING_NOTE
import `in`.smartie.quotedesk.ui.quotations.DOWNLOAD
import `in`.smartie.quotedesk.ui.quotations.PRINT
import `in`.smartie.quotedesk.ui.quotations.WHATSAPP
import `in`.smartie.quotedesk.ui.products.KEEP_EDITING
import `in`.smartie.quotedesk.ui.products.ProductsActions
import `in`.smartie.quotedesk.ui.products.ProductsCatalogue
import `in`.smartie.quotedesk.ui.products.QUOTE_BUILDER_TAG
import `in`.smartie.quotedesk.ui.products.SAVE_CHANGES
import `in`.smartie.quotedesk.ui.products.SAVING_CHANGES
import `in`.smartie.quotedesk.ui.products.discardQuestion
import `in`.smartie.quotedesk.ui.products.editNote
import `in`.smartie.quotedesk.ui.products.editingHeading
import `in`.smartie.quotedesk.ui.products.ratesNote
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The builder with an issued quotation open for editing (N5.10 commit 7).
 *
 * An edit is **saved, never finalised**: Save changes in place of Finalise,
 * Discard changes behind a question, the number in the heading, and the rates
 * it was issued at said in words. The discount cap on an edit warns **only
 * when the discount goes up** (amendment A), so the builder agrees with what
 * Save will do.
 *
 * Its own class: `QuoteBuilderScreenTest` is already the largest in the
 * suite. The same rule holds — every assertion scrolls to its key first.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class QuoteBuilderEditScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val motor = ProductRecord(
        documentId = Keys.productDocId("gate", "SIE1000"),
        key = Keys.productKey("gate", "SIE1000"),
        group = "gate",
        seedModel = "SIE1000",
        model = "SIE1000",
        categoryId = "cat-sliding",
        client = 22_200.0
    )

    private val number = "SIE/QD/2025-26/009"

    /** Issued at 10% off 44,400 — 4,440 — before the Owner lowered the cap to 5%. */
    private val editing: QuoteDraft = QuoteDraft(
        id = "qd_9",
        tier = RateTierV2.CLIENT,
        party = QuotationPartySnapshot(name = "Walk-in Builders"),
        discount = Discount(DiscountKind.PERCENT, 10.0),
        gstPercent = 18.0,
        editOf = EditOrigin("qd_9", number, 0, RateTierV2.CLIENT, 4_440.0, 44_400.0)
    ).add(motor, quantity = 2.0, id = "ln_1")

    private var saved = 0
    private var discarded = 0
    private var finalised = 0

    private fun render(
        draft: QuoteDraft = editing,
        open: Boolean = true,
        gatePhase: GatePhase = GatePhase.IDLE,
        discountCap: Double? = 5.0
    ) {
        val view = Catalogue.build(listOf(motor), emptyList(), emptyList(), "")
        compose.setContent {
            SmartieTheme {
                ProductsCatalogue(
                    canViewProducts = true,
                    view = view,
                    draft = draft,
                    canFinalise = true,
                    gatePhase = gatePhase,
                    discountCap = discountCap,
                    actions = ProductsActions(
                        onFinalise = { finalised++ },
                        onSaveEdit = { saved++ },
                        onDiscardEdit = { discarded++ }
                    )
                )
            }
        }
        if (open) compose.onNodeWithText("View quote").performClick()
    }

    private fun scrollTo(key: String) = compose.onNodeWithTag(QUOTE_BUILDER_TAG).performScrollToKey(key)

    private fun count(description: String): Int =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    // --- what it is -------------------------------------------------------------------

    @Test
    fun `an edit is headed with its number, and says whose rates its lines carry`() {
        render()
        scrollTo(BUILDER_HEADER_KEY)
        compose.onNodeWithText(editingHeading(number)).assertExists()
        scrollTo(BUILDER_TIER_KEY)

        assertEquals(
            "Rates as quoted on SIE/QD/2025-26/009. Switching Dealer / Client reprices at today's rates.",
            ratesNote(editing)
        )
        assertTrue(shows(ratesNote(editing)!!))
    }

    @Test
    fun `a new quotation says nothing about quoted rates - the witness`() {
        render(draft = editing.copy(editOf = null))
        scrollTo(BUILDER_TIER_KEY)

        assertTrue(shows("Client"))
        assertTrue(!shows("Rates as quoted on"))
    }

    // --- Save, never Finalise -----------------------------------------------------------

    @Test
    fun `an edit offers Save changes and no Finalise, and Save asks for the save`() {
        render()
        scrollTo(BUILDER_SAVE_EDIT_KEY)
        compose.onNodeWithContentDescription(SAVE_CHANGES).assertIsEnabled().performClick()
        assertEquals(1, saved)

        scrollTo(BUILDER_TAIL_KEY)
        compose.onNodeWithText(editNote(number)).assertExists()
        assertEquals(0, count(FINALISE))
        assertEquals(0, finalised)
    }

    @Test
    fun `an edit offers no Download, Print or WhatsApp - save it, then take the PDF from the quotation`() {
        // N5.11, the approved placement: none of the three in edit mode.
        render()
        scrollTo(BUILDER_TAIL_KEY)
        assertEquals(0, count(DOWNLOAD))
        assertEquals(0, count(PRINT))
        assertEquals(0, count(WHATSAPP))
    }

    @Test
    fun `a new quotation's tail is still the issuing note - the witness`() {
        render(draft = editing.copy(editOf = null))
        scrollTo(BUILDER_TAIL_KEY)
        compose.onNodeWithText(ISSUING_NOTE).assertExists()
    }

    @Test
    fun `while the save is out, Save says so and is disabled, and the panel is locked`() {
        render(gatePhase = GatePhase.SAVING)
        compose.onNodeWithTag(BUILDER_LOCK_TAG).assertExists()
        scrollTo(BUILDER_SAVE_EDIT_KEY)

        compose.onNodeWithContentDescription(SAVING_CHANGES).assertIsNotEnabled().performClick()
        assertEquals(0, saved)
    }

    // --- Discard, behind a question -----------------------------------------------------

    @Test
    fun `Discard asks first, and Keep editing discards nothing`() {
        render()
        scrollTo(BUILDER_DISCARD_KEY)
        compose.onNodeWithContentDescription(DISCARD_CHANGES).performClick()

        compose.onNodeWithText(discardQuestion(number)).assertExists()
        assertEquals(0, discarded)

        compose.onNodeWithContentDescription(KEEP_EDITING).performClick()
        assertEquals(0, discarded)
        scrollTo(BUILDER_DISCARD_KEY)
        compose.onNodeWithContentDescription(DISCARD_CHANGES).assertExists()
    }

    @Test
    fun `answering Discard them discards the edit`() {
        render()
        scrollTo(BUILDER_DISCARD_KEY)
        compose.onNodeWithContentDescription(DISCARD_CHANGES).performClick()
        compose.onNodeWithContentDescription(DISCARD_CONFIRM).performClick()

        assertEquals(1, discarded)
    }

    // --- the cap, only when the discount goes up (amendment A) -----------------------------

    @Test
    fun `a discount kept above a lowered cap is not flagged on an edit - case (i)`() {
        render()
        scrollTo(BUILDER_DISCOUNT_KEY)

        val overCap = QuoteDiscount.refusal(Discount(DiscountKind.PERCENT, 10.0), 44_400.0, 5.0)!!
        assertTrue(!shows(overCap))
    }

    @Test
    fun `the same discount on a new quotation is flagged - the witness`() {
        render(draft = editing.copy(editOf = null))
        scrollTo(BUILDER_DISCOUNT_KEY)

        assertTrue(shows(QuoteDiscount.refusal(Discount(DiscountKind.PERCENT, 10.0), 44_400.0, 5.0)!!))
    }

    @Test
    fun `a discount raised on an edit is flagged against the cap - case (ii)`() {
        render(draft = editing.copy(discount = Discount(DiscountKind.PERCENT, 12.0)))
        scrollTo(BUILDER_DISCOUNT_KEY)

        assertTrue(shows(QuoteDiscount.refusal(Discount(DiscountKind.PERCENT, 12.0), 44_400.0, 5.0)!!))
    }

    // --- a Duplicate's copy (N5.10 commit 8) -------------------------------------------------

    private val copied = editing.copy(
        id = "qd_copy",
        editOf = null,
        discount = null,
        gstPercent = null,
        copiedFrom = CopyOrigin(number, 1_760_000_000_000L)
    )

    @Test
    fun `a copy says whose rates it carries, dated, and is finalised like any new quotation`() {
        render(draft = copied)
        scrollTo(BUILDER_TIER_KEY)

        val note = ratesNote(copied)!!
        assertTrue(note, note.startsWith("Rates as quoted on SIE/QD/2025-26/009, "))
        assertTrue(shows(note))
        scrollTo(BUILDER_TAIL_KEY)
        compose.onNodeWithText(ISSUING_NOTE).assertExists()
        assertEquals(0, count(SAVE_CHANGES))
    }

    @Test
    fun `once a switch has repriced a line, the note is gone`() {
        render(draft = copied.copy(tierRepriced = true))
        scrollTo(BUILDER_TIER_KEY)

        assertTrue(shows("Client"))
        assertTrue(!shows("Rates as quoted on"))
    }

    // --- with the builder closed ------------------------------------------------------------

    @Test
    fun `the catalogue names the edit, and still opens it with every line removed`() {
        render(draft = editing.copy(lines = emptyList()), open = false)

        compose.onNodeWithText("Editing $number").assertExists()
        compose.onNodeWithText("View quote").assertIsEnabled().performClick()
        scrollTo(BUILDER_DISCARD_KEY)
        compose.onNodeWithContentDescription(DISCARD_CHANGES).assertExists()
    }

    @Test
    fun `and a save still out is said on the catalogue`() {
        render(open = false, gatePhase = GatePhase.SAVING)

        compose.onNodeWithTag(CATALOGUE_FINALISE_STATUS_TAG).assertExists()
        compose.onNodeWithText(SAVING_CHANGES).assertExists()
    }
}
