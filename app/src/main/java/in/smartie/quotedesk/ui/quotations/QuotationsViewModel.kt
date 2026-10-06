package `in`.smartie.quotedesk.ui.quotations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.core.toAppError
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.repository.CancelOutcome
import `in`.smartie.quotedesk.domain.CompanyState
import `in`.smartie.quotedesk.domain.CopyStart
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PdfAction
import `in`.smartie.quotedesk.domain.PdfOutcome
import `in`.smartie.quotedesk.domain.PdfReady
import `in`.smartie.quotedesk.domain.QuotationCancel
import `in`.smartie.quotedesk.domain.QuotationCopy
import `in`.smartie.quotedesk.domain.QuotationDocument
import `in`.smartie.quotedesk.domain.QuotationPdfMaker
import `in`.smartie.quotedesk.domain.QuoteDrafts
import `in`.smartie.quotedesk.domain.Rendered
import `in`.smartie.quotedesk.ui.products.QuoteFinaliser
import `in`.smartie.quotedesk.ui.products.QuoteRequest
import `in`.smartie.quotedesk.ui.products.QuoteRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Why cancelling [quotationId] did not happen. */
data class CancelFailure(val quotationId: String, val message: String)

/** Why [quotationId]'s PDF was not made (N5.11). */
data class PdfFailure(val quotationId: String, val message: String)

/**
 * The Quotations tab's writes (N5.10): **Edit** hands the quotation to the
 * builder, and **Cancel** cancels it — remote-first.
 *
 * The list itself is the app's listener, not this view model's: a quotation
 * turns Cancelled on screen **only when the listener brings the change**, so
 * there is never a moment when this device shows something the team does not
 * — V8C4's "Cancelled here, but not for the team" has no counterpart. Offline
 * a cancel is refused before anything is sent, as a courtesy; the transaction
 * failing is the real protection.
 */
class QuotationsViewModel(
    /** Whose screen this is: an edit request carries it (`QuoteRequest.requestedBy`). */
    private val uid: String,
    private val cancelWrite: suspend (quotationId: String, number: String) -> CancelOutcome,
    private val requests: QuoteRequests,
    onlineFlow: Flow<Boolean>,
    /** A thrown failure in words — `toAppError().message` in the app. */
    private val describe: (Throwable) -> String,
    private val log: (Throwable) -> Unit = {},
    private val capMillis: Long = QuoteFinaliser.CAP_MILLIS,
    /** This account's drafts on this device, for what Duplicate would come to. */
    drafts: Flow<QuoteDrafts> = flowOf(QuoteDrafts()),
    /** The one renderer, into the cache (N5.11) — `AppContainer.renderQuotation` in the app. */
    render: suspend (QuotationDocument) -> Rendered<File> = { throw IllegalStateException("No PDF renderer") }
) : ViewModel() {

    private val pdfMaker = QuotationPdfMaker(render = render, describe = describe, log = log)

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** V8C4's toast, `X cancelled`, held until the screen shows it. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Eager, as every guard in this app reads it: see `PurchaseViewModel.online`. */
    private val online: StateFlow<Boolean> = onlineFlow
        .catch { emit(true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _cancelling = MutableStateFlow<String?>(null)

    /** The id of the quotation being cancelled, while the write is out. */
    val cancelling: StateFlow<String?> = _cancelling.asStateFlow()

    private val _failure = MutableStateFlow<CancelFailure?>(null)

    /**
     * Why the last cancel did not happen, until the next is tried — naming
     * its quotation, so the detail of another one never shows it.
     */
    val failure: StateFlow<CancelFailure?> = _failure.asStateFlow()

    /**
     * What pressing Duplicate would come to now (amendment C): not offered
     * while an edit is open, V8C4's question when the quotation being worked
     * on has lines, straight through when it is empty. Until the store
     * answers, the question is asked — the safe way round. The builder
     * decides again under its lock (`ProductsViewModel.openCopy`).
     */
    val copyStart: StateFlow<CopyStart> = drafts
        .map(QuotationCopy::start)
        .catch { emit(CopyStart.AskFirst) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CopyStart.AskFirst)

    /**
     * Copies [record] into a new draft, once the person has answered V8C4's
     * question or had nothing to lose; the caller moves to the Products tab.
     */
    fun duplicate(record: QuotationRecord) {
        requests.request(QuoteRequest.Copy(record, requestedBy = uid))
    }

    private val _preparing = MutableStateFlow<String?>(null)

    /** The id of the quotation whose PDF is being made (N5.11). */
    val preparing: StateFlow<String?> = _preparing.asStateFlow()

    private val _pdfFailure = MutableStateFlow<PdfFailure?>(null)

    /** Why the last PDF was not made, until the next is asked for — naming its quotation. */
    val pdfFailure: StateFlow<PdfFailure?> = _pdfFailure.asStateFlow()

    private val _pdfReady = Channel<PdfReady>(Channel.BUFFERED)

    /** Each PDF made, once, for the screen to download, print or share. */
    val pdfReady: Flow<PdfReady> = _pdfReady.receiveAsFlow()

    /**
     * **Download, Print or WhatsApp** on the detail screen (N5.11). The
     * quotation is issued already, so there is no gate: it keeps its number
     * and goes straight to the PDF — [record] as the listener brought it from
     * the server, its issue time included. Settings never loaded are refused;
     * the native beta's records, whose shape the PDF does not know, are not
     * made (the screen does not offer them).
     */
    fun output(record: QuotationRecord, action: PdfAction, company: CompanyState, products: List<ProductRecord>) {
        if (_preparing.value != null || record.legacyBetaShape) return
        _pdfFailure.value = null
        QuotationPdfMaker.ready(company)?.let {
            _pdfFailure.value = PdfFailure(record.id, it)
            return
        }
        _preparing.value = record.id
        viewModelScope.launch {
            try {
                when (val made = pdfMaker.make(record, company, products)) {
                    is PdfOutcome.Ready ->
                        _pdfReady.send(PdfReady(action, made.output, made.number, made.fileName, made.notice))
                    is PdfOutcome.Refused -> _pdfFailure.value = PdfFailure(record.id, made.message)
                }
            } finally {
                _preparing.value = null
            }
        }
    }

    /** Opens [record] in the builder for editing; the caller moves to the Products tab. */
    fun edit(record: QuotationRecord) {
        requests.request(QuoteRequest.Edit(record, requestedBy = uid))
    }

    /** Cancels [record], once the person has answered V8C4's question. */
    fun cancel(record: QuotationRecord) {
        if (_cancelling.value != null) return
        if (!online.value) {
            _failure.value = CancelFailure(record.id, CANCEL_OFFLINE)
            return
        }
        _failure.value = null
        _cancelling.value = record.id
        viewModelScope.launch {
            try {
                val outcome = try {
                    withTimeoutOrNull(capMillis) { cancelWrite(record.id, record.number) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    log(failure)
                    _failure.value = CancelFailure(record.id, notCancelled(describe(failure), mayHaveLanded = true))
                    return@launch
                }
                when (outcome) {
                    null -> _failure.value =
                        CancelFailure(record.id, notCancelled(QuoteFinaliser.TIMED_OUT, mayHaveLanded = true))
                    is CancelOutcome.Cancelled -> _messages.trySend(QuotationCancel.cancelledText(outcome.number))
                    is CancelOutcome.Refused -> {
                        outcome.cause?.let(log)
                        _failure.value = CancelFailure(record.id, notCancelled(outcome.message))
                    }
                }
            } finally {
                _cancelling.value = null
            }
        }
    }

    class Factory(
        private val container: AppContainer,
        private val member: Member,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = QuotationsViewModel(
            uid = member.uid,
            drafts = container.devicePreferences.forAccount(member.uid).drafts,
            cancelWrite = { id, number -> container.quotationWriteRepository.cancel(member, id, number) },
            requests = container.quoteRequests,
            onlineFlow = container.connectivity.online,
            describe = { it.toAppError().message },
            log = { container.errorReporter.report(it) },
            render = container::renderQuotation
        ) as T
    }

    companion object {
        /** Ours: V8C4 cancels locally first and has no such refusal. */
        const val CANCEL_OFFLINE =
            "Cancelling needs an internet connection — the quotation is shared with the team"

        const val MAY_HAVE_LANDED = "If it went through, the list will show it as cancelled."

        fun notCancelled(reason: String, mayHaveLanded: Boolean = false): String = buildString {
            append("Not cancelled — ")
            append(reason.trim().trimEnd('.'))
            append('.')
            if (mayHaveLanded) append(' ').append(MAY_HAVE_LANDED)
        }
    }
}
