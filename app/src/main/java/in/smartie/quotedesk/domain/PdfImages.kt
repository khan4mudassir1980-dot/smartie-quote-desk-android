package `in`.smartie.quotedesk.domain

/**
 * The PDF's three stored images — the deciding, with no Android in it
 * (N5.11 commit 7). `util/QuotationPdf` does the decoding, on these terms.
 *
 * **Data URLs only.** V8C4 stores the logo and the QR as
 * `data:image/png;base64,…` (fact a of 2026-10-06), and the signature is
 * stored the same way. Anything else — a web address above all — is not an
 * image here: it is never fetched, it is left out, and the notice names it.
 */
object PdfImages {

    /** The longest edge each is decoded to, at most (the approved plan). */
    const val LOGO_EDGE = 512
    const val QR_EDGE = 512
    const val SIGNATURE_EDGE = 600

    /**
     * A stored image whose bytes would pass this is not decoded. V8C4
     * refuses uploads over 1.5 MB and stores them downscaled to 420 px
     * (fact a), so a genuine one is far smaller; this bounds what a bad
     * value can cost the phone.
     */
    const val MAX_BYTES = 2 * 1024 * 1024

    private val PREFIX = Regex("^data:image/[a-z0-9.+-]+;base64,", RegexOption.IGNORE_CASE)

    fun edge(role: ImageRole): Int = when (role) {
        ImageRole.LOGO -> LOGO_EDGE
        ImageRole.QR -> QR_EDGE
        ImageRole.SIGNATURE -> SIGNATURE_EDGE
    }

    /** The images [document] holds, by role; a blank one is not held. */
    fun sources(document: QuotationDocument): Map<ImageRole, String> = buildMap {
        document.letterhead.logo.takeIf { it.isNotBlank() }?.let { put(ImageRole.LOGO, it) }
        document.qr.takeIf { it.isNotBlank() }?.let { put(ImageRole.QR, it) }
        document.signature.takeIf { it.isNotBlank() }?.let { put(ImageRole.SIGNATURE, it) }
    }

    /**
     * The base64 text of a `data:image/…;base64,` URL, its whitespace
     * removed; null for anything else, for an empty one, and for one whose
     * bytes would pass [MAX_BYTES].
     */
    fun payload(url: String): String? {
        val trimmed = url.trim()
        val prefix = PREFIX.find(trimmed) ?: return null
        val body = trimmed.substring(prefix.range.last + 1).filterNot { it.isWhitespace() }
        if (body.isEmpty()) return null
        if (body.length.toLong() / 4 * 3 > MAX_BYTES) return null
        return body
    }

    /** How the notice names each image it could not print. */
    fun name(role: ImageRole): String = when (role) {
        ImageRole.LOGO -> "logo"
        ImageRole.QR -> "QR code"
        ImageRole.SIGNATURE -> "signature"
    }
}
