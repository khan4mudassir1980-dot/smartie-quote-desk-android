package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.asIntOrNull
import `in`.smartie.quotedesk.data.mapping.asLines
import `in`.smartie.quotedesk.data.mapping.asMapOrNull
import `in`.smartie.quotedesk.data.mapping.asStringOrNull
import `in`.smartie.quotedesk.data.model.CompanySettings

/**
 * What a quotation's PDF prints about the firm — resolved, every field, from
 * the quotation's own `snap` and the live company settings (N5.11 commit 2).
 */
data class CompanyDetails(
    val name: String = "",
    val tagline: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val web: String = "",
    val gstin: String = "",
    val pan: String = "",
    val bankName: String = "",
    val bankBranch: String = "",
    val bankAcc: String = "",
    val bankIfsc: String = "",
    val upi: String = "",
    val validityDays: Int? = null,
    val payTerms: String = "",
    val warranty: String = "",
    val pdfFooter: String = "",
    val terms: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val logo: String = "",
    val qr: String = "",
    val signature: String = ""
)

/**
 * **The letterhead's source, field by field** (the advisor's decision of
 * 2026-10-06): the quotation's `snap{}` where it holds the field — the
 * quotations V8C4 issued — and the **live** company settings for every field
 * it does not, and for every field when there is no `snap` at all, as this
 * app's quotations have until N6 freezes one. **Never a blank letterhead**:
 * V8C4's reader assigns `snap`'s fields with no fallback, so an absent `snap`
 * reprints with no firm at all; that is the bug not copied.
 *
 * `snap` keeps V8C4's own names, which are not the settings' (recorded under
 * the N5.9a answers): `tag` is the tagline, `addr` the address, `phones` the
 * phone, and the bank is a map `bank{name, branch, acc, ifsc, upi}`. They are
 * mapped here exactly as V8C4's reader maps them. **`snap` is text only** —
 * V8C4 never copies the logo or the QR into it — so the images always come
 * from the live settings, and so does the signature.
 *
 * Nothing is invented: a field neither holds is blank, and the PDF omits it.
 */
object Letterhead {

    fun resolve(snap: Map<String, Any?>, live: CompanySettings?): CompanyDetails {
        val now = live ?: CompanySettings()
        val bank = snap["bank"].asMapOrNull() ?: emptyMap()
        fun text(stored: Any?, current: String): String = stored.asStringOrNull() ?: current
        fun lines(stored: Any?, current: List<String>): List<String> =
            stored.asLines().ifEmpty { current }
        return CompanyDetails(
            name = text(snap["name"], now.name),
            tagline = text(snap["tag"], now.tagline),
            address = text(snap["addr"], now.address),
            phone = text(snap["phones"], now.phone),
            email = text(snap["email"], now.email),
            web = text(snap["web"], now.web),
            gstin = text(snap["gstin"], now.gstin),
            pan = text(snap["pan"], now.pan),
            bankName = text(bank["name"], now.bankName),
            bankBranch = text(bank["branch"], now.bankBranch),
            bankAcc = text(bank["acc"], now.bankAcc),
            bankIfsc = text(bank["ifsc"], now.bankIfsc),
            upi = text(bank["upi"], now.upi),
            validityDays = snap["validityDays"].asIntOrNull()?.takeIf { it > 0 } ?: now.validityDays,
            payTerms = text(snap["payTerms"], now.payTerms),
            warranty = text(snap["warranty"], now.warranty),
            pdfFooter = text(snap["pdfFooter"], now.pdfFooter),
            terms = lines(snap["terms"], now.terms),
            notes = lines(snap["notes"], now.notes),
            logo = now.logo,
            qr = now.qr,
            signature = now.signature
        )
    }

    /**
     * What the letterhead and the bank block printed without — the names the
     * non-blocking notice gives (the Owner's decision 3 of 2026-10-06). The
     * firm's identity and its bank details: the fields a customer's document
     * is incomplete without. A tagline, a website, a logo, terms and notes are
     * simply omitted when absent, and not called missing.
     */
    fun missing(details: CompanyDetails): List<String> = buildList {
        if (details.name.isBlank()) add(FIRM_NAME)
        if (details.address.isBlank()) add(ADDRESS)
        if (details.phone.isBlank()) add(PHONE)
        if (details.email.isBlank()) add(EMAIL)
        if (details.gstin.isBlank()) add(GSTIN)
        if (details.pan.isBlank()) add(PAN)
        if (details.bankName.isBlank() || details.bankAcc.isBlank() || details.bankIfsc.isBlank()) {
            add(BANK_DETAILS)
        }
    }

    const val FIRM_NAME = "firm name"
    const val ADDRESS = "address"
    const val PHONE = "phone"
    const val EMAIL = "email"
    const val GSTIN = "GSTIN"
    const val PAN = "PAN"
    const val BANK_DETAILS = "bank details"
}
