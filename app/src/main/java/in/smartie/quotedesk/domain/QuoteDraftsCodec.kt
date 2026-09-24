package `in`.smartie.quotedesk.domain

/**
 * A whole account's drafts, on the device.
 *
 * Wraps [QuoteDraftCodec] rather than reaching into it: each draft is encoded
 * by the codec that owns that shape, and the blob it returns is escaped whole
 * before being joined. So this file never has to know which separators a draft
 * uses, and a change to the draft format cannot break the collection format.
 *
 * Unreadable text decodes to no drafts rather than throwing, exactly as a
 * single draft does — the worst case is an empty builder, never a crash on
 * launch.
 */
object QuoteDraftsCodec {

    private const val VERSION = "c1"
    private val READABLE = setOf(VERSION)

    /** One level above the separators a single draft uses. */
    private const val GROUP = '\u001d'
    private const val ESCAPE = '\\'

    fun encode(drafts: QuoteDrafts): String = buildList {
        add(VERSION)
        add(escape(drafts.currentId))
        drafts.drafts.forEach { add(escape(QuoteDraftCodec.encode(it))) }
    }.joinToString(GROUP.toString())

    fun decode(stored: String?): QuoteDrafts {
        if (stored.isNullOrBlank()) return QuoteDrafts()
        val parts = stored.split(GROUP)
        if (parts.firstOrNull() !in READABLE) return QuoteDrafts()
        val currentId = parts.getOrNull(1)?.let(::unescape).orEmpty()
        val drafts = parts.drop(2)
            .map { QuoteDraftCodec.decode(unescape(it)) }
            // A draft with no id cannot be addressed, so it cannot be the one
            // the builder resumes. Dropping it is better than resurrecting
            // something nothing can name.
            .filter { it.id.isNotBlank() }
        return QuoteDrafts(drafts = drafts, currentId = currentId)
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (character in value) {
            when (character) {
                ESCAPE -> append(ESCAPE).append(ESCAPE)
                GROUP -> append(ESCAPE).append('g')
                else -> append(character)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == ESCAPE && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    ESCAPE -> append(ESCAPE)
                    'g' -> append(GROUP)
                    else -> append(next)
                }
                index += 2
            } else {
                append(character)
                index++
            }
        }
    }
}
