package `in`.smartie.quotedesk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.core.AppError
import `in`.smartie.quotedesk.core.toAppError
import `in`.smartie.quotedesk.data.retryingListener
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.ProductCategoryRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.StockMove
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.data.model.StoppedStockRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Shared read-only state for the phase N0/N1 shell.
 *
 * Product, stock, purchase and quotation writing is deliberately absent until
 * each screen is rebuilt in phases N2-N5: the beta write paths erased PWA
 * party fields, hard-deleted purchase requirements and wrote products the PWA
 * could not read.
 */
class AppDataViewModel(
    private val container: AppContainer,
    val member: Member,
) : ViewModel() {

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val online = container.connectivity.online
        .guarded("the connection")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val products = quotingOnly(container.catalogueRepository.observeProducts())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ProductRecord>())

    val categories = quotingOnly(container.catalogueRepository.observeCategories())
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList<ProductCategoryRecord>(),
        )

    val pinnedKeys = quotingOnly(container.catalogueRepository.observePinnedKeys())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<String>())

    /**
     * Every `/stock` row, the hidden `off: true` ones included.
     *
     * One listener, two lists. Filtering here rather than subscribing twice
     * keeps the board's cost what it always was: the legacy sweep below
     * reads nothing the board has not already paid for.
     */
    private val allStock = container.catalogueRepository.observeAllStock()
        .guarded("stock")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every role, Workers included, may see stock. */
    val stock = allStock
        .map { rows -> rows.filterNot { it.archived } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Rows the old "stop tracking" left behind: invisible on the board, and
     * still blocking their own re-add with "already exists".
     *
     * Only an Owner or Administrator can do anything about one, and the rules
     * agree, so nobody else is even shown the list.
     */
    val legacyStopped = (
        if (Permissions.canStopTrackingStock(member)) {
            allStock.map { rows -> rows.filter { it.archived } }
        } else {
            flowOf(emptyList<StockRecord>())
        }
        )
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Stopped-item history. Read-only, and readable by every role that may
     * see stock at all — the rules say `member()`, the same as `/stock`.
     */
    val stoppedStock = container.catalogueRepository.observeStoppedStock()
        .guarded("stopped-item history")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Stock movements, for the History action on a stock card. A Worker may
     * read `/stock` but the rules refuse them `/stockMoves`, so they get an
     * empty list and no History control.
     */
    val movements = (
        if (Permissions.canViewStockHistory(member)) {
            container.catalogueRepository.observeRecentMovements()
        } else {
            flowOf(emptyList())
        }
        )
        .guarded("stock history")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val requirements = container.operationsRepository.observeRequirements()
        .guarded("purchase requirements")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val quotations = quotingOnly(container.operationsRepository.observeQuotations())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<QuotationRecord>())

    val parties = quotingOnly(container.operationsRepository.observeParties())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<PartyRecord>())

    /** Data only quoting roles may read; a Worker gets an empty list. */
    private fun <T> quotingOnly(source: Flow<List<T>>): Flow<List<T>> =
        (if (Permissions.canQuote(member)) source else flowOf(emptyList()))
            .guarded("products and quotations")

    /**
     * A listener that comes back, and says so when it does not.
     *
     * It used to `catch` and emit a fallback, which **ended the flow**. A
     * `stateIn` whose upstream has completed is never collected again, and
     * this view model is scoped to the whole signed-in session — so one
     * transient refusal froze a screen's data for the life of the process,
     * silently. That is what made a newly added requirement invisible until
     * the app was closed and reopened.
     *
     * Now every failure is reported and the listener is attached again after
     * a growing wait. **Nothing is replaced with an empty list**: whatever was
     * last read stays on screen while the retries run, because a list that
     * failed to refresh is not a list of nothing.
     *
     * Silence would be the other way to get this wrong, so once the failures
     * stop looking like a blip the person is told, once, in the words the
     * error came with. Before that nothing is said — a listener that drops and
     * returns must not shout about it.
     */
    private fun <T> Flow<T>.guarded(label: String): Flow<T> =
        retryingListener { throwable, attempt, waitMillis ->
            val error = throwable.toAppError()
            container.errorReporter.report(error)
            if (attempt == PERSISTENT_ATTEMPT) {
                messages.emit(persistentMessage(label, error, waitMillis))
            }
        }

    private companion object {
        /**
         * The failure on which the person is told. Zero is the first, so this
         * is the third — two silent recoveries, then a sentence.
         */
        const val PERSISTENT_ATTEMPT = 2L

        /**
         * A refusal that keeps coming back is worth naming, and an
         * unauthenticated one is worth naming differently: it means signing
         * in again, not waiting.
         */
        fun persistentMessage(label: String, error: AppError, waitMillis: Long): String = when {
            error.code == "unauthenticated" ->
                "Signed out somewhere else — sign in again to see $label"
            error.code == "permission-denied" ->
                "This account is not allowed to read $label any more"
            else -> "Still trying to reach $label — retrying in ${waitMillis / 1_000}s"
        }
    }

    class Factory(
        private val container: AppContainer,
        private val member: Member,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppDataViewModel(container, member) as T
    }
}
