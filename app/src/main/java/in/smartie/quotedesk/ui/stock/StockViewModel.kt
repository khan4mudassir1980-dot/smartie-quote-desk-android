package `in`.smartie.quotedesk.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.core.StockPendingStore
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.data.repository.StockWriteRepository
import `in`.smartie.quotedesk.data.repository.StockWriteResult
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.StockEntry
import `in`.smartie.quotedesk.domain.StockFilter
import `in`.smartie.quotedesk.domain.StockPendingCodec
import kotlinx.coroutines.flow.Flow
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
 * What Our Stock holds that Firestore does not: the search box, the filter,
 * and the `+`/`−` counts nobody has committed yet.
 *
 * **Nothing here is optimistic.** A pending delta is a number this person has
 * keyed in; the quantity on screen always comes from the Firestore listener,
 * and a pending count is cleared only when its own transaction returns
 * success. A failed write leaves it exactly where it was, so it can be
 * retried. Nothing commits itself when connectivity returns — the person
 * presses Done.
 */
class StockViewModel(
    private val member: Member,
    private val writes: StockWriteRepository,
    private val drafts: StockPendingStore,
    onlineFlow: Flow<Boolean>,
    private val report: (Throwable) -> Unit = {}
) : ViewModel() {

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(StockFilter.ALL)
    val filter: StateFlow<StockFilter> = _filter.asStateFlow()

    private val _pending = MutableStateFlow<Map<String, Double>>(emptyMap())
    val pending: StateFlow<Map<String, Double>> = _pending.asStateFlow()

    /** Keys with a write in flight, so a second tap on Done does nothing. */
    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    val saving: StateFlow<Set<String>> = _saving.asStateFlow()

    val online: StateFlow<Boolean> = onlineFlow
        .catch { emit(true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        // Read once. After that this view model owns the pending counts, so a
        // slow store catching up can never overwrite a fresh keystroke.
        viewModelScope.launch {
            runCatching { drafts.pending.first() }
                .onSuccess { stored -> if (stored.isNotEmpty()) _pending.value = stored }
        }
    }

    // --- what the person is looking at -------------------------------------

    fun setQuery(value: String) {
        _query.value = value
    }

    /** Tapping the selected tile clears the filter, as the PWA's tiles do. */
    fun toggleFilter(value: StockFilter) {
        _filter.value = if (_filter.value == value) StockFilter.ALL else value
    }

    // --- pending counts -----------------------------------------------------

    fun changePending(key: String, delta: Double) {
        if (!requireAdjust()) return
        persist(StockPendingCodec.change(_pending.value, key, delta))
    }

    fun clearPending(key: String) {
        if (key !in _pending.value) return
        persist(_pending.value - key)
        emit(CLEARED)
    }

    fun clearAllPending() {
        if (_pending.value.isEmpty()) return
        persist(emptyMap())
        emit(CLEARED)
    }

    // --- committing ---------------------------------------------------------

    /** One row's Done. */
    fun save(record: StockRecord, note: String = "") {
        if (!requireAdjust() || !requireOnline()) return
        val delta = _pending.value[record.key] ?: return
        if (record.key in _saving.value) return
        launchSave(setOf(record.key)) {
            val outcome = runCatching { writes.adjust(member, record, delta, note) }
            if (outcome.isSuccess) {
                clearSaved(record.key)
                emit(savedMessage(1))
            } else {
                emit(failureOf(outcome))
            }
        }
    }

    /**
     * Done for every pending row at once.
     *
     * Each row is its own transaction, so one failure cannot roll back a row
     * that succeeded. What is reported is what actually happened: the rows
     * that saved are cleared, the rows that failed stay pending, and a mixed
     * result says so rather than claiming everything was saved.
     */
    fun saveAll(records: List<StockRecord>, note: String = "") {
        if (!requireAdjust() || !requireOnline()) return
        val byKey = records.associateBy { it.key }
        val outstanding = _pending.value.keys.filter { it in byKey && it !in _saving.value }
        if (outstanding.isEmpty()) return
        launchSave(outstanding.toSet()) {
            var saved = 0
            val failures = mutableListOf<String>()
            for (key in outstanding) {
                val record = byKey.getValue(key)
                val delta = _pending.value[key] ?: continue
                runCatching { writes.adjust(member, record, delta, note) }
                    .onSuccess {
                        if (it == StockWriteResult.WRITTEN || it == StockWriteResult.NO_CHANGE) {
                            clearSaved(key)
                            saved++
                        }
                    }
                    .onFailure { failures += failureOf(Result.failure<Unit>(it)) }
            }
            emit(
                when {
                    failures.isEmpty() -> savedMessage(saved)
                    saved == 0 -> "Nothing saved — ${failures.first()}"
                    // Honest, not cheerful: the failures are still pending.
                    else -> "$saved saved, ${failures.size} still to save — ${failures.first()}"
                }
            )
        }
    }

    fun edit(record: StockRecord, quantity: Double, reorderLevel: Double, note: String) {
        if (!requireOnline()) return
        if (!Permissions.canSetReorderLevel(member)) {
            emit(NOT_ALLOWED)
            return
        }
        if (record.key in _saving.value) return
        launchSave(setOf(record.key)) {
            runCatching { writes.edit(member, record, quantity, reorderLevel, note) }
                .onSuccess { emit(if (it == StockWriteResult.WRITTEN) SAVED else NOTHING_CHANGED) }
                .onFailure { emit(failureOf(Result.failure<Unit>(it))) }
        }
    }

    fun addFromProduct(product: ProductRecord, quantity: Double, reorderLevel: Double, note: String) {
        create(StockEntry.fromProduct(product), quantity, reorderLevel, note)
    }

    fun addManual(
        model: String,
        name: String,
        categoryId: String,
        unit: String,
        quantity: Double,
        reorderLevel: Double,
        note: String
    ) {
        create(StockEntry.manual(model, name, categoryId, unit), quantity, reorderLevel, note)
    }

    private fun create(entry: StockEntry, quantity: Double, reorderLevel: Double, note: String) {
        if (!requireAdjust() || !requireOnline()) return
        entry.refusal(quantity, reorderLevel)?.let {
            emit(it)
            return
        }
        if (entry.key in _saving.value) return
        launchSave(setOf(entry.key)) {
            runCatching { writes.create(member, entry, quantity, reorderLevel, note) }
                .onSuccess { emit(ADDED) }
                .onFailure { emit(failureOf(Result.failure<Unit>(it))) }
        }
    }

    fun togglePin(record: StockRecord) {
        if (!requireOnline()) return
        if (!Permissions.canPinStock(member)) {
            emit(NOT_ALLOWED)
            return
        }
        if (record.key in _saving.value) return
        launchSave(setOf(record.key)) {
            runCatching { writes.togglePin(member, record) }
                .onSuccess { emit(if (record.pinned) UNPINNED else PINNED) }
                .onFailure { emit(failureOf(Result.failure<Unit>(it))) }
        }
    }

    fun stopTracking(record: StockRecord, note: String = "") {
        if (!requireOnline()) return
        if (!Permissions.canStopTrackingStock(member)) {
            emit(NOT_ALLOWED)
            return
        }
        if (record.key in _saving.value) return
        launchSave(setOf(record.key)) {
            runCatching { writes.stopTracking(member, record, note) }
                .onSuccess { emit(STOPPED) }
                .onFailure { emit(failureOf(Result.failure<Unit>(it))) }
        }
    }

    // --- what the screen may offer -----------------------------------------

    fun canAdjust(): Boolean = Permissions.canAdjustStock(member)

    fun canSetExactQuantity(): Boolean = Permissions.canSetExactQuantity(member)

    fun canSetReorderLevel(): Boolean = Permissions.canSetReorderLevel(member)

    fun canPin(): Boolean = Permissions.canPinStock(member)

    fun canStopTracking(): Boolean = Permissions.canStopTrackingStock(member)

    // --- plumbing -----------------------------------------------------------

    private fun launchSave(keys: Set<String>, block: suspend () -> Unit) {
        _saving.value = _saving.value + keys
        viewModelScope.launch {
            try {
                block()
            } finally {
                _saving.value = _saving.value - keys
            }
        }
    }

    /** Cleared **only** on success; a failed row stays pending for a retry. */
    private fun clearSaved(key: String) {
        persist(_pending.value - key)
    }

    private fun persist(pending: Map<String, Double>) {
        _pending.value = pending
        viewModelScope.launch {
            runCatching { drafts.setPending(pending) }
        }
    }

    private fun requireAdjust(): Boolean {
        if (Permissions.canAdjustStock(member)) return true
        emit(NOT_ALLOWED)
        return false
    }

    private fun requireOnline(): Boolean {
        if (online.value) return true
        emit(OFFLINE)
        return false
    }

    private fun failureOf(outcome: Result<*>): String {
        val throwable = outcome.exceptionOrNull() ?: return SAVE_FAILED
        report(throwable)
        return throwable.message?.takeIf { it.isNotBlank() } ?: SAVE_FAILED
    }

    private fun savedMessage(saved: Int): String = when (saved) {
        0 -> NOTHING_CHANGED
        1 -> SAVED
        else -> "$saved items saved"
    }

    private fun emit(message: String) {
        messages.tryEmit(message)
    }

    class Factory(
        private val container: AppContainer,
        private val member: Member
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StockViewModel(
            member = member,
            writes = container.stockWriteRepository,
            drafts = container.devicePreferences,
            onlineFlow = container.connectivity.online,
            report = container.errorReporter::report
        ) as T
    }

    companion object {
        /** Shown on every disabled control, and if one is somehow reached. */
        const val OFFLINE = "Internet required to change stock"
        const val NOT_ALLOWED = "You do not have permission to change stock"
        const val SAVED = "Saved"
        const val ADDED = "Added to stock"
        const val PINNED = "Pinned"
        const val UNPINNED = "Unpinned"
        const val STOPPED = "Stopped tracking"
        const val CLEARED = "Pending changes cleared"
        const val NOTHING_CHANGED = "Nothing to save"
        const val SAVE_FAILED = "Could not save — try again"
    }
}
