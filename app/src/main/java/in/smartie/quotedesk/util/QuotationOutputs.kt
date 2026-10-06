package `in`.smartie.quotedesk.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import `in`.smartie.quotedesk.domain.QuotationDocument
import `in`.smartie.quotedesk.domain.QuotationOutput
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The PDF's three ways out (N5.11 commit 8) — Download, Print and the
 * WhatsApp share — all from the one file [QuotationPdf] writes into
 * `cacheDir/quotations`. Every choice is [QuotationOutput]'s.
 */
object QuotationOutputs {

    /** The PDF on disk, and the `content://` address the FileProvider gives it. */
    data class Cached(val file: File, val uri: Uri, val written: QuotationPdf.Written)

    /**
     * Writes [document] to `cacheDir/quotations/<its file name>` — the older
     * files there deleted first, so only the file in use is kept.
     */
    fun cache(context: Context, document: QuotationDocument): Cached {
        val file = slot(context, document.fileName)
        val written = try {
            FileOutputStream(file).use { QuotationPdf(context).write(document, it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return Cached(file, uriOf(context, file), written)
    }

    /**
     * Where [name] is to be written, every other cached quotation deleted
     * first. Not written here, so it is tested without a PDF.
     */
    fun slot(context: Context, name: String): File {
        val directory = File(context.cacheDir, QuotationOutput.CACHE_DIRECTORY).apply { mkdirs() }
        for (stale in QuotationOutput.stale(directory.list()?.toList().orEmpty(), name)) {
            File(directory, stale).delete()
        }
        return File(directory, name)
    }

    fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    // --- Download ------------------------------------------------------------------------

    /**
     * API 29 and later: into `Download/SMARTIE` through MediaStore — no
     * permission asked. Below 29 the screen uses the system "Save as" picker
     * instead and hands its address to [copyTo].
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, QuotationOutput.MIME)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + QuotationOutput.DOWNLOAD_FOLDER
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads would not take the file")
        try {
            copyTo(context, file, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** The cached PDF copied to [target] — the "Save as" picker's answer, or a Downloads entry. */
    fun copyTo(context: Context, file: File, target: Uri) {
        val out = context.contentResolver.openOutputStream(target) ?: throw IOException("Nowhere to write")
        out.use { stream -> FileInputStream(file).use { it.copyTo(stream) } }
    }

    // --- Print ---------------------------------------------------------------------------

    /** The system print dialog on the same file, A4. [context] must be an activity. */
    fun print(context: Context, file: File, jobName: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attributes = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
        manager.print(jobName, FileAdapter(file, jobName), attributes)
    }

    /** Streams the finished PDF to the printer as it is: nothing is re-laid out for print. */
    private class FileAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.WriteResultCallback
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) }
                }
                if (cancellationSignal?.isCanceled == true) callback.onWriteCancelled()
                else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: IOException) {
                callback.onWriteFailed(e.message)
            }
        }
    }

    // --- Share ---------------------------------------------------------------------------

    /** Which of the two WhatsApps are installed — seen through the manifest's `<queries>`. */
    fun installedWhatsApps(context: Context): Set<String> =
        listOf(QuotationOutput.WHATSAPP, QuotationOutput.WHATSAPP_BUSINESS)
            .filter { installed(context.packageManager, it) }
            .toSet()

    @Suppress("DEPRECATION")
    private fun installed(manager: PackageManager, packageName: String): Boolean = try {
        manager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * The PDF, and only the PDF — no text (the Owner's decision 1) — with a
     * read grant on its `content://` address. [packageName] sends it to one
     * app; null leaves the choice to the share sheet.
     */
    fun shareIntent(uri: Uri, packageName: String?): Intent = Intent(Intent.ACTION_SEND).apply {
        type = QuotationOutput.MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        packageName?.let { setPackage(it) }
    }

    /**
     * Sends [uri] to [packageName]'s WhatsApp; if it cannot be opened, the
     * share sheet instead — the share never simply fails.
     */
    fun shareWith(context: Context, uri: Uri, packageName: String?) {
        if (packageName != null) {
            try {
                context.startActivity(shareIntent(uri, packageName).addNewTaskIfNeeded(context))
                return
            } catch (e: ActivityNotFoundException) {
                // Fall through to the sheet.
            }
        }
        val sheet = Intent.createChooser(shareIntent(uri, null), null).addNewTaskIfNeeded(context)
        context.startActivity(sheet)
    }

    private fun Intent.addNewTaskIfNeeded(context: Context): Intent = apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
