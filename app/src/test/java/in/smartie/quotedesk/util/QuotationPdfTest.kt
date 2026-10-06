package `in`.smartie.quotedesk.util

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.CompanySettings
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.domain.DrawOp
import `in`.smartie.quotedesk.domain.ImageRole
import `in`.smartie.quotedesk.domain.QuotationDocument
import `in`.smartie.quotedesk.domain.QuotationDocumentBuilder
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The renderer's smoke tests (N5.11 commit 7), on CI under Robolectric.
 *
 * **The bitmap fallback is the one that must pass.** Robolectric has no
 * implementation of the platform's `PdfDocument` — its native calls are
 * stubbed, so a page cannot be started — and the approved plan's fallback
 * is to draw the same pages onto a bitmap canvas instead, which the legacy
 * graphics shadows record text by text. The `PdfDocument` test is kept and
 * **skips, saying why,** where it cannot run; the real file is checked on
 * the phone.
 *
 * Synthetic only: the three images are the plain squares listed for the
 * staging pass in `docs/PROJECT-STATUS.md`, and the firm is invented.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class QuotationPdfTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 48 × 48. */
    private val logo = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAIAAADYYG7QAAAAOklEQVR42u3OQQ0AAAgEoItjCMMa1RbOBxsBSPW8EiEhISEhISEhISEhISEhISEhISEhISEhISGhOwuLLCSIuUCuZwAAAABJRU5ErkJggg=="

    /** 48 × 48. */
    private val qr = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAIAAADYYG7QAAAANklEQVR42u3OQREAAAwCIEPYP6stdntAAtJnIiQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCR0Z2liHB9QAXLVAAAAAElFTkSuQmCC"

    /** 96 × 32. */
    private val signature = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAGAAAAAgCAIAAABiouoDAAAARElEQVR42u3QQQkAAAgEsEtywewfxAb+hcESLO1wiAJBggQJEiRIkCAECRIkSJAgQYIQJEiQIEGCBAlCkCBBggQJ+msBRWsIanFzrOUAAAAASUVORK5CYII="

    private val company = CompanySettings(
        name = "Test Gates & Shutters",
        address = "1 Test Road, Test City 400001",
        phone = "+91 90000 00001",
        email = "quotes@example.invalid",
        gstin = "27AAAAA0000A1Z5",
        pan = "AAAAA0000A",
        bankName = "Test Bank",
        bankAcc = "000000000000",
        bankIfsc = "TEST0000000",
        upi = "test-only@invalid",
        terms = listOf("Test term one."),
        logo = logo,
        qr = qr,
        signature = signature
    )

    private val record = QuotationRecord(
        id = "q_test",
        number = "TEST/QD/2026-27/001",
        at = 1_791_176_400_000L,
        party = QuotationPartySnapshot(name = "Test Customer"),
        lines = (1..40).map {
            QuotationLineRecord(title = "Test item $it", unit = "each", quantity = 1.0, rate = 1_000.0, manual = true, amount = 1_000.0)
        },
        subtotal = 40_000.0,
        total = 40_000.0,
        status = "Cancelled"
    )

    private fun document(settings: CompanySettings = company): QuotationDocument =
        QuotationDocumentBuilder.build(record, settings, emptyMap(), TimeZone.getTimeZone("Asia/Kolkata"))

    @Test
    fun `every page draws onto a bitmap canvas, each word where the layout put it - the fallback smoke test`() {
        QuotationPdf(context).prepare(document()).use { prepared ->
            assertTrue("40 items run over more than one page", prepared.pages.size > 1)
            for (page in prepared.pages) {
                val bitmap = Bitmap.createBitmap(QuotationPdf.PAGE_WIDTH, QuotationPdf.PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                prepared.draw(page, canvas)
                val shadow = shadowOf(canvas)
                val drawn = (0 until shadow.textHistoryCount).map { shadow.getDrawnTextEvent(it) }
                val laid = page.ops.filter { it is DrawOp.Text || it is DrawOp.Stamp }
                assertEquals("page ${page.number}", laid.size, drawn.size)
                laid.zip(drawn).forEach { (op, event) ->
                    when (op) {
                        is DrawOp.Text -> {
                            assertEquals(op.text, event.text)
                            assertEquals(op.x, event.x, 0.001f)
                            assertEquals(op.y, event.y, 0.001f)
                        }
                        is DrawOp.Stamp -> assertEquals(QuotationDocument.CANCELLED, event.text)
                        else -> Unit
                    }
                }
                bitmap.recycle()
            }
        }
    }

    @Test
    fun `the stored images decode at their own proportions and are drawn fitted to their boxes`() {
        QuotationPdf(context).prepare(document()).use { prepared ->
            assertEquals(emptyList<ImageRole>(), prepared.unreadable)
            val boxes = prepared.pages.flatMap { page -> page.ops.filterIsInstance<DrawOp.Image>() }.associateBy { it.role }
            assertEquals(48f to 48f, boxes.getValue(ImageRole.LOGO).let { it.w to it.h })
            assertEquals(96f to 96f, boxes.getValue(ImageRole.QR).let { it.w to it.h })
            // 96 × 32 is three to one: the full 150 × 50 box.
            assertEquals(150f to 50f, boxes.getValue(ImageRole.SIGNATURE).let { it.w to it.h })
        }
    }

    @Test
    fun `an image that cannot be read is left out and named - and a web address is never fetched`() {
        val settings = company.copy(
            logo = "https://example.invalid/logo.png",
            qr = "data:image/png;base64,bm90IGFuIGltYWdl",
            signature = "data:image/png;base64,***"
        )
        QuotationPdf(context).prepare(document(settings)).use { prepared ->
            assertEquals(listOf(ImageRole.LOGO, ImageRole.QR, ImageRole.SIGNATURE), prepared.unreadable)
            assertTrue(prepared.pages.all { page -> page.ops.none { it is DrawOp.Image } })
            val texts = prepared.pages.flatMap { page -> page.ops.filterIsInstance<DrawOp.Text>().map { it.text } }
            assertTrue("no SCAN TO PAY without a QR to scan", QuotationDocument.SCAN_TO_PAY !in texts)
            assertTrue("the signatory's words stand alone", "Authorised signatory for Test Gates & Shutters" in texts)
        }
    }

    @Test
    fun `no images at all is not an error`() {
        QuotationPdf(context).prepare(document(company.copy(logo = "", qr = "", signature = ""))).use { prepared ->
            assertEquals(emptyList<ImageRole>(), prepared.unreadable)
            assertTrue(prepared.pages.isNotEmpty())
        }
    }

    @Test
    fun `PdfDocument writes the pages as a PDF - skipped where Robolectric cannot run it`() {
        val out = ByteArrayOutputStream()
        val written = try {
            QuotationPdf(context).write(document(), out)
        } catch (e: IllegalStateException) {
            assumeNoException("Robolectric has no PdfDocument: its native page could not be started", e)
            return
        } catch (e: UnsatisfiedLinkError) {
            assumeNoException("Robolectric has no PdfDocument: its native library is not there", e)
            return
        }
        assumeTrue("Robolectric's PdfDocument wrote nothing", out.size() > 0)
        assertEquals("%PDF", String(out.toByteArray().copyOfRange(0, 4), Charsets.US_ASCII))
        assertTrue(written.pages > 1)
    }
}
