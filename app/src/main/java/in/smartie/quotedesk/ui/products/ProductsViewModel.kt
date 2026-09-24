package `in`.smartie.quotedesk.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.core.toAppError
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.PinChange
import `in`.smartie.quotedesk.domain.ProductDraft
import `in`.smartie.quotedesk.domain.ProductPins
import `in`.smartie.quotedesk.domain.ProductWrite
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteDrafts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the Products tab holds that Firestore does not: the search box, the
 * load filter, the tier, which shelves are open, and the quotation being
 * built.
 *
 * The catalogue itself is arranged by [`in`.smartie.quotedesk.domain.Catalogue]
 * from the shared read-only flows, so this class stays small and every
 * arrangement rule remains unit-tested without Android.
 */
class ProductsViewModel(
    private val container: AppContainer,
    private val member: Member,
) : ViewModel() {

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _minimumKg = MutableStateFlow<Double?>(null)
    val minimumKg: StateFlow<Double?> = _minimumKg.asStateFlow()

    private val _draft = MutableStateFlow(QuoteDraft())
    val draft: StateFlow<QuoteDraft> = _draft.asStateFlow()

    /** The draft owns the tier, so the selector and the lines cannot disagree. */
    val tier: RateTierV2 get() = _draft.value.tier

    val openShelves: StateFlow<Set<String>> = container.devicePreferences.openShelves
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** This account's own device storage, never the phone's shared keys. */
    private val account = container.devicePreferences.forAccount(member.uid)

    /**
     * The draft's id, once the store has answered.
     *
     * **This view model cannot mint one**, and that is deliberate. Minting
     * here was the third appearance of one shape in a single batch: a view
     * model is recreated on process death, so an id made in its constructor
     * made a *second* draft out of one quotation. `currentDraftId` owns
     * creation now, and every save waits for its answer rather than
     * inventing one to get on with.
     */
    private val draftId = CompletableDeferred<String>()

    init {
        // The stored draft is read once. After that this view model owns it,
        // so an edit is never overwritten by the store catching up.
        viewModelScope.launch {
            // Rescues a draft and any pending stock counts left under the old
            // ownerless keys. The **leak** is already closed by the keying
            // itself — nothing reads those keys any more — so this only saves
            // work in progress, and it is idempotent.
            runCatching { account.adoptOwnerlessValues() }.onFailure { report(it) }

            runCatching {
                val id = account.currentDraftId()
                id to account.drafts.first()[id]
            }.onSuccess { (id, stored) ->
                // Never a straight assignment. A tap on Add can land before
                // this returns, and overwriting would take the person's line
                // away silently; skipping when the stored draft is empty
                // would leave this holding no id and mint a second one on the
                // next save. `resume` is where both are decided, and it is
                // unit-tested — this view model cannot be.
                _draft.value = QuoteDrafts.resume(_draft.value, stored, id)
                draftId.complete(id)
            }.onFailure { failure ->
                report(failure)
                // The store is unreadable. The screen still works and the
                // draft simply is not persisted — it is never given an
                // invented id to carry on with.
                draftId.complete("")
            }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setMinimumKg(value: Double?) {
        _minimumKg.value = value?.takeIf { it > 0.0 }
    }

    fun toggleShelf(categoryId: String, open: Boolean) {
        viewModelScope.launch {
            runCatching { container.devicePreferences.setShelfOpen(categoryId, open) }
        }
    }

    /**
     * Switches tier and reprices the lines nobody typed a rate into, saying
     * what moved and what was kept, as the PWA does (2269-2288).
     */
    fun setTier(newTier: RateTierV2, products: List<ProductRecord>) {
        val current = _draft.value
        if (newTier == current.tier) return
        val byKey = products.associateBy { it.key }
        val outcome = current.withTier(newTier) { key -> byKey[key]?.priceFor(newTier) }
        persist(outcome.draft)
        if (outcome.repriced > 0 || outcome.kept > 0) {
            val parts = buildList {
                if (outcome.repriced > 0) add("${outcome.repriced} repriced")
                if (outcome.kept > 0) add("${outcome.kept} kept at your rate")
            }
            emit(parts.joinToString(", ").replaceFirstChar { it.uppercase() })
        }
    }

    fun add(product: ProductRecord) {
        val before = _draft.value.quantityOf(product.key)
        persist(_draft.value.add(product))
        if (before > 0.0) emit(ALREADY_IN_QUOTE)
    }

    fun changeQuantity(key: String, delta: Double) {
        persist(_draft.value.changeCatalogueQuantity(key, delta))
    }

    fun setQuantity(key: String, quantity: Double) {
        if (!QuoteDraft.isValidQuantity(quantity)) {
            emit(NEGATIVE_QUANTITY)
            return
        }
        persist(_draft.value.setCatalogueQuantity(key, quantity))
    }

    fun clearDraft() {
        persist(_draft.value.clear())
    }

    // --- pins --------------------------------------------------------------

    fun canManagePins(): Boolean = Permissions.canManageCategoriesAndPins(member)

    fun togglePin(currentKeys: List<String>, key: String) {
        applyPinChange(ProductPins.toggle(currentKeys, key), pinned = key !in currentKeys)
    }

    /** The drag handle's named accessibility actions: one place at a time. */
    fun movePin(currentKeys: List<String>, key: String, delta: Int) {
        applyPinChange(ProductPins.move(currentKeys, key, delta), announce = false)
    }

    /**
     * A drop: [movedKey] goes where [targetKey] sits.
     *
     * One write, after the finger lifts, through the same shared
     * `teamSettings/productPins` document as every other pin change.
     */
    fun reorderPin(currentKeys: List<String>, movedKey: String, targetKey: String) {
        applyPinChange(ProductPins.reorderTo(currentKeys, movedKey, targetKey), announce = false)
    }

    private fun applyPinChange(
        change: PinChange,
        pinned: Boolean = true,
        announce: Boolean = true,
    ) {
        if (!canManagePins()) {
            emit(NOT_ALLOWED)
            return
        }
        when (change) {
            PinChange.Unchanged -> Unit
            PinChange.Full -> emit(ProductPins.FULL_MESSAGE)
            is PinChange.Updated -> viewModelScope.launch {
                runCatching { container.productPinsRepository.save(member, change.keys) }
                    .onSuccess {
                        if (announce) {
                            emit(if (pinned) ProductPins.PINNED_MESSAGE else ProductPins.UNPINNED_MESSAGE)
                        }
                    }
                    .onFailure { report(it) }
            }
        }
    }

    // --- correcting a product (N5.7) ---------------------------------------

    private val _savingProduct = MutableStateFlow(false)
    val savingProduct: StateFlow<Boolean> = _savingProduct.asStateFlow()

    /**
     * The last refusal, shown on the editor rather than as a passing message.
     *
     * A refusal here names a field or a stored value the person has to act on,
     * so it has to stay on screen while they do. Cleared when the next save
     * starts.
     */
    private val _productFailure = MutableStateFlow<String?>(null)
    val productFailure: StateFlow<String?> = _productFailure.asStateFlow()

    fun saveProduct(record: ProductRecord, draft: ProductDraft) {
        if (!Permissions.canEditProducts(member)) {
            emit(ProductWrite.CANNOT_EDIT)
            return
        }
        _productFailure.value = null
        _savingProduct.value = true
        viewModelScope.launch {
            runCatching { container.productEditRepository.save(member, record, draft = draft) }
                .onSuccess { written -> if (written) emit(PRODUCT_SAVED) }
                .onFailure { failure ->
                    // A refusal `ProductWrite` decided carries the person's own
                    // words and belongs on the sheet; anything else is a real
                    // failure and goes through the reporter like the rest.
                    val refusal = (failure as? IllegalStateException)?.message
                    if (refusal != null) _productFailure.value = refusal else report(failure)
                }
            _savingProduct.value = false
        }
    }

    // --- plumbing ----------------------------------------------------------

    private fun persist(draft: QuoteDraft) {
        // Shown immediately; stored once the id is known. The person never
        // waits on a disk read to see their own tap.
        _draft.value = draft.copy(updatedAt = System.currentTimeMillis())
        viewModelScope.launch {
            runCatching {
                val id = draftId.await()
                if (id.isNotBlank()) {
                    val latest = _draft.value.copy(id = id)
                    _draft.value = latest
                    account.setDrafts(account.drafts.first().save(latest))
                }
            }.onFailure { report(it) }
        }
    }

    private fun report(throwable: Throwable) {
        val error = throwable.toAppError()
        container.errorReporter.report(error)
        if (!error.isBenign) emit(error.message)
    }

    private fun emit(message: String) {
        messages.tryEmit(message)
    }

    class Factory(
        private val container: AppContainer,
        private val member: Member,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProductsViewModel(container, member) as T
    }

    private companion object {
        const val ALREADY_IN_QUOTE = "Already in the quotation — quantity raised"
        const val NEGATIVE_QUANTITY = "Quantity cannot be negative"
        const val NOT_ALLOWED = "Only an Owner or Administrator can manage pinned products"
        const val PRODUCT_SAVED = "Product saved"
    }
}
