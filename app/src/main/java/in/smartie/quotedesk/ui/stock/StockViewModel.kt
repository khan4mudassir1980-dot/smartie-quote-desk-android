package `in`.smartie.quotedesk.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.core.StockPendingStore
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.data.repository.FirestoreFailures
import `in`.smartie.quotedesk.data.model.StoppedStockRecord
import `in`.smartie.quotedesk.data.repository.StockPhotoRepository
import `in`.smartie.quotedesk.data.repository.StoppedStockRepository
import `in`.smartie.quotedesk.data.repository.StockWriteRepository
import `in`.smartie.quotedesk.data.repository.StockWriteResult
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.StockEntry
import `in`.smartie.quotedesk.domain.StockFilter
import `in`.smartie.quotedesk.domain.StockPendingCodec
import `in`.smartie.quotedesk.domain.StockPhotoImage
import `in`.smartie.quotedesk.domain.StockRemoval
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
    private val photos: StockPhotoRepository? = null,
    private val history: StoppedStockRepository? = null,
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

    /** Which photo sheet is open, and what it holds. See [StockPhotoFlow]. */
    private val _photo = MutableStateFlow(StockPhotoUi())
    val photo: StateFlow<StockPhotoUi> = _photo.asStateFlow()

    /** The row whose removal is being confirmed, if any. */
    private val _removing = MutableStateFlow<StockRecord?>(null)
    val removing: StateFlow<StockRecord?> = _removing.asStateFlow()

    /** Whether the stopped-item history is open. Collapsed by default. */
    private val _historyExpanded = MutableStateFlow(false)
    val historyExpanded: StateFlow<Boolean> = _historyExpanded.asStateFlow()

    /** Whether the clear-history confirmation is open. */
    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    /**
     * Collected **eagerly**, not [SharingStarted.WhileSubscribed].
     *
     * This is a guard, not a display value: `requireOnline` reads `.value`
     * before every write. Under `WhileSubscribed` that value is the initial
     * `true` until something subscribes, and reverts five seconds after the
     * screen goes away — so a save could be attempted offline in exactly the
     * window the guard exists to cover.
     */
    val online: StateFlow<Boolean> = onlineFlow
        .catch { emit(true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

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

    // --- removal --------------------------------------------------------------

    /** Opening the confirmation. Nothing is written by asking. */
    fun askRemove(record: StockRecord) {
        if (!Permissions.canStopTrackingStock(member)) {
            emit(NOT_ALLOWED)
            return
        }
        _removing.value = record
    }

    /** Cancel. Explicitly writes nothing, which is the point of the sheet. */
    fun cancelRemove() {
        _removing.value = null
    }

    /**
     * Confirm. The row, its photo and its photo cache go; a small record
     * stays. There is no Restore, by decision — see [StockRemoval].
     */
    fun removeFromStock() {
        val record = _removing.value ?: return
        if (!requireOnline()) return
        if (record.key in _saving.value) return
        launchSave(setOf(record.key)) {
            runCatching { writes.removeFromStock(member, record) }
                .onSuccess {
                    // The bytes on disk outlive the document unless told.
                    photos?.forget(record)
                    _removing.value = null
                    emit(if (it == StockWriteResult.WRITTEN) REMOVED else NOTHING_CHANGED)
                }
                .onFailure {
                    // The sheet stays open so it can be tried again.
                    emit(failureOf(Result.failure<Unit>(it)))
                }
        }
    }

    // --- rows the old stop-tracking left behind -----------------------------

    /** Document ids already attempted this session, so a sweep runs once. */
    private val sweptLegacy = mutableSetOf<String>()

    /**
     * Finish what "stop tracking" started.
     *
     * A row written with `off: true` is invisible on the board and still
     * refuses its own re-add with "already exists" — the `SIE-EXTRECEIVER`
     * defect. There is no second identity scheme here and no new collection:
     * the row is put through exactly the removal above, under a **fixed** id
     * derived from its own document id, so a retry after a failure lands on
     * the document the first attempt wrote instead of writing a second one.
     *
     * Only an Owner or Administrator reaches this, because only they may
     * delete a stock row, and the rules say the same. For anybody else the
     * list is empty and nothing is attempted. A row that is **not** marked
     * is never touched: [StockWriteRepository.convertLegacyStopped] returns
     * without writing unless `archived` is set.
     *
     * Silent on success — nothing visible changed, an invisible row stopped
     * being in the way. A failure leaves the row exactly where it was, so it
     * is still recoverable and the next sweep tries again.
     */
    fun convertLegacyStopped(rows: List<StockRecord>) {
        if (rows.isEmpty() || !Permissions.canStopTrackingStock(member)) return
        if (!online.value) return
        val outstanding = rows.filter { it.archived && sweptLegacy.add(it.documentId) }
        if (outstanding.isEmpty()) return
        viewModelScope.launch {
            for (row in outstanding) {
                runCatching { writes.convertLegacyStopped(member, row) }
                    .onFailure {
                        // Retryable: let the next sweep have another go.
                        sweptLegacy.remove(row.documentId)
                        report(it)
                    }
            }
        }
    }

    // --- stopped-item history ---------------------------------------------

    fun toggleHistory() {
        _historyExpanded.value = !_historyExpanded.value
    }

    fun askClearHistory() {
        if (!Permissions.canStopTrackingStock(member)) {
            emit(NOT_ALLOWED)
            return
        }
        _clearing.value = true
    }

    fun cancelClearHistory() {
        _clearing.value = false
    }

    /**
     * Clear the history, and **only** the history.
     *
     * Deleted in batches, because a Firestore batch caps at 500 writes and
     * this list is not guaranteed to stay small. A batch that fails leaves
     * the batches before it deleted and the rest in place — which is safe
     * because every entry is independent, and a retry finishes the job.
     */
    fun clearHistory(entries: List<StoppedStockRecord>) {
        if (!Permissions.canStopTrackingStock(member)) {
            emit(NOT_ALLOWED)
            return
        }
        if (!requireOnline()) return
        val ids = entries.map { it.id }
        _clearing.value = false
        if (ids.isEmpty()) {
            emit(NOTHING_TO_CLEAR)
            return
        }
        viewModelScope.launch {
            runCatching { history?.clear(member, ids) }
                .onSuccess { emit(HISTORY_CLEARED) }
                .onFailure { emit(failureOf(Result.failure<Unit>(it))) }
        }
    }

    // --- photos -------------------------------------------------------------

    /**
     * The bytes to draw for a row, or null when there are none to draw.
     *
     * Straight through to the repository, which answers from memory, then
     * from disk, and only then spends a Firestore read. A row with no photo
     * is not asked about at all — the screen does not call this for one.
     */
    suspend fun loadPhoto(record: StockRecord): ByteArray? =
        photos?.let { runCatching { it.load(record) }.getOrNull() }

    /** Tapping a thumbnail, or the Photo button on a row without one. */
    fun openPhoto(record: StockRecord) {
        _photo.value = StockPhotoFlow.open(record, canManagePhoto())
    }

    fun closePhoto() {
        _photo.value = StockPhotoFlow.closed()
    }

    /** Replace: back to choosing a source, with the row kept. */
    fun replacePhoto() {
        if (!requirePhotoOnline()) return
        _photo.value = StockPhotoFlow.replace(_photo.value)
    }

    /** The camera or the picker has been launched; show the person that. */
    fun awaitingPhoto() {
        _photo.value = StockPhotoFlow.awaiting(_photo.value)
    }

    /** What the camera or the picker produced, already compressed. */
    fun photoPrepared(image: StockPhotoImage?) {
        _photo.value = StockPhotoFlow.prepared(_photo.value, image)
    }

    /** Backed out without choosing anything. Nothing was written. */
    fun photoAbandoned() {
        _photo.value = StockPhotoFlow.abandoned(_photo.value)
    }

    /**
     * Confirm. **This is the only path from a photo to Firestore**, and it
     * runs once: a second tap while the first is in flight does nothing.
     */
    fun confirmPhoto() {
        val current = _photo.value
        val record = current.record ?: return
        val image = current.prepared ?: return
        if (current.saving || !requirePhotoAllowed() || !requirePhotoOnline()) return
        _photo.value = StockPhotoFlow.saving(current)
        viewModelScope.launch {
            runCatching { writes.setPhoto(member, record, image) }
                .onSuccess {
                    // The row's revision has moved, so whatever is cached for
                    // it is now the old picture. One read replaces it; that is
                    // cheaper than risking the wrong photograph on a shelf.
                    photos?.forget(record)
                    _photo.value = StockPhotoFlow.closed()
                    emit(PHOTO_SAVED)
                }
                .onFailure {
                    _photo.value = StockPhotoFlow.failed(_photo.value)
                    emit(failureOf(Result.failure<Unit>(it)))
                }
        }
    }

    /** Take the photo off a row. The revision still advances. */
    fun removePhoto() {
        val current = _photo.value
        val record = current.record ?: return
        if (current.saving || !requirePhotoAllowed() || !requirePhotoOnline()) return
        _photo.value = StockPhotoFlow.saving(current)
        viewModelScope.launch {
            runCatching { writes.removePhoto(member, record) }
                .onSuccess {
                    photos?.forget(record)
                    _photo.value = StockPhotoFlow.closed()
                    emit(PHOTO_REMOVED)
                }
                .onFailure {
                    _photo.value = StockPhotoFlow.failed(_photo.value)
                    emit(failureOf(Result.failure<Unit>(it)))
                }
        }
    }

    private fun requirePhotoAllowed(): Boolean {
        if (canManagePhoto()) return true
        emit(NOT_ALLOWED_PHOTO)
        return false
    }

    private fun requirePhotoOnline(): Boolean {
        if (online.value) return true
        emit(PHOTO_OFFLINE_MESSAGE)
        return false
    }

    // --- what the screen may offer -----------------------------------------

    /**
     * Everything the board may show this person, as one value.
     *
     * The screen takes this rather than calling the eight predicates below
     * itself, so there is exactly one mapping from a role to a set of
     * controls and a test can hold it to account per role.
     */
    fun capabilities(): StockCapabilities = StockCapabilities.forMember(member)

    /** Owner, Administrator and Staff; the rules agree. */
    fun canManagePhoto(): Boolean = Permissions.canManageStockPhoto(member)

    /** Everyone who may read `/stock`, Workers included. */
    fun canViewPhoto(): Boolean = Permissions.canViewStockPhoto(member)

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
        // An exhausted daily quota is worth naming. Firestore calls it "Quota
        // exceeded", which reads as though somebody could pay for more; on
        // Spark nobody can, and it passes at the daily reset.
        FirestoreFailures.message(throwable)?.let { return it }
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
            // This account's own pending counts, never the phone's shared key.
            drafts = container.devicePreferences.forAccount(member.uid),
            onlineFlow = container.connectivity.online,
            photos = container.stockPhotoRepository,
            history = container.stoppedStockRepository,
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
        const val CLEARED = "Pending changes cleared"
        const val NOTHING_CHANGED = "Nothing to save"
        const val SAVE_FAILED = "Could not save — try again"
        const val PHOTO_SAVED = "Photo saved"
        const val PHOTO_REMOVED = "Photo removed"
        const val NOT_ALLOWED_PHOTO = "You do not have permission to change photos"
        const val NOTHING_TO_CLEAR = "There is nothing to clear"

        /**
         * The removal wording lives beside the panels that show it, in
         * [StockRemovalPanels.kt], so a message and the control it answers
         * cannot drift apart. Re-exported here for the tests that read
         * everything else off this companion.
         */
        val REMOVED_MESSAGE: String = REMOVED
        val HISTORY_CLEARED_MESSAGE: String = HISTORY_CLEARED

        /** The same wording every disabled photo control carries. */
        const val PHOTO_OFFLINE_MESSAGE = PHOTO_OFFLINE
    }
}
