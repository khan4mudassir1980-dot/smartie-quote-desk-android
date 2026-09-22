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
     * Still waiting to be bought: **mine first, then most urgent**.
     *
     * Two groups, and the viewer's own requirements are the first of them.
     * Somebody opening this tab is usually looking for the thing they raised
     * — to correct it, or to record what arrived against it — and on a list
     * six people are adding to, theirs was scrolling away.
     *
     * Inside each group, red then yellow then green, because that is the
     * order the shop floor works in: a requirement that stops a gate going up
     * today must not sit below one merely wanted this month. And inside a
     * colour the newest is first, so adding one still puts it where the
     * person who added it will look.
     *
     * [viewerUid] empty means nobody's rows come first, which is what a
     * caller that does not know who is looking should get.
     *
     * [PurchaseRecord.isOpen] already excludes a soft-deleted row, which is
     * the whole point of a soft delete: the document survives so a PWA device
     * cannot resurrect it, and nobody sees it again. A requirement that has
     * been **partly** received is still open, so it keeps its place in its own
     * colour rather than dropping to the bottom.
     */
    fun active(
        records: List<PurchaseRecord>,
        viewerUid: String = ""
    ): List<PurchaseRecord> =
        records.filter { it.isOpen }.sortedWith(mostUrgentFirst(viewerUid))

    /**
     * Whether [record] is the viewer's own.
     *
     * Both sides must be a real uid. A blank `byUid` is a row the PWA wrote
     * without recording an author, and `"" == ""` would make every one of
     * them everybody's — so a row whose author cannot be proved always sorts
     * as somebody else's.
     */
    fun isMine(record: PurchaseRecord, viewerUid: String): Boolean =
        viewerUid.isNotBlank() && record.byUid == viewerUid

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
     * Mine, then urgency, then newest, then the id.
     *
     * The rank comes from [UrgencyV2.rank] rather than the enum's declaration
     * order, so reordering the enum cannot silently reorder the board.
     *
     * Ties break on the id, descending. Two requirements added in the same
     * millisecond are possible — a PWA import writes many with one timestamp —
     * and a comparator that called them equal would let the list reorder
     * itself between recompositions, which on a `LazyColumn` keyed by id is a
     * visible jump under the thumb.
     *
     * Built per viewer rather than held as a constant, because the first key
     * depends on who is looking.
     */
    private fun mostUrgentFirst(viewerUid: String): Comparator<PurchaseRecord> =
        compareBy<PurchaseRecord> { if (isMine(it, viewerUid)) 0 else 1 }
            .thenBy { it.urgency.rank }
            .thenByDescending { addedAt(it) }
            .thenByDescending { it.id }

    /**
     * History is chronological, not urgent.
     *
     * Nothing in the closed list is waiting for anybody, so what matters is
     * when it stopped being active — not how badly it was once wanted.
     */
    private val NEWEST_CLOSED_FIRST: Comparator<PurchaseRecord> =
        compareByDescending<PurchaseRecord> { closedAt(it) }.thenByDescending { it.id }
}
