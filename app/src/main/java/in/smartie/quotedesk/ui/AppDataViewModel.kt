package `in`.smartie.quotedesk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.data.model.MemberRole
import `in`.smartie.quotedesk.data.model.Product
import `in`.smartie.quotedesk.data.model.PurchaseRequirement
import `in`.smartie.quotedesk.data.model.QuotationSummary
import `in`.smartie.quotedesk.data.model.StockItem
import `in`.smartie.quotedesk.data.model.Urgency
import `in`.smartie.quotedesk.data.model.UserProfile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppDataViewModel(
    private val container: AppContainer,
    val profile: UserProfile,
) : ViewModel() {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val products = (if (profile.canQuote) container.productRepository.observeProducts() else flowOf(emptyList<Product>()))
        .catch { messages.emit(it.readableMessage()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pins = (if (profile.canQuote) container.productRepository.observePins() else flowOf(emptySet<String>()))
        .catch { messages.emit(it.readableMessage()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val stock = container.stockRepository.observeStock()
        .catch { messages.emit(it.readableMessage()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<StockItem>())
    val requirements = container.purchaseRepository.observeRequirements()
        .catch { messages.emit(it.readableMessage()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<PurchaseRequirement>())
    val quotations = (if (profile.canQuote) container.quotationRepository.observeQuotations() else flowOf(emptyList<QuotationSummary>()))
        .catch { messages.emit(it.readableMessage()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val people = (if (profile.isAdmin) container.peopleRepository.observePeople() else flowOf(emptyList<UserProfile>()))
        .catch { messages.emit(it.readableMessage()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun togglePin(productId: String, pinned: Boolean) = action {
        container.productRepository.togglePin(productId, pinned)
    }

    fun commitStock(item: StockItem, delta: Double, note: String = "") = action {
        container.stockRepository.commitDelta(item, delta, note)
    }

    fun editStock(item: StockItem, quantity: Double, reorderLevel: Double, note: String) = action {
        container.stockRepository.editStock(item, quantity, reorderLevel, note)
    }

    fun addRequirement(name: String, quantity: Double, urgency: Urgency, note: String) = action {
        container.purchaseRepository.addRequirement(name, quantity, urgency, note)
    }

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

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { messages.emit("Saved") }
                .onFailure { messages.emit(it.readableMessage()) }
        }
    }
}
