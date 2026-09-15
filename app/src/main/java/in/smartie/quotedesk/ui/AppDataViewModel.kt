package `in`.smartie.quotedesk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.core.toAppError
import `in`.smartie.quotedesk.data.model.MemberRole
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.ProductCategoryRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.data.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Shared read-only state for the phase N0 shell.
 *
 * Product, stock, purchase and quotation writing is deliberately absent until
 * each screen is rebuilt in phases N2-N5: the beta write paths erased PWA
 * party fields, hard-deleted purchase requirements and wrote products the PWA
 * could not read (audit D1-D3).
 */
class AppDataViewModel(
    private val container: AppContainer,
    val profile: UserProfile,
) : ViewModel() {

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val online = container.connectivity.online
        .guarded(true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val products = catalogue(container.catalogueRepository.observeProducts())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ProductRecord>())

    val categories = catalogue(container.catalogueRepository.observeCategories())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ProductCategoryRecord>())

    val pinnedKeys = catalogue(container.catalogueRepository.observePinnedKeys())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<String>())

    /** Every role, Workers included, may see stock. */
    val stock = container.catalogueRepository.observeStock()
        .guarded(emptyList<StockRecord>())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val requirements = container.operationsRepository.observeRequirements()
        .guarded(emptyList<PurchaseRecord>())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val quotations = catalogue(container.operationsRepository.observeQuotations())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<QuotationRecord>())

    val parties = catalogue(container.operationsRepository.observeParties())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<PartyRecord>())

    val people = (
        if (profile.isAdmin) container.peopleRepository.observePeople() else flowOf(emptyList())
        )
        .guarded(emptyList<UserProfile>())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- team actions (rebuilt in phase N1) --------------------------------

    fun changeRole(person: UserProfile, role: MemberRole) = action {
        if (role == MemberRole.OWNER) container.peopleRepository.appointSecondOwner(person)
        else container.peopleRepository.changeRole(person, role)
    }

    fun setActive(person: UserProfile, active: Boolean) = action {
        container.peopleRepository.setActive(person, active)
    }

    fun deleteProfile(person: UserProfile) = action {
        container.peopleRepository.deleteProfile(person)
    }

    fun emergencyRevoke(person: UserProfile) = action {
        container.peopleRepository.emergencyRevoke(person)
    }

    /** Anything only quoting roles may read; Workers get an empty list. */
    private fun <T> catalogue(source: Flow<List<T>>): Flow<List<T>> =
        (if (profile.canQuote) source else flowOf(emptyList())).guarded(emptyList())

    private fun <T> Flow<T>.guarded(fallback: T): Flow<T> = catch { throwable ->
        val error = throwable.toAppError()
        container.errorReporter.report(error)
        if (!error.isBenign) messages.emit(error.message)
        emit(fallback)
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { messages.emit("Saved") }
                .onFailure {
                    val error = it.toAppError()
                    container.errorReporter.report(error)
                    messages.emit(error.message)
                }
        }
    }
}
