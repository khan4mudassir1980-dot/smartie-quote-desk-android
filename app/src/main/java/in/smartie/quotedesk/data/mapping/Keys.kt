package `in`.smartie.quotedesk.data.mapping

/**
 * Document-id and logical-key handling.
 *
 * Stock is keyed by the PWA's logical product key (`group|model`), which can
 * contain `/` — `hwWheel|SIEBAL58H/V` is a real one. A `/` in a document id
 * builds an invalid Firestore reference, which is audit issue C6, so the same
 * sanitisation has to be used on every read and every write.
 */
object Keys {

    /** Characters Firestore refuses, or that break a document path. */
    fun sanitiseDocId(raw: String): String {
        val cleaned = raw.trim()
            .replace('/', '_')
            .replace("__proto__", "proto")
        return when {
            cleaned.isEmpty() -> "_"
            cleaned == "." -> "_"
            cleaned == ".." -> "__"
            else -> cleaned
        }
    }

    /** The PWA's logical product key, stored as `id`/`key` on every document. */
    fun productKey(group: String, seedModel: String): String = "$group|$seedModel"

    /**
     * Canonical product document id for schema v2: one document per product.
     *
     * Seeding wrote `group|model` and editing wrote `group__model`, so the
     * same product could exist twice with the same `id` field — the duplicate
     * Compose key that crashes the beta Products screen (audit C1/P3).
     */
    fun productDocId(group: String, seedModel: String): String =
        sanitiseDocId("${group}__${seedModel}")

    /** Splits either id scheme back into group and model. */
    fun splitProductKey(raw: String): Pair<String, String>? {
        val pipe = raw.indexOf('|')
        if (pipe > 0) return raw.substring(0, pipe) to raw.substring(pipe + 1)
        val underscores = raw.indexOf("__")
        if (underscores > 0) return raw.substring(0, underscores) to raw.substring(underscores + 2)
        return null
    }

    fun isLegacyProductDocId(docId: String): Boolean = docId.contains('|')

    /** Emails identify a person across duplicate uid records; compare folded. */
    fun normaliseEmail(raw: String?): String = raw?.trim()?.lowercase().orEmpty()

    private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** PWA-compatible ids: `ta_…`, `mv_…`, `c_…`, `q_…`. */
    fun generateId(prefix: String, now: Long = System.currentTimeMillis()): String {
        val time = now.toString(36)
        val random = (1..5).map { ID_ALPHABET.random() }.joinToString("")
        return "$prefix$time$random"
    }
}
