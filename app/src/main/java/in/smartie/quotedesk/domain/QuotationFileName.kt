package `in`.smartie.quotedesk.domain

/**
 * The PDF's file name — V8C4's: `Quotation-<no>-<client>.pdf`. Every
 * character that is not an ASCII letter or digit becomes "-", the client part
 * is at most [MAX_CLIENT] characters, and the fallbacks are "Draft" for a
 * missing number and "Client" for a missing name (V8C4's output facts,
 * recorded 2026-10-06). The same name is used for Download, Print's job, the
 * share and the cached file.
 *
 * Character by character, as the fact reads: "M/s. A & B" is `M-s--A---B`.
 * A run of them is not collapsed.
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

    private fun clean(text: String): String =
        text.trim().map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
}
