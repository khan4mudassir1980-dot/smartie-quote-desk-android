package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteParty
import `in`.smartie.quotedesk.ui.products.BUILDER_SAVE_CUSTOMER_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_TYPE_ANSWERS_KEY
import `in`.smartie.quotedesk.ui.products.DONT_SAVE_CUSTOMER
import `in`.smartie.quotedesk.ui.products.QUOTE_BUILDER_TAG
import `in`.smartie.quotedesk.ui.products.QuoteBuilderPanel
import `in`.smartie.quotedesk.ui.products.SAVE_CUSTOMER
import `in`.smartie.quotedesk.ui.products.saveAsLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Save this customer" on a new customer asks Dealer or Client, with no
 * default (N5.10 commit 9, amendment D). The question and its three answers,
 * on the builder; `PartyWriteRepositoryTest` has what each answer writes.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class QuoteBuilderPartyTypeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val answers = mutableListOf<RateTierV2?>()
    private val question = QuoteParty.typeQuestion("Metro Glass")

    private fun render(typeQuestion: String? = question) {
        compose.setContent {
            SmartieTheme {
                QuoteBuilderPanel(
                    draft = QuoteDraft(id = "qd_1", party = QuotationPartySnapshot(name = "Metro Glass"))
                        .addManual(id = "ln_1", title = "Site visit", rate = 1_000.0),
                    onBack = {},
                    onTierChange = {},
                    onChangeLineQuantity = { _, _ -> },
                    onRemoveLine = {},
                    onClear = {},
                    savingCustomer = typeQuestion != null,
                    typeQuestion = typeQuestion,
                    onAnswerType = { answers += it }
                )
            }
        }
        compose.onNodeWithTag(QUOTE_BUILDER_TAG).performScrollToKey(BUILDER_TYPE_ANSWERS_KEY.takeIf { typeQuestion != null }
            ?: BUILDER_SAVE_CUSTOMER_KEY)
    }

    @Test
    fun `the question names the customer, and Dealer answers Dealer`() {
        render()
        compose.onNodeWithText(question).assertExists()
        compose.onNodeWithContentDescription(saveAsLabel(RateTierV2.DEALER)).performClick()

        assertEquals(listOf<RateTierV2?>(RateTierV2.DEALER), answers)
    }

    @Test
    fun `Client answers Client`() {
        render()
        compose.onNodeWithContentDescription(saveAsLabel(RateTierV2.CLIENT)).performClick()

        assertEquals(listOf<RateTierV2?>(RateTierV2.CLIENT), answers)
    }

    @Test
    fun `Cancel answers nothing chosen`() {
        render()
        compose.onNodeWithContentDescription(DONT_SAVE_CUSTOMER).performClick()

        assertEquals(listOf<RateTierV2?>(null), answers)
    }

    @Test
    fun `with no question there are no answers - Save this customer, beside them, proves the reach`() {
        render(typeQuestion = null)

        compose.onNodeWithContentDescription(SAVE_CUSTOMER).assertExists()
        assertEquals(0, compose.onAllNodesWithContentDescription(saveAsLabel(RateTierV2.DEALER)).fetchSemanticsNodes().size)
    }
}
