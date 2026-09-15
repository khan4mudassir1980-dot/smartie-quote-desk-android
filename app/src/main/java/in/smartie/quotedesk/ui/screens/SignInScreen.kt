package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.R
import `in`.smartie.quotedesk.ui.SignInUiState
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * Sign-in, matching the approved PWA: Google first, an existing email and
 * password behind a disclosure, and a password reset. There is no
 * self-registration — a new person enters as an active Worker by signing in.
 */
@Composable
fun SignInScreen(
    state: SignInUiState,
    onGoogleSignIn: () -> Unit,
    onEmailSignIn: (String, String) -> Unit,
    onPasswordReset: (String) -> Unit,
) {
    val dimens = LocalSmartieDimens.current
    var showEmailForm by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(
            start = dimens.screenPadding,
            end = dimens.screenPadding,
            top = 40.dp,
            bottom = dimens.listBottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(dimens.gapM),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)),
                )
            }
        }

        item {
            SmartieCard {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Sign in",
                        style = MaterialTheme.typography.titleLarge,
                        color = SmartieColors.Ink,
                    )
                    Text(
                        "Use your Google account. New members enter securely as Workers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SmartieColors.Steel,
                        modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
                    )

                    state.error?.let { Note(it, isError = true) }
                    state.info?.let { Note(it, isError = false) }
                    state.linkEmail?.let {
                        Note(
                            "Sign in with the password for $it once, and Google will be linked " +
                                "to the same account.",
                            isError = false,
                        )
                    }

                    SmartiePrimaryButton(
                        text = "Continue with Google",
                        onClick = onGoogleSignIn,
                        busy = state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SmartieGhostButton(
                        text = if (showEmailForm) {
                            "Hide email and password"
                        } else {
                            "Use existing email and password"
                        },
                        onClick = { showEmailForm = !showEmailForm },
                        modifier = Modifier.padding(top = 10.dp),
                    )

                    if (showEmailForm) {
                        SmartieField(
                            label = "Email",
                            value = email,
                            onValueChange = { email = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        SmartieField(
                            label = "Password",
                            value = password,
                            onValueChange = { password = it },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SmartiePrimaryButton(
                                text = "Sign in",
                                onClick = { onEmailSignIn(email, password) },
                                busy = state.busy,
                            )
                            SmartieGhostButton(
                                text = "Reset app password",
                                onClick = { onPasswordReset(email) },
                            )
                        }
                        Text(
                            "Password reset is only for an existing email and password account. " +
                                "It does not change your Google or Gmail password.",
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartieColors.Steel,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    Text(
                        "Switched-off accounts stay blocked. A removed member may sign in again " +
                            "and returns as a Worker.",
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartieColors.Steel2,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(message: String, isError: Boolean) {
    val dimens = LocalSmartieDimens.current
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) SmartieColors.Danger else SmartieColors.PurpleDark,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radiusSmall))
            .padding(bottom = 10.dp),
    )
}
