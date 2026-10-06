package `in`.smartie.quotedesk.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.domain.QuotationOutput
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The PDF's ways out (N5.11 commit 8), under Robolectric. The cache-clearing
 * is the Owner's addition of 2026-10-06 and is tested on real files; Print,
 * the Downloads folder and a real WhatsApp are phone rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class QuotationOutputsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cache get() = File(context.cacheDir, QuotationOutput.CACHE_DIRECTORY)

    @Test
    fun `before a new PDF is written, every older cached quotation is deleted`() {
        cache.mkdirs()
        File(cache, "Quotation-TEST-001-Customer-A.pdf").writeText("synthetic")
        File(cache, "Quotation-TEST-002-Customer-B.pdf").writeText("synthetic")

        val slot = QuotationOutputs.slot(context, "Quotation-TEST-003-Customer-C.pdf")

        assertEquals(File(cache, "Quotation-TEST-003-Customer-C.pdf"), slot)
        assertEquals(emptyList<String>(), cache.list()!!.toList())
    }

    @Test
    fun `the file in use is the one kept`() {
        cache.mkdirs()
        File(cache, "Quotation-TEST-003-Customer-C.pdf").writeText("synthetic")
        File(cache, "Quotation-TEST-001-Customer-A.pdf").writeText("synthetic")

        QuotationOutputs.slot(context, "Quotation-TEST-003-Customer-C.pdf")

        assertEquals(listOf("Quotation-TEST-003-Customer-C.pdf"), cache.list()!!.toList())
    }

    @Test
    fun `nothing outside the quotations folder is touched`() {
        val photos = File(context.cacheDir, "stock-photos").apply { mkdirs() }
        val capture = File(photos, "capture-1.jpg").apply { writeText("synthetic") }

        QuotationOutputs.slot(context, "Quotation-TEST-003-Customer-C.pdf")

        assertTrue(capture.exists())
    }

    @Test
    fun `the cached PDF's address is the FileProvider's, under the quotations path`() {
        val file = QuotationOutputs.slot(context, "Quotation-TEST-003-Customer-C.pdf").apply { writeText("synthetic") }

        val uri = QuotationOutputs.uriOf(context, file)

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
        assertEquals("/shared_quotations/Quotation-TEST-003-Customer-C.pdf", uri.path)
    }

    @Test
    fun `the share carries the PDF and only the PDF, readable by the app it goes to`() {
        // The Owner's decision 1: no text.
        val uri = Uri.parse("content://in.smartie.quotedesk.files/shared_quotations/Quotation-TEST-003.pdf")

        val intent = QuotationOutputs.shareIntent(uri, QuotationOutput.WHATSAPP)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/pdf", intent.type)
        @Suppress("DEPRECATION")
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_SUBJECT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(QuotationOutput.WHATSAPP, intent.`package`)

        assertNull("the sheet's intent names no app", QuotationOutputs.shareIntent(uri, null).`package`)
    }

    @Test
    fun `the installed WhatsApps are seen, one and both`() {
        assertEquals(emptySet<String>(), QuotationOutputs.installedWhatsApps(context))

        install(QuotationOutput.WHATSAPP_BUSINESS)
        assertEquals(setOf(QuotationOutput.WHATSAPP_BUSINESS), QuotationOutputs.installedWhatsApps(context))

        install(QuotationOutput.WHATSAPP)
        assertEquals(
            setOf(QuotationOutput.WHATSAPP, QuotationOutput.WHATSAPP_BUSINESS),
            QuotationOutputs.installedWhatsApps(context)
        )
    }

    @Test
    fun `a share to one WhatsApp opens it directly, and with none the share sheet opens`() {
        val uri = Uri.parse("content://in.smartie.quotedesk.files/shared_quotations/Quotation-TEST-003.pdf")
        val started = shadowOf(context as Application)

        QuotationOutputs.shareWith(context, uri, QuotationOutput.WHATSAPP)
        val direct = started.nextStartedActivity
        assertEquals(Intent.ACTION_SEND, direct.action)
        assertEquals(QuotationOutput.WHATSAPP, direct.`package`)

        QuotationOutputs.shareWith(context, uri, null)
        val sheet = started.nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, sheet.action)
        @Suppress("DEPRECATION")
        val inner = sheet.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertNull(inner.`package`)
    }

    private fun install(packageName: String) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
        )
    }
}
