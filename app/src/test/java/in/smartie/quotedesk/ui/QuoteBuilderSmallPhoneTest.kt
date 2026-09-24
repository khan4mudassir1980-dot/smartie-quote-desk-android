package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.domain.Installation
import `in`.smartie.quotedesk.domain.InstallationMode
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.ui.products.BUILDER_INSTALLATION_KEY
import `in`.smartie.quotedesk.ui.products.INSTALLATION_LABEL
import `in`.smartie.quotedesk.ui.products.QUOTE_BUILDER_TAG
import `in`.smartie.quotedesk.ui.products.QuoteBuilderPanel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The installation mode picker at 360dp, which is where it would clip.
 *
 * **Four options in one `SegmentedChoice` do not fit, and that is arithmetic
 * rather than a worry.** It weights its labels equally and clips each to one
 * line, so four share 360dp at roughly 78dp apiece less padding — and
 * "% of products" and "Fixed amount" do not fit in that. The label would be
 * truncated silently, and on a control deciding how installation is charged
 * that is the difference between a fixed ₹8 and 8% of the products.
 *
 * `SegmentedChoiceGrid` lays them two to a row, so each label has half the
 * width rather than a quarter. This is the test that says so.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class QuoteBuilderSmallPhoneTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(draft: QuoteDraft) {
        compose.setContent {
            SmartieTheme {
                QuoteBuilderPanel(
                    draft = draft,
                    onBack = {},
                    onTierChange = {},
                    onChangeLineQuantity = { _, _ -> },
                    onRemoveLine = {},
                    onClear = {}
                )
            }
        }
    }

    private fun charging() = QuoteDraft(id = "qd_1")
        .addManual("ln_1", "Motor", rate = 22_200.0)
        .copy(installation = Installation(InstallationMode.PERCENT, 8.0, 22_200.0))

    @Test
    fun `every installation mode is readable whole at 360dp`() {
        render(charging())
        compose.onNodeWithTag(QUOTE_BUILDER_TAG).performScrollToKey(BUILDER_INSTALLATION_KEY)

        // All four labels in full. A clipped one still reports its text to the
        // semantics tree, so this alone would not catch a truncation — the
        // painted-width check below is what does.
        InstallationMode.entries.forEach { mode ->
            compose.onNodeWithText(mode.label).assertExists()
        }

        val card = compose.onNodeWithTag(QUOTE_BUILDER_TAG).fetchSemanticsNode().boundsInRoot
        InstallationMode.entries.forEach { mode ->
            val node = compose.onNodeWithText(mode.label).fetchSemanticsNode()
            val whole = node.unclippedBounds()
            val painted = node.boundsInRoot

            assertTrue("${mode.label} was never laid out: $whole", whole.width > 0f)
            assertTrue(
                "${mode.label} is clipped — $painted painted of $whole",
                painted.width >= whole.width - 0.5f
            )
            assertTrue(
                "${mode.label} falls outside the panel — $whole against $card",
                whole.left >= card.left - 0.5f && whole.right <= card.right + 0.5f
            )
        }
    }

    @Test
    fun `switching installation off leaves nothing to clip`() {
        render(charging())
        compose.onNodeWithTag(QUOTE_BUILDER_TAG).performScrollToKey(BUILDER_INSTALLATION_KEY)
        compose.onNodeWithContentDescription(INSTALLATION_LABEL).performClick()

        // The panel is still there and still scrollable: turning a charge off
        // must not take the block away with it.
        compose.onNodeWithTag(QUOTE_BUILDER_TAG).assertExists()
    }
}
