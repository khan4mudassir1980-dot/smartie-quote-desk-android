package `in`.smartie.quotedesk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.core.toAppError
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.ProductCategoryRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.StockMove
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
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
        .guarded(true)
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

    /** Every role, Workers included, may see stock. */
    val stock = container.catalogueRepository.observeStock()
        .guarded(emptyList<StockRecord>())
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
        .guarded(emptyList<StockMove>())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val requirements = container.operationsRepository.observeRequirements()
        .guarded(emptyList<PurchaseRecord>())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val quotations = quotingOnly(container.operationsRepository.observeQuotations())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<QuotationRecord>())

    val parties = quotingOnly(container.operationsRepository.observeParties())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<PartyRecord>())

    /** Data only quoting roles may read; a Worker gets an empty list. */
    private fun <T> quotingOnly(source: Flow<List<T>>): Flow<List<T>> =
        (if (Permissions.canQuote(member)) source else flowOf(emptyList())).guarded(emptyList())

    private fun <T> Flow<T>.guarded(fallback: T): Flow<T> = catch { throwable ->
        val error = throwable.toAppError()
        container.errorReporter.report(error)
        if (!error.isBenign) messages.emit(error.message)
        emit(fallback)
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
