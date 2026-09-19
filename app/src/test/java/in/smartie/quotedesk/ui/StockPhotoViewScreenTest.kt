package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.stock.PHOTO_OFFLINE
import `in`.smartie.quotedesk.ui.stock.PHOTO_UNAVAILABLE
import `in`.smartie.quotedesk.ui.stock.REMOVE_PHOTO
import `in`.smartie.quotedesk.ui.stock.REPLACE_PHOTO
import `in`.smartie.quotedesk.ui.stock.StockPhotoActions
import `in`.smartie.quotedesk.ui.stock.StockPhotoViewPanel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The picture at a size worth looking at, and who may change it.
 *
 * The rule the board already follows, applied here: a Worker gets **no**
 * control rather than a disabled one, because their role is not going to
 * change in a minute. Offline the controls are there and disabled, because
 * that will.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockPhotoViewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val name = "Sliding gate motor"

    @Test
    fun `a Worker sees the picture and no way to change it`() {
        compose.setContent { SmartieTheme { StockPhotoViewPanel(null, name, canManage = false) } }

        compose.onNodeWithText("Close").assertExists()
        for (control in listOf(REPLACE_PHOTO, REMOVE_PHOTO)) {
            assertTrue(
                "a Worker must not see $control",
                compose.onAllNodesWithText(control).fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun `Staff get Replace and Remove`() {
        compose.setContent { SmartieTheme { StockPhotoViewPanel(null, name, canManage = true) } }

        compose.onNodeWithText(REPLACE_PHOTO).assertIsEnabled()
        compose.onNodeWithText(REMOVE_PHOTO).assertIsEnabled()
    }

    @Test
    fun `Replace and Remove report themselves and write nothing by themselves`() {
        val events = mutableListOf<String>()
        compose.setContent {
            SmartieTheme {
                StockPhotoViewPanel(
                    image = null,
                    name = name,
                    canManage = true,
                    actions = StockPhotoActions(
                        onRetake = { events += "replace" },
                        onRemove = { events += "remove" },
                        onCancel = { events += "close" }
                    )
                )
            }
        }

        compose.onNodeWithText(REPLACE_PHOTO).performClick()
        compose.onNodeWithText(REMOVE_PHOTO).performClick()
        compose.onNodeWithText("Close").performClick()

        assertEquals(listOf("replace", "remove", "close"), events)
    }

    @Test
    fun `offline the controls are disabled and say why`() {
        compose.setContent {
            SmartieTheme {
                StockPhotoViewPanel(null, name, canManage = true, online = false)
            }
        }

        compose.onNodeWithText(REPLACE_PHOTO).assertIsNotEnabled()
        compose.onNodeWithText(REMOVE_PHOTO).assertIsNotEnabled()
        compose.onAllNodesWithText(PHOTO_OFFLINE)[0].assertIsDisplayed()
    }

    @Test
    fun `offline a Worker is told nothing, because nothing was on offer`() {
        compose.setContent {
            SmartieTheme {
                StockPhotoViewPanel(null, name, canManage = false, online = false)
            }
        }
        assertTrue(
            "an offline warning about controls they never had is noise",
            compose.onAllNodesWithText(PHOTO_OFFLINE).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `a removal in flight cannot be started twice`() {
        var removed = 0
        compose.setContent {
            SmartieTheme {
                StockPhotoViewPanel(
                    image = null,
                    name = name,
                    canManage = true,
                    saving = true,
                    actions = StockPhotoActions(onRemove = { removed++ })
                )
            }
        }

        compose.onNodeWithText(REMOVE_PHOTO).assertIsNotEnabled()
        assertEquals(0, removed)
    }

    @Test
    fun `a picture that will not load shows a placeholder, not a spinner`() {
        compose.setContent { SmartieTheme { StockPhotoViewPanel(null, name) } }

        compose.onNodeWithText(PHOTO_UNAVAILABLE).assertIsDisplayed()
        compose.onNodeWithContentDescription("No photo showing for $name").assertExists()
    }

    @Test
    fun `Close is there for everyone, online or not`() {
        compose.setContent {
            SmartieTheme {
                StockPhotoViewPanel(null, name, canManage = false, online = false)
            }
        }
        compose.onNodeWithText("Close").assertIsEnabled()
    }
}
