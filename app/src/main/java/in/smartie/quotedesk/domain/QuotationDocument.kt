package `in`.smartie.quotedesk.domain

/**
 * **Everything a quotation's PDF prints — every string, row, total and
 * section, in order — and nothing about how it is drawn** (the advisor's
 * decision of 2026-10-06; N5.11 commit 5). [QuotationDocumentBuilder] makes
 * one from a stored quotation and the company settings; it is tested
 * exhaustively in the JVM, and the renderer only places what is here.
 *
 * The printed order is the one the Owner approved on 2026-10-06 (recorded in
 * `docs/PROJECT-STATUS.md`): the letterhead; CANCELLED when it is; the title;
 * BILL TO and QUOTATION DETAILS side by side; the items; the totals; the
 * amount in words; validity, payment terms and warranty; NOTES and TERMS &
 * CONDITIONS side by side; BANK DETAILS and SCAN TO PAY; the signatory; the
 * acceptance; and on every page the footer and "Page p of n".
 *
 * **Empty is omitted, never invented** (the Owner's decision 3): every list
 * here holds only what there is to print.
 */
data class QuotationDocument(
    val letterhead: LetterheadBlock,
    /** A cancelled quotation is marked so on every page — V8C4 re-issues it unmarked. */
    val cancelled: Boolean,
    val billTo: List<DocRow>,
    val details: List<DocRow>,
    val items: List<ItemRow>,
    val totals: List<TotalRow>,
    val amountInWords: String,
    /** "Rates hold for N days …", "Payment terms: …", "Warranty: …" — those there are. */
    val conditions: List<String>,
    val notes: List<String>,
    val terms: List<String>,
    /** Empty when there are no bank details to print: the block is omitted. */
    val bank: List<DocRow>,
    /** The stored QR image's data URL; blank means no SCAN TO PAY block. */
    val qr: String,
    /** The stored signature image's data URL; blank means the words alone. */
    val signature: String,
    val signatory: String,
    val acceptance: String,
    /** "pdfFooter | web", the empty parts dropped; blank when both are. */
    val footer: String,
    val fileName: String,
    /** What the letterhead and bank block printed without, for the notice. */
    val missing: List<String>
) {
    companion object {
        const val TITLE = "QUOTATION"
        const val CANCELLED = "CANCELLED"
        const val BILL_TO = "BILL TO (CUSTOMER)"
        const val DETAILS = "QUOTATION DETAILS"
        const val COLUMN_NUMBER = "#"
        const val COLUMN_PRODUCT = "MODEL / PRODUCT"
        const val COLUMN_DESCRIPTION = "DESCRIPTION"
        const val COLUMN_QUANTITY = "QTY"
        const val COLUMN_RATE = "RATE"
        const val COLUMN_AMOUNT = "AMOUNT"
        const val AMOUNT_IN_WORDS = "AMOUNT IN WORDS"
        const val NOTES = "NOTES"
        const val TERMS = "TERMS & CONDITIONS"
        const val BANK_DETAILS = "BANK DETAILS"
        const val SCAN_TO_PAY = "SCAN TO PAY"
        const val UPI_APPS = "PhonePe / GPay / Paytm / any UPI app"
        const val SIGNATURE_AND_DATE = "Signature & date"

        /** "Page p of n", the renderer's once the pages are counted. */
        fun pageOf(page: Int, pages: Int): String = "Page $page of $pages"
    }
}

/** The letterhead: the logo, the firm's name, and the lines beneath it that are there. */
data class LetterheadBlock(
    val logo: String,
    val name: String,
    /** The tagline, the address, the phones, "email | web", "GSTIN … PAN …" — those not blank. */
    val lines: List<String>
)

data class DocRow(val label: String, val value: String)

/** One item: its number, model and product, the working, quantity, rate and amount, as printed. */
data class ItemRow(
    val number: Int,
    val model: String,
    val product: String,
    val description: String,
    val quantity: String,
    val rate: String,
    val amount: String
)

/** A totals row. [amount] is blank for a row of words alone ("Installation extra"). */
data class TotalRow(
    val label: String,
    val amount: String,
    val note: String = "",
    val emphasis: Boolean = false
)
