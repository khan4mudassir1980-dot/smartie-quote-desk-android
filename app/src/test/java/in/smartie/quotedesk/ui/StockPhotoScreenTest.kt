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
import `in`.smartie.quotedesk.domain.StockPhotoImage
import `in`.smartie.quotedesk.ui.stock.ADD_PHOTO
import `in`.smartie.quotedesk.ui.stock.CHOOSE_FROM_GALLERY
import `in`.smartie.quotedesk.ui.stock.PHOTO_OFFLINE
import `in`.smartie.quotedesk.ui.stock.PREPARING
import `in`.smartie.quotedesk.ui.stock.REMOVE_PHOTO
import `in`.smartie.quotedesk.ui.stock.RETAKE
import `in`.smartie.quotedesk.ui.stock.StockPhotoActions
import `in`.smartie.quotedesk.ui.stock.StockPhotoPreviewPanel
import `in`.smartie.quotedesk.ui.stock.StockPhotoSourcePanel
import `in`.smartie.quotedesk.ui.stock.TAKE_PHOTO
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The photo sheets. Panels, not dialogs, for the reason recorded in
 * `docs/PROJECT-STATUS.md`.
 *
 * The point most of these make: **nothing is written before confirmation**.
 *
 * Every preview test leaves the drawn bitmap null. That is deliberate and not
 * a shortcut around Robolectric's graphics: the sheet's confirm button is tied
 * to the *bytes that will be written*, never to the bitmap, so a picture whose
 * preview could not be rendered is still savable and one that renders without
 * prepared bytes is still not.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockPhotoScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** Bytes of the size a real compressed photo would be; content is immaterial here. */
    private fun prepared(size: Int = 40_000) = StockPhotoImage(ByteArray(size), 800, 600)

    // --- choosing a source -------------------------------------------------

    @Test
    fun `the source sheet offers exactly two ways in`() {
        compose.setContent { SmartieTheme { StockPhotoSourcePanel(online = true) } }

        compose.onNodeWithText(TAKE_PHOTO).assertIsEnabled()
        compose.onNodeWithText(CHOOSE_FROM_GALLERY).assertIsEnabled()
        // Nothing about saving belongs on this sheet.
        assertTrue(compose.onAllNodesWithText(ADD_PHOTO).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `each way in reports itself and writes nothing`() {
        val taken = mutableListOf<String>()
        compose.setContent {
            SmartieTheme {
                StockPhotoSourcePanel(
                    online = true,
                    actions = StockPhotoActions(
                        onTakePhoto = { taken += "camera" },
                        onChooseFromGallery = { taken += "gallery" },
                        onConfirm = { taken += "WRITTEN" }
                    )
                )
            }
        }

        compose.onNodeWithText(TAKE_PHOTO).performClick()
        compose.onNodeWithText(CHOOSE_FROM_GALLERY).performClick()
        assertEquals(listOf("camera", "gallery"), taken)
    }

    @Test
    fun `Remove is offered only when there is something to remove`() {
        compose.setContent { SmartieTheme { StockPhotoSourcePanel(online = true) } }
        assertTrue(compose.onAllNodesWithText(REMOVE_PHOTO).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `Remove appears for a row that has a photo`() {
        var removed = false
        compose.setContent {
            SmartieTheme {
                StockPhotoSourcePanel(
                    online = true,
                    canRemove = true,
                    actions = StockPhotoActions(onRemove = { removed = true })
                )
            }
        }
        compose.onNodeWithText(REMOVE_PHOTO).performClick()
        assertTrue(removed)
    }

    @Test
    fun `offline the source sheet offers nothing and says why`() {
        compose.setContent {
            SmartieTheme { StockPhotoSourcePanel(online = false, canRemove = true) }
        }

        compose.onNodeWithText(TAKE_PHOTO).assertIsNotEnabled()
        compose.onNodeWithText(CHOOSE_FROM_GALLERY).assertIsNotEnabled()
        compose.onNodeWithText(REMOVE_PHOTO).assertIsNotEnabled()
        compose.onAllNodesWithText(PHOTO_OFFLINE)[0].assertIsDisplayed()
    }

    // --- the preview -------------------------------------------------------

    @Test
    fun `a prepared picture can be confirmed, and only then is anything written`() {
        var confirmed = 0
        compose.setContent {
            SmartieTheme {
                StockPhotoPreviewPanel(
                    prepared = prepared(),
                    actions = StockPhotoActions(onConfirm = { confirmed++ })
                )
            }
        }

        assertEquals("nothing written on arrival", 0, confirmed)
        compose.onNodeWithText(ADD_PHOTO).assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `cancelling and choosing another write nothing`() {
        val events = mutableListOf<String>()
        compose.setContent {
            SmartieTheme {
                StockPhotoPreviewPanel(
                    prepared = prepared(),
                    actions = StockPhotoActions(
                        onConfirm = { events += "WRITTEN" },
                        onRetake = { events += "retake" },
                        onCancel = { events += "cancel" }
                    )
                )
            }
        }

        compose.onNodeWithText(RETAKE).performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("retake", "cancel"), events)
    }

    @Test
    fun `a picture still being prepared cannot be confirmed`() {
        compose.setContent { SmartieTheme { StockPhotoPreviewPanel(prepared = null) } }

        compose.onNodeWithText(PREPARING).assertIsDisplayed()
        compose.onNodeWithText(ADD_PHOTO).assertIsNotEnabled()
    }

    @Test
    fun `a refusal is shown and the picture cannot be saved`() {
        compose.setContent {
            SmartieTheme {
                StockPhotoPreviewPanel(
                    prepared = prepared(),
                    refusal = "That picture could not be made small enough"
                )
            }
        }

        compose.onNodeWithContentDescription("Why this picture cannot be saved").assertExists()
        compose.onNodeWithText(ADD_PHOTO).assertIsNotEnabled()
    }

    @Test
    fun `offline the preview cannot be confirmed and says why`() {
        compose.setContent {
            SmartieTheme { StockPhotoPreviewPanel(prepared = prepared(), online = false) }
        }

        compose.onNodeWithText(ADD_PHOTO).assertIsNotEnabled()
        compose.onAllNodesWithText(PHOTO_OFFLINE)[0].assertIsDisplayed()
    }

    @Test
    fun `a save already in flight cannot be started twice`() {
        var confirmed = 0
        compose.setContent {
            SmartieTheme {
                StockPhotoPreviewPanel(
                    prepared = prepared(),
                    saving = true,
                    actions = StockPhotoActions(onConfirm = { confirmed++ })
                )
            }
        }

        compose.onNodeWithContentDescription("Saving the photo").assertIsNotEnabled()
        assertEquals(0, confirmed)
    }

    @Test
    fun `the size is shown in whole kilobytes so the cost is not a surprise`() {
        compose.setContent { SmartieTheme { StockPhotoPreviewPanel(prepared = prepared(40_960)) } }
        compose.onNodeWithText("40 KB, stored with the item").assertExists()
    }

    @Test
    fun `a size that is not a whole number of kilobytes rounds up`() {
        compose.setContent { SmartieTheme { StockPhotoPreviewPanel(prepared = prepared(40_961)) } }
        compose.onNodeWithText("41 KB, stored with the item").assertExists()
    }
}
