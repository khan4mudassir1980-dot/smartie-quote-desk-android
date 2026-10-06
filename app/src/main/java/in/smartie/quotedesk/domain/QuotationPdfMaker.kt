package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.CompanySettings
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import java.io.File
import java.util.TimeZone
import kotlinx.coroutines.CancellationException

/** Download, Print or WhatsApp — the three buttons, one renderer. */
enum class PdfAction { DOWNLOAD, PRINT, SHARE }

/** The company settings as this phone has them. */
sealed interface CompanyState {
    /** The server has not answered yet: no PDF, so a blank letterhead is never made by accident. */
    data object NotLoaded : CompanyState

    /** The server's answer — null when there is no document at all. */
    data class Loaded(val settings: CompanySettings?) : CompanyState
}

/** What one PDF request came to. */
sealed interface PdfOutcome<out T> {
    /** Written: [output] is the renderer's; [notice] says what it was made without. */
    data class Ready<T>(val output: T, val number: String, val fileName: String, val notice: String?) : PdfOutcome<T>

    /** Not made, and why, in the person's words. */
    data class Refused(val message: String) : PdfOutcome<Nothing>
}

/** What the renderer wrote, and the stored images it had to leave out. */
data class Rendered<T>(val output: T, val unreadable: List<ImageRole>)

/** A PDF in the cache, ready for its button's way out — what a screen is handed. */
data class PdfReady(
    val action: PdfAction,
    val file: File,
    val number: String,
    val fileName: String,
    /** Said after the output: what the PDF was made without, or null. */
    val notice: String?
)

/**
 * **One PDF, from an issued quotation** (N5.11 commit 9) — the step the
 * builder's buttons take after the gate, and the detail screen's take at
 * once. The rendering, the words for a failure and the clock are handed in,
 * so every branch is tested in the JVM.
 *
 * 1. **Settings never loaded → refused**, with [PdfNotice.NOT_LOADED]. The
 *    builder asks [ready] *before* the gate, so a number is never taken for a
 *    PDF that would then be refused.
 * 2. The document is built by [QuotationDocumentBuilder] — the letterhead
 *    from `snap` then the settings, the catalogue for each line's model and
 *    name.
 * 3. Rendered; a failure is reported in words and logged, never thrown at
 *    the screen.
 * 4. **The notice**, [PdfNotice.after], names what it was made without.
 */
class QuotationPdfMaker<T>(
    private val render: suspend (QuotationDocument) -> Rendered<T>,
    /** A thrown failure in words. */
    private val describe: (Throwable) -> String,
    private val log: (Throwable) -> Unit = {},
    private val zone: () -> TimeZone = { TimeZone.getDefault() }
) {

    suspend fun make(record: QuotationRecord, company: CompanyState, products: List<ProductRecord>): PdfOutcome<T> {
        if (company !is CompanyState.Loaded) return PdfOutcome.Refused(PdfNotice.NOT_LOADED)
        val document = QuotationDocumentBuilder.build(record, company.settings, products.associateBy { it.key }, zone())
        val rendered = try {
            render(document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            log(failure)
            return PdfOutcome.Refused(notMade(describe(failure)))
        }
        return PdfOutcome.Ready(
            output = rendered.output,
            number = record.number,
            fileName = document.fileName,
            notice = PdfNotice.after(document.missing, rendered.unreadable)
        )
    }

    companion object {
        /** Null when a PDF may be made now; otherwise why not. Asked before the gate. */
        fun ready(company: CompanyState): String? =
            if (company is CompanyState.NotLoaded) PdfNotice.NOT_LOADED else null

        fun notMade(reason: String): String = "The PDF was not made — ${reason.trim().trimEnd('.')}."
    }
}
