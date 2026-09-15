package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.screens.SignInScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// A plain Application: SmartieApplication would need a real Firebase project.
// The qualifiers put the test on one of the two target screen sizes.
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class SignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(state: SignInUiState) {
        compose.setContent {
            SmartieTheme {
                SignInScreen(
                    state = state,
                    onGoogleSignIn = {},
                    onEmailSignIn = { _, _ -> },
                    onPasswordReset = {},
                )
            }
        }
    }

    @Test
    fun `google is offered first and the password form is behind a disclosure`() {
        setContent(SignInUiState())

        compose.onNodeWithText("Continue with Google").assertIsDisplayed()
        compose.onNodeWithText("Use existing email and password").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Password").fetchSemanticsNodes().size)

        compose.onNodeWithText("Use existing email and password").performClick()

        compose.onNodeWithText("Email").assertExists()
        compose.onNodeWithText("Password").assertExists()
        compose.onNodeWithText("Reset app password").assertExists()
    }

    @Test
    fun `a failure is shown in the card`() {
        setContent(SignInUiState(error = "That email and password do not match an account."))
        compose.onNodeWithText("That email and password do not match an account.").assertExists()
    }

    @Test
    fun `a collision explains how the two sign-ins are linked`() {
        setContent(SignInUiState(linkEmail = "someone@example.invalid"))
        compose.onNodeWithText(
            "Sign in with the password for someone@example.invalid once, and Google will be " +
                "linked to the same account.",
        ).assertExists()
    }
}
