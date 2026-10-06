package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.CompanySettings
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A stored quotation and the company settings → the [QuotationDocument] the
 * PDF prints (N5.11 commit 5). Pure: no drawing, no Android.
 *
 * **Every figure is the stored one, never recomputed** — the subtotal, the
 * GST, the grand total, the installation and the discount as they were saved
 * at issue or at the last edit, as the detail screen shows them. The one sum
 * made here is the products subtotal, the stored line amounts added up.
 *
 * Divergences from V8C4, each the advisor's or the Owner's of 2026-10-06:
 * the date is the **issue** date ([QuotationRecord.issuedAt]), never today; a
 * cancelled quotation is marked; the letterhead comes from `snap` field by
 * field and then the live settings, never blank and never V8C4's built-in
 * defaults; there is no "Price list" row (fact d).
 */
object QuotationDocumentBuilder {

    const val CUSTOMER = "Customer"
    const val CONTACT = "Contact"
    const val PHONE = "Phone"
    const val EMAIL = "Email"
    const val ADDRESS = "Address"
    const val GSTIN = "GSTIN"
    const val QUOTATION_NO = "Quotation no"
    const val DATE = "Date"
    const val LAST_EDITED_BY = "Last edited by"
    const val SITE = "Site"
    const val RATE_BASIS = "Rate basis"
    const val VALID_FOR = "Valid for"
    const val MANUAL = "Manual"
    const val PRODUCTS_SUBTOTAL = "Products subtotal"
    const val INSTALLATION = "Installation"
    const val INSTALLATION_EXTRA = "Installation extra"
    const val DISCOUNT = "Discount"
    const val TRANSPORTATION = "Transportation"
    const val SUBTOTAL = "Subtotal"
    const val GST_NOT_INCLUDED = "GST — Not included"
    const val GRAND_TOTAL = "Grand total"
    const val BANK_NAME = "Name"
    const val BANK = "Bank"
    const val ACCOUNT_NO = "Account no"
    const val IFSC_CODE = "IFSC code"
    const val UPI_ID = "UPI ID"
    const val NOT_RECORDED = "Not recorded"
    const val SIGNATORY = "Authorised signatory"
    /** V8C4's words when the quotation names nobody (the Owner's review of 2026-10-06). */
    const val THE_CLIENT = "the client"
    private const val CANCELLED_STATUS = "Cancelled"
    private const val TITLE_SPLIT = " — "
    private val LOCALE: Locale = Locale.forLanguageTag("en-IN")

    fun build(
        record: QuotationRecord,
        company: CompanySettings?,
        products: Map<String, ProductRecord> = emptyMap(),
        zone: TimeZone = TimeZone.getDefault()
    ): QuotationDocument {
        val firm = Letterhead.resolve(record.snapshot, company)
        val carriage = QuotationEdit.carriageOf(record.lines)
        val items = record.lines.filter { it !== carriage }
        val party = record.party
        return QuotationDocument(
            letterhead = letterhead(firm),
            cancelled = record.status.equals(CANCELLED_STATUS, ignoreCase = true),
            billTo = listOf(
                DocRow(CUSTOMER, party.name),
                DocRow(CONTACT, party.contact),
                DocRow(PHONE, party.phone),
                DocRow(EMAIL, party.email),
                DocRow(ADDRESS, joined(", ", party.address, party.city)),
                DocRow(GSTIN, party.gstin)
            ).withValues(),
            details = listOf(
                DocRow(QUOTATION_NO, record.number),
                DocRow(DATE, record.issuedAt.takeIf { it > 0L }?.let { date(it, zone) }.orEmpty()),
                DocRow(LAST_EDITED_BY, lastEdited(record, zone)),
                DocRow(SITE, party.site),
                DocRow(RATE_BASIS, rateBasis(record)),
                DocRow(VALID_FOR, firm.validityDays?.let { "$it days" }.orEmpty())
            ).withValues(),
            items = items.mapIndexed { index, line -> itemRow(index + 1, line, products) },
            totals = totals(record, items, carriage),
            amountInWords = AmountInWords.rupees(record.total),
            conditions = listOfNotNull(
                firm.validityDays?.let { "Rates hold for $it days from the date of this quotation." },
                firm.payTerms.trim().takeIf { it.isNotEmpty() }?.let { "Payment terms: $it" },
                firm.warranty.trim().takeIf { it.isNotEmpty() }?.let { "Warranty: $it" }
            ),
            notes = firm.notes,
            terms = firm.terms,
            bank = bank(firm),
            qr = firm.qr.trim(),
            signature = firm.signature.trim(),
            signatory = if (firm.name.isBlank()) SIGNATORY else "$SIGNATORY for ${firm.name.trim()}",
            acceptance = "Accepted for ${party.name.trim().ifEmpty { THE_CLIENT }}",
            footer = joined(" | ", firm.pdfFooter, firm.web),
            fileName = QuotationFileName.of(record.number, party.name),
            missing = Letterhead.missing(firm)
        )
    }

    /**
     * "Last edited by <name>, <date time>" — the recorded form (the Owner's
     * decision 1.2 of 2026-10-06), the time the server's since N5.11. Blank,
     * so the row is skipped, when nobody has edited it.
     */
    private fun lastEdited(record: QuotationRecord, zone: TimeZone): String =
        if (record.lastEditedAt > 0L) {
            "${record.lastEditedBy.trim().ifEmpty { NOT_RECORDED }}, ${dateTime(record.lastEditedAt, zone)}"
        } else {
            ""
        }

    private fun letterhead(firm: CompanyDetails) = LetterheadBlock(
        logo = firm.logo.trim(),
        name = firm.name.trim(),
        lines = listOf(
            firm.tagline,
            firm.address,
            firm.phone,
            joined(" | ", firm.email, firm.web),
            joined(
                " · ",
                firm.gstin.trim().takeIf { it.isNotEmpty() }?.let { "GSTIN $it" }.orEmpty(),
                firm.pan.trim().takeIf { it.isNotEmpty() }?.let { "PAN $it" }.orEmpty()
            )
        ).map { it.trim() }.filter { it.isNotEmpty() }
    )

    /**
     * Dealer or Client, as stored: `tierName`, else the stored `tier` word's
     * label when it is one this app knows. An unknown word prints nothing —
     * never the reader's lenient Dealer.
     */
    private fun rateBasis(record: QuotationRecord): String =
        record.tierName.trim().ifEmpty {
            RateTierV2.entries.firstOrNull { it.wireValue.equals(record.storedTier, ignoreCase = true) }
                ?.label.orEmpty()
        }

    /**
     * A catalogue line's model and name come from its product, by key; a line
     * whose product is gone, or that never had one, splits its title on
     * " — "; a manual line says "Manual" (V8C4's output facts).
     */
    private fun itemRow(number: Int, line: QuotationLineRecord, products: Map<String, ProductRecord>): ItemRow {
        val product = line.key.takeIf { it.isNotBlank() }?.let { products[it] }
        val (model, name) = when {
            line.manual -> MANUAL to line.title.trim()
            product != null -> product.model.trim() to product.name.trim().ifEmpty { line.title.trim() }
            line.title.contains(TITLE_SPLIT) ->
                line.title.substringBefore(TITLE_SPLIT).trim() to line.title.substringAfter(TITLE_SPLIT).trim()
            else -> "" to line.title.trim()
        }
        return ItemRow(
            number = number,
            model = model,
            product = name,
            description = line.spec.trim(),
            quantity = quantity(line),
            rate = rupees(line.rate),
            amount = rupees(line.amount)
        )
    }

    /** The quantity, with its unit unless the unit is "each" or blank. */
    private fun quantity(line: QuotationLineRecord): String {
        val figure = Money.formatQuantity(line.quantity)
        val unit = line.unit.trim()
        return if (unit.isEmpty() || unit.equals(ProductUnit.EACH, ignoreCase = true)) figure else "$figure $unit"
    }

    private fun totals(
        record: QuotationRecord,
        items: List<QuotationLineRecord>,
        carriage: QuotationLineRecord?
    ): List<TotalRow> = buildList {
        add(TotalRow(PRODUCTS_SUBTOTAL, rupees(items.sumOf { it.amount })))
        val installation = record.installation
        when {
            installation == null -> add(TotalRow(INSTALLATION_EXTRA, ""))
            installation.amount != null -> add(TotalRow(INSTALLATION, rupees(installation.amount)))
            // Stored but unreadable: nothing is invented, and nothing is
            // called extra that was charged.
            else -> Unit
        }
        record.discount?.amount?.takeIf { it > 0.0 }?.let { add(TotalRow(DISCOUNT, "- " + rupees(it))) }
        carriage?.let { add(TotalRow(TRANSPORTATION, rupees(it.amount), note = it.spec.trim())) }
        add(TotalRow(SUBTOTAL, rupees(record.subtotal)))
        add(
            if (record.gstEnabled) {
                TotalRow(gstLabel(record.gstPercent), rupees(record.total - record.subtotal))
            } else {
                TotalRow(GST_NOT_INCLUDED, "")
            }
        )
        add(TotalRow(GRAND_TOTAL, rupees(record.total), emphasis = true))
    }

    /** As the detail screen labels it. */
    fun gstLabel(percent: Double): String = "GST ${Money.formatQuantity(percent)}%"

    /**
     * Name, Bank, Account no, IFSC code, UPI ID — V8C4's rows exactly (the
     * Owner's review of 2026-10-06): **Name is `bankName`** (`snap`
     * `bank.name`) and **Bank is `bankBranch`** (`snap` `bank.branch`). The
     * firm's own name is never printed here. A row with nothing stored is
     * omitted, never invented. Omitted whole when there are no bank details
     * at all.
     */
    private fun bank(firm: CompanyDetails): List<DocRow> {
        if (listOf(firm.bankName, firm.bankAcc, firm.bankIfsc, firm.upi).all { it.isBlank() }) return emptyList()
        return listOf(
            DocRow(BANK_NAME, firm.bankName.trim()),
            DocRow(BANK, firm.bankBranch.trim()),
            DocRow(ACCOUNT_NO, firm.bankAcc.trim()),
            DocRow(IFSC_CODE, firm.bankIfsc.trim()),
            DocRow(UPI_ID, firm.upi.trim())
        ).withValues()
    }

    /** "₹" and whole rupees, Indian grouping (V8C4's money). */
    fun rupees(value: Double): String = Money.formatRupees(value, decimals = 0)

    fun date(millis: Long, zone: TimeZone): String = format("d MMM yyyy", millis, zone)

    fun dateTime(millis: Long, zone: TimeZone): String = format("d MMM yyyy, h:mm a", millis, zone)

    private fun format(pattern: String, millis: Long, zone: TimeZone): String =
        SimpleDateFormat(pattern, LOCALE).apply { timeZone = zone }.format(Date(millis))

    private fun joined(separator: String, vararg parts: String): String =
        parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(separator)

    private fun List<DocRow>.withValues(): List<DocRow> = filter { it.value.isNotBlank() }
}
