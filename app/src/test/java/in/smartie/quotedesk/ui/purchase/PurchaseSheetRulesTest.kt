package `in`.smartie.quotedesk.ui.purchase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a purchase sheet may be closed.
 *
 * A plain test rather than a rendered one on purpose: these are the
 * properties the `AlertDialog` is given, and a Compose `Dialog` opens its own
 * window with its own recomposer that the Robolectric test clock does not
 * drive — which is the recorded reason the panels are extracted in the first
 * place. Pinning the properties is what stops somebody flipping them.
 */
class PurchaseSheetRulesTest {

    @Test
    fun `Back closes a purchase sheet rather than leaving the tab`() {
        // The requirement, on every sheet: Back is a person saying "not this",
        // and it must close the panel, not navigate away from Purchase.
        assertTrue(PURCHASE_FORM_PROPERTIES.dismissOnBackPress)
        assertTrue(PURCHASE_CONFIRM_PROPERTIES.dismissOnBackPress)
    }

    @Test
    fun `a sheet holding typed values is not dismissed by a tap outside`() {
        // Add and Edit hold what somebody has typed, and a tap outside is
        // usually a miss. This is the one place the purchase sheets and the
        // stock sheets differ: stock refuses Back as well, purchase does not.
        assertFalse(PURCHASE_FORM_PROPERTIES.dismissOnClickOutside)
    }

    @Test
    fun `a confirmation holds nothing typed, so a tap outside may close it`() {
        assertTrue(PURCHASE_CONFIRM_PROPERTIES.dismissOnClickOutside)
    }

    @Test
    fun `every sheet has a title, and none of them offers to cancel a requirement`() {
        val titles = listOf(
            ADD_TITLE,
            EDIT_TITLE,
            URGENCY_TITLE,
            RECEIVE_TITLE,
            REOPEN_TITLE,
            REMOVE_TITLE
        )
        assertTrue(titles.all { it.isNotBlank() })
        // N4 has no Cancel action and no generic status setter. A `Cancelled`
        // requirement written by the PWA still reads correctly; nothing here
        // writes one, and no sheet is named after one.
        assertTrue(titles.none { it.contains("Cancel", ignoreCase = true) })
    }
}
