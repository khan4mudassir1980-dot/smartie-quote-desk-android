package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.ui.purchase.CONFIRM_EDIT
import `in`.smartie.quotedesk.ui.purchase.EditRequirementPanel
import `in`.smartie.quotedesk.ui.purchase.NAME_LABEL
import `in`.smartie.quotedesk.ui.purchase.PurchaseActions
import `in`.smartie.quotedesk.ui.purchase.QUANTITY_LABEL
import `in`.smartie.quotedesk.ui.purchase.SetUrgencyPanel
import `in`.smartie.quotedesk.ui.purchase.urgencyOptionLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Editing a requirement, and changing just its urgency from the card. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseEditPanelScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val record = PurchaseRecord(
        id = "pr_one",
        name = "Sliding gate rack",
        quantity = 6.0,
        urgency = UrgencyV2.URGENT,
        note = "For the Kandivali site"
    )

    @Test
    fun `the sheet opens on what is stored`() {
        compose.setContent { SmartieTheme { EditRequirementPanel(record = record) } }

        compose.onNodeWithText("Sliding gate rack").assertIsDisplayed()
        compose.onNodeWithText("For the Kandivali site").assertIsDisplayed()
        compose.onNodeWithText("6").assertIsDisplayed()
    }

    @Test
    fun `saving sends what was changed, and nothing it was not asked about`() {
        var seen: List<Any?>? = null
        compose.setContent {
            SmartieTheme {
                EditRequirementPanel(
                    record = record,
                    actions = PurchaseActions(
                        onEdit = { r, name, quantity, urgency, note ->
                            seen = listOf(r.id, name, quantity, urgency, note)
                        }
                    )
                )
            }
        }

        compose.field(QUANTITY_LABEL).performTextClearance()
        compose.field(QUANTITY_LABEL).performTextInput("9")
        compose.onNodeWithContentDescription(urgencyOptionLabel(UrgencyV2.NORMAL)).performClick()
        compose.onNodeWithContentDescription(CONFIRM_EDIT).performClick()

        assertEquals(
            listOf("pr_one", "Sliding gate rack", 9.0, UrgencyV2.NORMAL, "For the Kandivali site"),
            seen
        )
    }

    @Test
    fun `a row whose stored quantity is unusable opens with one that is`() {
        // The rescue path. A V8C4 row holding `"qty": "10"` reads back as 0.0,
        // and the rules refuse every update to it until a number is written —
        // so the sheet must not hand back the unusable value it was given.
        var quantity: Double? = null
        compose.setContent {
            SmartieTheme {
                EditRequirementPanel(
                    record = record.copy(quantity = 0.0),
                    actions = PurchaseActions(onEdit = { _, _, q, _, _ -> quantity = q })
                )
            }
        }

        compose.onNodeWithContentDescription(CONFIRM_EDIT).performClick()

        assertEquals(1.0, quantity)
    }

    @Test
    fun `changing just the urgency sends the wire value straight away`() {
        var chosen: UrgencyV2? = null
        compose.setContent {
            SmartieTheme {
                SetUrgencyPanel(
                    record = record,
                    actions = PurchaseActions(onSetUrgency = { _, u -> chosen = u })
                )
            }
        }

        compose.onNodeWithText("Needed, but not now").assertIsDisplayed()
        compose.onNodeWithContentDescription(urgencyOptionLabel(UrgencyV2.NORMAL)).performClick()

        assertEquals(UrgencyV2.NORMAL, chosen)
        assertEquals("normal", chosen?.wireValue)
    }

    @Test
    fun `an emptied name cannot be saved`() {
        var edits = 0
        compose.setContent {
            SmartieTheme {
                EditRequirementPanel(
                    record = record,
                    actions = PurchaseActions(onEdit = { _, _, _, _, _ -> edits++ })
                )
            }
        }

        compose.field(NAME_LABEL).performTextClearance()
        compose.onNodeWithContentDescription(CONFIRM_EDIT).performClick()

        assertEquals(0, edits)
    }
}
