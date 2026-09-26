package `in`.smartie.quotedesk.ui.products

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The id this account's draft is saved under — **and able to move**, which
 * is what finalising needs.
 *
 * Until N5.9b the view model held the id in a `CompletableDeferred`,
 * completed once when the store answered. That was right while a quotation
 * could only be built: one account, one draft, one id for the life of the
 * screen. It stops being right the moment a draft is finalised and retired.
 * A deferred cannot be completed twice, so every save after the retire would
 * still land on the **finalised** id — the next quotation stored as the old
 * draft, and finalise's read-first answering it with the old number.
 *
 * So the id lives here, behind **one lock** that every save and every
 * retire go through:
 *
 * - [withCurrent] runs a save with the id **read under the lock**, so a save
 *   can never hold an id that a retire has since replaced;
 * - [replace] runs a retire under the same lock, so no save interleaves with
 *   it, and every save queued behind it writes the new id.
 *
 * That closes the second hazard too: a save already in flight when the
 * draft is retired would otherwise write the finalised draft straight back.
 * Here it finishes first, and the retire removes what it wrote.
 *
 * Both wait until the store has answered ([resolve]); nothing is ever saved
 * under an id this class invented. An unreadable store resolves to `""`,
 * and callers skip saving under a blank id, as they always have.
 *
 * Pure — coroutines only — because `ProductsViewModel` takes an
 * `AppContainer` and cannot be unit-tested. This is where the ordering is
 * decided, so this is what is tested.
 */
class DraftWrites {

    private val lock = Mutex()
    private val id = MutableStateFlow<String?>(null)

    /** The id as it stands, or null while the store has not answered. */
    val current: String? get() = id.value

    /**
     * The store's answer at start-up: the draft's id, or `""` when it could
     * not be read. **The first answer wins** — a late one never moves an id
     * that [replace] has already moved on.
     */
    fun resolve(value: String) {
        id.compareAndSet(null, value)
    }

    /** One save, with the id it must be written under. */
    suspend fun <T> withCurrent(block: suspend (id: String) -> T): T =
        lock.withLock { block(resolved()) }

    /**
     * One retire: [block] is given the id being retired and answers the id
     * saves move to. Nothing else is written while it runs.
     */
    suspend fun replace(block: suspend (retiring: String) -> String): String =
        lock.withLock {
            val next = block(resolved())
            id.value = next
            next
        }

    private suspend fun resolved(): String = id.filterNotNull().first()
}
