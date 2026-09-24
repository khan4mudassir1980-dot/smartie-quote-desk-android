package `in`.smartie.quotedesk.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.core.toAppError
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.data.repository.PartyWriteResult
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PartyWrite
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.PinChange
import `in`.smartie.quotedesk.domain.ProductDraft
import `in`.smartie.quotedesk.domain.ProductPins
import `in`.smartie.quotedesk.domain.ProductWrite
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteDrafts
import `in`.smartie.quotedesk.domain.QuoteParty
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
     * Brings lines typed while the stored draft was still loading into step
     * with its tier.
     *
     * Called when the catalogue is ready, because that is the first moment
     * there is anything to reprice *from* — this view model has no catalogue
     * of its own, which is why the screen hands it one. A no-op on every
     * ordinary start, so it costs nothing to call.
     */
    fun alignDraftToTier(products: List<ProductRecord>) {
        val current = _draft.value
        if (!current.hasLinesOutOfStep) return
        val byKey = products.associateBy { it.key }
        val outcome = current.alignLinesToTier { key -> byKey[key]?.priceFor(current.tier) }
        persist(outcome.draft)
        if (outcome.repriced > 0) {
            emit("${outcome.repriced} repriced at ${current.tier.label} rates")
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

    /**
     * A line on the builder, by its **id**.
     *
     * [changeQuantity] above takes a catalogue key, because a product card
     * knows a product and not a line. That is right there and wrong here: a
     * hand-typed line has no key at all, and two openings of one shutter share
     * theirs, so a key-addressed stepper on the builder would change a figure
     * the person was not looking at.
     */
    fun changeLineQuantity(id: String, delta: Double) {
        persist(_draft.value.changeQuantity(id, delta))
    }

    fun setQuantity(key: String, quantity: Double) {
        if (!QuoteDraft.isValidQuantity(quantity)) {
            emit(NEGATIVE_QUANTITY)
            return
        }
        persist(_draft.value.setCatalogueQuantity(key, quantity))
    }

    // --- who the quotation is for (N5.8b) -----------------------------------

    /**
     * The customer block, as it is typed.
     *
     * Persisted on every keystroke, the same as every other change to a
     * draft: a half-typed customer must survive the app being killed exactly
     * as a half-built line list does (audit C8).
     */
    fun setPartyDetails(party: QuotationPartySnapshot) {
        persist(_draft.value.copy(party = party))
    }

    /** A saved customer, chosen from the picker. The site is kept. */
    fun chooseParty(record: PartyRecord) {
        persist(_draft.value.withParty(record))
    }

    /**
     * An id for a customer this quotation is about to create.
     *
     * **A party id, never a draft id, and the difference is the whole of
     * 8a's structural fix.** A draft id may only come from
     * `AccountPreferences.currentDraftId()`, because a draft is one per
     * account, resolved from the store, and minting one here turned a
     * process death into two drafts. A *new customer* is a document this
     * person is creating right now: it has no stored id to resolve, and the
     * id must stay the same across a retry — which is why the screen holds
     * the answer in `rememberSaveable` rather than calling this again.
     *
     * The same shape as `PartiesViewModel.mintId`, and called on demand,
     * never from `init`.
     */
    fun mintPartyId(): String = Keys.generateId(PartyWrite.ID_PREFIX)

    /**
     * "Save this customer": merge into the chosen one, or create a new one.
     *
     * On a create, the draft **adopts** the new id, so saving twice corrects
     * the same customer instead of making a second.
     */
    fun saveCustomer(newId: String) {
        val draft = _draft.value
        val party = QuoteParty.draftOf(draft.party)
        party.refusal()?.let {
            _partyFailure.value = it
            return
        }
        _partyFailure.value = null
        _savingParty.value = true
        viewModelScope.launch {
            runCatching {
                container.partyWriteRepository.saveFromQuotation(
                    member = member,
                    partyId = draft.partyId,
                    draft = party,
                    newId = newId
                )
            }.onSuccess { result ->
                if (draft.partyId.isBlank()) persist(_draft.value.copy(partyId = newId))
                emit(
                    if (result == PartyWriteResult.WRITTEN) CUSTOMER_SAVED
                    else CUSTOMER_UNCHANGED
                )
            }.onFailure { failure ->
                val refusal = (failure as? IllegalStateException)?.message
                if (refusal != null) _partyFailure.value = refusal else report(failure)
            }
            _savingParty.value = false
        }
    }

    fun clearDraft() {
        persist(_draft.value.clear())
    }

    private val _savingParty = MutableStateFlow(false)
    val savingParty: StateFlow<Boolean> = _savingParty.asStateFlow()

    /**
     * The last refusal from "Save this customer", kept on the panel.
     *
     * A refusal names something the person has to act on — a customer with
     * no name — so it stays on screen rather than passing by as a message.
     */
    private val _partyFailure = MutableStateFlow<String?>(null)
    val partyFailure: StateFlow<String?> = _partyFailure.asStateFlow()

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
        const val CUSTOMER_SAVED = "Customer saved"
        // Not a failure. Everything on the quotation already matches what is
        // stored, so there was nothing to write.
        const val CUSTOMER_UNCHANGED = "Nothing to save — this customer is already up to date"
    }
}
