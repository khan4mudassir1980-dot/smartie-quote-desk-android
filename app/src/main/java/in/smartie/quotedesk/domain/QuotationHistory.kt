package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.QuotationRecord

/**
 * Which quotations each person may look back at.
 *
 * **Owner and Administrator see every quotation; a Manager sees only the ones
 * they issued.** A quotation carries a price somebody negotiated, so it is not
 * the same kind of fact as a purchase requirement — a Manager who can read
 * every quotation in the business can read every discount anybody has ever
 * given.
 *
 * "Theirs" is a **uid on both sides**. A quotation the PWA wrote without
 * recording an author has a blank `byUid`, and a blank is not a match for
 * anybody: `"" == ""` would hand every authorless row to whoever happened to
 * be signed in. Such a row belongs to nobody and appears only for an Owner or
 * an Administrator.
 *
 * ## This is an app-level filter, and it says so
 *
 * It is **not** enforced by the Firestore rules, for the same reason
 * `PurchaseHistory` is not: Firestore evaluates a list query against its
 * *constraints* rather than document by document, so a read rule mentioning
 * `resource.data` refuses an unconstrained listener outright — and a filtered
 * `where('byUid','==',mine())` query would refuse the PWA's own listener in
 * the same project, as well as dropping every legacy row with no author.
 *
 * So this hides quotations from a screen. It does not stop a determined client
 * reading them, and nothing in this repository may describe it as if it did.
 * The rules-level version is deferred until the PWA is retired, and is
 * recorded as deferred in `docs/PROJECT-STATUS.md`.
 *
 * Pure, so the whole matrix is a unit test that needs neither Firebase nor a
 * screen.
 */
object QuotationHistory {

    /** Every quotation [viewer] may see, newest first. */
    fun visibleTo(records: List<QuotationRecord>, viewer: Member): List<QuotationRecord> =
        records.filter { visibleTo(it, viewer) }.sortedWith(NEWEST_FIRST)

    /** Whether [viewer] may see [record] at all. */
    fun visibleTo(record: QuotationRecord, viewer: Member): Boolean = when {
        !viewer.active -> false
        // A Staff account has no Quotation tab and no history; the rules
        // refuse the read as well.
        !Permissions.canQuote(viewer) -> false
        Permissions.isAdmin(viewer) -> true
        else -> isCreator(viewer, record)
    }

    /**
     * Whether [viewer] issued [record].
     *
     * Both uids must be real. A quotation with no author belongs to nobody,
     * which is the only safe reading of a blank.
     */
    fun isCreator(viewer: Member, record: QuotationRecord): Boolean =
        viewer.uid.isNotBlank() && record.byUid.isNotBlank() && viewer.uid == record.byUid

    /**
     * When a quotation was issued, for ordering only.
     *
     * `at` is what the issue itself recorded. A row carrying nothing sorts
     * last and is still **shown**; dropping it is the one thing that must
     * never happen, because a quotation with a bad timestamp is still a
     * quotation somebody sent a customer.
     */
    fun issuedAt(record: QuotationRecord): Long = record.at

    private val NEWEST_FIRST: Comparator<QuotationRecord> =
        compareByDescending<QuotationRecord> { issuedAt(it) }.thenByDescending { it.id }
}
