package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData

internal const val NUMBERING = "teamSettings/numbering"

/**
 * A `QuotationStore` that behaves as Firestore does where finalise depends on
 * it: reads see committed documents, a replayed body's writes are discarded,
 * and a commit can land while its acknowledgement is lost. Documents by path,
 * e.g. `quotations/qd_1` or [NUMBERING].
 *
 * Shared since N5.9b: the repository's tests and the finalise gate's both run
 * the real `QuotationWriteRepository` over it. Pure, so it runs in the local
 * JVM sweep as well as on CI.
 */
internal class FakeQuotationStore(
    val docs: MutableMap<String, Map<String, Any?>> = mutableMapOf(),
    /** How many times Firestore runs the body before committing. */
    private val attempts: Int = 1,
    /** Called before each run, with its index — to change a document between runs. */
    private val beforeRun: (Int) -> Unit = {}
) : QuotationStore {
    var transactions = 0
    var bodyRuns = 0
    val reads = mutableListOf<String>()

    /** Commit the next transaction, then throw as though its response was lost. */
    var loseNextResponse = false

    /**
     * Refuse this many transactions outright, committing nothing — what
     * the rules do to a transaction built on a stale counter read.
     */
    var refuseNext = 0

    /** Called before a refused transaction throws — to move the counter on, say. */
    var onRefuse: () -> Unit = {}

    /**
     * A rule this fake models, checked against what a transaction would
     * commit. Returning true refuses the commit as the rules would. Off by
     * default: the fake is not the rules, and a test that relies on one says
     * which rule it is modelling.
     */
    var refuseCommitWhen: (staged: Map<String, Map<String, Any?>>) -> Boolean = { false }

    override fun isRefusal(error: Throwable): Boolean = error is Refusal

    override suspend fun <T> transaction(body: (QuotationTransaction) -> T): T {
        transactions++
        if (refuseNext > 0) {
            refuseNext--
            onRefuse()
            throw Refusal()
        }
        var result: T? = null
        val staged = mutableListOf<Pair<String, Map<String, Any?>>>()
        repeat(attempts) { run ->
            beforeRun(run)
            bodyRuns++
            // Firestore discards what an abandoned run recorded.
            staged.clear()
            result = body(object : QuotationTransaction {
                override fun readQuotation(id: String) = read("quotations/$id")
                override fun readNumbering() = read(NUMBERING)
                override fun readQuoting() = read("teamSettings/quoting")

                override fun writeQuotation(id: String, data: Map<String, Any?>) {
                    staged += "quotations/$id" to data
                }

                override fun writeNumbering(fields: Map<String, Any?>) {
                    // An update: named fields replaced whole, the rest kept.
                    staged += NUMBERING to (docs.getValue(NUMBERING) + fields)
                }
            })
        }
        // What the deployed rules would refuse at commit, when a test models
        // one: the whole transaction fails, nothing lands, and the caller sees
        // the same `PERMISSION_DENIED` a stale counter produces.
        if (refuseCommitWhen(staged.toMap())) {
            onRefuse()
            throw Refusal()
        }
        staged.forEach { (path, data) -> docs[path] = data }
        if (loseNextResponse) {
            loseNextResponse = false
            throw IllegalStateException("the commit landed; its acknowledgement did not")
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun read(path: String): DocData? {
        reads += path
        return docs[path]?.let { DocData(path.substringAfterLast('/'), it) }
    }

    fun quotations(): List<String> = docs.keys.filter { it.startsWith("quotations/") }
}

/** Stands in for `PERMISSION_DENIED`, which names no document. */
internal class Refusal : RuntimeException("PERMISSION_DENIED")
