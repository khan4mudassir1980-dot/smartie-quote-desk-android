package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.RoleTitles
import `in`.smartie.quotedesk.domain.StockAuthor
import `in`.smartie.quotedesk.domain.StockEntry
import `in`.smartie.quotedesk.domain.StockPhotoImage
import `in`.smartie.quotedesk.domain.StockPhotoPlan
import `in`.smartie.quotedesk.domain.StockRemoval
import `in`.smartie.quotedesk.domain.StockRemovalPlan
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

    // --- removal ------------------------------------------------------------

    /**
     * **Permanently** remove an item from stock.
     *
     * Replaces the old "stop tracking", which wrote `off: true` and left the
     * document in place — invisible to the board, still refusing a re-add
     * with "already exists", and with no route back. See [StockRemoval].
     *
     * One transaction does all of it: the history document is written, the
     * photo is deleted when there is one, and the stock row is deleted. A
     * failure anywhere leaves the item and its photo exactly as they were —
     * there is no half-removed state, and no history entry without a removal.
     *
     * **No movement is written.** Nothing moved; the quantity was not
     * adjusted, it ceased to exist along with the row.
     */
    suspend fun removeFromStock(member: Member, record: StockRecord): StockWriteResult {
        require(Permissions.canStopTrackingStock(member)) { StockRemoval.NOT_ALLOWED }
        return commitRemoval(member, record) { stored, author, at, eventId ->
            StockRemoval.remove(
                record = record,
                stillThere = stored != null,
                storedQuantity = stored?.quantity ?: record.quantity,
                hasStoredPhoto = stored?.hasPhoto ?: record.hasPhoto,
                author = author,
                at = at,
                eventId = eventId
            )
        }
    }

    /**
     * Convert a row left behind by the old stop-tracking behaviour.
     *
     * Idempotent by construction: the event id is derived from the stock
     * document id, and the history document is written only when it is not
     * already there, so a retry after a partial failure finishes the job
     * rather than doubling the record. A row that is **not** marked with the
     * legacy flag is never touched.
     */
    suspend fun convertLegacyStopped(member: Member, record: StockRecord): StockWriteResult {
        require(Permissions.canStopTrackingStock(member)) { StockRemoval.NOT_ALLOWED }
        if (!record.archived) return StockWriteResult.NO_CHANGE
        val eventId = StockRemoval.legacyEventId(record.documentId)
        return commitRemoval(member, record, eventId) { stored, author, at, id ->
            StockRemoval.remove(
                record = record,
                stillThere = stored != null,
                storedQuantity = stored?.quantity ?: record.quantity,
                hasStoredPhoto = stored?.hasPhoto ?: record.hasPhoto,
                author = author,
                at = at,
                eventId = id
            )
        }
    }

    private suspend fun commitRemoval(
        member: Member,
        record: StockRecord,
        eventId: String = StockRemoval.EVENT_PREFIX + Keys.generateId(""),
        plan: (StoredStock?, StockAuthor, Long, String) -> StockRemovalPlan
    ): StockWriteResult {
        val author = StockAuthor(name = member.name.ifBlank { member.email }, uid = member.uid)
        val at = now()
        return store.transaction { transaction ->
            val raw = transaction.readStock(record.documentId)
            val stored = raw?.let { storedOf(it, record) }
            when (val outcome = plan(stored, author, at, eventId)) {
                StockRemovalPlan.NoChange -> StockWriteResult.NO_CHANGE
                is StockRemovalPlan.Remove -> {
                    // Written only when it is not already there, so a retried
                    // conversion completes rather than duplicating. A fresh
                    // removal's id is random, so this is always false for one.
                    if (!transaction.stoppedExists(outcome.eventId)) {
                        transaction.writeStopped(outcome.eventId, outcome.event)
                    }
                    outcome.photoDocId?.let { transaction.deletePhoto(it) }
                    transaction.deleteStock(outcome.stockDocId)
                    StockWriteResult.WRITTEN
                }
            }
        }
    }

    // --- photos ------------------------------------------------------------

    /**
     * Attach or replace a photo. Owner, Administrator and Staff; the rules
     * agree.
     *
     * Both documents go in **one** transaction, so the pair can never be seen
     * or left disagreeing, and the rules refuse a commit in which they do. The
     * revision read inside the transaction is what the new one is built from,
     * so two devices replacing at once resolve on a monotonic counter rather
     * than on whose clock is right: whichever commits second re-runs against
     * the first's data and lands one higher.
     */
    suspend fun setPhoto(
        member: Member,
        record: StockRecord,
        image: StockPhotoImage
    ): StockWriteResult {
        require(Permissions.canManageStockPhoto(member)) { NOT_ALLOWED_PHOTO }
        return commitPhoto(member, record) { stored, author, at ->
            StockWrite.setPhoto(
                record = record,
                storedQuantity = stored.quantity,
                storedReorderLevel = stored.reorderLevel,
                storedPhotoRev = stored.photoRev,
                image = image,
                author = author,
                at = at
            )
        }
    }

    /** Take a photo off a row. The revision still advances. */
    suspend fun removePhoto(member: Member, record: StockRecord): StockWriteResult {
        require(Permissions.canManageStockPhoto(member)) { NOT_ALLOWED_PHOTO }
        return commitPhoto(member, record) { stored, author, at ->
            StockWrite.removePhoto(
                record = record,
                storedQuantity = stored.quantity,
                storedReorderLevel = stored.reorderLevel,
                storedPhotoRev = stored.photoRev,
                hasStoredPhoto = stored.hasPhoto,
                author = author,
                at = at
            )
        }
    }

    /**
     * What the transaction found on the stock row.
     *
     * Read **once** per attempt: `transaction.get()` on the same document
     * twice is two reads, and this runs against a shared daily quota.
     */
    private data class StoredStock(
        val quantity: Double,
        val reorderLevel: Double,
        val photoRev: Double,
        val hasPhoto: Boolean
    )

    private suspend fun commitPhoto(
        member: Member,
        record: StockRecord,
        plan: (StoredStock, StockAuthor, Long) -> StockPhotoPlan
    ): StockWriteResult {
        val author = StockAuthor(name = member.name.ifBlank { member.email }, uid = member.uid)
        val at = now()
        return store.transaction { transaction ->
            val stored = readStored(transaction, record)
            when (val outcome = plan(stored, author, at)) {
                is StockPhotoPlan.Refused -> throw IllegalStateException(outcome.message)
                StockPhotoPlan.NoChange -> StockWriteResult.NO_CHANGE
                is StockPhotoPlan.Write -> {
                    // The photo first, then the row that points at it. Order
                    // is immaterial inside a transaction; both land together.
                    if (outcome.photo == null) transaction.deletePhoto(outcome.photoDocId)
                    else transaction.writePhoto(outcome.photoDocId, outcome.photo)
                    transaction.writeStock(outcome.stockDocId, outcome.stock, merge = true)
                    StockWriteResult.WRITTEN
                }
            }
        }
    }

    /** The stored fields as this repository reads them. One read, reused. */
    private fun storedOf(stored: Map<String, Any?>, record: StockRecord): StoredStock = StoredStock(
        quantity = number(stored["q"] ?: stored["quantity"], record.quantity),
        reorderLevel = number(stored["min"] ?: stored["reorderLevel"], record.reorderLevel),
        photoRev = number(stored["photoRev"], record.photoRev),
        hasPhoto = when (val flag = stored["hasPhoto"]) {
            is Boolean -> flag
            is Number -> flag.toDouble() != 0.0
            else -> record.hasPhoto
        }
    )

    /** One read, and the record as the fallback when the row is not there yet. */
    private fun readStored(transaction: StockTransaction, record: StockRecord): StoredStock {
        val stored = transaction.readStock(record.documentId)
            ?: return StoredStock(
                quantity = record.quantity,
                reorderLevel = record.reorderLevel,
                photoRev = record.photoRev,
                hasPhoto = record.hasPhoto
            )
        return storedOf(stored, record)
    }

    /** An imported row can hold a number as a string; tolerate it here too. */
    private fun number(value: Any?, fallback: Double): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull() ?: fallback
        else -> fallback
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
        /**
         * The titles come from [RoleTitles], built from the roles the
         * permission actually allows, so a message cannot drift from the rule
         * it describes. `Role.STAFF` reads **Manager**; the stored value is
         * untouched.
         */
        private val STOCK_WRITERS = RoleTitles.anyOf(Role.OWNER, Role.ADMIN, Role.STAFF)
        private val ADMINS = RoleTitles.anyOf(Role.OWNER, Role.ADMIN)

        val NOT_ALLOWED_ADJUST = "Only $STOCK_WRITERS can change stock"
        val NOT_ALLOWED_EDIT = "Only $STOCK_WRITERS can edit stock"
        val NOT_ALLOWED_EXACT = "Only $ADMINS can set an exact quantity"
        val NOT_ALLOWED_PIN = "Only $STOCK_WRITERS can pin stock"
        val NOT_ALLOWED_ARCHIVE = "Only $ADMINS can stop tracking an item"
        val NOT_ALLOWED_PHOTO = "Only $STOCK_WRITERS can change a photo"
    }
}
