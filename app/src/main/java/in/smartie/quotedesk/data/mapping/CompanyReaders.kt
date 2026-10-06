package `in`.smartie.quotedesk.data.mapping

import `in`.smartie.quotedesk.data.model.CompanySettings

/**
 * `teamSettings/company` → [CompanySettings] (N5.11 commit 2).
 *
 * Tolerant as every reader here is: strings trimmed, V8C4's unset marker read
 * as blank, numbers from numeric strings. `terms` and `notes` are lists of
 * strings in V8C4 (fact b); a single multi-line string is read one entry per
 * line, and blank entries are dropped — never replaced.
 */
fun DocData.toCompanySettings(): CompanySettings = CompanySettings(
    name = string("name"),
    tagline = string("tagline"),
    address = string("address"),
    phone = string("phone"),
    email = string("email"),
    web = string("web"),
    gstin = string("gstin"),
    pan = string("pan"),
    bankName = string("bankName"),
    bankBranch = string("bankBranch"),
    bankAcc = string("bankAcc"),
    bankIfsc = string("bankIfsc"),
    upi = string("upi"),
    validityDays = this["validityDays"].asIntOrNull()?.takeIf { it > 0 },
    payTerms = string("payTerms"),
    warranty = string("warranty"),
    pdfFooter = string("pdfFooter"),
    defaultGst = optionalDouble("defaultGst"),
    logo = string("logo"),
    qr = string("qr"),
    signature = string("signature"),
    terms = this["terms"].asLines(),
    notes = this["notes"].asLines()
)

/** A list of strings, or one string one entry per line; blank entries dropped. */
internal fun Any?.asLines(): List<String> = when (this) {
    is String -> lines().mapNotNull { it.asStringOrNull() }
    else -> asStringList()
}
