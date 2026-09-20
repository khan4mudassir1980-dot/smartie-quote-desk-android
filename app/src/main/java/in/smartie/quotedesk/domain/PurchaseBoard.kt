package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PurchaseRecord

/**
 * Which requirements the Purchase tab shows, and in what order.
 *
 * **Sorted here, never by Firestore.** `docs/N4-plan.md` records why: every
 * server-side narrowing is unsafe against V8C4 data. An `orderBy("t")` drops
 * rows with no `t` — `pr_critical_no_created` is exactly that shape — and a
 * requirement that silently vanishes from the shop floor is worse than the
 * read cost. So one unordered listener feeds this, and the ordering is
 * arithmetic on a list that is already in memory.
 *
 * Pure, so the ordering has a test that does not need Firebase or a screen.
 */
object PurchaseBoard {

    /**
     * Still waiting to be bought, newest first.
     *
     * [PurchaseRecord.isOpen] already excludes a soft-deleted row, which is
     * the whole point of a soft delete: the document survives so a PWA device
     * cannot resurrect it, and nobody sees it again.
     */
    fun active(records: List<PurchaseRecord>): List<PurchaseRecord> =
        records.filter { it.isOpen }.sortedWith(NEWEST_ADDED_FIRST)

    /**
     * Received or cancelled, newest first — and **never** a removed one.
     *
     * `isOpen` is `!deleted && !isClosed`, so "not open" is not the same as
     * "closed": a removed requirement that was never received is neither.
     * Filtering on `!deleted && isClosed` rather than on `!isOpen` is what
     * keeps a removal out of *both* lists, which is what removing it means.
     */
    fun closed(records: List<PurchaseRecord>): List<PurchaseRecord> =
        records.filter { !it.deleted && it.isClosed }.sortedWith(NEWEST_CLOSED_FIRST)

    /**
     * When a requirement was raised, for ordering only.
     *
     * The reader already falls back from `t` to `updated`, so this is zero
     * only for a row that carries neither. Such a row sorts last and is still
     * **shown**; dropping it is the one thing that must never happen.
     */
    fun addedAt(record: PurchaseRecord): Long = record.createdAt

    /** When it stopped being active: received if known, else last touched. */
    fun closedAt(record: PurchaseRecord): Long = when {
        record.receivedAt > 0L -> record.receivedAt
        record.updatedAt > 0L -> record.updatedAt
        else -> record.createdAt
    }

    /**
     * Ties break on the id, descending.
     *
     * Two requirements added in the same millisecond are possible — a PWA
     * import writes many with one timestamp — and a comparator that called
     * them equal would let the list reorder itself between recompositions,
     * which on a `LazyColumn` keyed by id is a visible jump under the thumb.
     */
    private val NEWEST_ADDED_FIRST: Comparator<PurchaseRecord> =
        compareByDescending<PurchaseRecord> { addedAt(it) }.thenByDescending { it.id }

    private val NEWEST_CLOSED_FIRST: Comparator<PurchaseRecord> =
        compareByDescending<PurchaseRecord> { closedAt(it) }.thenByDescending { it.id }
}
