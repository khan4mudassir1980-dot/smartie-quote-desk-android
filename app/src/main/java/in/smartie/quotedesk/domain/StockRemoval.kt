package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.StockRecord

/**
 * Removing an item from stock, decided off device.
 *
 * ## Why this replaced "Stop tracking"
 *
 * Stopping tracking wrote `off: true` and left the document where it was.
 * The board filters `off` rows out, so the item vanished — but it was still
 * there, and three things followed from that:
 *
 * - Add stock could still select the catalogue product;
 * - the form offered zero defaults, knowing nothing of the hidden row;
 * - saving refused with "already exists", because it did exist.
 *
 * There was no route back in the app. A hidden document that blocks its own
 * replacement is worse than no document, so removal is now **permanent**: the
 * stock row and its photo are deleted, and a small immutable record is
 * written to `/stoppedStock` so the removal is not silent.
 *
 * What is deleted is deleted. Quantity, reorder level, note, pin state and
 * the photo do not survive, and nothing in the app restores them — that is
 * the Owner's decision, not an oversight. The catalogue product is untouched,
 * so the same item may be added again as a **fresh** entry, starting empty.
 */
object StockRemoval {

    /** The prefix on a removal event id, as `mv_` is on a movement. */
    const val EVENT_PREFIX: String = "sr_"

    /** A legacy `off: true` row converts under a deterministic id. */
    const val LEGACY_PREFIX: String = "sl_"

    const val NOT_ALLOWED: String = "Only an Owner or Administrator can remove an item from stock"

    const val NOT_ALLOWED_CLEAR: String =
        "Only an Owner or Administrator can clear stopped-item history"

    /**
     * The id a legacy conversion writes under.
     *
     * Deterministic on the stock document id — which already has `/`
     * replaced, so it is a legal document id on its own — so a retry lands on the
     * document the first attempt wrote and cannot double up. A fresh removal
     * uses a random id instead, because two genuine add-then-remove cycles
     * for one key are two events and must stay distinct.
     */
    fun legacyEventId(stockDocId: String): String = LEGACY_PREFIX + stockDocId

    /**
     * What a removal comes to: one history document created, the stock row
     * and its photo deleted, all in one commit.
     *
     * Returns [StockRemovalPlan.NoChange] when there is nothing to remove, so
     * a double tap cannot write a second history entry for the same item.
     */
    fun remove(
        record: StockRecord,
        stillThere: Boolean,
        storedQuantity: Double,
        hasStoredPhoto: Boolean,
        author: StockAuthor,
        at: Long,
        eventId: String
    ): StockRemovalPlan {
        if (!stillThere) return StockRemovalPlan.NoChange
        return StockRemovalPlan.Remove(
            stockDocId = record.documentId,
            photoDocId = StockPhoto.documentId(record.key).takeIf { hasStoredPhoto },
            eventId = eventId,
            event = eventFields(
                id = eventId,
                record = record,
                quantity = storedQuantity,
                author = author,
                at = at
            )
        )
    }

    /**
     * The snapshot that outlives the item.
     *
     * Deliberately small. The name, the model and whether it was a manual
     * item are what somebody needs to recognise the row; the last quantity is
     * what they are likely to ask about. `at` and `byUid` are here because
     * ordering needs the first and the rules need the second — **neither is
     * ever rendered.** No note, no photo, no price, no reorder level, no pin.
     */
    fun eventFields(
        id: String,
        record: StockRecord,
        quantity: Double,
        author: StockAuthor,
        at: Long
    ): Map<String, Any?> = buildMap {
        put("id", id)
        put("key", record.key)
        // The rules read this back to prove the row is gone after the commit.
        put("stockDoc", record.documentId)
        put("q", quantity)
        put("manual", record.manual)
        put("at", at)
        put("byUid", author.uid)
        put("serverAt", ServerTimestamp)
        if (record.name.isNotBlank()) put("name", record.name)
        if (record.model.isNotBlank()) put("model", record.model)
        if (record.unit.isNotBlank()) put("unit", record.unit)
    }

    /**
     * Fields a history document must never carry.
     *
     * **The app-layer protection is [eventFields] itself, not this list.**
     * That function is an allowlist — it `put`s the permitted keys and
     * nothing else — so a forbidden field cannot be written by construction
     * and there is no runtime check to perform. This is the *mirror* of the
     * rules' denial list, read only by `StockRemovalTest`, which asserts that
     * what `eventFields` builds carries none of them.
     *
     * So "asserted in both layers" means the Kotlin test and the emulator
     * rules test. It does **not** mean the app reads this at runtime; nothing
     * in `app/src/main` does, and nothing needs to.
     */
    val FORBIDDEN_FIELDS: List<String> = listOf(
        "note", "stockNote", "bytes", "hasPhoto", "photoRev", "min", "pinned",
        "dealer", "contractor", "client", "gst"
    )
}

/** What removing an item comes to, before anything reaches Firestore. */
sealed interface StockRemovalPlan {

    /**
     * One commit: the history document written, the stock row deleted, and
     * the photo deleted when there was one.
     *
     * [photoDocId] is null when the row carried no photo — there is nothing
     * to delete, and asking Firestore to delete a document that is not there
     * would spend a write for no reason.
     */
    data class Remove(
        val stockDocId: String,
        val photoDocId: String?,
        val eventId: String,
        val event: Map<String, Any?>
    ) : StockRemovalPlan

    /** The row was already gone. Nothing to write, and no second history entry. */
    data object NoChange : StockRemovalPlan
}
