package `in`.smartie.quotedesk.ui.quotations

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import `in`.smartie.quotedesk.domain.PdfAction
import `in`.smartie.quotedesk.domain.PdfReady
import `in`.smartie.quotedesk.domain.QuotationOutput
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import `in`.smartie.quotedesk.util.QuotationOutputs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Download · Print · WhatsApp** (N5.11 commit 10) — one compact row, the
 * same on the builder (under Finalise) and on a quotation's detail (at the
 * top of its actions), as the Owner approved on 2026-10-06. While a PDF is
 * being made the row says so instead, and a press does nothing.
 */
@Composable
internal fun PdfButtons(
    onOutput: (PdfAction) -> Unit,
    enabled: Boolean,
    preparing: Boolean,
    modifier: Modifier = Modifier
) {
    val dimens = LocalSmartieDimens.current
    if (preparing) {
        Text(
            PREPARING_PDF,
            style = MaterialTheme.typography.bodyMedium,
            color = SmartieColors.Steel,
            modifier = modifier.semantics { contentDescription = PREPARING_PDF }
        )
        return
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
        for ((action, label) in listOf(
            PdfAction.DOWNLOAD to DOWNLOAD,
            PdfAction.PRINT to PRINT,
            PdfAction.SHARE to WHATSAPP
        )) {
            SmartieGhostButton(
                text = label,
                onClick = { if (enabled) onOutput(action) },
                enabled = enabled,
                compact = true,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = label }
            )
        }
    }
}

/**
 * Carries each PDF the view model makes out of the app — Download, Print
 * or the WhatsApp share — and says what happened, the notice included
 * (the Owner's non-blocking notice, after the output). Every decision is
 * `QuotationOutput`'s; the work is `QuotationOutputs`'.
 */
@Composable
internal fun PdfOutputs(ready: Flow<PdfReady>, say: suspend (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf<PdfReady?>(null) }
    var saving by remember { mutableStateOf<PdfReady?>(null) }

    // API 23 to 28: the system "Save as" picker — no permission prompt.
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(QuotationOutput.MIME)) { uri ->
        val pdf = saving
        saving = null
        if (uri != null && pdf != null) {
            scope.launch {
                val saved = runCatching {
                    withContext(Dispatchers.IO) { QuotationOutputs.copyTo(context, pdf.file, uri) }
                }
                say(if (saved.isSuccess) savedAs(pdf.fileName) else NOT_SAVED)
                pdf.notice?.let { say(it) }
            }
        }
    }

    LaunchedEffect(ready) {
        ready.collect { pdf ->
            when (pdf.action) {
                PdfAction.DOWNLOAD ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val saved = runCatching {
                            withContext(Dispatchers.IO) { QuotationOutputs.saveToDownloads(context, pdf.file) }
                        }
                        say(if (saved.isSuccess) savedToDownloads(pdf.fileName) else NOT_SAVED)
                    } else {
                        saving = pdf
                        saveAs.launch(pdf.fileName)
                        // The notice follows the picker's answer.
                        return@collect
                    }
                PdfAction.PRINT -> {
                    val started = runCatching {
                        QuotationOutputs.print(context, pdf.file, pdf.fileName.removeSuffix(".pdf"))
                    }
                    if (started.isFailure) say(NOT_PRINTED)
                }
                PdfAction.SHARE -> {
                    val uri = QuotationOutputs.uriOf(context, pdf.file)
                    when (val target = QuotationOutput.shareTarget(QuotationOutputs.installedWhatsApps(context))) {
                        is QuotationOutput.ShareTarget.App -> QuotationOutputs.shareWith(context, uri, target.packageName)
                        QuotationOutput.ShareTarget.Sheet -> QuotationOutputs.shareWith(context, uri, null)
                        QuotationOutput.ShareTarget.Choose -> choosing = pdf
                    }
                }
            }
            pdf.notice?.let { say(it) }
        }
    }

    choosing?.let { pdf ->
        SendWithDialog(
            onChoose = { packageName ->
                choosing = null
                if (packageName != null) {
                    QuotationOutputs.shareWith(context, QuotationOutputs.uriOf(context, pdf.file), packageName)
                }
            }
        )
    }
}

/**
 * Both WhatsApps are installed: which one sends it — WhatsApp, WhatsApp
 * Business or Cancel (the approved "Send with"). Nothing is remembered; the
 * next share asks again. The dialog only wraps [SendWithChoices], which is
 * where the choice is and what the tests drive (a Compose dialog's own window
 * is not driven by Robolectric's clock — see `StockRolesScreenTest`).
 */
@Composable
internal fun SendWithDialog(onChoose: (packageName: String?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        confirmButton = {},
        text = { SendWithChoices(onChoose) }
    )
}

@Composable
internal fun SendWithChoices(onChoose: (packageName: String?) -> Unit) {
    val dimens = LocalSmartieDimens.current
    Column(verticalArrangement = Arrangement.spacedBy(dimens.gapS)) {
        Text(QuotationOutput.SEND_WITH, style = MaterialTheme.typography.titleLarge)
        for ((packageName, label) in listOf(
            QuotationOutput.WHATSAPP to QuotationOutput.WHATSAPP_LABEL,
            QuotationOutput.WHATSAPP_BUSINESS to QuotationOutput.WHATSAPP_BUSINESS_LABEL,
            null to SEND_WITH_CANCEL
        )) {
            TextButton(
                onClick = { onChoose(packageName) },
                modifier = Modifier.semantics { contentDescription = label }
            ) {
                Text(label, color = if (packageName == null) SmartieColors.Steel else SmartieColors.Purple)
            }
        }
    }
}

internal const val DOWNLOAD = "Download"
internal const val PRINT = "Print"
internal const val WHATSAPP = "WhatsApp"
internal const val PREPARING_PDF = "Preparing the PDF…"
internal const val SEND_WITH_CANCEL = "Cancel"
internal const val NOT_SAVED = "The PDF was not saved — try again"
internal const val NOT_PRINTED = "Printing could not start — try again"

internal fun savedToDownloads(fileName: String) = "Saved to Downloads/${QuotationOutput.DOWNLOAD_FOLDER} as $fileName"

internal fun savedAs(fileName: String) = "Saved as $fileName"
