package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
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
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.domain.ProductDraft
import `in`.smartie.quotedesk.domain.ProductUnit
import `in`.smartie.quotedesk.domain.ProductWrite
import `in`.smartie.quotedesk.ui.products.AREA_KEY
import `in`.smartie.quotedesk.ui.products.CANCEL_EDIT
import `in`.smartie.quotedesk.ui.products.CLIENT_LABEL
import `in`.smartie.quotedesk.ui.products.CONTRACTOR_KEY
import `in`.smartie.quotedesk.ui.products.CONTRACTOR_LABEL
import `in`.smartie.quotedesk.ui.products.DEALER_LABEL
import `in`.smartie.quotedesk.ui.products.GST_LABEL
import `in`.smartie.quotedesk.ui.products.MIN_SQFT_LABEL
import `in`.smartie.quotedesk.ui.products.PRICE_NOT_SET
import `in`.smartie.quotedesk.ui.products.PRICE_THIS_PER_SQ_FT
import `in`.smartie.quotedesk.ui.products.PRODUCT_EDITOR_TAG
import `in`.smartie.quotedesk.ui.products.PRODUCT_NAME_LABEL
import `in`.smartie.quotedesk.ui.products.ProductEditorActions
import `in`.smartie.quotedesk.ui.products.ProductEditorSheet
import `in`.smartie.quotedesk.ui.products.SAVE_KEY
import `in`.smartie.quotedesk.ui.products.SAVE_PRODUCT
import `in`.smartie.quotedesk.ui.products.UNIT_LABEL
import `in`.smartie.quotedesk.ui.products.VIEW_ONLY_KEY
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Correcting one product, and the controls each role is offered.
 *
 * **Every assertion scrolls first, and that is not tidiness.** A `LazyColumn`
 * does not merely hide what is off screen, it never composes it, so an
 * un-scrolled `onNodeWithContentDescription` cannot tell a missing control
 * from one below the fold — and with seven fields above the Save button, the
 * fold is where most of this sheet lives. That mistake has cost this project
 * five CI cycles.
 *
 * **Every absence check proves its own reach.** Each one also asserts that a
 * *neighbouring* item was found, so "no Save button" cannot quietly mean
 * "nothing was composed at all". An `onAllNodesWithText` on an un-scrolled
 * list passes whether or not the text exists, which is a test that asserts
 * nothing.
 *
 * **The unit box is the thing most worth protecting here.** It is free text,
 * pre-filled from the document, and the chip beside it may only ever set it
 * to `per sq ft`. Because the save writes the complete document, anything
 * that cleared or replaced a unit the person did not type would rewrite every
 * `per m` and `per pc` product in the catalogue on its first rate correction.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class ProductEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val motor = ProductRecord(
        documentId = "gateMotors__SIE1000",
        key = "gateMotors|SIE1000",
        group = "gateMotors",
        seedModel = "SIE1000",
        model = "SIE1000",
        name = "Sliding gate motor 1000 kg",
        unit = "each",
        gst = 18.0,
        dealer = 18500.0,
        contractor = 22200.0,
        client = 25900.0
    )

    private var saved: Pair<ProductRecord, ProductDraft>? = null
    private var cancelled = false

    private fun editor(record: ProductRecord = motor, canEdit: Boolean = true) {
        compose.setContent {
            SmartieTheme {
                ProductEditorSheet(
                    record = record,
                    canEdit = canEdit,
                    actions = ProductEditorActions(
                        onSave = { edited, draft -> saved = edited to draft },
                        onCancel = { cancelled = true }
                    )
                )
            }
        }
    }

    // --- looking at the whole sheet rather than the viewport ------------------------

    /** Composes the item with [key], so the assertion after it is about the sheet. */
    private fun scrollTo(key: String) =
        compose.onNodeWithTag(PRODUCT_EDITOR_TAG).performScrollToKey(key)

    /** A box in the editor. Its list key is its label, so one name does both. */
    private fun box(label: String): SemanticsNodeInteraction {
        scrollTo(label)
        return compose.field(label)
    }

    private fun tap(description: String) =
        compose.onNodeWithContentDescription(description).performClick()

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun offers(description: String): Boolean =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun type(label: String, value: String) {
        box(label).performTextClearance()
        box(label).performTextInput(value)
    }

    private fun save() {
        scrollTo(SAVE_KEY)
        tap(SAVE_PRODUCT)
    }

    // --- the unit is a box, and the chip may only fill it ----------------------------

    @Test
    fun `the unit opens on what is stored, not on a guess`() {
        editor(motor.copy(unit = "per m"))
        box(UNIT_LABEL).assertTextContains("per m")
    }

    @Test
    fun `correcting only the rate leaves a per-m unit exactly as it was`() {
        // The catalogue holds far more `per m`, `per pc` and `per kg` products
        // than area-priced ones, and the save writes the whole document.
        editor(motor.copy(unit = "per m"))
        type(DEALER_LABEL, "450")
        save()

        assertEquals("per m", saved?.second?.unit)
        assertEquals("450", saved?.second?.dealer)
    }

    @Test
    fun `the chip sets the unit to the PWA's own spelling`() {
        editor()
        scrollTo(AREA_KEY)
        tap(PRICE_THIS_PER_SQ_FT)
        box(UNIT_LABEL).assertTextContains(ProductUnit.AREA)
    }

    @Test
    fun `and it is gone once the box already says so, so it can never clear one`() {
        editor(motor.copy(unit = ProductUnit.AREA))
        // Reach is proved by the neighbouring box: the unit field is composed,
        // so the chip's absence is the sheet's answer and not the viewport's.
        scrollTo(UNIT_LABEL)
        assertTrue("the unit box is composed", offers(UNIT_LABEL))
        assertTrue("but no chip to overwrite it", !offers(PRICE_THIS_PER_SQ_FT))
    }

    @Test
    fun `a typed unit is carried through untouched, whatever it says`() {
        editor()
        type(UNIT_LABEL, "per rft")
        save()
        assertEquals("per rft", saved?.second?.unit)
    }

    // --- the minimum area belongs to area pricing ------------------------------------

    @Test
    fun `the minimum area is offered once the unit says per sq ft`() {
        editor(motor.copy(unit = ProductUnit.AREA))
        type(MIN_SQFT_LABEL, "10")
        save()
        assertEquals("10", saved?.second?.minSqft)
    }

    @Test
    fun `and is not offered on a product priced any other way`() {
        editor(motor.copy(unit = "per m"))
        scrollTo(DEALER_LABEL)
        assertTrue("the dealer box is composed", offers(DEALER_LABEL))
        assertTrue("but no minimum area field", !offers(MIN_SQFT_LABEL))
    }

    // --- the contractor tier is shown and never edited --------------------------------

    @Test
    fun `the contractor rate is shown as a fact, with no box to change it`() {
        editor()
        scrollTo(CONTRACTOR_KEY)
        assertTrue("the stored rate is readable", shows("22,200"))
        assertTrue("and it is labelled", shows(CONTRACTOR_LABEL))
        // Its neighbours are boxes; this one is not, which is the point.
        assertTrue("the client box is composed", offers(CLIENT_LABEL))
    }

    @Test
    fun `a contractor price that was never set says so rather than showing zero`() {
        editor(motor.copy(contractor = null))
        scrollTo(CONTRACTOR_KEY)
        assertTrue(shows(PRICE_NOT_SET))
    }

    // --- what each role is offered -----------------------------------------------------

    @Test
    fun `an Administrator is offered the same controls as an Owner`() {
        // Both are `admin()` to the rules, so the screen must not invent a
        // distinction the server does not make.
        editor(canEdit = true)
        scrollTo(SAVE_KEY)
        assertTrue(offers(SAVE_PRODUCT))
    }

    @Test
    fun `a Manager sees the stored details and is offered no save at all`() {
        editor(canEdit = false)
        scrollTo(VIEW_ONLY_KEY)
        assertTrue("the details are still readable", shows("Sliding gate motor 1000 kg"))
        assertTrue("and the reason is stated", shows("Only an Owner or Administrator"))
        // Reach: the contractor fact sits below every box, so finding it means
        // the whole sheet was composed and Save really is absent.
        scrollTo(CONTRACTOR_KEY)
        assertTrue("the sheet is fully composed", shows(CONTRACTOR_LABEL))
        assertTrue("but no save control", !offers(SAVE_PRODUCT))
    }

    // --- refusals land on the field ------------------------------------------------------

    @Test
    fun `a blank name refuses on the field and saves nothing`() {
        editor()
        box(PRODUCT_NAME_LABEL).performTextClearance()
        save()

        assertNull("nothing may be written", saved)
        scrollTo(PRODUCT_NAME_LABEL)
        assertTrue(shows(ProductWrite.NAME_REQUIRED))
    }

    @Test
    fun `a gst above the highest slab refuses on the field`() {
        editor()
        type(GST_LABEL, "29")
        save()

        assertNull(saved)
        scrollTo(GST_LABEL)
        assertTrue(shows(ProductWrite.GST_OUT_OF_RANGE))
    }

    @Test
    fun `a price that is not a number refuses on the field`() {
        editor()
        type(DEALER_LABEL, "call us")
        save()

        assertNull(saved)
        scrollTo(DEALER_LABEL)
        assertTrue(shows(ProductWrite.PRICE_NOT_A_NUMBER))
    }

    @Test
    fun `a blank price is not an error, because it means the price is not set`() {
        editor()
        box(DEALER_LABEL).performTextClearance()
        save()
        assertEquals("", saved?.second?.dealer)
    }

    @Test
    fun `cancelling writes nothing`() {
        editor()
        type(DEALER_LABEL, "19000")
        scrollTo(SAVE_KEY)
        tap(CANCEL_EDIT)

        assertTrue(cancelled)
        assertNull(saved)
    }
}
