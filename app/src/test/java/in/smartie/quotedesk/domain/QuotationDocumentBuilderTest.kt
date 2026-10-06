package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.CompanySettings
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationDiscountRecord
import `in`.smartie.quotedesk.data.model.QuotationInstallationRecord
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.QuotationDocumentBuilder as B
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PDF's content, every string in order (N5.11 commit 5) — the
 * advisor's "pure document model, tested exhaustively in the JVM".
 *
 * **Synthetic only** (the Owner's addition of 2026-10-06): the firm, the
 * customer, the images and the terms below are invented for the test and
 * match nothing V8C4 holds.
 */
class QuotationDocumentBuilderTest {

    private val ist: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** 5 Oct 2026, 10:30 IST — when the server issued it. */
    private val serverAt = 1_791_176_400_000L

    /** 3 Oct 2026, 23:50 IST — the phone's clock, two days out. */
    private val phoneAt = 1_791_051_600_000L

    /** 6 Oct 2026, 15:04 IST. */
    private val editedAt = 1_791_279_240_000L

    private val company = CompanySettings(
        name = "Test Gates & Shutters",
        tagline = "Synthetic test data",
        address = "1 Test Road, Test City 400001",
        phone = "+91 90000 00001",
        email = "quotes@example.invalid",
        web = "example.invalid",
        gstin = "27AAAAA0000A1Z5",
        pan = "AAAAA0000A",
        bankName = "Test Bank",
        bankBranch = "Test Branch",
        bankAcc = "000000000000",
        bankIfsc = "TEST0000000",
        upi = "test-only@invalid",
        validityDays = 15,
        payTerms = "50% advance (test)",
        warranty = "12 months (test)",
        pdfFooter = "Synthetic test footer",
        logo = "data:image/png;base64,TE9HTw==",
        qr = "data:image/png;base64,UVI=",
        signature = "data:image/png;base64,U0lH",
        terms = listOf("Test term one.", "Test term two."),
        notes = listOf("Test note one.")
    )

    private val motor = ProductRecord(
        documentId = "p_1", key = "gates|TM-1", group = "gates", seedModel = "TM-1",
        model = "TM-1", name = "Test gate motor"
    )

    private val catalogueLine = QuotationLineRecord(
        title = "TM-1 — Test gate motor (old name)", spec = "Synthetic spec", unit = "each",
        quantity = 1.0, rate = 44_400.0, key = "gates|TM-1", amount = 44_400.0
    )
    private val areaLine = QuotationLineRecord(
        title = "Test shutter", spec = "2 × 10 ft × 6.025 ft", unit = "sqft",
        quantity = 120.5, rate = 100.0, key = "shutters|TS-9", amount = 12_050.0
    )
    private val manualLine = QuotationLineRecord(
        title = "Test site visit", unit = "each", quantity = 2.0, rate = 1_000.0, manual = true, amount = 2_000.0
    )
    private val carriage = QuotationLineRecord(
        title = QuotationWrite.TRANSPORT_TITLE, spec = "Test depot to site", unit = QuotationWrite.TRANSPORT_UNIT,
        quantity = 1.0, rate = 1_500.0, amount = 1_500.0
    )

    /** Products 58,450; installation 1,500; discount 5,845; carriage 1,500; GST 18%. */
    private val record = QuotationRecord(
        id = "q_test",
        number = "TEST/QD/2026-27/001",
        at = phoneAt,
        serverAt = serverAt,
        by = "Test Manager",
        byUid = "u_m",
        tier = RateTierV2.CLIENT,
        tierName = "Client",
        storedTier = "client",
        party = QuotationPartySnapshot(
            name = "Test Customer & Sons",
            site = "Test site, Plot 1",
            gstin = "27BBBBB1111B1Z5",
            contact = "Test Contact",
            phone = "+91 90000 00009",
            email = "buyer@example.invalid",
            address = "9 Test Lane",
            city = "Test City"
        ),
        lines = listOf(catalogueLine, areaLine, carriage, manualLine),
        gstEnabled = true,
        gstPercent = 18.0,
        subtotal = 55_605.0,
        total = 65_614.0,
        installation = QuotationInstallationRecord(mode = "fixed", rate = 1_500.0, amount = 1_500.0),
        discount = QuotationDiscountRecord(kind = "pct", value = 10.0, amount = 5_845.0),
        discountBase = 58_450.0
    )

    private fun build(
        quotation: QuotationRecord = record,
        settings: CompanySettings? = company,
        zone: TimeZone = ist
    ) = B.build(quotation, settings, mapOf(motor.key to motor), zone)

    // --- the whole document ---------------------------------------------------------

    @Test
    fun `the whole document, in the printed order, from synthetic settings`() {
        val doc = build()

        assertEquals(
            LetterheadBlock(
                logo = "data:image/png;base64,TE9HTw==",
                name = "Test Gates & Shutters",
                lines = listOf(
                    "Synthetic test data",
                    "1 Test Road, Test City 400001",
                    "+91 90000 00001",
                    "quotes@example.invalid | example.invalid",
                    "GSTIN 27AAAAA0000A1Z5 · PAN AAAAA0000A"
                )
            ),
            doc.letterhead
        )
        assertFalse(doc.cancelled)
        assertEquals(
            listOf(
                DocRow(B.CUSTOMER, "Test Customer & Sons"),
                DocRow(B.CONTACT, "Test Contact"),
                DocRow(B.PHONE, "+91 90000 00009"),
                DocRow(B.EMAIL, "buyer@example.invalid"),
                DocRow(B.ADDRESS, "9 Test Lane, Test City"),
                DocRow(B.GSTIN, "27BBBBB1111B1Z5")
            ),
            doc.billTo
        )
        assertEquals(
            listOf(
                DocRow(B.QUOTATION_NO, "TEST/QD/2026-27/001"),
                DocRow(B.DATE, "5 Oct 2026"),
                DocRow(B.SITE, "Test site, Plot 1"),
                DocRow(B.RATE_BASIS, "Client"),
                DocRow(B.VALID_FOR, "15 days")
            ),
            doc.details
        )
        assertEquals(
            listOf(
                ItemRow(1, "TM-1", "Test gate motor", "Synthetic spec", "1", "₹44,400", "₹44,400"),
                ItemRow(2, "", "Test shutter", "2 × 10 ft × 6.025 ft", "120.5 sqft", "₹100", "₹12,050"),
                ItemRow(3, B.MANUAL, "Test site visit", "", "2", "₹1,000", "₹2,000")
            ),
            doc.items
        )
        assertEquals(
            listOf(
                TotalRow(B.PRODUCTS_SUBTOTAL, "₹58,450"),
                TotalRow(B.INSTALLATION, "₹1,500"),
                TotalRow(B.DISCOUNT, "- ₹5,845"),
                TotalRow(B.TRANSPORTATION, "₹1,500", note = "Test depot to site"),
                TotalRow(B.SUBTOTAL, "₹55,605"),
                TotalRow("GST 18%", "₹10,009"),
                TotalRow(B.GRAND_TOTAL, "₹65,614", emphasis = true)
            ),
            doc.totals
        )
        assertEquals("Sixty-five thousand six hundred and fourteen rupees only", doc.amountInWords)
        assertEquals(
            listOf(
                "Rates hold for 15 days from the date of this quotation.",
                "Payment terms: 50% advance (test)",
                "Warranty: 12 months (test)"
            ),
            doc.conditions
        )
        assertEquals(listOf("Test note one."), doc.notes)
        assertEquals(listOf("Test term one.", "Test term two."), doc.terms)
        assertEquals(
            listOf(
                DocRow(B.BANK_NAME, "Test Gates & Shutters"),
                DocRow(B.BANK, "Test Bank, Test Branch"),
                DocRow(B.ACCOUNT_NO, "000000000000"),
                DocRow(B.IFSC_CODE, "TEST0000000"),
                DocRow(B.UPI_ID, "test-only@invalid")
            ),
            doc.bank
        )
        assertEquals("data:image/png;base64,UVI=", doc.qr)
        assertEquals("data:image/png;base64,U0lH", doc.signature)
        assertEquals("Authorised signatory for Test Gates & Shutters", doc.signatory)
        assertEquals("Accepted for Test Customer & Sons", doc.acceptance)
        assertEquals("Synthetic test footer | example.invalid", doc.footer)
        assertEquals("Quotation-TEST-QD-2026-27-001-Test-Customer---Sons.pdf", doc.fileName)
        assertEquals(emptyList<String>(), doc.missing)
    }

    @Test
    fun `the fixed words, as the approved order prints them`() {
        assertEquals("QUOTATION", QuotationDocument.TITLE)
        assertEquals("CANCELLED", QuotationDocument.CANCELLED)
        assertEquals("BILL TO (CUSTOMER)", QuotationDocument.BILL_TO)
        assertEquals("QUOTATION DETAILS", QuotationDocument.DETAILS)
        assertEquals(
            listOf("#", "MODEL / PRODUCT", "DESCRIPTION", "QTY", "RATE", "AMOUNT"),
            listOf(
                QuotationDocument.COLUMN_NUMBER, QuotationDocument.COLUMN_PRODUCT,
                QuotationDocument.COLUMN_DESCRIPTION, QuotationDocument.COLUMN_QUANTITY,
                QuotationDocument.COLUMN_RATE, QuotationDocument.COLUMN_AMOUNT
            )
        )
        assertEquals("AMOUNT IN WORDS", QuotationDocument.AMOUNT_IN_WORDS)
        assertEquals("NOTES", QuotationDocument.NOTES)
        assertEquals("TERMS & CONDITIONS", QuotationDocument.TERMS)
        assertEquals("BANK DETAILS", QuotationDocument.BANK_DETAILS)
        assertEquals("SCAN TO PAY", QuotationDocument.SCAN_TO_PAY)
        assertEquals("PhonePe / GPay / Paytm / any UPI app", QuotationDocument.UPI_APPS)
        assertEquals("Signature & date", QuotationDocument.SIGNATURE_AND_DATE)
        assertEquals("Page 2 of 3", QuotationDocument.pageOf(2, 3))
    }

    // --- the dates --------------------------------------------------------------------

    @Test
    fun `the date is the server's issue time, else the phone's, and never today`() {
        // The Owner's decision 1.1: serverAt, else at. V8C4 prints today.
        assertEquals("5 Oct 2026", build().details.single { it.label == B.DATE }.value)
        assertEquals(
            "3 Oct 2026",
            build(record.copy(serverAt = 0L)).details.single { it.label == B.DATE }.value
        )
        assertFalse(
            "nothing recorded prints no date, never today's",
            build(record.copy(serverAt = 0L, at = 0L)).details.any { it.label == B.DATE }
        )
    }

    @Test
    fun `the date is read in the phone's own zone`() {
        // 00:20 IST on 4 Oct is still 3 Oct in UTC.
        val lateNight = record.copy(serverAt = 1_791_053_400_000L)
        assertEquals("4 Oct 2026", build(lateNight, zone = ist).details.single { it.label == B.DATE }.value)
        assertEquals("3 Oct 2026", build(lateNight, zone = utc).details.single { it.label == B.DATE }.value)
    }

    @Test
    fun `last edited by is printed right after the date, only when edited, with the name and the time`() {
        val edited = build(record.copy(lastEditedBy = "Test Owner", lastEditedByUid = "u_o", lastEditedAt = editedAt))
        assertEquals(
            listOf(B.QUOTATION_NO, B.DATE, B.LAST_EDITED_BY, B.SITE, B.RATE_BASIS, B.VALID_FOR),
            edited.details.map { it.label }
        )
        assertEquals(
            "test owner, 6 oct 2026, 3:04 pm",
            edited.details.single { it.label == B.LAST_EDITED_BY }.value.lowercase()
        )

        val nameless = build(record.copy(lastEditedAt = editedAt))
        assertTrue(nameless.details.single { it.label == B.LAST_EDITED_BY }.value.startsWith("Not recorded, "))

        assertFalse(build().details.any { it.label == B.LAST_EDITED_BY })
    }

    // --- the details panel ----------------------------------------------------------

    @Test
    fun `no Price list row is ever printed`() {
        // Fact d: V8C4 never stores it, and prints the device's label.
        val labels = build().details.map { it.label }
        assertFalse(labels.any { it.contains("Price", ignoreCase = true) })
    }

    @Test
    fun `rate basis is the stored tier name, else the stored tier's label, and nothing for a word it does not know`() {
        fun basis(q: QuotationRecord) = build(q).details.firstOrNull { it.label == B.RATE_BASIS }?.value
        assertEquals("Client", basis(record))
        assertEquals("Dealer", basis(record.copy(tierName = "", tier = RateTierV2.DEALER, storedTier = "dealer")))
        // The reader turns an unknown word into Dealer; the PDF does not.
        assertEquals(null, basis(record.copy(tierName = "", tier = RateTierV2.DEALER, storedTier = "wholesale")))
        assertEquals(null, basis(record.copy(tierName = "", storedTier = "")))
    }

    @Test
    fun `valid for is printed only when the settings give a validity`() {
        assertFalse(build(settings = company.copy(validityDays = null)).details.any { it.label == B.VALID_FOR })
    }

    @Test
    fun `bill to skips every empty row, and the address carries the city`() {
        val bare = record.copy(party = QuotationPartySnapshot(name = "Test Walk-in", city = "Test City"))
        assertEquals(
            listOf(DocRow(B.CUSTOMER, "Test Walk-in"), DocRow(B.ADDRESS, "Test City")),
            build(bare).billTo
        )
        assertFalse(build(bare).details.any { it.label == B.SITE })
    }

    @Test
    fun `a cancelled quotation is marked, and only a cancelled one`() {
        assertTrue(build(record.copy(status = "Cancelled")).cancelled)
        assertTrue(build(record.copy(status = "cancelled")).cancelled)
        assertFalse(build(record.copy(status = "Finalised")).cancelled)
    }

    // --- the items ----------------------------------------------------------------------

    @Test
    fun `a catalogue line takes its model and name from its product, by key`() {
        val item = build().items.first()
        assertEquals("TM-1", item.model)
        assertEquals("Test gate motor", item.product)
    }

    @Test
    fun `a line whose product has gone splits its title on the dash`() {
        val doc = B.build(record, company, products = emptyMap(), zone = ist)
        assertEquals("TM-1", doc.items.first().model)
        assertEquals("Test gate motor (old name)", doc.items.first().product)
        assertEquals("" to "Test shutter", doc.items[1].model to doc.items[1].product)
    }

    @Test
    fun `a manual line says Manual, even when its title has a dash`() {
        val manual = manualLine.copy(title = "Test — welding")
        val doc = build(record.copy(lines = listOf(manual)))
        assertEquals(B.MANUAL to "Test — welding", doc.items.single().model to doc.items.single().product)
    }

    @Test
    fun `the carriage line moves to the totals with its note, and the items are numbered without it`() {
        val doc = build()
        assertEquals(listOf(1, 2, 3), doc.items.map { it.number })
        assertFalse(doc.items.any { it.product == QuotationWrite.TRANSPORT_TITLE })
        assertEquals(TotalRow(B.TRANSPORTATION, "₹1,500", note = "Test depot to site"), doc.totals[3])
    }

    @Test
    fun `two carriage-shaped lines are not V8C4's shape, so both stay items and no Transportation row is made`() {
        val doc = build(record.copy(lines = listOf(catalogueLine, carriage, carriage.copy(rate = 900.0, amount = 900.0))))
        assertEquals(3, doc.items.size)
        assertFalse(doc.totals.any { it.label == B.TRANSPORTATION })
    }

    @Test
    fun `the quantity carries its unit, unless the unit is each or blank`() {
        val lines = listOf(
            manualLine.copy(quantity = 3.0, unit = "each"),
            manualLine.copy(quantity = 1.25, unit = "m"),
            manualLine.copy(quantity = 4.0, unit = "")
        )
        assertEquals(listOf("3", "1.25 m", "4"), build(record.copy(lines = lines)).items.map { it.quantity })
    }

    // --- the totals ---------------------------------------------------------------------

    @Test
    fun `the stored figures are printed, never recomputed`() {
        // A subtotal and total that do not add up are printed as stored: the
        // PDF is a copy of what was issued, not a second opinion on it.
        val odd = record.copy(subtotal = 1_000.0, total = 1_180.0)
        val totals = build(odd).totals.associate { it.label to it.amount }
        assertEquals("₹1,000", totals[B.SUBTOTAL])
        assertEquals("₹180", totals["GST 18%"])
        assertEquals("₹1,180", totals[B.GRAND_TOTAL])
        assertEquals("₹58,450", totals[B.PRODUCTS_SUBTOTAL])
        assertEquals("One thousand one hundred and eighty rupees only", build(odd).amountInWords)
    }

    @Test
    fun `with nothing optional - installation extra, no discount, no transport, GST not included`() {
        val plain = record.copy(
            lines = listOf(catalogueLine),
            installation = null,
            discount = null,
            gstEnabled = false,
            subtotal = 44_400.0,
            total = 44_400.0
        )
        assertEquals(
            listOf(
                TotalRow(B.PRODUCTS_SUBTOTAL, "₹44,400"),
                TotalRow(B.INSTALLATION_EXTRA, ""),
                TotalRow(B.SUBTOTAL, "₹44,400"),
                TotalRow(B.GST_NOT_INCLUDED, ""),
                TotalRow(B.GRAND_TOTAL, "₹44,400", emphasis = true)
            ),
            build(plain).totals
        )
    }

    @Test
    fun `a discount of nothing prints no discount row`() {
        val none = record.copy(discount = QuotationDiscountRecord(kind = "pct", value = 0.0, amount = 0.0))
        assertFalse(build(none).totals.any { it.label == B.DISCOUNT })
    }

    @Test
    fun `an installation stored but unreadable prints no row - not invented, and not called extra`() {
        val unreadable = record.copy(installation = QuotationInstallationRecord(mode = "fixed", amount = null))
        val labels = build(unreadable).totals.map { it.label }
        assertFalse(B.INSTALLATION in labels)
        assertFalse(B.INSTALLATION_EXTRA in labels)
    }

    @Test
    fun `money is whole rupees with Indian grouping`() {
        assertEquals("₹1,23,457", B.rupees(123_456.5))
        assertEquals("₹1,00,00,000", B.rupees(10_000_000.0))
        assertEquals("₹0", B.rupees(0.0))
    }

    @Test
    fun `the GST row is labelled with the stored rate`() {
        val gst = build(record.copy(gstPercent = 12.5)).totals.single { it.label.startsWith("GST") }
        assertEquals("GST 12.5%", gst.label)
    }

    // --- conditions, notes and terms ----------------------------------------------

    @Test
    fun `conditions are only those the settings give, in order`() {
        assertEquals(
            listOf("Warranty: 12 months (test)"),
            build(settings = company.copy(validityDays = null, payTerms = " ")).conditions
        )
        assertEquals(
            emptyList<String>(),
            build(settings = company.copy(validityDays = null, payTerms = "", warranty = "")).conditions
        )
    }

    @Test
    fun `empty notes and terms are omitted - V8C4's built-in wording is never printed`() {
        // Fact b and the Owner's decision 3.
        val doc = build(settings = company.copy(notes = emptyList(), terms = emptyList()))
        assertEquals(emptyList<String>(), doc.notes)
        assertEquals(emptyList<String>(), doc.terms)
    }

    // --- bank, images, signatory, footer ------------------------------------------

    @Test
    fun `no bank details at all means no bank block`() {
        val none = company.copy(bankName = "", bankBranch = "Test Branch", bankAcc = "", bankIfsc = "", upi = "")
        assertEquals(emptyList<DocRow>(), build(settings = none).bank)
    }

    @Test
    fun `a UPI id alone still prints the block, with the firm's name and the UPI id`() {
        val upiOnly = company.copy(bankName = "", bankBranch = "", bankAcc = "", bankIfsc = "")
        assertEquals(
            listOf(DocRow(B.BANK_NAME, "Test Gates & Shutters"), DocRow(B.UPI_ID, "test-only@invalid")),
            build(settings = upiOnly).bank
        )
    }

    @Test
    fun `the bank is printed without a branch when there is none`() {
        val noBranch = company.copy(bankBranch = "")
        assertEquals("Test Bank", build(settings = noBranch).bank.single { it.label == B.BANK }.value)
    }

    @Test
    fun `the images are the live settings' data URLs, and blank means none`() {
        val doc = build(settings = company.copy(logo = "", qr = "", signature = ""))
        assertEquals("", doc.letterhead.logo)
        assertEquals("", doc.qr)
        assertEquals("", doc.signature)
    }

    @Test
    fun `the images never come from snap, which V8C4 fills with text only`() {
        val snap = mapOf<String, Any?>("logo" to "data:image/png;base64,U05BUA==", "qr" to "data:image/png;base64,U05BUA==")
        val doc = build(record.copy(snapshot = snap))
        assertEquals(company.logo, doc.letterhead.logo)
        assertEquals(company.qr, doc.qr)
    }

    @Test
    fun `the signatory is for the firm, or alone when there is no firm name`() {
        assertEquals("Authorised signatory for Test Gates & Shutters", build().signatory)
        assertEquals("Authorised signatory", build(settings = company.copy(name = "")).signatory)
    }

    @Test
    fun `the acceptance names the customer, or the customer in words when there is no name`() {
        assertEquals("Accepted for Test Customer & Sons", build().acceptance)
        assertEquals("Accepted for the customer", build(record.copy(party = QuotationPartySnapshot())).acceptance)
    }

    @Test
    fun `the footer is pdfFooter and web, the empty parts dropped`() {
        assertEquals("example.invalid", build(settings = company.copy(pdfFooter = "")).footer)
        assertEquals("Synthetic test footer", build(settings = company.copy(web = "")).footer)
        assertEquals("", build(settings = company.copy(pdfFooter = "", web = "")).footer)
    }

    // --- the letterhead's source --------------------------------------------------

    @Test
    fun `snap first, then the live settings, field by field`() {
        // V8C4's names in snap: tag, addr, phones, bank{...}.
        val snap = mapOf<String, Any?>(
            "name" to "Snap Test Firm",
            "addr" to "2 Snap Street",
            "bank" to mapOf("acc" to "111111111111")
        )
        val doc = build(record.copy(snapshot = snap))
        assertEquals("Snap Test Firm", doc.letterhead.name)
        assertEquals(
            listOf(
                "Synthetic test data",
                "2 Snap Street",
                "+91 90000 00001",
                "quotes@example.invalid | example.invalid",
                "GSTIN 27AAAAA0000A1Z5 · PAN AAAAA0000A"
            ),
            doc.letterhead.lines
        )
        assertEquals("111111111111", doc.bank.single { it.label == B.ACCOUNT_NO }.value)
        assertEquals("Snap Test Firm", doc.bank.single { it.label == B.BANK_NAME }.value)
        assertEquals("Authorised signatory for Snap Test Firm", doc.signatory)
    }

    @Test
    fun `the GSTIN and PAN line prints whichever there is`() {
        assertEquals("PAN AAAAA0000A", build(settings = company.copy(gstin = "")).letterhead.lines.last())
        assertEquals("GSTIN 27AAAAA0000A1Z5", build(settings = company.copy(pan = "")).letterhead.lines.last())
        assertEquals("quotes@example.invalid", build(settings = company.copy(gstin = "", pan = "", web = "")).letterhead.lines.last())
    }

    @Test
    fun `no settings and no snap - an empty letterhead, everything named missing, nothing invented`() {
        val doc = build(settings = null)
        assertEquals(LetterheadBlock(logo = "", name = "", lines = emptyList()), doc.letterhead)
        assertEquals(
            listOf(
                Letterhead.FIRM_NAME, Letterhead.ADDRESS, Letterhead.PHONE, Letterhead.EMAIL,
                Letterhead.GSTIN, Letterhead.PAN, Letterhead.BANK_DETAILS
            ),
            doc.missing
        )
        assertEquals(emptyList<DocRow>(), doc.bank)
        assertEquals(emptyList<String>(), doc.conditions)
        assertEquals(emptyList<String>(), doc.notes + doc.terms)
        assertEquals("", doc.footer)
        assertEquals("", doc.qr + doc.signature)
        assertFalse(doc.details.any { it.label == B.VALID_FOR })
    }

    @Test
    fun `what is missing is named for the notice`() {
        val doc = build(settings = company.copy(gstin = "", bankAcc = ""))
        assertEquals(listOf(Letterhead.GSTIN, Letterhead.BANK_DETAILS), doc.missing)
    }

    // --- the file name ------------------------------------------------------------------

    @Test
    fun `the file name is the shared rule's, from the number and the customer`() {
        assertEquals(QuotationFileName.of(record.number, record.party.name), build().fileName)
        assertEquals("Quotation-Draft-Client.pdf", build(record.copy(number = "", party = QuotationPartySnapshot())).fileName)
    }
}
