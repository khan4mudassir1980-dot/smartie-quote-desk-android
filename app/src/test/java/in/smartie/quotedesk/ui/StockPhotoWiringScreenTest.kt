package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.stock.StockCapabilities
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The photo action **on a small phone**, laid out rather than merely present.
 *
 * This class exists because of a real staging defect. The card's controls
 * used to be a `Row`, which does not wrap: once the stepper and the buttons
 * exceeded the card's width, Compose measured the remainder at zero and the
 * card clipped them. The Photo button was the last child, so on every phone
 * width it was invisible — while remaining in the semantics tree, which is
 * why `assertExists()` and `performClick()` in the earlier tests all passed.
 *
 * So nothing here asserts existence. Every assertion is about **layout**:
 * displayed, and wide enough to be a button. `assertIsDisplayed()` fails on a
 * zero-size node, which is precisely the failure that shipped.
 *
 * 360×640 is the narrowest phone this app supports, and the width at which
 * the old row overflowed worst.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class StockPhotoWiringScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val plain = stockRecord("SIE2000", "Swing gate motor", quantity = 9.0, reorder = 2.0)
    private val photographed =
        stockRecord("SIE1000", "Sliding gate motor", hasPhoto = true, photoRev = 3.0)

    private val addPhoto = "Add a photo of Swing gate motor"

    /** A button nobody can tap is not a button. */
    private fun assertUsable(description: String) {
        compose.onNodeWithContentDescription(description)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(32.dp)
    }

    // --- the defect ---------------------------------------------------------

    @Test
    fun `an Owner sees a usable Photo action on a 360dp phone`() {
        compose.showStock(stock = listOf(plain), capabilities = ownerCaps)
        assertUsable(addPhoto)
    }

    @Test
    fun `an Administrator sees a usable Photo action on a 360dp phone`() {
        compose.showStock(stock = listOf(plain), capabilities = adminCaps)
        assertUsable(addPhoto)
    }

    @Test
    fun `Staff see a usable Photo action on a 360dp phone`() {
        compose.showStock(stock = listOf(plain), capabilities = staffCaps)
        assertUsable(addPhoto)
    }

    @Test
    fun `the controls it shares a line with are not clipped either`() {
        // The same overflow was already eating History before photos existed.
        // Wrapping is what fixes both, so both are asserted.
        compose.showStock(stock = listOf(plain), capabilities = adminCaps)

        assertUsable("Pin Swing gate motor")
        assertUsable("Edit Swing gate motor")
        assertUsable("History for Swing gate motor")
        assertUsable(addPhoto)
    }

    @Test
    fun `the stepper survives the wrap`() {
        compose.showStock(stock = listOf(plain), capabilities = adminCaps)
        compose.onNodeWithContentDescription("Change Swing gate motor by one")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(100.dp)
    }

    // --- roles --------------------------------------------------------------

    @Test
    fun `a Worker gets no Photo action at all`() {
        compose.showStock(stock = listOf(plain), capabilities = workerCaps)
        assertTrue(
            "no control at all, not a clipped one",
            compose.onAllNodesWithContentDescription(addPhoto).fetchSemanticsNodes().isEmpty()
        )
    }

    // --- a photographed row -------------------------------------------------

    @Test
    fun `a photographed row shows a laid-out thumbnail on a 360dp phone`() {
        compose.showStock(stock = listOf(photographed), capabilities = staffCaps)
        compose.onNodeWithContentDescription("No photo showing for Sliding gate motor")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(40.dp)
            .assertHeightIsAtLeast(40.dp)
    }

    @Test
    fun `a Worker sees the thumbnail too`() {
        compose.showStock(stock = listOf(photographed), capabilities = workerCaps)
        compose.onNodeWithContentDescription("No photo showing for Sliding gate motor")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(40.dp)
    }

    // --- the mapping the screen actually uses -------------------------------

    @Test
    fun `the capability mapping is what the screen reads, per role`() {
        // StockScreen builds its capabilities from exactly this function, so
        // a role that loses its photo controls loses them here first.
        assertTrue("an Owner manages photos", StockCapabilities.forMember(owner).photoManage)
        assertTrue("an Administrator manages photos", StockCapabilities.forMember(admin).photoManage)
        assertTrue("Staff manage photos", StockCapabilities.forMember(staff).photoManage)
        assertTrue("a Worker does not", !StockCapabilities.forMember(worker).photoManage)

        // Everyone active sees them, exactly as they see stock.
        for (member in listOf(owner, admin, staff, worker)) {
            assertTrue("${member.role} sees photos", StockCapabilities.forMember(member).photoView)
        }
    }

    @Test
    fun `photo management tracks stock management, so Pin proves Photo`() {
        // The staging report said Pin, Edit and History were present. Those
        // come from the same predicate as photoManage, which is why the
        // capability could never have been the cause.
        for (member in listOf(owner, admin, staff, worker)) {
            val capabilities = StockCapabilities.forMember(member)
            assertTrue(
                "${member.role}: pin and photoManage must agree",
                capabilities.pin == capabilities.photoManage
            )
        }
    }
}
