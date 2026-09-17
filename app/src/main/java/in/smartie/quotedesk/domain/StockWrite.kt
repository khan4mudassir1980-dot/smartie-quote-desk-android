package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.StockRecord

/**
 * Stand-in for `FieldValue.serverTimestamp()`.
 *
 * The planner below is ordinary Kotlin with no Firebase in it, so the
 * repository swaps this marker for the real sentinel on its way out. Tests
 * assert the marker is present, which is stronger than asserting a number.
 */
object ServerTimestamp

/** Who a write is attributed to; the rules demand `byUid == request.auth.uid`. */
data class StockAuthor(val name: String, val uid: String)

/** What a write comes to, decided before anything reaches Firestore. */
sealed interface StockWritePlan {
    /**
     * The documents to write. [movement] is null when nothing moved — a
     * note-only edit changes shared text, not a count, so it earns no row in
     * the audit trail.
     */
    data class Write(
        val stockDocId: String,
        val stock: Map<String, Any?>,
        val movementDocId: String? = null,
        val movement: Map<String, Any?>? = null,
        val merge: Boolean = true
    ) : StockWritePlan

    /** Nothing changed. Write nothing at all, and say nothing happened. */
    data object NoChange : StockWritePlan

    /** The write must not be attempted; [message] is for the person. */
    data class Refused(val message: String) : StockWritePlan
}

/**
 * What each stock write puts on the wire.
 *
 * Every field here comes from the approved V8C4 source, inspected on
 * 2026-09-17, with exactly one addition: `name`, the descriptive catalogue
 * name, so a Worker — who may read `/stock` but never `/products` — sees more
 * than a model code. The PWA ignores fields it does not know. **No price or
 * tax field may ever be written to `/stock`**, and the v9 rules refuse one.
 *
 * Pure on purpose: the stored quantity arrives as a parameter, read inside the
 * caller's transaction, so every branch below is unit-tested without Firebase.
 */
object StockWrite {

    const val BELOW_ZERO_PREFIX = "Only"
    const val ALREADY_EXISTS = "This item is already in stock"

    /** Actions that reach `/stockMoves`. `note` deliberately is not one. */
    const val ACTION_IN = "in"
    const val ACTION_OUT = "out"
    const val ACTION_SET = "set"
    const val ACTION_MIN = "min"
    const val ACTION_ADD = "add"

    /**
     * Written to `lastAction` on the stock document for a note-only edit.
     *
     * A native additive value, alongside `name`. It is never a `/stockMoves`
     * action: nothing moved, so nothing is logged — and calling it `min`
     * would claim a minimum changed when none did.
     */
    const val LAST_ACTION_NOTE = "note"

    fun belowZero(available: Double): String =
        "$BELOW_ZERO_PREFIX ${trim(available)} left — somebody else took some while you were counting"

    /**
     * Plus or minus, committed against [storedQuantity] as read inside the
     * transaction. Never against what the screen was showing.
     */
    fun adjust(
        record: StockRecord,
        storedQuantity: Double,
        delta: Double,
        note: String,
        author: StockAuthor,
        at: Long,
        movementId: String
    ): StockWritePlan {
        if (delta == 0.0) return StockWritePlan.NoChange
        val next = storedQuantity + delta
        if (next < 0.0) return StockWritePlan.Refused(belowZero(storedQuantity))
        val action = if (delta > 0.0) ACTION_IN else ACTION_OUT
        val effectiveNote = note.trim().ifBlank { Permissions.DEFAULT_STOCK_NOTE }
        return StockWritePlan.Write(
            stockDocId = record.documentId,
            stock = stockFields(
                record = record,
                quantity = next,
                reorderLevel = record.reorderLevel,
                lastAction = action,
                note = note.trim(),
                author = author,
                at = at
            ),
            movementDocId = movementId,
            movement = movementFields(
                id = movementId,
                record = record,
                action = action,
                previous = storedQuantity,
                next = next,
                reorderLevel = record.reorderLevel,
                note = effectiveNote,
                author = author,
                at = at
            )
        )
    }

    /**
     * The Edit dialog. Three outcomes, and the third is the point of it:
     *
     * - the quantity moved → `set`, with a movement;
     * - only the reorder level moved → `min`, with a movement whose
     *   `prev == next` and `delta == 0`;
     * - neither moved but a note was typed → the note and the audit metadata
     *   are saved and **no movement is written**;
     * - nothing moved and no note → nothing at all is written.
     *
     * A blank note never clears a stored one; clearing is not an edit this
     * dialog offers.
     */
    fun edit(
        record: StockRecord,
        storedQuantity: Double,
        quantity: Double,
        reorderLevel: Double,
        note: String,
        author: StockAuthor,
        at: Long,
        movementId: String
    ): StockWritePlan {
        if (quantity < 0.0) return StockWritePlan.Refused(StockEntry.NEGATIVE_QUANTITY)
        if (reorderLevel < 0.0) return StockWritePlan.Refused(StockEntry.NEGATIVE_REORDER)

        val trimmedNote = note.trim()
        val quantityMoved = quantity != storedQuantity
        val reorderMoved = reorderLevel != record.reorderLevel
        val noteChanged = trimmedNote.isNotEmpty() && trimmedNote != record.note

        if (!quantityMoved && !reorderMoved && !noteChanged) return StockWritePlan.NoChange

        val action = when {
            quantityMoved -> ACTION_SET
            reorderMoved -> ACTION_MIN
            else -> LAST_ACTION_NOTE
        }

        val stock = stockFields(
            record = record,
            quantity = quantity,
            reorderLevel = reorderLevel,
            lastAction = action,
            note = trimmedNote,
            author = author,
            at = at
        )

        // Nothing moved: the note and the audit metadata are the whole write.
        if (action == LAST_ACTION_NOTE) {
            return StockWritePlan.Write(stockDocId = record.documentId, stock = stock)
        }

        return StockWritePlan.Write(
            stockDocId = record.documentId,
            stock = stock,
            movementDocId = movementId,
            movement = movementFields(
                id = movementId,
                record = record,
                action = action,
                previous = storedQuantity,
                next = quantity,
                reorderLevel = reorderLevel,
                note = trimmedNote.ifBlank { Permissions.DEFAULT_STOCK_NOTE },
                author = author,
                at = at
            )
        )
    }

    /** Tracking something for the first time. The full V8C4 shape, plus `name`. */
    fun create(
        entry: StockEntry,
        quantity: Double,
        reorderLevel: Double,
        note: String,
        author: StockAuthor,
        at: Long,
        movementId: String,
        alreadyExists: Boolean
    ): StockWritePlan {
        entry.refusal(quantity, reorderLevel)?.let { return StockWritePlan.Refused(it) }
        if (alreadyExists) return StockWritePlan.Refused(ALREADY_EXISTS)
        val trimmedNote = note.trim()
        return StockWritePlan.Write(
            stockDocId = entry.documentId,
            // A new document, written whole rather than merged, so it carries
            // the V8C4 shape exactly and nothing is left undefined.
            merge = false,
            stock = mapOf(
                "key" to entry.key,
                "group" to entry.group,
                "model" to entry.model,
                "name" to entry.name,
                "q" to quantity,
                "min" to reorderLevel,
                "off" to false,
                "t" to at,
                "lastAction" to ACTION_ADD,
                "pinned" to false,
                "pinOrder" to 0.0,
                "manual" to entry.manual,
                "manualName" to entry.manualName,
                "manualModel" to entry.manualModel,
                "categoryId" to entry.categoryId,
                "unit" to entry.unit,
                "linkedKey" to "",
                "stockNote" to trimmedNote,
                "by" to author.name,
                "byUid" to author.uid,
                "serverAt" to ServerTimestamp
            ),
            movementDocId = movementId,
            movement = mapOf(
                "id" to movementId,
                "key" to entry.key,
                "group" to entry.group,
                "model" to entry.model,
                "name" to entry.name,
                "action" to ACTION_ADD,
                "prev" to 0.0,
                "delta" to quantity,
                "next" to quantity,
                "min" to reorderLevel,
                "note" to trimmedNote.ifBlank { "Added to stock" },
                "by" to author.name,
                "byUid" to author.uid,
                "at" to at,
                "serverAt" to ServerTimestamp
            )
        )
    }

    /**
     * The fields every merge write puts on `/stock`.
     *
     * `q` and `min` are always written as numbers, even when their value has
     * not changed: an imported PWA row can store them as strings, and the v9
     * rules require `q is number`, so re-asserting them is what keeps a
     * legacy row writable at all.
     *
     * Identity fields are written only when this record actually carries
     * them, so a merge can backfill a blank `name` but never blank a good one.
     */
    private fun stockFields(
        record: StockRecord,
        quantity: Double,
        reorderLevel: Double,
        lastAction: String,
        note: String,
        author: StockAuthor,
        at: Long
    ): Map<String, Any?> = buildMap {
        put("key", record.key)
        put("q", quantity)
        put("min", reorderLevel)
        put("t", at)
        put("lastAction", lastAction)
        put("by", author.name)
        put("byUid", author.uid)
        put("serverAt", ServerTimestamp)
        if (note.isNotEmpty()) put("stockNote", note)
        if (record.group.isNotBlank()) put("group", record.group)
        if (record.model.isNotBlank()) put("model", record.model)
        if (record.name.isNotBlank()) put("name", record.name)
    }

    private fun movementFields(
        id: String,
        record: StockRecord,
        action: String,
        previous: Double,
        next: Double,
        reorderLevel: Double,
        note: String,
        author: StockAuthor,
        at: Long
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "key" to record.key,
        "group" to record.group,
        "model" to record.model,
        "name" to record.name,
        "action" to action,
        "prev" to previous,
        // Always signed, and always next - prev. There is no `qty` field.
        "delta" to next - previous,
        "next" to next,
        "min" to reorderLevel,
        "note" to note,
        "by" to author.name,
        "byUid" to author.uid,
        "at" to at,
        "serverAt" to ServerTimestamp
    )

    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
