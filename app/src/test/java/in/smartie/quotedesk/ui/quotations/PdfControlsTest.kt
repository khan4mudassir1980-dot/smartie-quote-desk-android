package `in`.smartie.quotedesk.ui.quotations

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.domain.PdfAction
import `in`.smartie.quotedesk.domain.QuotationOutput
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The PDF controls shared by the builder and the detail (N5.11 commit 10).
 * "Send with" is driven through its panel, not its dialog: a Compose dialog's
 * own window is not driven by Robolectric's clock (`StockRolesScreenTest`).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PdfControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<String?>()
    private val pressed = mutableListOf<PdfAction>()

    @Test
    fun `Send with offers WhatsApp, WhatsApp Business and Cancel, and remembers nothing`() {
        compose.setContent { SmartieTheme { SendWithChoices { chosen += it } } }

        compose.onNodeWithText(QuotationOutput.SEND_WITH).assertExists()
        compose.onNodeWithContentDescription(QuotationOutput.WHATSAPP_BUSINESS_LABEL).performClick()
        compose.onNodeWithContentDescription(QuotationOutput.WHATSAPP_LABEL).performClick()
        compose.onNodeWithContentDescription(SEND_WITH_CANCEL).performClick()

        assertEquals(listOf(QuotationOutput.WHATSAPP_BUSINESS, QuotationOutput.WHATSAPP, null), chosen)
    }

    @Test
    fun `the row's three buttons each ask for their own output`() {
        compose.setContent { SmartieTheme { PdfButtons(onOutput = { pressed += it }, enabled = true, preparing = false) } }

        compose.onNodeWithContentDescription(WHATSAPP).performClick()
        compose.onNodeWithContentDescription(DOWNLOAD).performClick()
        compose.onNodeWithContentDescription(PRINT).performClick()

        assertEquals(listOf(PdfAction.SHARE, PdfAction.DOWNLOAD, PdfAction.PRINT), pressed)
    }

    @Test
    fun `disabled, a press does nothing`() {
        compose.setContent { SmartieTheme { PdfButtons(onOutput = { pressed += it }, enabled = false, preparing = false) } }

        compose.onNodeWithContentDescription(DOWNLOAD).assertIsNotEnabled().performClick()

        assertTrue(pressed.isEmpty())
    }

    @Test
    fun `while a PDF is made, the row says so in place of the buttons`() {
        compose.setContent { SmartieTheme { PdfButtons(onOutput = { pressed += it }, enabled = true, preparing = true) } }

        compose.onNodeWithContentDescription(PREPARING_PDF).assertExists()
        assertEquals(0, compose.onAllNodesWithContentDescription(PRINT).fetchSemanticsNodes().size)
    }

    @Test
    fun `what a download says, word for word`() {
        assertEquals(
            "Saved to Downloads/SMARTIE as Quotation-TEST-001-Client.pdf",
            savedToDownloads("Quotation-TEST-001-Client.pdf")
        )
        assertEquals("Saved as Quotation-TEST-001-Client.pdf", savedAs("Quotation-TEST-001-Client.pdf"))
    }
}
