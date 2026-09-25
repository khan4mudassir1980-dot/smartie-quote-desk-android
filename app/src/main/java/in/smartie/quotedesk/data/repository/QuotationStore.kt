package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData

/**
 * The slice of Firestore that finalising a quotation uses.
 *
 * **It spans three documents, so its reads are named rather than generic.**
 * `PartyStore` reads one collection by id; finalise reads this draft's
 * quotation, the counter and the Owner's discount limit, and writes the first
 * two. V8C4's transaction reads only the first two — the discount limit is
 * this app's addition, for the cap V8C4 does not have. **No customer is read
 * here**: the party link is derived from the list the screen already holds,
 * as V8C4 derives it from `state.customers`. Naming each read keeps the fake
 * in the tests simple and the rules-relevant shape of every write visible at
 * its call site.
 *
 * **No Firebase type appears here.** The real implementation is
 * [FirestoreQuotationStore], in its own file, so this interface and
 * `QuotationWriteRepository` compile without the Android toolchain — which is
 * what lets their tests run in the local JVM sweep as well as on CI.
 */
interface QuotationStore {
    /**
     * Runs [body] inside one Firestore transaction and returns its result.
     *
     * Firestore may run [body] more than once when a document it read changes
     * underneath it, and discards every write an abandoned run recorded. So
     * [body] decides everything from the reads it makes, and nothing that must
     * stay the same across runs — the clock, an id — is generated inside it.
     */
    suspend fun <T> transaction(body: (QuotationTransaction) -> T): T

    /**
     * Whether [error] is the security rules refusing — `PERMISSION_DENIED`.
     *
     * Asked of the store because the store is the only layer that knows what
     * a Firestore exception is; `QuotationWriteRepository` stays free of
     * Firebase types, which keeps it in the local JVM sweep.
     */
    fun isRefusal(error: Throwable): Boolean
}

/**
 * The reads and writes available inside one finalise.
 *
 * Every read comes before every write, as a Firestore transaction requires.
 * **No delete, and no write to `/teamSettings/quoting`**, which finalise
 * reads and never changes.
 */
interface QuotationTransaction {
    /** `/quotations/{id}` — this draft's own quotation, if it was already issued. */
    fun readQuotation(id: String): DocData?

    /** `/teamSettings/numbering`, the one counter every quotation queues behind. */
    fun readNumbering(): DocData?

    /** `/teamSettings/quoting`, the Owner's discount limit for a Manager. */
    fun readQuoting(): DocData?

    /** A whole new document at `/quotations/{id}` — a set, never a merge. */
    fun writeQuotation(id: String, data: Map<String, Any?>)

    /**
     * Named fields of `/teamSettings/numbering`, **each replaced whole** — an
     * update, not a merge — so `lastIssued` carries exactly the keys this app
     * wrote rather than a blend with whatever the PWA left there.
     */
    fun writeNumbering(fields: Map<String, Any?>)
}
