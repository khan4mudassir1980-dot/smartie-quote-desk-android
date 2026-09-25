package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData

/**
 * The slice of Firestore that finalising a quotation uses.
 *
 * **It spans four documents, so its reads are named rather than generic.**
 * `PartyStore` reads one collection by id; finalise reads this draft's
 * quotation, the counter, the Owner's discount limit and the saved customer,
 * and writes two of them. Naming each keeps the fake in the tests simple and
 * keeps the rules-relevant shape of every write visible at its call site.
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
}

/**
 * The reads and writes available inside one finalise.
 *
 * Every read comes before every write, as a Firestore transaction requires.
 * **No delete, and no write to `/customers` or `/teamSettings/quoting`:**
 * finalise reads those and changes neither.
 */
interface QuotationTransaction {
    /** `/quotations/{id}` — this draft's own quotation, if it was already issued. */
    fun readQuotation(id: String): DocData?

    /** `/teamSettings/numbering`, the one counter every quotation queues behind. */
    fun readNumbering(): DocData?

    /** `/teamSettings/quoting`, the Owner's discount limit for a Manager. */
    fun readQuoting(): DocData?

    /** `/customers/{id}`, the saved customer the draft names. */
    fun readCustomer(id: String): DocData?

    /** A whole new document at `/quotations/{id}` — a set, never a merge. */
    fun writeQuotation(id: String, data: Map<String, Any?>)

    /**
     * Named fields of `/teamSettings/numbering`, **each replaced whole** — an
     * update, not a merge — so `lastIssued` carries exactly the keys this app
     * wrote rather than a blend with whatever the PWA left there.
     */
    fun writeNumbering(fields: Map<String, Any?>)
}
