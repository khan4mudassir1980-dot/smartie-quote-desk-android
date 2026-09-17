package `in`.smartie.quotedesk.domain

/**
 * Storing uncommitted `+`/`−` counts on the device.
 *
 * A pending delta is **a number this person has keyed in, nothing more**. It
 * is never a queued write: it does not commit itself when signal returns, it
 * does not commit itself on restart, and nothing may read it as though it had
 * been written. It survives the app being killed for the same reason the quote
 * draft does (audit C8) — a shelf count is long, and losing it halfway is
 * worse than any of the alternatives.
 *
 * Hand-written for the same reason as [QuoteDraftCodec]: the app has no JSON
 * library on its main classpath, so a plain text format keeps this pure Kotlin
 * and unit-testable.
 */
object StockPendingCodec {

    private const val VERSION = "v1"
    private const val FIELD = ''
    private const val RECORD = ''

    fun encode(pending: Map<String, Double>): String {
        val records = pending
            .filter { (key, delta) -> key.isNotBlank() && delta != 0.0 && delta.isFinite() }
            .map { (key, delta) -> "$key$FIELD$delta" }
        if (records.isEmpty()) return ""
        return (listOf(VERSION) + records).joinToString(RECORD.toString())
    }

    /** Anything unreadable decodes to nothing pending, rather than throwing. */
    fun decode(stored: String?): Map<String, Double> {
        if (stored.isNullOrBlank()) return emptyMap()
        val records = stored.split(RECORD)
        if (records.firstOrNull() != VERSION) return emptyMap()
        return records.drop(1).mapNotNull { record ->
            val separator = record.indexOf(FIELD)
            if (separator <= 0) return@mapNotNull null
            val key = record.substring(0, separator)
            val delta = record.substring(separator + 1).toDoubleOrNull() ?: return@mapNotNull null
            if (key.isBlank() || delta == 0.0 || !delta.isFinite()) null else key to delta
        }.toMap()
    }

    /**
     * Applies one `+` or `−`. A delta returning to zero is **removed**, so an
     * untouched row never carries an empty draft around.
     */
    fun change(pending: Map<String, Double>, key: String, delta: Double): Map<String, Double> {
        if (key.isBlank() || delta == 0.0) return pending
        val next = (pending[key] ?: 0.0) + delta
        return if (next == 0.0) pending - key else pending + (key to next)
    }
}
