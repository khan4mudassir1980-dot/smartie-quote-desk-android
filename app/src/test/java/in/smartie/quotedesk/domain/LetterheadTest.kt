package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.CompanySettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The letterhead's source, field by field (N5.11 commit 2): the quotation's
 * `snap` first — under V8C4's own names — then the live settings, never a
 * blank letterhead and never an invented one. Synthetic values only.
 */
class LetterheadTest {

    private val live = CompanySettings(
        name = "Live Firm", tagline = "Live tagline", address = "Live address",
        phone = "+91 90000 00001", email = "live@example.invalid", web = "live.example.invalid",
        gstin = "27AAAAA0000A1Z5", pan = "AAAAA0000A",
        bankName = "Live Bank", bankBranch = "Live Branch", bankAcc = "111111111111",
        bankIfsc = "LIVE0000001", upi = "live@invalid",
        validityDays = 15, payTerms = "Live pay terms", warranty = "Live warranty",
        pdfFooter = "Live footer",
        logo = "data:image/png;base64,LOGO", qr = "data:image/png;base64,QR",
        signature = "data:image/png;base64,SIGN",
        terms = listOf("Live term."), notes = listOf("Live note.")
    )

    // V8C4's snap, under its own names (recorded under the N5.9a answers).
    private val snap = mapOf(
        "name" to "Snap Firm", "tag" to "Snap tagline", "addr" to "Snap address",
        "phones" to "+91 90000 00009", "email" to "snap@example.invalid", "web" to "snap.example.invalid",
        "gstin" to "29BBBBB1111B1Z5", "pan" to "BBBBB1111B",
        "bank" to mapOf(
            "name" to "Snap Bank", "branch" to "Snap Branch", "acc" to "222222222222",
            "ifsc" to "SNAP0000002", "upi" to "snap@invalid"
        ),
        "terms" to listOf("Snap term."), "notes" to listOf("Snap note."),
        "validityDays" to 30L, "gstPct" to 18L, "payTerms" to "Snap pay terms",
        "warranty" to "Snap warranty", "pdfFooter" to "Snap footer",
        // V8C4 never puts images in snap; one here must still not be used.
        "logo" to "data:image/png;base64,STALE"
    )

    @Test
    fun `snap first, under V8C4's names`() {
        val d = Letterhead.resolve(snap, live)
        assertEquals("Snap Firm", d.name)
        assertEquals("Snap tagline", d.tagline)
        assertEquals("Snap address", d.address)
        assertEquals("+91 90000 00009", d.phone)
        assertEquals("snap@example.invalid", d.email)
        assertEquals("snap.example.invalid", d.web)
        assertEquals("29BBBBB1111B1Z5", d.gstin)
        assertEquals("BBBBB1111B", d.pan)
        assertEquals("Snap Bank", d.bankName)
        assertEquals("Snap Branch", d.bankBranch)
        assertEquals("222222222222", d.bankAcc)
        assertEquals("SNAP0000002", d.bankIfsc)
        assertEquals("snap@invalid", d.upi)
        assertEquals(30, d.validityDays)
        assertEquals("Snap pay terms", d.payTerms)
        assertEquals("Snap warranty", d.warranty)
        assertEquals("Snap footer", d.pdfFooter)
        assertEquals(listOf("Snap term."), d.terms)
        assertEquals(listOf("Snap note."), d.notes)
    }

    @Test
    fun `the images always come from the live settings`() {
        val d = Letterhead.resolve(snap, live)
        assertEquals("data:image/png;base64,LOGO", d.logo)
        assertEquals("data:image/png;base64,QR", d.qr)
        assertEquals("data:image/png;base64,SIGN", d.signature)
    }

    @Test
    fun `a field snap lacks falls back to the live one, field by field`() {
        val partial = mapOf("name" to "Snap Firm", "bank" to mapOf("acc" to "222222222222"), "terms" to emptyList<String>())
        val d = Letterhead.resolve(partial, live)
        assertEquals("Snap Firm", d.name)
        assertEquals("Live address", d.address)
        assertEquals("222222222222", d.bankAcc)
        assertEquals("Live Bank", d.bankName)
        assertEquals("LIVE0000001", d.bankIfsc)
        assertEquals(15, d.validityDays)
        assertEquals(listOf("Live term."), d.terms)
    }

    @Test
    fun `no snap at all — the live settings, never a blank letterhead`() {
        val d = Letterhead.resolve(emptyMap(), live)
        assertEquals("Live Firm", d.name)
        assertEquals("Live address", d.address)
        assertEquals("Live Bank", d.bankName)
        assertEquals(listOf("Live note."), d.notes)
    }

    @Test
    fun `a blank in snap is not a value`() {
        val d = Letterhead.resolve(mapOf("name" to "  ", "gstin" to "∅"), live)
        assertEquals("Live Firm", d.name)
        assertEquals("27AAAAA0000A1Z5", d.gstin)
    }

    @Test
    fun `nothing is invented when neither holds a field`() {
        val d = Letterhead.resolve(emptyMap(), null)
        assertEquals(CompanyDetails(), d)
    }

    @Test
    fun `the notice names the identity and bank fields printed without`() {
        assertEquals(emptyList<String>(), Letterhead.missing(Letterhead.resolve(emptyMap(), live)))
        assertEquals(
            listOf(
                Letterhead.FIRM_NAME, Letterhead.ADDRESS, Letterhead.PHONE, Letterhead.EMAIL,
                Letterhead.GSTIN, Letterhead.PAN, Letterhead.BANK_DETAILS
            ),
            Letterhead.missing(CompanyDetails())
        )
        val noGstinNoAccount = Letterhead.resolve(emptyMap(), live.copy(gstin = "", bankAcc = ""))
        assertEquals(listOf(Letterhead.GSTIN, Letterhead.BANK_DETAILS), Letterhead.missing(noGstinNoAccount))
        // Optional content is omitted, not called missing.
        val bare = live.copy(tagline = "", web = "", logo = "", terms = emptyList(), notes = emptyList(), upi = "")
        assertEquals(emptyList<String>(), Letterhead.missing(Letterhead.resolve(emptyMap(), bare)))
    }
}
