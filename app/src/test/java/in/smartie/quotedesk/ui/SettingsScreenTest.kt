package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.SemanticsNodeInteraction
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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.NumberingRecord
import `in`.smartie.quotedesk.data.model.QuotingRecord
import `in`.smartie.quotedesk.domain.NumberingDraft
import `in`.smartie.quotedesk.ui.more.CAP_LABEL
import `in`.smartie.quotedesk.ui.more.CAP_SECTION
import `in`.smartie.quotedesk.ui.more.CAP_VIEW_ONLY
import `in`.smartie.quotedesk.ui.more.CAP_VIEW_ONLY_KEY
import `in`.smartie.quotedesk.ui.more.CONFIRM
import `in`.smartie.quotedesk.ui.more.LATER_KEY
import `in`.smartie.quotedesk.ui.more.NEXT_LABEL
import `in`.smartie.quotedesk.ui.more.NUMBERING_VIEW_ONLY
import `in`.smartie.quotedesk.ui.more.PREFIX_LABEL
import `in`.smartie.quotedesk.ui.more.PREVIEW_KEY
import `in`.smartie.quotedesk.ui.more.SAVE_CAP
import `in`.smartie.quotedesk.ui.more.SAVE_CAP_KEY
import `in`.smartie.quotedesk.ui.more.SAVE_NUMBERING
import `in`.smartie.quotedesk.ui.more.SAVE_NUMBERING_KEY
import `in`.smartie.quotedesk.ui.more.SETTINGS_LIST_TAG
import `in`.smartie.quotedesk.ui.more.SettingsActions
import `in`.smartie.quotedesk.ui.more.SettingsCapabilities
import `in`.smartie.quotedesk.ui.more.SettingsScreen
import `in`.smartie.quotedesk.ui.more.VIEW_ONLY_KEY
import `in`.smartie.quotedesk.ui.more.YEAR_LABEL
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Settings: who is offered a control, and what has to be confirmed first.
 *
 * **The screen offers only what the rules would accept.** Configuring the
 * counter and setting the discount limit are both the Owner's, so an
 * Administrator and a Manager are shown the same facts with no controls at
 * all. A control that earns a permission error is worse than no control.
 *
 * **Every assertion scrolls first.** A `LazyColumn` never composes an
 * off-screen item, so an un-scrolled `onNodeWithContentDescription` cannot
 * tell a missing control from one below the fold — and the discount limit sits
 * below four numbering fields and a Save button. The absence checks prove
 * their own reach: each also finds a *neighbouring* node, so "no Save control"
 * cannot quietly mean "nothing was composed".
 *
 * Stored `staff` is displayed **Manager**.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val stored = NumberingRecord(
        prefix = "SIE/QD", financialYear = "2025-26", next = 9, pad = 3
    )
    private val cap = QuotingRecord(managerDiscountPct = 10.0)

    private val ownerCan = SettingsCapabilities(canConfigureNumbering = true, canSetDiscountCap = true)
    private val viewerOnly = SettingsCapabilities(canConfigureNumbering = false, canSetDiscountCap = false)

    private var savedNumbering: NumberingDraft? = null
    private var savedCap: Double? = null

    private fun screen(
        capabilities: SettingsCapabilities,
        numbering: NumberingRecord? = stored,
        quoting: QuotingRecord? = cap
    ) {
        compose.setContent {
            SmartieTheme {
                SettingsScreen(
                    numbering = numbering,
                    quoting = quoting,
                    capabilities = capabilities,
                    actions = SettingsActions(
                        onSaveNumbering = { savedNumbering = it },
                        onSaveDiscountCap = { savedCap = it },
                    ),
                )
            }
        }
    }

    // --- looking at the whole screen rather than the viewport ---------------------

    private fun scrollTo(key: String) =
        compose.onNodeWithTag(SETTINGS_LIST_TAG).performScrollToKey(key)

    private fun box(label: String): SemanticsNodeInteraction {
        scrollTo(label)
        return compose.field(label)
    }

    private fun tap(key: String, description: String) {
        scrollTo(key)
        compose.onNodeWithContentDescription(description).performClick()
    }

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun offers(description: String): Boolean =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    /**
     * A control that is present but refused, which is what a guard looks like
     * — as opposed to one that is missing, which is what a role looks like.
     * Both are asserted, because "not enabled" on a node that was never
     * composed is an error rather than a pass.
     */
    private fun assertRefused(key: String, label: String) {
        scrollTo(key)
        assertTrue("the control is there to be refused", offers(label))
        compose.onNodeWithText(label).assertIsNotEnabled()
    }

    private fun retype(label: String, value: String) {
        box(label).performTextClearance()
        box(label).performTextInput(value)
    }

    // --- the preview ----------------------------------------------------------------

    @Test
    fun `the screen shows the next number exactly as it will be issued`() {
        screen(ownerCan)
        scrollTo(PREVIEW_KEY)

        assertTrue(shows("SIE/QD/2025-26/009"))
    }

    @Test
    fun `and the preview follows what is being typed, before anything is saved`() {
        // The point of the preview: four fields nobody assembles in their head
        // into a quotation number, and an error in one is invisible until a
        // document has gone out under the wrong reference.
        screen(ownerCan)
        retype(YEAR_LABEL, "2026-27")
        retype(NEXT_LABEL, "10")
        scrollTo(PREVIEW_KEY)

        assertTrue(shows("SIE/QD/2026-27/010"))
        assertNull("and nothing was written", savedNumbering)
    }

    // --- what each role is offered ----------------------------------------------------

    @Test
    fun `an Administrator sees the numbering but is offered no control`() {
        screen(viewerOnly)
        scrollTo(VIEW_ONLY_KEY)

        // The view-only note sits exactly where the Save button would be, so
        // finding it proves that part of the list composed and the control is
        // genuinely absent rather than merely off screen.
        assertTrue("it says why there is nothing to press", shows(NUMBERING_VIEW_ONLY))
        assertTrue("the facts are there", shows("2025-26"))
        assertTrue("but no save control", !offers(SAVE_NUMBERING))
    }

    @Test
    fun `and is offered no discount control either, though the limit is readable`() {
        screen(viewerOnly)
        scrollTo(CAP_VIEW_ONLY_KEY)

        // The neighbouring node proves this part of the list composed, so the
        // absence below is about the screen rather than the viewport.
        assertTrue("the limit reads", shows("10%"))
        assertTrue(shows(CAP_VIEW_ONLY))
        assertTrue("but no save control", !offers(SAVE_CAP))
    }

    @Test
    fun `an Owner is offered both`() {
        screen(ownerCan)

        scrollTo(SAVE_NUMBERING_KEY)
        assertTrue(offers(SAVE_NUMBERING))
        scrollTo(SAVE_CAP_KEY)
        assertTrue(offers(SAVE_CAP))
    }

    // --- the guard on `next` -----------------------------------------------------------

    @Test
    fun `a next that is already spent is refused as it is typed, and says which to use`() {
        // Not on Save — while they type. The rules refuse the same write, so
        // the only thing this adds is telling the person what to type instead.
        screen(ownerCan)
        retype(NEXT_LABEL, "8")

        assertTrue("names the number already reached", shows("SIE/QD/2025-26/009"))
        assertTrue("and the lowest one allowed", shows("type 10 or more"))
        assertRefused(SAVE_NUMBERING_KEY, SAVE_NUMBERING)
    }

    @Test
    fun `but the stored number itself is fine, because leaving it alone changes nothing`() {
        // `next` is what the *next* quotation will carry, so 9 has not gone
        // out. Refusing it would mean correcting a prefix cost a quotation
        // number — which is exactly the defect the deployed rule carried for
        // one commit, and what this test now stops coming back.
        screen(ownerCan)
        retype(NEXT_LABEL, "9")

        scrollTo(SAVE_NUMBERING_KEY)
        compose.onNodeWithText(SAVE_NUMBERING).assertIsEnabled()
        assertTrue("and no refusal is shown", !shows("only move forward"))
    }

    @Test
    fun `a forward correction saves, once the consequence has been agreed to`() {
        screen(ownerCan)
        retype(NEXT_LABEL, "40")

        tap(SAVE_NUMBERING_KEY, SAVE_NUMBERING)

        // The confirmation names what will happen, in plain words, before it
        // happens — the numbers in between are never issued.
        assertTrue(shows("SIE/QD/2025-26/040"))
        assertTrue(shows("never issued"))
        assertNull("and still nothing is written until it is agreed to", savedNumbering)

        compose.onNodeWithText(CONFIRM).performClick()
        assertEquals(40, savedNumbering?.next)
    }

    @Test
    fun `declining the confirmation writes nothing`() {
        screen(ownerCan)
        retype(NEXT_LABEL, "40")
        tap(SAVE_NUMBERING_KEY, SAVE_NUMBERING)

        compose.onNodeWithText("Cancel").performClick()

        assertNull(savedNumbering)
    }

    @Test
    fun `rolling the financial year is confirmed in its own words`() {
        screen(ownerCan)
        retype(YEAR_LABEL, "2026-27")
        retype(NEXT_LABEL, "1")

        tap(SAVE_NUMBERING_KEY, SAVE_NUMBERING)

        assertTrue("says the year is restarting", shows("financial year"))
        assertTrue("and that issued quotations are untouched", shows("2025-26"))

        compose.onNodeWithText(CONFIRM).performClick()
        assertEquals("2026-27", savedNumbering?.financialYear)
        assertEquals(1, savedNumbering?.next)
    }

    @Test
    fun `changing only the prefix saves without a confirmation`() {
        // Confirming every edit is confirming nothing: a prefix changes how
        // the next number reads, which the preview already shows.
        screen(ownerCan)
        retype(PREFIX_LABEL, "SIE/QT")

        tap(SAVE_NUMBERING_KEY, SAVE_NUMBERING)

        assertEquals("SIE/QT", savedNumbering?.prefix)
    }

    // --- the discount limit --------------------------------------------------------------

    @Test
    fun `the Owner sets the limit`() {
        screen(ownerCan)
        retype(CAP_LABEL, "15")

        tap(SAVE_CAP_KEY, SAVE_CAP)

        assertEquals(15.0, savedCap!!, 0.0)
    }

    @Test
    fun `a limit outside nought to a hundred is refused before it is sent`() {
        screen(ownerCan)
        retype(CAP_LABEL, "150")

        assertRefused(SAVE_CAP_KEY, SAVE_CAP)
        assertNull(savedCap)
        assertTrue(shows("between 0% and 100%"))
    }

    @Test
    fun `an unseeded counter says so rather than looking broken`() {
        screen(ownerCan, numbering = null, quoting = null)
        scrollTo(PREVIEW_KEY)

        assertTrue(shows("has not been set up"))
        // And a missing cap reads as nought, which is what the rules do with
        // it — not as "no limit".
        scrollTo(CAP_SECTION)
        assertTrue(shows("cannot discount at all"))
    }

    @Test
    fun `the screen says what it does not hold yet`() {
        screen(ownerCan)
        scrollTo(LATER_KEY)

        assertTrue(shows("Company details"))
    }
}
