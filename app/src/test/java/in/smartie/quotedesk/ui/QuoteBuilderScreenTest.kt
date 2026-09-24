package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.Catalogue
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.AreaEntry
import `in`.smartie.quotedesk.domain.AreaLine
import `in`.smartie.quotedesk.domain.ManualEntry
import `in`.smartie.quotedesk.domain.QuoteLineEntry
import `in`.smartie.quotedesk.domain.QuoteTier
import `in`.smartie.quotedesk.ui.products.BACK_TO_PRODUCTS
import `in`.smartie.quotedesk.ui.products.BUILDER_CLEAR_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_HEADING
import `in`.smartie.quotedesk.ui.products.BUILDER_TAIL_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_NO_PARTIES_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_PARTY_SEARCH_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_PICK_PARTY_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_TIER_KEY
import `in`.smartie.quotedesk.ui.products.CHOOSE_PARTY
import `in`.smartie.quotedesk.ui.products.PARTY_NAME_LABEL
import `in`.smartie.quotedesk.ui.products.ADD_AREA
import `in`.smartie.quotedesk.ui.products.ADD_AREA_LINE
import `in`.smartie.quotedesk.ui.products.ADD_MANUAL
import `in`.smartie.quotedesk.ui.products.ADD_MANUAL_LINE
import `in`.smartie.quotedesk.ui.products.BUILDER_ADD_AREA_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_ADD_MANUAL_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_AREA_ADD_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_AREA_WORKING_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_MANUAL_ADD_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_SAVE_CUSTOMER_KEY
import `in`.smartie.quotedesk.ui.products.DESCRIPTION_LABEL
import `in`.smartie.quotedesk.ui.products.HEIGHT_LABEL
import `in`.smartie.quotedesk.ui.products.SAVE_OPENING
import `in`.smartie.quotedesk.ui.products.WIDTH_LABEL
import `in`.smartie.quotedesk.ui.products.PARTY_SEARCH_LABEL
import `in`.smartie.quotedesk.ui.products.SAVE_CUSTOMER
import `in`.smartie.quotedesk.ui.products.SITE_LABEL
import `in`.smartie.quotedesk.ui.products.chooseLabel
import `in`.smartie.quotedesk.ui.products.noSavedParty
import `in`.smartie.quotedesk.ui.products.CLEAR_LINES
import `in`.smartie.quotedesk.ui.products.moreOf
import `in`.smartie.quotedesk.ui.products.NOTHING_ON_IT
import `in`.smartie.quotedesk.ui.products.ProductsActions
import `in`.smartie.quotedesk.ui.products.ProductsCatalogue
import `in`.smartie.quotedesk.ui.products.QuoteBuilderPanel
import `in`.smartie.quotedesk.ui.products.QUOTE_BUILDER_TAG
import `in`.smartie.quotedesk.ui.products.AREA_LINE
import `in`.smartie.quotedesk.ui.products.MANUAL_LINE
import `in`.smartie.quotedesk.ui.products.RATE_LABEL
import `in`.smartie.quotedesk.ui.products.RATE_NEEDED
import `in`.smartie.quotedesk.ui.products.removeLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The builder panel: opening it, reading it, and getting back out.
 *
 * **It is a plain composable, not a sheet, and this file is the reason.** A
 * `ModalBottomSheet` is a Compose `Dialog`, which opens a recomposer
 * Robolectric's clock does not drive — the lesson N4 wrote down at
 * `PurchasePanels.kt:60-64`. Every assertion below would have had to be an
 * assertion about a view model instead.
 *
 * **Every assertion scrolls to its key first.** A `LazyColumn` never composes
 * an off-screen item, so an un-scrolled lookup cannot tell a missing control
 * from one below the fold — five CI cycles were lost to exactly that. The
 * absence checks scroll to [BUILDER_TAIL_KEY], whose only job is to be the end
 * of the list, and then also assert that a neighbouring node *was* found, so
 * "not there" can never quietly mean "nothing composed at all".
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class QuoteBuilderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun product(seedModel: String, client: Double? = 22200.0) = ProductRecord(
        documentId = Keys.productDocId("gate", seedModel),
        key = Keys.productKey("gate", seedModel),
        group = "gate",
        seedModel = seedModel,
        model = seedModel,
        categoryId = "cat-sliding",
        client = client
    )

    private val motor = product("SIE1000")
    private val unpriced = product("SIE600", client = null)

    private var changed: Pair<String, Double>? = null
    private var removed: String? = null
    private var addedManual: Pair<String, ManualEntry>? = null
    private var addedArea: Pair<String, AreaEntry>? = null
    private var editedArea: Pair<String, AreaEntry>? = null
    private var retiered: RateTierV2? = null
    private var chosen: PartyRecord? = null
    private var typedParty: QuotationPartySnapshot? = null
    private val savedWith = mutableListOf<String>()
    private var minted = 1

    private val sunrise = PartyRecord(
        id = "c_1", name = "Sunrise Constructions", contact = "Mr Deshmukh",
        phone = "9876543210", gstin = "27AAACS1234F1Z5", city = "Mumbai",
        address = "14 Marine Lines"
    )
    private val harbour = PartyRecord(id = "c_2", name = "Harbour Interiors", city = "Thane")
    private val retired = PartyRecord(id = "c_3", name = "Old Steel Works", archived = true)
    private var cleared = 0

    /** A draft holding one priced line, the way a catalogue tap leaves it. */
    private fun oneLine() = QuoteDraft(id = "qd_1").add(motor, quantity = 2.0, id = "ln_1")

    private fun render(
        draft: QuoteDraft,
        open: Boolean = true,
        parties: List<PartyRecord> = listOf(sunrise, harbour, retired),
        customerFailure: String? = null
    ) {
        val view = Catalogue.build(listOf(motor, unpriced), emptyList(), emptyList(), "")
        compose.setContent {
            SmartieTheme {
                ProductsCatalogue(
                    canViewProducts = true,
                    view = view,
                    draft = draft,
                    parties = parties,
                    customerFailure = customerFailure,
                    // A different id on every call, so a test can prove the
                    // panel mints once and then holds what it minted.
                    newPartyId = { "c_new_${'$'}{minted++}" },
                    newLineId = { "ln_new_${'$'}{minted++}" },
                    actions = ProductsActions(
                        onTierChange = { retiered = it },
                        onPartyChange = { typedParty = it },
                        onChooseParty = { chosen = it },
                        onSaveCustomer = { savedWith += it },
                        onChangeLineQuantity = { id, delta -> changed = id to delta },
                        onRemoveLine = { removed = it },
                        onAddManual = { id, entry -> addedManual = id to entry },
                        onAddArea = { id, entry -> addedArea = id to entry },
                        onEditArea = { line, entry -> editedArea = line.id to entry },
                        onClearDraft = { cleared++ }
                    )
                )
            }
        }
        if (open) compose.onNodeWithText("View quote").performClick()
    }

    private fun scrollTo(key: String) =
        compose.onNodeWithTag(QUOTE_BUILDER_TAG).performScrollToKey(key)

    @Test
    fun `the quote bar opens the builder in place of the catalogue`() {
        render(oneLine(), open = false)
        // The catalogue is what is on screen until the bar is tapped.
        compose.onNodeWithText("Search").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(QUOTE_BUILDER_TAG).fetchSemanticsNodes().size)

        compose.onNodeWithText("View quote").performClick()

        compose.onNodeWithTag(QUOTE_BUILDER_TAG).assertExists()
        compose.onNodeWithText(BUILDER_HEADING).assertExists()
        // Replaced, not floated over: the catalogue's own search box is gone.
        assertEquals(0, compose.onAllNodesWithText("Search").fetchSemanticsNodes().size)
    }

    @Test
    fun `back to products puts the catalogue back`() {
        render(oneLine())
        compose.onNodeWithContentDescription(BACK_TO_PRODUCTS).performClick()

        compose.onNodeWithText("Search").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(QUOTE_BUILDER_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun `a line shows what it is, what it comes to, and a stepper`() {
        render(oneLine())
        scrollTo("ln_1")

        compose.onNodeWithText("SIE1000").assertExists()
        // The working in the meta line, and the answer as the trailing figure
        // — `QuotationDetail`'s shape, so a draft and a finalised quotation
        // read alike.
        compose.onNodeWithText("2 each × ₹22,200").assertExists()
        compose.onNodeWithText("₹44,400").assertExists()
    }

    @Test
    fun `the stepper addresses the line by its id, never by a product key`() {
        render(oneLine())
        scrollTo("ln_1")
        compose.onNodeWithContentDescription(moreOf("SIE1000")).performClick()

        // `ln_1`, not `gate|SIE1000`. A hand-typed line has no key at all, so
        // a key-addressed stepper could not drive one.
        assertEquals("ln_1" to 1.0, changed)
    }

    @Test
    fun `an unpriced line says so rather than showing a rupee zero`() {
        render(QuoteDraft(id = "qd_1").add(unpriced, id = "ln_2"))
        scrollTo("ln_2")

        assertTrue(compose.onAllNodesWithText(RATE_NEEDED).fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, compose.onAllNodesWithText("₹0").fetchSemanticsNodes().size)
    }

    @Test
    fun `clearing empties the lines and stays on the builder`() {
        render(oneLine())
        scrollTo(BUILDER_CLEAR_KEY)
        compose.onNodeWithContentDescription(CLEAR_LINES).performClick()

        assertEquals(1, cleared)
        // The panel is still the thing on screen: the party and the charges on
        // this quotation are not lines and were not thrown away with them.
        compose.onNodeWithTag(QUOTE_BUILDER_TAG).assertExists()
    }

    @Test
    fun `the empty panel says what to do and draws no Clear`() {
        compose.setContent {
            SmartieTheme {
                QuoteBuilderPanel(
                    draft = QuoteDraft(id = "qd_1"),
                    onBack = {},
                    onTierChange = {},
                    onChangeLineQuantity = { _, _ -> },
                    onRemoveLine = {},
                    onClear = {}
                )
            }
        }
        scrollTo(BUILDER_TAIL_KEY)

        compose.onNodeWithText(NOTHING_ON_IT).assertExists()
        // The absence proves its own reach: the heading is still found, so the
        // list composed and simply has no Clear on it.
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription(CLEAR_LINES).fetchSemanticsNodes().size
        )
        assertTrue(
            compose.onAllNodesWithContentDescription(BACK_TO_PRODUCTS)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun `the builder offers two rates, and Contractor is not one of them`() {
        render(oneLine())
        scrollTo(BUILDER_TIER_KEY)

        compose.onNodeWithText("Dealer").assertExists()
        compose.onNodeWithText("Client").assertExists()
        // `RateTierV2` still has three, and a quotation already issued at the
        // contractor tier still displays as one. Only building is narrowed.
        assertEquals(2, QuoteTier.OFFERED.size)
        assertEquals(0, compose.onAllNodesWithText("Contractor").fetchSemanticsNodes().size)
    }

    @Test
    fun `the catalogue's own picker offers the same two, never a third`() {
        render(oneLine(), open = false)
        // Scrolled to a tier that IS offered first, so the absence below
        // proves the filters row composed rather than the list stopping short.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Dealer"))

        // It offered all three until now, so a person could price a quotation
        // at a tier `QuoteDraft.refusal` then refused to issue.
        compose.onNodeWithText("Dealer").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Contractor").fetchSemanticsNodes().size)
    }

    @Test
    fun `choosing a rate reports it, so the lines can be repriced`() {
        render(oneLine())
        scrollTo(BUILDER_TIER_KEY)
        compose.onNodeWithText("Dealer").performClick()

        assertEquals(RateTierV2.DEALER, retiered)
    }

    @Test
    fun `a draft somehow at Contractor says why, rather than showing nothing lit`() {
        compose.setContent {
            SmartieTheme {
                QuoteBuilderPanel(
                    draft = QuoteDraft(id = "qd_1", tier = RateTierV2.CONTRACTOR)
                        .addManual("ln_1", "Motor", rate = 100.0),
                    onBack = {},
                    onTierChange = {},
                    onChangeLineQuantity = { _, _ -> },
                    onRemoveLine = {},
                    onClear = {}
                )
            }
        }
        scrollTo(BUILDER_TIER_KEY)

        compose.onNodeWithText(QuoteDraft.TIER_NOT_OFFERED).assertExists()
    }

    // --- who the quotation is for ---------------------------------------------

    @Test
    fun `the picker lists the saved customers, and never an archived one`() {
        render(oneLine())
        scrollTo(BUILDER_PICK_PARTY_KEY)
        compose.onNodeWithContentDescription(CHOOSE_PARTY).performClick()

        scrollTo("party-c_1")
        compose.onNodeWithContentDescription(chooseLabel("Sunrise Constructions")).assertExists()
        compose.onNodeWithContentDescription(chooseLabel("Harbour Interiors")).assertExists()
        // A quotation raised today against a customer somebody retired is a
        // mistake nobody makes on purpose. The two above prove the reach.
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription(chooseLabel("Old Steel Works"))
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun `the search narrows the picker to what was typed`() {
        render(oneLine())
        scrollTo(BUILDER_PICK_PARTY_KEY)
        compose.onNodeWithContentDescription(CHOOSE_PARTY).performClick()
        scrollTo(BUILDER_PARTY_SEARCH_KEY)
        compose.field(PARTY_SEARCH_LABEL).performTextInput("Harbour")

        scrollTo("party-c_2")
        compose.onNodeWithContentDescription(chooseLabel("Harbour Interiors")).assertExists()
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription(chooseLabel("Sunrise Constructions"))
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun `choosing a customer reports it and closes the picker`() {
        render(oneLine())
        scrollTo(BUILDER_PICK_PARTY_KEY)
        compose.onNodeWithContentDescription(CHOOSE_PARTY).performClick()
        scrollTo("party-c_1")
        compose.onNodeWithContentDescription(chooseLabel("Sunrise Constructions")).performClick()

        assertEquals("c_1", chosen?.id)
        // Closed again, so the seven boxes are what is on screen.
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription(PARTY_SEARCH_LABEL)
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun `an empty customer list says so rather than showing nothing`() {
        render(oneLine(), parties = emptyList())
        scrollTo(BUILDER_PICK_PARTY_KEY)
        compose.onNodeWithContentDescription(CHOOSE_PARTY).performClick()

        scrollTo(BUILDER_NO_PARTIES_KEY)
        compose.onNodeWithText(noSavedParty("")).assertExists()
    }

    @Test
    fun `typing the customer reaches the draft, so it survives the app dying`() {
        render(oneLine())
        scrollTo(PARTY_NAME_LABEL)
        compose.field(PARTY_NAME_LABEL).performTextInput("Acme")

        assertEquals("Acme", typedParty?.name)
    }

    @Test
    fun `the site is its own box, separate from the customer's address`() {
        render(oneLine())
        scrollTo(SITE_LABEL)
        compose.field(SITE_LABEL).performTextInput("Bhiwandi godown")

        assertEquals("Bhiwandi godown", typedParty?.site)
        // It is not the address: that box is empty and stays empty.
        assertEquals("", typedParty?.address)
    }

    // --- "Save this customer" -------------------------------------------------

    @Test
    fun `saving a typed customer sends the id the panel minted`() {
        render(oneLine())
        scrollTo(BUILDER_SAVE_CUSTOMER_KEY)
        compose.onNodeWithContentDescription(SAVE_CUSTOMER).performClick()

        assertEquals(listOf("c_new_1"), savedWith)
    }

    @Test
    fun `pressing it twice sends the same id, so a retry cannot make two`() {
        // N4.4's B2. An id minted per press writes a second customer when the
        // first commit landed and its acknowledgement did not — and every
        // quotation ever issued points at one of the two.
        render(oneLine())
        scrollTo(BUILDER_SAVE_CUSTOMER_KEY)
        compose.onNodeWithContentDescription(SAVE_CUSTOMER).performClick()
        compose.onNodeWithContentDescription(SAVE_CUSTOMER).performClick()

        assertEquals(listOf("c_new_1", "c_new_1"), savedWith)
    }

    @Test
    fun `a refusal stays on the panel, where the person can act on it`() {
        render(oneLine(), customerFailure = "Enter the party's name")
        scrollTo(BUILDER_SAVE_CUSTOMER_KEY)

        compose.onNodeWithText("Enter the party's name").assertExists()
    }

    // --- the three card types ---------------------------------------------------

    @Test
    fun `a hand-typed line says so, and a catalogue line does not`() {
        render(
            oneLine().addManual("ln_m", "Site visit", rate = 2000.0)
        )
        scrollTo("ln_m")
        compose.onNodeWithText(MANUAL_LINE).assertExists()

        // One tag, on the one line that earned it.
        assertEquals(1, compose.onAllNodesWithText(MANUAL_LINE).fetchSemanticsNodes().size)
    }

    @Test
    fun `an opening shows its working, and gets no stepper`() {
        render(
            QuoteDraft(id = "qd_1").addArea(
                id = "ln_a",
                area = AreaLine(width = 3000.0, height = 3500.0, count = 2.0),
                rate = 450.0,
                title = "Rolling shutter",
                key = "rs|RS500"
            )
        )
        scrollTo("ln_a")

        // `QuoteArea.describe`, which already produces exactly this.
        compose.onNodeWithText("3000 × 3500 mm = 113.5 sq ft × ₹450 × 2 nos").assertExists()
        compose.onNodeWithText(AREA_LINE).assertExists()

        // No stepper: the quantity IS the chargeable area, recomputed from the
        // opening. Typing it directly would leave the figure and the sentence
        // describing different lines.
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription(moreOf("Rolling shutter"))
                .fetchSemanticsNodes().size
        )
        // And the absence proves its reach — the card itself is on screen.
        compose.onNodeWithContentDescription(removeLabel("Rolling shutter")).assertExists()
    }

    @Test
    fun `every line can be taken off, by its id`() {
        render(oneLine())
        scrollTo("ln_1")
        compose.onNodeWithContentDescription(removeLabel("SIE1000")).performClick()

        assertEquals("ln_1", removed)
    }

    // --- the two forms ----------------------------------------------------------

    @Test
    fun `both forms start collapsed`() {
        render(oneLine())
        scrollTo(BUILDER_ADD_MANUAL_KEY)
        compose.onNodeWithContentDescription(ADD_MANUAL).assertExists()
        compose.onNodeWithContentDescription(ADD_AREA).assertExists()

        // The boxes are not merely off screen: the two controls above prove
        // the list reached this far.
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription(DESCRIPTION_LABEL)
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun `a hand-typed line is sent under the id the panel minted`() {
        render(oneLine())
        scrollTo(BUILDER_ADD_MANUAL_KEY)
        compose.onNodeWithContentDescription(ADD_MANUAL).performClick()

        scrollTo(DESCRIPTION_LABEL)
        compose.field(DESCRIPTION_LABEL).performTextInput("Site visit")
        scrollTo(BUILDER_MANUAL_ADD_KEY)
        compose.onNodeWithContentDescription(ADD_MANUAL_LINE).performClick()

        assertEquals("ln_new_1", addedManual?.first)
        assertEquals("Site visit", addedManual?.second?.title)
    }

    @Test
    fun `a line with no description says so and cannot be added`() {
        render(oneLine())
        scrollTo(BUILDER_ADD_MANUAL_KEY)
        compose.onNodeWithContentDescription(ADD_MANUAL).performClick()
        scrollTo(BUILDER_MANUAL_ADD_KEY)

        compose.onNodeWithText(QuoteLineEntry.NO_DESCRIPTION).assertExists()
        compose.onNodeWithContentDescription(ADD_MANUAL_LINE).performClick()
        assertNull("nothing was added", addedManual)
    }

    @Test
    fun `a rate that is not a number is refused, never partly read`() {
        render(oneLine())
        scrollTo(BUILDER_ADD_MANUAL_KEY)
        compose.onNodeWithContentDescription(ADD_MANUAL).performClick()
        scrollTo(DESCRIPTION_LABEL)
        compose.field(DESCRIPTION_LABEL).performTextInput("Site visit")
        scrollTo(RATE_LABEL)
        compose.field(RATE_LABEL).performTextInput("12x")

        scrollTo(BUILDER_MANUAL_ADD_KEY)
        compose.onNodeWithText(QuoteLineEntry.NOT_A_RATE).assertExists()
        compose.onNodeWithContentDescription(ADD_MANUAL_LINE).performClick()
        assertNull("nothing was added", addedManual)
    }

    @Test
    fun `an opening shows its chargeable area as it is typed`() {
        render(oneLine())
        scrollTo(BUILDER_ADD_AREA_KEY)
        compose.onNodeWithContentDescription(ADD_AREA).performClick()

        scrollTo(DESCRIPTION_LABEL)
        compose.field(DESCRIPTION_LABEL).performTextInput("Rolling shutter")
        scrollTo(WIDTH_LABEL)
        compose.field(WIDTH_LABEL).performTextInput("3000")
        scrollTo(HEIGHT_LABEL)
        compose.field(HEIGHT_LABEL).performTextInput("3500")

        scrollTo(BUILDER_AREA_WORKING_KEY)
        compose.onNodeWithText("113.5 sq ft × 1 nos = 113.5 sq ft").assertExists()
    }

    @Test
    fun `adding an opening sends what was typed, not a parsed guess`() {
        render(oneLine())
        scrollTo(BUILDER_ADD_AREA_KEY)
        compose.onNodeWithContentDescription(ADD_AREA).performClick()
        scrollTo(DESCRIPTION_LABEL)
        compose.field(DESCRIPTION_LABEL).performTextInput("Rolling shutter")
        scrollTo(WIDTH_LABEL)
        compose.field(WIDTH_LABEL).performTextInput("3000")
        scrollTo(HEIGHT_LABEL)
        compose.field(HEIGHT_LABEL).performTextInput("3500")

        scrollTo(BUILDER_AREA_ADD_KEY)
        compose.onNodeWithContentDescription(ADD_AREA_LINE).performClick()

        assertEquals("ln_new_1", addedArea?.first)
        assertEquals("3000", addedArea?.second?.width)
        assertEquals("3500", addedArea?.second?.height)
    }

    @Test
    fun `tapping an opening reopens its form, filled in, to be saved not added`() {
        render(
            QuoteDraft(id = "qd_1").addArea(
                id = "ln_a",
                area = AreaLine(width = 3000.0, height = 3500.0, count = 2.0),
                rate = 450.0,
                title = "Rolling shutter"
            )
        )
        scrollTo("ln_a")
        compose.onNodeWithText("Rolling shutter").performClick()

        scrollTo(WIDTH_LABEL)
        // Filled in from the line, not blank.
        compose.onNodeWithText("3000").assertExists()

        scrollTo(BUILDER_AREA_ADD_KEY)
        // Save, not Add: this opening is already on the quotation.
        compose.onNodeWithContentDescription(SAVE_OPENING).performClick()

        assertEquals("ln_a", editedArea?.first)
        assertEquals("3500", editedArea?.second?.height)
        assertNull("nothing was added as a second line", addedArea)
    }
}
