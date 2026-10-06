package `in`.smartie.quotedesk.ui.products

import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.domain.CompanyState
import `in`.smartie.quotedesk.domain.PdfOutcome
import `in`.smartie.quotedesk.domain.QuotationPdfMaker
import `in`.smartie.quotedesk.domain.QuoteDraft
import kotlinx.coroutines.CancellationException

/**
 * **Download, Print or WhatsApp on the draft** (N5.11 commit 9): the gate,
 * then the PDF — every branch here, so the view model only shows what a press
 * came to.
 *
 * V8C4's order, the gate in front of every action that issues: `ensureFinalised`
 * first — a draft takes its number, an already-issued one keeps it — and only
 * then the PDF of **the issued quotation**. Before the gate, two refusals that
 * no number should be taken for: company settings that have never loaded
 * (the approved plan), and an edit draft, which `ensureFinalised` would refuse
 * anyway and which the builder never offers these buttons on.
 *
 * **The PDF is made from the quotation read back from the server**
 * ([stored]), never from what was sent: only the server's copy carries the
 * issue time the PDF prints (the Owner's decision 1.1). The quotation's id is
 * the draft's (`QuotationWrite.plan`), so it is known before the press.
 *
 * Once the number is taken it stands: a PDF that then fails says the
 * quotation is issued and where to get the PDF, and never reads as though
 * nothing happened.
 */
class PdfPress<T>(
    private val maker: QuotationPdfMaker<T>,
    /** `QuoteFinaliser.ensureFinalised`, with the screen's discount cap. */
    private val gate: suspend (QuoteDraft) -> GateOutcome,
    /** `QuotationWriteRepository.stored`: the quotation as the server holds it. */
    private val stored: suspend (quotationId: String) -> QuotationRecord?,
    private val log: (Throwable) -> Unit = {}
) {

    /** What one press came to. */
    sealed interface Pressed<out T> {
        /** Issued — by this press or before — and the PDF made. [finalised] is "Finalised as X". */
        data class Ready<T>(val finalised: String, val pdf: PdfOutcome.Ready<T>) : Pressed<T>

        /** Stopped before a number was taken, and why. The draft is as it was. */
        data class NotIssued(val message: String) : Pressed<Nothing>

        /** Issued, but no PDF: the number stands, and [message] says where to get the PDF. */
        data class IssuedWithoutPdf(val finalised: String, val message: String) : Pressed<Nothing>

        /** The ₹0 question answered Cancel, or a press while the gate was open: nothing to say. */
        data object Quiet : Pressed<Nothing>
    }

    /**
     * One press. [onIssued] runs as soon as the number is in hand, before the
     * PDF is made — the screen moves from "Taking a number…" to "Preparing
     * the PDF…" there.
     */
    suspend fun press(
        draft: QuoteDraft,
        company: CompanyState,
        products: List<ProductRecord>,
        onIssued: () -> Unit = {}
    ): Pressed<T> {
        QuotationPdfMaker.ready(company)?.let { return Pressed.NotIssued(it) }
        if (draft.editOf != null) return Pressed.NotIssued(EDIT_FIRST)
        val finalised = when (val outcome = gate(draft)) {
            is GateOutcome.Finalised -> outcome
            is GateOutcome.NotFinalised -> return Pressed.NotIssued(outcome.message)
            GateOutcome.Cancelled, GateOutcome.AlreadyRunning,
            is GateOutcome.Saved, is GateOutcome.NotSaved -> return Pressed.Quiet
        }
        onIssued()
        val record = try {
            stored(draft.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            log(failure)
            null
        } ?: return Pressed.IssuedWithoutPdf(finalised.message, notReadBack(finalised.number))
        return when (val made = maker.make(record, company, products)) {
            is PdfOutcome.Ready -> Pressed.Ready(finalised.message, made)
            is PdfOutcome.Refused ->
                Pressed.IssuedWithoutPdf(finalised.message, "${made.message} ${fromQuotations(finalised.number)}")
        }
    }

    companion object {
        /** The approved plan's words, should an edit draft ever reach a PDF button. */
        const val EDIT_FIRST = "Save changes, then download it from the quotation"

        fun fromQuotations(number: String): String =
            "$number is issued — download, print or share it from Quotations."

        fun notReadBack(number: String): String =
            "The PDF was not made — the issued quotation could not be read back. ${fromQuotations(number)}"
    }
}
