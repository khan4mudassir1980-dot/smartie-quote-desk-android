package `in`.smartie.quotedesk.domain

/**
 * What the person is told about a PDF (N5.11 commit 9) — the Owner's
 * decision 3 of 2026-10-06: an incomplete letterhead never stops the PDF; a
 * **non-blocking notice** after it says what it was made without. And the one
 * refusal: settings that have never loaded, so a blank letterhead is never
 * produced by accident.
 */
object PdfNotice {

    /** The approved plan's words, when the company settings have never loaded on this phone. */
    const val NOT_LOADED = "Company details have not loaded yet — connect and try again"

    /**
     * "Made without the GSTIN and bank details — they are not in company
     * settings." and "The logo could not be read and was left out.", each
     * when it applies; null when the PDF is complete.
     */
    fun after(missing: List<String>, unreadable: List<ImageRole>): String? {
        val sentences = buildList {
            if (missing.isNotEmpty()) {
                val verb = if (missing.size == 1) "it is" else "they are"
                add("Made without the ${listed(missing)} — $verb not in company settings.")
            }
            if (unreadable.isNotEmpty()) {
                val names = listed(unreadable.map { PdfImages.name(it) })
                val tail = if (unreadable.size == 1) "could not be read and was left out" else "could not be read and were left out"
                add("The $names $tail.")
            }
        }
        return sentences.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun listed(parts: List<String>): String = when (parts.size) {
        1 -> parts.single()
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}
