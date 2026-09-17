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
     * The `/stock` document id for a logical key.
     *
     * **A different scheme from [productDocId], and not interchangeable with
     * it.** Confirmed against the approved V8C4 source: a stock document is
     * addressed by the logical key `group|model` with **only `/` replaced**,
     * where a product document is `group__model` with `/ . # $ [ ]` replaced.
     * `gate|SIE2.5MSMALL` is `gate|SIE2.5MSMALL` as a stock id and
     * `gate__SIE2_5MSMALL` as a product id — the dot survives in one and not
     * the other. Using the product function for a stock document would read
     * and write the wrong row.
     *
     * Deliberately nothing else is replaced, because the ids already in
     * Firestore are the PWA's, not the ones Firestore would merely tolerate.
     * A blank key is the caller's to refuse before it gets here; `StockEntry`
     * validation does that.
     */
    fun stockDocId(key: String): String = key.replace('/', '_')

    /**
     * The `/stockMoves` document id: the movement's own `id` field.
     *
     * The v9 rules require `request.resource.data.id == id`, so the two can
     * never drift. Generate it with `generateId("mv_")` **once, before the
     * transaction**, and reuse it if Firestore replays the transaction body.
     */
    fun stockMoveDocId(movementId: String): String = movementId

    /**
     * The characters the PWA's own `docId` replaces (`index.html:5723`),
     * mirrored in `tools/catalogue-import/lib/keys.mjs`: the path separator,
     * and the four Realtime-Database-era characters the PWA has always
     * stripped. Firestore itself only refuses `/`, but the id that matters is
     * the one already on disk, not the one Firestore would tolerate.
     */
    private val PWA_DOC_ID_CHARACTERS = Regex("[/.#\$\\[\\]]")

    /**
     * Canonical product document id for schema v2: one document per product.
     *
     * Seeding wrote `group|model` and editing wrote `group__model`, so the
     * same product could exist twice with the same `id` field — the duplicate
     * Compose key that crashes the beta Products screen (audit C1/P3).
     *
     * This must be **character for character** what the staging importer
     * wrote, or a native write lands on a second document and re-creates the
     * duplicate the canonical id exists to prevent. It previously replaced
     * only `/`, which is right for `SIEBAL58H/V` and wrong for every model
     * carrying a `.`, `#`, `$`, `[` or `]`. Only the model is canonicalised,
     * not the group, because that is what the PWA does.
     *
     * `sanitiseDocId` is deliberately not used here and is deliberately left
     * alone: it guards arbitrary ids, including stock keys, whose own rule is
     * still an open question against the V8C4 source.
     */
    fun productDocId(group: String, seedModel: String): String {
        val canonical = seedModel.replace(PWA_DOC_ID_CHARACTERS, "_")
        return "${group}__$canonical"
    }

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
