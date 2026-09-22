package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PurchaseRecord

/**
 * What each person may look back at.
 *
 * Two lists: requirements that were **received** — the ordinary history — and
 * requirements somebody **removed**, which sit in their own collapsed section
 * because they are a different kind of fact. A removal is usually a mistake
 * being tidied away, and putting those among the deliveries would make the
 * deliveries harder to read.
 *
 * ## Who sees whose
 *
 * Owner, Administrator and Manager see everyone's, of both kinds. **Staff see
 * only what they raised themselves** — and "theirs" is a uid on both sides,
 * so a row the PWA wrote without recording an author belongs to nobody and is
 * never in a Staff account's history.
 *
 * ## This is an app-level filter, and the plan says so
 *
 * It is **not** enforced by the Firestore rules, deliberately. Firestore
 * evaluates a list query against its *constraints* rather than document by
 * document, so a read rule mentioning `resource.data` refuses an
 * unconstrained listener outright — and the filtered query that would replace
 * it, `where('del','==',false)`, silently drops every legacy row carrying
 * `del: 1` or no `del` at all. It would refuse the PWA's own listener in the
 * same project too. `docs/N4.3-plan.md` records the rules-level version as
 * deferred, with its two conditions: after the PWA is retired, and together
 * with a one-time `del` normalisation.
 *
 * So this hides rows from a screen. It does not stop a determined client
 * reading them, and nothing here should be described as if it did.
 *
 * Pure, so the whole matrix has a test that needs neither Firebase nor a
 * screen.
 */
object PurchaseHistory {

    /**
     * Received and closed, newest first.
     *
     * The same order `PurchaseBoard.closed` uses, and for the same reason:
     * nothing here is waiting for anybody, so what matters is when it stopped
     * being active rather than how badly it was once wanted.
     */
    fun received(records: List<PurchaseRecord>, viewer: Member): List<PurchaseRecord> =
        PurchaseBoard.closed(records.filter { visibleTo(it, viewer) })

    /**
     * Removed, newest first.
     *
     * `deleted` is the reader's coercion of `del`, which is why a PWA row
     * holding the number `1` and a native row holding `true` both land here,
     * and `0`, `false` and an absent field all stay out of it.
     */
    fun removed(records: List<PurchaseRecord>, viewer: Member): List<PurchaseRecord> =
        records
            .filter { it.deleted && visibleTo(it, viewer) }
            .sortedWith(NEWEST_REMOVED_FIRST)

    /**
     * Whether [viewer] may see [record] in their history at all.
     *
     * Everybody above the limited role sees everyone's. The limited role sees
     * what it raised, which needs a real uid on both sides — `"" == ""` would
     * hand every authorless PWA row to whoever happened to be signed in.
     */
    fun visibleTo(record: PurchaseRecord, viewer: Member): Boolean = when {
        !viewer.active -> false
        Permissions.canEditPurchase(viewer) -> true
        else -> PurchaseAccess.isCreator(viewer, record)
    }

    /**
     * When a requirement was removed, for ordering only.
     *
     * `delAt` is what the removal itself recorded, and a legacy row has none
     * — so this falls back to when the document was last touched, and then to
     * when it was raised. A row carrying nothing at all sorts last and is
     * still **shown**; dropping it is the one thing that must never happen.
     */
    fun removedAt(record: PurchaseRecord): Long = when {
        record.removedAt > 0L -> record.removedAt
        record.updatedAt > 0L -> record.updatedAt
        else -> record.createdAt
    }

    private val NEWEST_REMOVED_FIRST: Comparator<PurchaseRecord> =
        compareByDescending<PurchaseRecord> { removedAt(it) }.thenByDescending { it.id }
}
