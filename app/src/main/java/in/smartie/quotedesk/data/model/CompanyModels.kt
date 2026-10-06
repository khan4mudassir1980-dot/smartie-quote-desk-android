package `in`.smartie.quotedesk.data.model

/**
 * `teamSettings/company`, as V8C4's settings screen stores it — read-only
 * here (N5.11 commit 2); N6 builds the screen that writes it.
 *
 * **V8C4's own field names, never renamed** (the advisor's list of
 * 2026-10-06): `name, tagline, address, phone, email, web, gstin, pan,
 * bankName, bankBranch, bankAcc, bankIfsc, upi, validityDays, payTerms,
 * warranty, pdfFooter, defaultGst, logo, qr, terms, notes` — and
 * [signature], the field the Owner approved for N6's upload.
 *
 * Every string is blank when absent: **nothing here is a default**. The PDF
 * prints what is stored and omits what is not (the Owner's decision 3 of
 * 2026-10-06 — none of V8C4's built-in firm details, bank, UPI, terms, notes
 * or images are copied).
 */
data class CompanySettings(
    val name: String = "",
    val tagline: String = "",
    val address: String = "",
    /** As stored: V8C4 keeps several numbers in one string ("… · …"). */
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
    /** Null when unset — "Valid for N days" is then not printed. */
    val validityDays: Int? = null,
    val payTerms: String = "",
    val warranty: String = "",
    val pdfFooter: String = "",
    /** Read for completeness; the PDF prints the quotation's own GST rate. */
    val defaultGst: Double? = null,
    /** A `data:image/png;base64,…` URL, as V8C4 stores it (fact a), or blank. */
    val logo: String = "",
    val qr: String = "",
    val signature: String = "",
    /** One entry per line; empty means the section is omitted (fact b). */
    val terms: List<String> = emptyList(),
    val notes: List<String> = emptyList()
)
