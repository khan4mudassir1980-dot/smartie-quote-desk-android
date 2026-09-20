package `in`.smartie.quotedesk.ui.purchase

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.data.repository.FirestoreFailures
import `in`.smartie.quotedesk.data.repository.PurchaseWriteRepository
import `in`.smartie.quotedesk.data.repository.PurchaseWriteResult
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.domain.PurchaseDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which panel is open, and for which requirement. */
data class PurchaseSheetState(
    val sheet: PurchaseSheet? = null,
    val record: PurchaseRecord? = null
) {
    val isOpen: Boolean get() = sheet != null
}

/**
 * What the Purchase tab holds that Firestore does not: which panel is open,
 * which requirements have a write in flight, and what to say about the one
 * that just finished.
 *
 * **Six operations and no more** — [add], [edit], [setUrgency], [markReceived],
 * [reopen] and [remove]. There is no cancel and no generic status setter: a
 * status is moved only by the operation that owns it, so no caller can invent
 * a state nobody designed.
 *
 * **Nothing here is optimistic.** Every list on screen comes from the
 * Firestore listener, and a write changes nothing locally — the document
 * comes back changed, or it does not. Writing is online-only, per project
 * convention, because a transaction needs a round trip.
 *
 * **A conflict is never resolved by trying again.** A purchase write carries
 * `rev = stored.rev + 1`, read inside the transaction, so a retry would be a
 * second opinion about a document somebody else has just changed — exactly
 * the double-completion the revision counter exists to stop. A refusal is
 * reported by name and the panel stays open; the person looks and decides.
 */
class PurchaseViewModel(
    private val member: Member,
    private val writes: PurchaseWriteRepository,
    requirements: Flow<List<PurchaseRecord>>,
    onlineFlow: Flow<Boolean>,
    private val report: (Throwable) -> Unit = {}
) : ViewModel() {

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * True until the listener has said something, so an empty board can be
     * told apart from one that has not arrived. It goes false on the first
     * emission **and** on a failure — a list that will never come is not
     * still loading.
     */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Ids with a write in flight, so a second tap does nothing. */
    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    val saving: StateFlow<Set<String>> = _saving.asStateFlow()

    private val _sheet = MutableStateFlow(PurchaseSheetState())
    val sheet: StateFlow<PurchaseSheetState> = _sheet.asStateFlow()

    /**
     * Collected **eagerly**, not [SharingStarted.WhileSubscribed].
     *
     * This is a guard, not a display value: [requireOnline] reads `.value`
     * before every write. Under `WhileSubscribed` that value is the initial
     * `true` until something subscribes, so a save could be attempted offline
     * in exactly the window the guard exists to cover.
     */
    val online: StateFlow<Boolean> = onlineFlow
        .catch { emit(true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val records: StateFlow<List<PurchaseRecord>> = requirements
        .onEach { _loading.value = false }
        .catch { throwable ->
            report(throwable)
            _loading.value = false
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Waiting to be bought, newest first — sorted **here**, never by
     * Firestore. `docs/N4-plan.md` records why an `orderBy("t")` is unsafe:
     * it drops rows that have no `t`, and a requirement that silently
     * vanishes from the shop floor is worse than the read cost.
     */
    val active: StateFlow<List<PurchaseRecord>> = records
        .map { PurchaseBoard.active(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Received or cancelled, newest first. A removed one is in neither list. */
    val closed: StateFlow<List<PurchaseRecord>> = records
        .map { PurchaseBoard.closed(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What this person may do, in one value the screen and its tests share. */
    fun capabilities(): PurchaseCapabilities = PurchaseCapabilities.forMember(member)

    // --- which panel is open -------------------------------------------------

    /**
     * Open a panel. Nothing is written by asking, but a panel this person may
     * not act on is not opened at all — otherwise the only thing standing
     * between a Manager and a reopen would be the control being hidden.
     */
    fun open(sheet: PurchaseSheet, record: PurchaseRecord? = null) {
        val allowed = capabilities()
        val refusal = when (sheet) {
            PurchaseSheet.ADD -> if (allowed.add) null else NOT_ALLOWED_ADD
            PurchaseSheet.EDIT, PurchaseSheet.URGENCY ->
                if (allowed.edit) null else NOT_ALLOWED_EDIT
            PurchaseSheet.RECEIVE -> if (allowed.receive) null else NOT_ALLOWED_EDIT
            PurchaseSheet.REOPEN -> if (allowed.reopen) null else NOT_ALLOWED_REOPEN
            PurchaseSheet.REMOVE -> if (allowed.remove) null else NOT_ALLOWED_REMOVE
        }
        if (refusal != null) {
            emit(refusal)
            return
        }
        if (sheet != PurchaseSheet.ADD && record == null) return
        _sheet.value = PurchaseSheetState(sheet, record)
    }

    /** Cancel. Explicitly writes nothing, which is the point of the sheet. */
    fun dismiss() {
        _sheet.value = PurchaseSheetState()
    }

    // --- the six operations ---------------------------------------------------

    fun add(draft: PurchaseDraft) {
        draft.refusal()?.let {
            emit(it)
            return
        }
        if (!requireOnline()) return
        write(ADD_KEY, ADDED) { writes.create(member, draft) }
    }

    fun edit(
        record: PurchaseRecord,
        name: String,
        quantity: Double,
        urgency: UrgencyV2,
        note: String
    ) {
        if (!requireOnline()) return
        write(record.id, SAVED) { writes.edit(member, record, name, quantity, urgency, note) }
    }

    fun setUrgency(record: PurchaseRecord, urgency: UrgencyV2) {
        if (!requireOnline()) return
        write(record.id, SAVED) { writes.setUrgency(member, record, urgency) }
    }

    fun markReceived(record: PurchaseRecord, receivedQuantity: Double) {
        if (!requireOnline()) return
        write(record.id, RECEIVED) { writes.markReceived(member, record, receivedQuantity) }
    }

    fun reopen(record: PurchaseRecord) {
        if (!requireOnline()) return
        write(record.id, REOPENED) { writes.reopen(member, record) }
    }

    fun remove(record: PurchaseRecord) {
        if (!requireOnline()) return
        write(record.id, REMOVED) { writes.softDelete(member, record) }
    }

    // --- plumbing ---------------------------------------------------------------

    /**
     * One write, once.
     *
     * The [key] is in [saving] for as long as the transaction is on the wire,
     * which is what stops a second tap starting a second one. On success the
     * panel closes; on any failure it stays open, holding what was typed, so
     * the person can look at the refusal and decide — this is never retried
     * for them.
     */
    private fun write(
        key: String,
        success: String,
        block: suspend () -> PurchaseWriteResult
    ) {
        if (key in _saving.value) return
        _saving.value = _saving.value + key
        viewModelScope.launch {
            try {
                runCatching { block() }
                    .onSuccess { result ->
                        dismiss()
                        emit(if (result == PurchaseWriteResult.WRITTEN) success else NOTHING_CHANGED)
                    }
                    .onFailure { emit(failureOf(it)) }
            } finally {
                _saving.value = _saving.value - key
            }
        }
    }

    private fun requireOnline(): Boolean {
        if (online.value) return true
        emit(OFFLINE)
        return false
    }

    /**
     * A sentence, never an error code.
     *
     * The order matters. A refusal the app decided — a Worker's write stopped
     * before the transaction opened, or a requirement somebody else has
     * already received — carries its own wording and must survive; only a
     * failure that came back from Firestore is translated.
     */
    private fun failureOf(throwable: Throwable): String {
        report(throwable)
        FirestoreFailures.message(throwable)?.let { return it }
        if (FirestoreFailures.isWriteConflict(throwable)) return FirestoreFailures.WRITE_CONFLICT
        if (FirestoreFailures.isRefused(throwable)) return FirestoreFailures.REFUSED
        return throwable.message?.takeIf { it.isNotBlank() } ?: SAVE_FAILED
    }

    private fun emit(message: String) {
        messages.tryEmit(message)
    }

    /**
     * Built with the requirements flow the screen already has.
     *
     * Deliberately **not** a second `observeRequirements()`: that would double
     * the tab's cost against a shared daily quota to read rows the first
     * listener already holds, which is the same reason Our Stock splits one
     * listener into two lists rather than subscribing twice.
     */
    class Factory(
        private val container: AppContainer,
        private val member: Member,
        private val requirements: Flow<List<PurchaseRecord>>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PurchaseViewModel(
            member = member,
            writes = container.purchaseWriteRepository,
            requirements = requirements,
            onlineFlow = container.connectivity.online,
            report = container.errorReporter::report
        ) as T
    }

    companion object {
        /** The key [saving] uses for the add panel, which has no record yet. */
        const val ADD_KEY: String = "add"

        /** Shown on every disabled control, and if one is somehow reached. */
        const val OFFLINE: String = "Internet required to change a requirement"

        const val ADDED: String = "Requirement added"
        const val SAVED: String = "Saved"
        const val RECEIVED: String = "Marked as received"
        const val REOPENED: String = "Back on the active list"
        const val REMOVED: String = "Requirement removed"
        const val NOTHING_CHANGED: String = "Nothing to save"
        const val SAVE_FAILED: String = "Could not save — try again"

        const val NOT_ALLOWED_ADD: String = "Your account cannot add a requirement"
        const val NOT_ALLOWED_EDIT: String = "Your account cannot change a requirement"
        const val NOT_ALLOWED_REOPEN: String = "Your account cannot reopen a requirement"
        const val NOT_ALLOWED_REMOVE: String = "Your account cannot remove a requirement"
    }
}
