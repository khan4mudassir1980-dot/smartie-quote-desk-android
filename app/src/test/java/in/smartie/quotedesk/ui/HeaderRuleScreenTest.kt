package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.components.HEADER_RULE_LABEL
import `in`.smartie.quotedesk.ui.components.SmartieTopBar
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every screen header carries the one purple rule. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class HeaderRuleScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the app bar draws one rule beneath itself`() {
        compose.setContent { SmartieTheme { SmartieTopBar(title = "Our Stock") } }
        compose.onNodeWithContentDescription(HEADER_RULE_LABEL).assertExists()
    }
}
