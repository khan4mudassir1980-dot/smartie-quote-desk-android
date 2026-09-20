package `in`.smartie.quotedesk.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * How tall a dialog's scrolling body may be, as a share of the window.
 *
 * A Material dialog caps its own height and **clips** what does not fit
 * rather than scrolling it, so a body sized for a tall phone loses its
 * buttons off the bottom of a short one — and with the keyboard up, every
 * phone is a short one. Half the window leaves room for the dialog's title,
 * its padding and the keyboard, and the actions sit directly under the bound.
 *
 * One copy, shared by the stock sheets and the purchase panels, because two
 * copies of a figure like this drift.
 */
@Composable
internal fun sheetBodyHeight(): Dp =
    (LocalConfiguration.current.screenHeightDp * 0.5f).dp.coerceAtMost(430.dp)

/**
 * Confirmation matching the PWA's `confirmMemberAction` (`index.html:2145`).
 *
 * When [requireTypedText] is set the confirm button stays disabled until the
 * exact phrase is typed — the PWA requires this for Remove member and for the
 * Owner emergency revoke.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    warning: String? = null,
    requireTypedText: String? = null,
    danger: Boolean = false
) {
    var typed by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    val satisfied = requireTypedText == null || typed.trim() == requireTypedText

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(message, style = MaterialTheme.typography.bodyLarge)
                if (warning != null) {
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (danger) SmartieColors.Danger else SmartieColors.Warn,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (requireTypedText != null) {
                    SmartieField(
                        label = "To confirm, type $requireTypedText",
                        value = typed,
                        onValueChange = { typed = it; mismatch = false },
                        isError = mismatch,
                        supportingText = if (mismatch) "The name does not match." else null,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = satisfied,
                onClick = {
                    if (satisfied) onConfirm() else mismatch = true
                }
            ) {
                Text(
                    confirmText,
                    color = if (danger) SmartieColors.Danger else SmartieColors.Purple
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = SmartieColors.Steel) }
        }
    )
}
