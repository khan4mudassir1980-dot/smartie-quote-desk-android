package `in`.smartie.quotedesk.domain

/**
 * The PDF's file name — V8C4's: `Quotation-<no>-<client>.pdf`, built exactly
 * as V8C4 builds it (the Owner's review of 2026-10-06):
 *
 * - **clean** a text: every run of characters other than A–Z, a–z and 0–9
 *   becomes one "-", then a leading and a trailing "-" are stripped;
 * - **no** is the cleaned number, or "Draft" if that comes out empty;
 * - **client** is the cleaned name cut to 36 characters, or "Client" if
 *   empty.
 *
 * A run of other characters collapses to one "-": "M/s. A & B" is `M-s-A-B`.
 * The cut comes after the clean, as V8C4's does. The same name is used for
 * Download, Print's job, the share and the cached file.
 */
object QuotationFileName {

    const val MAX_CLIENT = 36
    const val DRAFT = "Draft"
    const val CLIENT = "Client"

    fun of(number: String, client: String): String {
        val no = clean(number).ifEmpty { DRAFT }
        val who = clean(client).take(MAX_CLIENT).ifEmpty { CLIENT }
        return "Quotation-$no-$who.pdf"
    }

    /** The print job's name: the file name without `.pdf`. */
    fun jobName(number: String, client: String): String = of(number, client).removeSuffix(".pdf")

    /** V8C4's `/[^A-Za-z0-9]+/g` — ASCII letters and digits only, a run at a time. */
    private val OTHERS = Regex("[^A-Za-z0-9]+")

    private fun clean(text: String): String =
        text.replace(OTHERS, "-").removePrefix("-").removeSuffix("-")
}
