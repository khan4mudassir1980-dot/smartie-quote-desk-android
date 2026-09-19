package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.team.RoleMenu
import `in`.smartie.quotedesk.ui.team.RoleOptions
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The role selector, through the real composables the Team screen renders.
 *
 * This is the surface where a title and an identity could genuinely come
 * apart: somebody picks a word and a value lands in Firestore. Every
 * selection assertion checks both ends — the word tapped **and** the
 * `wireValue` the selection carries — because a mapping that looks right and
 * writes the wrong role is the failure worth guarding against.
 *
 * `RoleOptions` is driven directly rather than through the `DropdownMenu`
 * wrapper, for the reason recorded in `docs/PROJECT-STATUS.md`: a popup has
 * its own recomposer, which the Robolectric test clock does not drive. The
 * wrapper holds open/closed state and no mapping.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class TeamRoleSelectorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** What an Owner may assign. */
    private val assignable = listOf(Role.ADMIN, Role.STAFF, Role.WORKER)

    private fun showOptions(current: Role, chosen: MutableList<Role>) {
        compose.setContent {
            SmartieTheme {
                RoleOptions(options = assignable, current = current, onSelect = { chosen += it })
            }
        }
    }

    // --- what the menu offers -----------------------------------------------

    @Test
    fun `the menu offers the new titles`() {
        showOptions(Role.WORKER, mutableListOf())

        compose.onNodeWithText("Administrator").assertExists()
        compose.onNodeWithText("Manager").assertExists()
        compose.onNodeWithText("Staff").assertExists()
    }

    @Test
    fun `the menu offers no Worker`() {
        showOptions(Role.WORKER, mutableListOf())
        assertTrue(
            "the old title must not appear anywhere in the selector",
            compose.onAllNodesWithText("Worker").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `each title appears exactly once, so a choice is never ambiguous`() {
        showOptions(Role.WORKER, mutableListOf())
        for (title in listOf("Administrator", "Manager", "Staff")) {
            assertEquals(
                "$title must name one option and one only",
                1,
                compose.onAllNodesWithText(title).fetchSemanticsNodes().size
            )
        }
    }

    // --- what a selection actually writes -----------------------------------

    @Test
    fun `choosing Manager selects the stored staff role`() {
        val chosen = mutableListOf<Role>()
        showOptions(Role.WORKER, chosen)

        compose.onNodeWithText("Manager").performClick()

        assertEquals(listOf(Role.STAFF), chosen)
        assertEquals("the value written to Firestore is unchanged", "staff", chosen.single().wireValue)
    }

    @Test
    fun `choosing Staff selects the stored worker role`() {
        val chosen = mutableListOf<Role>()
        showOptions(Role.STAFF, chosen)

        compose.onNodeWithText("Staff").performClick()

        assertEquals(listOf(Role.WORKER), chosen)
        assertEquals("the value written to Firestore is unchanged", "worker", chosen.single().wireValue)
    }

    @Test
    fun `choosing Administrator still selects admin`() {
        val chosen = mutableListOf<Role>()
        showOptions(Role.WORKER, chosen)

        compose.onNodeWithText("Administrator").performClick()

        assertEquals(listOf(Role.ADMIN), chosen)
        assertEquals("admin", chosen.single().wireValue)
    }

    @Test
    fun `picking the role somebody already has writes nothing`() {
        val chosen = mutableListOf<Role>()
        showOptions(Role.STAFF, chosen)

        compose.onNodeWithText("Manager").performClick()

        assertTrue("no change is not a change", chosen.isEmpty())
    }

    // --- the button that opens it -------------------------------------------

    @Test
    fun `the button shows a stored worker as Staff`() {
        compose.setContent {
            SmartieTheme {
                RoleMenu(
                    person = Member(uid = "u", name = "Sam", role = Role.WORKER),
                    options = assignable,
                    onSelect = {}
                )
            }
        }
        compose.onNodeWithText("Role: Staff").assertExists()
    }

    @Test
    fun `the button shows a stored staff as Manager`() {
        compose.setContent {
            SmartieTheme {
                RoleMenu(
                    person = Member(uid = "u", name = "Mo", role = Role.STAFF),
                    options = assignable,
                    onSelect = {}
                )
            }
        }
        compose.onNodeWithText("Role: Manager").assertExists()
    }
}
