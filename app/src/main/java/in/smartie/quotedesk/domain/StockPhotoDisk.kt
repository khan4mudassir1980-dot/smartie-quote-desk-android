package `in`.smartie.quotedesk.domain

import java.security.MessageDigest

/**
 * How a cached photo is named on disk, and nothing else.
 *
 * The file name is the whole of the cache's correctness argument, so it is
 * decided here, off device, where it can be tested:
 *
 * - a name carries the **stock document id** and the **revision**, so a file
 *   can never be mistaken for a different row's, or for an older picture of
 *   the same row;
 * - the identity half is a hash, because a stock document id is `group|model`
 *   with only `/` replaced — it may hold spaces, punctuation, and enough
 *   characters to exceed a file name's length. A fixed 32 characters cannot;
 * - the revision half is a plain integer, so a stale file is recognised by
 *   reading its name rather than by opening it.
 */
object StockPhotoDisk {

    /**
     * The disk ceiling. About 500 photos at the 80 KiB maximum, or 680 at the
     * 60 KiB average — comfortably more than the 403-item catalogue, so the
     * bound is a guard against a runaway rather than a working constraint.
     */
    const val MAX_BYTES: Long = 40L * 1024 * 1024

    const val EXTENSION: String = ".webp"

    /** Written first under this suffix, then renamed. Never served. */
    const val PARTIAL: String = ".part"

    private const val SEPARATOR = '_'
    private const val PREFIX_LENGTH = 32

    /**
     * The identity half: the first 32 hex characters of the document id's
     * SHA-256.
     *
     * A hash rather than the id itself because the id is not a safe file
     * name. 128 bits is far past any chance of two of a few hundred stock
     * rows colliding, and a collision would at worst show one row's photo on
     * another — never corrupt a write, since each write replaces the whole
     * entry.
     */
    fun prefixFor(documentId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(documentId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(PREFIX_LENGTH)

    /**
     * The file name for one revision of one row, or **null** when the
     * revision cannot be named.
     *
     * A revision is produced as `stored + 1` from zero, so in practice it is
     * always a small non-negative whole number. Anything else — a fraction, a
     * negative, an infinity, a NaN out of a malformed document — is refused a
     * name rather than rounded into one, because rounding two different
     * revisions to the same name is exactly how a stale picture would come
     * back. Such a photo simply is not cached on disk.
     */
    fun nameFor(documentId: String, rev: Double): String? {
        val token = tokenFor(rev) ?: return null
        return "${prefixFor(documentId)}$SEPARATOR$token$EXTENSION"
    }

    /** The whole-number token for [rev], or null when there is not one. */
    fun tokenFor(rev: Double): String? {
        if (!rev.isFinite() || rev < 0.0) return null
        if (rev != Math.floor(rev)) return null
        if (rev > MAX_REV) return null
        return rev.toLong().toString()
    }

    /** Whether [name] is one of [documentId]'s files. */
    fun belongsTo(name: String, documentId: String): Boolean =
        name.startsWith("${prefixFor(documentId)}$SEPARATOR") && name.endsWith(EXTENSION)

    /** The revision [name] holds, or null when it is not a cache file name. */
    fun revOf(name: String): Double? {
        if (!name.endsWith(EXTENSION)) return null
        val stem = name.removeSuffix(EXTENSION)
        val cut = stem.indexOf(SEPARATOR)
        if (cut <= 0 || cut == stem.length - 1) return null
        return stem.substring(cut + 1).toLongOrNull()?.toDouble()
    }

    /**
     * Beyond this a Double can no longer represent every whole number, so a
     * token would stop being one-to-one with a revision.
     */
    private const val MAX_REV = 9_007_199_254_740_992.0
}
