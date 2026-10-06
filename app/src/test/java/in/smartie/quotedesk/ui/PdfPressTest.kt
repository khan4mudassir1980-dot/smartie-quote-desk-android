package `in`.smartie.quotedesk.ui

import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.repository.FakeQuotationStore
import `in`.smartie.quotedesk.data.repository.NUMBERING
import `in`.smartie.quotedesk.data.repository.QuotationWriteRepository
import `in`.smartie.quotedesk.data.repository.SERVER_TIME
import `in`.smartie.quotedesk.domain.CompanyState
import `in`.smartie.quotedesk.domain.EditOrigin
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PdfNotice
import `in`.smartie.quotedesk.domain.QuotationDocument
import `in`.smartie.quotedesk.domain.QuotationDocumentBuilder
import `in`.smartie.quotedesk.domain.QuotationPdfMaker
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.Rendered
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.products.GateOutcome
import `in`.smartie.quotedesk.ui.products.PdfPress
import `in`.smartie.quotedesk.ui.products.PdfPress.Pressed
import `in`.smartie.quotedesk.ui.products.QuoteFinaliser
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download, Print or WhatsApp on the draft (N5.11 commit 9): the gate, then
 * the PDF of the quotation as the server holds it. Synthetic only.
 */
class PdfPressTest {

    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    private val loaded = CompanyState.Loaded(null)
    private val draft = QuoteDraft(id = "qd_1", party = QuotationPartySnapshot(name = "Test Customer"), gstPercent = 18.0)
        .addManual(id = "ln_1", title = "Test visit", rate = 1_000.0)
    private val issued = QuotationRecord(id = "qd_1", number = "TEST/QD/2026-27/009", serverAt = SERVER_TIME)

    private val rendered = mutableListOf<QuotationDocument>()
    private val maker = QuotationPdfMaker(
        render = { document -> rendered += document; Rendered(document.fileName, emptyList()) },
        describe = { it.message ?: "unknown" },
        zone = { ist }
    )

    private var gateCalls = 0
    private var issuedSeen = 0

    private fun press(
        gate: GateOutcome = GateOutcome.Finalised("TEST/QD/2026-27/009", "Finalised as TEST/QD/2026-27/009"),
        stored: suspend (String) -> QuotationRecord? = { issued },
        company: CompanyState = loaded,
        on: QuoteDraft = draft
    ) = runBlocking {
        PdfPress(maker, gate = { gateCalls++; gate }, stored = stored)
            .press(on, company, emptyList(), onIssued = { issuedSeen++ })
    }

    @Test
    fun `settings never loaded are refused before the gate - no number is taken`() {
        assertEquals(Pressed.NotIssued(PdfNotice.NOT_LOADED), press(company = CompanyState.NotLoaded))
        assertEquals(0, gateCalls)
    }

    @Test
    fun `an edit draft is refused before the gate`() {
        val edit = draft.copy(editOf = EditOrigin(quotationId = "qd_9", number = "TEST/QD/2026-27/009"))
        assertEquals(Pressed.NotIssued(PdfPress.EDIT_FIRST), press(on = edit))
        assertEquals(0, gateCalls)
    }

    @Test
    fun `issued, the PDF is made from the stored quotation, and the screen hears when the number is in hand`() {
        val pressed = press() as Pressed.Ready
        assertEquals("Finalised as TEST/QD/2026-27/009", pressed.finalised)
        assertEquals("TEST/QD/2026-27/009", pressed.pdf.number)
        assertEquals(1, issuedSeen)
        assertEquals(
            QuotationDocumentBuilder.date(SERVER_TIME, ist),
            rendered.single().details.single { it.label == QuotationDocumentBuilder.DATE }.value
        )
    }

    @Test
    fun `stopped by the gate, its words are shown and no PDF is made`() {
        assertEquals(Pressed.NotIssued("Not finalised — test."), press(gate = GateOutcome.NotFinalised("Not finalised — test.")))
        assertTrue(rendered.isEmpty())
        assertEquals(0, issuedSeen)
    }

    @Test
    fun `the zero-rate Cancel and a second press say nothing`() {
        assertEquals(Pressed.Quiet, press(gate = GateOutcome.Cancelled))
        assertEquals(Pressed.Quiet, press(gate = GateOutcome.AlreadyRunning))
        assertTrue(rendered.isEmpty())
    }

    @Test
    fun `issued but not read back - the number stands, and the person is told where to get the PDF`() {
        val pressed = press(stored = { null })
        assertEquals(
            Pressed.IssuedWithoutPdf("Finalised as TEST/QD/2026-27/009", PdfPress.notReadBack("TEST/QD/2026-27/009")),
            pressed
        )
        assertEquals(
            Pressed.IssuedWithoutPdf("Finalised as TEST/QD/2026-27/009", PdfPress.notReadBack("TEST/QD/2026-27/009")),
            press(stored = { throw IllegalStateException("unavailable") })
        )
    }

    @Test
    fun `end to end on the fake store - the first PDF after Finalise prints the server's date, not the phone's`() {
        val clock = 1_760_000_000_000L // 9 Oct 2025, the phone's
        val store = FakeQuotationStore(
            mutableMapOf(NUMBERING to mapOf<String, Any?>("prefix" to "SIE/QD", "fy" to "2025-26", "next" to 9, "pad" to 3))
        )
        val repository = QuotationWriteRepository(store, now = { clock })
        val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)
        val gate = QuoteFinaliser(
            online = { true },
            finalise = { repository.finalise(manager, it, emptyList(), null) },
            retire = {},
            confirmZeroRates = { true },
            describe = { it.message ?: "unknown" }
        )

        val outcome = runBlocking {
            PdfPress(maker, gate = { gate.ensureFinalised(it, null) }, stored = repository::stored)
                .press(draft, loaded, emptyList())
        }
        val pressed = outcome as? Pressed.Ready ?: throw AssertionError("not ready: $outcome")

        assertEquals("SIE/QD/2025-26/009", pressed.pdf.number)
        val printed = rendered.single().details.single { it.label == QuotationDocumentBuilder.DATE }.value
        assertEquals(QuotationDocumentBuilder.date(SERVER_TIME, ist), printed)
        assertTrue(printed != QuotationDocumentBuilder.date(clock, ist))
    }
}
