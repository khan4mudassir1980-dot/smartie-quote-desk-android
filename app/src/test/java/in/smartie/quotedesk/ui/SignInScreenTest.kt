package `in`.smartie.quotedesk.ui

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

@RunWith(AndroidJUnit4::class)
class SignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `google is offered first and the password form is behind a disclosure`() {
        compose.setContent {
            SmartieTheme {
                SignInScreen(
                    state = SignInUiState(),
                    onGoogleSignIn = {},
                    onEmailSignIn = { _, _ -> },
                    onPasswordReset = {},
                )
            }
        }

        compose.onNodeWithText("Continue with Google").assertIsDisplayed()
        compose.onNodeWithText("Use existing email and password").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Password").fetchSemanticsNodes().size)

        compose.onNodeWithText("Use existing email and password").performClick()
        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("Reset app password").assertIsDisplayed()
    }

    @Test
    fun `a failure is shown in the card`() {
        compose.setContent {
            SmartieTheme {
                SignInScreen(
                    state = SignInUiState(
                        error = "That email and password do not match an account.",
                    ),
                    onGoogleSignIn = {},
                    onEmailSignIn = { _, _ -> },
                    onPasswordReset = {},
                )
            }
        }
        compose.onNodeWithText("That email and password do not match an account.")
            .assertIsDisplayed()
    }
}
