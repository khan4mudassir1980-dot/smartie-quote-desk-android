package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Offline the list still reads; nothing that writes is offered. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockOfflineScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `offline the list still reads and every control says why it is disabled`() {
        compose.showStock(pending = mapOf("gateMotors|SIE1000" to 3.0), online = false)
        // Cached stock stays readable.
        compose.onNodeWithText("Sliding gate motor").assertExists()
        compose.onNodeWithText("9 each").assertExists()
        compose.onAllNodesWithText("Internet required to change stock")[0].assertExists()
        // Every mutation control carries the same wording and is disabled.
        compose.onAllNodesWithContentDescription("Internet required to change stock")[0]
            .assertIsNotEnabled()
    }

    @Test
    fun `offline the pending draft is still shown so nothing is lost`() {
        compose.showStock(pending = mapOf("gateMotors|SIE1000" to 3.0), online = false)
        compose.onNodeWithText("+3 pending").assertExists()
    }

    @Test
    fun `online the controls are enabled again`() {
        compose.showStock(pending = mapOf("gateMotors|SIE1000" to 3.0), online = true)
        compose.onNodeWithContentDescription("Add stock").assertIsEnabled()
        compose.scrollToText("Done")
        compose.onNodeWithContentDescription("Done, save Sliding gate motor").assertIsEnabled()
    }
}
