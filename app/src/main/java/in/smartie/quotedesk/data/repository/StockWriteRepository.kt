package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.StockAuthor
import `in`.smartie.quotedesk.domain.StockEntry
import `in`.smartie.quotedesk.domain.StockWrite
import `in`.smartie.quotedesk.domain.StockWritePlan

/** Whether a call actually put anything on the wire. */
enum class StockWriteResult { WRITTEN, NO_CHANGE }

/**
 * The only writer N3 adds.
 *
 * **The transaction contract**, binding on every call below:
 *
 * 1. the movement id and `at` are generated **once, before** the transaction;
 * 2. both are **reused** if Firestore replays the body, so one action can
 *    never produce two movement documents or drift its own timestamp;
 * 3. the stored `q` is read **inside** the transaction — never the figure the
 *    screen was showing, never a cached one;
 * 4. `prev`, the signed `delta` and `next` are derived from that stored value;
 * 5. anything that would take stock below zero is **rejected**, not clamped;
 * 6. the stock document and its movement are written **atomically**.
 *
 * Writing is **online-only**. A Firestore transaction needs a round trip, so
 * there is no offline queue here and no optimistic local mutation: a call
 * either commits or fails, and the caller keeps its pending delta either way.
 *
 * Permission is checked before the transaction opens, so a write the rules
 * would refuse never leaves the device. The rules refuse it as well; both
 * halves are tested.
 */
class StockWriteRepository(
    private val store: StockStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newMovementId: () -> String = { Keys.generateId("mv_") }
) {

    /** Plus or minus, committed against the stored quantity. */
    suspend fun adjust(
        member: Member,
        record: StockRecord,
        delta: Double,
        note: String = ""
    ): StockWriteResult {
        require(Permissions.canAdjustStock(member)) { NOT_ALLOWED_ADJUST }
        return commit(member) { transaction, author, at, movementId ->
            StockWrite.adjust(
                record = record,
                storedQuantity = storedQuantity(transaction, record),
                delta = delta,
                note = note,
                author = author,
                at = at,
                movementId = movementId
            )
        }
    }

    /** The Edit dialog: exact quantity, reorder level, and the note. */
    suspend fun edit(
        member: Member,
        record: StockRecord,
        quantity: Double,
        reorderLevel: Double,
        note: String = ""
    ): StockWriteResult {
        require(Permissions.canSetReorderLevel(member)) { NOT_ALLOWED_EDIT }
        return commit(member) { transaction, author, at, movementId ->
            val stored = storedQuantity(transaction, record)
            // Only an Owner or Administrator may move the number itself; Staff
            // reach this dialog for the reorder level and the note.
            if (quantity != stored && !Permissions.canSetExactQuantity(member)) {
                StockWritePlan.Refused(NOT_ALLOWED_EXACT)
            } else {
                StockWrite.edit(
                    record = record,
                    storedQuantity = stored,
                    quantity = quantity,
                    reorderLevel = reorderLevel,
                    note = note,
                    author = author,
                    at = at,
                    movementId = movementId
                )
            }
        }
    }

    /** Track a catalogue product or a manual item for the first time. */
    suspend fun create(
        member: Member,
        entry: StockEntry,
        quantity: Double,
        reorderLevel: Double,
        note: String = ""
    ): StockWriteResult {
        require(Permissions.canAdjustStock(member)) { NOT_ALLOWED_ADJUST }
        return commit(member) { transaction, author, at, movementId ->
            StockWrite.create(
                entry = entry,
                quantity = quantity,
                reorderLevel = reorderLevel,
                note = note,
                author = author,
                at = at,
                movementId = movementId,
                alreadyExists = transaction.readStock(entry.documentId) != null
            )
        }
    }

    /** Pin or unpin. A merge write, and never a movement. */
    suspend fun togglePin(member: Member, record: StockRecord): StockWriteResult {
        require(Permissions.canPinStock(member)) { NOT_ALLOWED_PIN }
        return commit(member) { transaction, author, at, _ ->
            StockWrite.pin(
                record = record,
                storedQuantity = storedQuantity(transaction, record),
                pinned = !record.pinned,
                author = author,
                at = at
            )
        }
    }

    /** Stop tracking a row. Administrator only; the rules agree. */
    suspend fun stopTracking(
        member: Member,
        record: StockRecord,
        note: String = ""
    ): StockWriteResult {
        require(Permissions.canStopTrackingStock(member)) { NOT_ALLOWED_ARCHIVE }
        return commit(member) { transaction, author, at, movementId ->
            StockWrite.stopTracking(
                record = record,
                storedQuantity = storedQuantity(transaction, record),
                note = note,
                author = author,
                at = at,
                movementId = movementId
            )
        }
    }

    /**
     * Runs one plan in one transaction.
     *
     * `at` and the movement id are taken **here**, outside [StockStore.transaction],
     * which is the whole of contract points 1 and 2: the body may run several
     * times and will reuse both every time.
     */
    private suspend fun commit(
        member: Member,
        plan: (StockTransaction, StockAuthor, Long, String) -> StockWritePlan
    ): StockWriteResult {
        val author = StockAuthor(name = member.name.ifBlank { member.email }, uid = member.uid)
        val at = now()
        val movementId = newMovementId()
        return store.transaction { transaction ->
            when (val outcome = plan(transaction, author, at, movementId)) {
                is StockWritePlan.Refused -> throw IllegalStateException(outcome.message)
                StockWritePlan.NoChange -> StockWriteResult.NO_CHANGE
                is StockWritePlan.Write -> {
                    transaction.writeStock(outcome.stockDocId, outcome.stock, outcome.merge)
                    if (outcome.movement != null && outcome.movementDocId != null) {
                        transaction.writeMovement(outcome.movementDocId, outcome.movement)
                    }
                    StockWriteResult.WRITTEN
                }
            }
        }
    }

    /** The stored quantity, read inside the transaction. Contract point 3. */
    private fun storedQuantity(transaction: StockTransaction, record: StockRecord): Double {
        val stored = transaction.readStock(record.documentId) ?: return record.quantity
        // An imported PWA row can hold `q` as a string; the reader tolerates
        // that everywhere else, so it has to be tolerated here too.
        return when (val q = stored["q"] ?: stored["quantity"]) {
            is Number -> q.toDouble()
            is String -> q.trim().toDoubleOrNull() ?: record.quantity
            else -> record.quantity
        }
    }

    private companion object {
        const val NOT_ALLOWED_ADJUST = "Only an Owner, Administrator or Staff can change stock"
        const val NOT_ALLOWED_EDIT = "Only an Owner, Administrator or Staff can edit stock"
        const val NOT_ALLOWED_EXACT = "Only an Owner or Administrator can set an exact quantity"
        const val NOT_ALLOWED_PIN = "Only an Owner, Administrator or Staff can pin stock"
        const val NOT_ALLOWED_ARCHIVE = "Only an Owner or Administrator can stop tracking an item"
    }
}
