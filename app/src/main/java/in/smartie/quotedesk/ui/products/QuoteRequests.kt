package `in`.smartie.quotedesk.ui.products

import `in`.smartie.quotedesk.data.model.QuotationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A finalised quotation to open in the builder, asked for from the Quotations tab. */
sealed interface QuoteRequest {
    val record: QuotationRecord

    /**
     * Who asked. The slot is the app's and outlives a sign-out, so a request
     * is acted on only by the account that made it.
     */
    val requestedBy: String

    /** Edit it: the same number, saved over (N5.10). */
    data class Edit(override val record: QuotationRecord, override val requestedBy: String) : QuoteRequest

    /**
     * Duplicate it into a new draft, in place of the one in progress — asked
     * only once the person has answered V8C4's question, or had nothing to
     * lose (N5.10, amendment C).
     */
    data class Copy(override val record: QuotationRecord, override val requestedBy: String) : QuoteRequest
}

/**
 * The one hand-off from the Quotations tab to the builder.
 *
 * The two screens have view models of their own, each scoped to its tab, so
 * the request lives in the app's container and the builder's view model takes
 * it. **A single slot, taken once**: the latest request replaces an untaken
 * one, and [take] succeeds for exactly one caller — so a request is never
 * acted on twice, whichever order the tab switch and the collection happen in.
 */
class QuoteRequests {
    private val _pending = MutableStateFlow<QuoteRequest?>(null)
    val pending: StateFlow<QuoteRequest?> = _pending.asStateFlow()

    fun request(request: QuoteRequest) {
        _pending.value = request
    }

    /** True when this caller took [request]; false when it had gone already. */
    fun take(request: QuoteRequest): Boolean = _pending.compareAndSet(request, null)
}
