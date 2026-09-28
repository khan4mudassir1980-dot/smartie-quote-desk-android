package `in`.smartie.quotedesk.ui

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toQuotationRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.repository.EditOutcome
import `in`.smartie.quotedesk.data.repository.FakeQuotationStore
import `in`.smartie.quotedesk.data.repository.FinaliseOutcome
import `in`.smartie.quotedesk.data.repository.NUMBERING
import `in`.smartie.quotedesk.data.repository.QuotationWriteRepository
import `in`.smartie.quotedesk.domain.EditConflict
import `in`.smartie.quotedesk.domain.EditOrigin
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.QuotationEdit
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.products.GateOutcome
import `in`.smartie.quotedesk.ui.products.QuoteFinaliser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate and an edit of an issued quotation (N5.10 commit 5).
 *
 * **`ensureFinalised` must never take an edit draft**: its read-first would
 * find the quotation, answer "already issued", retire the draft and say
 * "Finalised as X" — every edit discarded without a word. **`saveEdit`** runs
 * the same checks and saves through the edit's own path. `QuoteFinaliserTest`
 * is unchanged beside this file: that is the pin that sharing the checks
 * moved nothing for finalise.
 *
 * `runBlocking`, so it also runs in the local JVM sweep.
 */
class QuoteEditGateTest {

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)

    private val editing = QuoteDraft(
        id = "qd_1",
        party = QuotationPartySnapshot(name = "Walk-in Builders"),
        gstPercent = 18.0,
        editOf = EditOrigin(quotationId = "qd_1", number = "SIE/QD/2025-26/009")
    ).addManual(id = "ln_1", title = "Site visit", rate = 1_000.0)

    private val finalised = mutableListOf<String>()
    private val retired = mutableListOf<String>()
    private val saved = mutableListOf<String>()
    private val closed = mutableListOf<String>()
    private val logged = mutableListOf<Throwable>()

    private fun gate(
        online: Boolean = true,
        capMillis: Long = QuoteFinaliser.CAP_MILLIS,
        closeEdit: suspend (String) -> Unit = { closed += it },
        edit: suspend (QuoteDraft) -> EditOutcome = { EditOutcome.Saved("SIE/QD/2025-26/009") }
    ) = QuoteFinaliser(
        online = { online },
        finalise = { draft ->
            finalised += draft.id
            FinaliseOutcome.AlreadyIssued(QuotationRecord(id = draft.id, number = "SIE/QD/2025-26/009"))
        },
        retire = { retired += it },
        confirmZeroRates = { true },
        describe = { it.message ?: "Something went wrong" },
        log = { logged += it },
        capMillis = capMillis,
        edit = { draft -> saved += draft.id; edit(draft) },
        closeEdit = closeEdit,
        timeText = { "11:20" }
    )

    @Test
    fun `Finalise on an edit draft never writes and never retires`() = runBlocking {
        // R14. Without the refusal the finalise here answers "already
        // issued", the draft is retired, and the person reads "Finalised as".
        val outcome = gate().ensureFinalised(editing, capPercent = null)

        assertEquals(
            GateOutcome.NotFinalised(QuoteFinaliser.editNotFinalised("SIE/QD/2025-26/009")),
            outcome
        )
        assertTrue("finalised $finalised", finalised.isEmpty())
        assertTrue("retired $retired", retired.isEmpty())
    }

    @Test
    fun `Save changes saves through the edit path, closes the edit, and never finalises`() = runBlocking {
        val outcome = gate().saveEdit(editing, capPercent = null)

        assertEquals(GateOutcome.Saved("SIE/QD/2025-26/009", "Saved changes to SIE/QD/2025-26/009"), outcome)
        assertEquals(listOf("qd_1"), saved)
        assertEquals(listOf("qd_1"), closed)
        assertTrue(finalised.isEmpty())
        assertTrue(retired.isEmpty())
    }

    @Test
    fun `offline, nothing is sent and the edit stays`() = runBlocking {
        val outcome = gate(online = false).saveEdit(editing, capPercent = null)

        assertEquals(GateOutcome.NotSaved(QuoteFinaliser.EDIT_OFFLINE), outcome)
        assertTrue(saved.isEmpty())
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `the draft's own refusal stops a save as it stops a finalise`() = runBlocking {
        val outcome = gate().saveEdit(editing.copy(gstPercent = null), capPercent = null)

        assertEquals(GateOutcome.NotSaved(QuoteDraft.GST_NOT_SET), outcome)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a malformed phone stops a save, prefixed as at finalise`() = runBlocking {
        val outcome = gate().saveEdit(editing.copy(party = editing.party.copy(phone = "12345")), capPercent = null)

        assertTrue(outcome.toString(), (outcome as GateOutcome.NotSaved).message.startsWith(QuoteFinaliser.CLIENT_PHONE))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a conflict is shown in the loser's words, and the edit stays`() = runBlocking {
        val outcome = gate(edit = {
            EditOutcome.Conflict(EditConflict.Changed("SIE/QD/2025-26/009", "Asha Nair", 1_760_300_000_000L))
        }).saveEdit(editing, capPercent = null)

        assertEquals(
            GateOutcome.NotSaved(
                "Not saved — SIE/QD/2025-26/009 was changed by Asha Nair at 11:20 after you opened it. " +
                    "Your changes are still here: discard them and edit again."
            ),
            outcome
        )
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `a refusal from the rules is reported, and the edit stays`() = runBlocking {
        val outcome = gate(edit = { EditOutcome.Refused(QuotationWriteRepository.EDIT_REFUSED) })
            .saveEdit(editing, capPercent = null)

        assertEquals(GateOutcome.NotSaved(QuoteFinaliser.notSaved(QuotationWriteRepository.EDIT_REFUSED)), outcome)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `a save that never answers ends at the cap and says a second press is safe`() = runBlocking {
        val outcome = gate(capMillis = 50, edit = { kotlinx.coroutines.awaitCancellation() })
            .saveEdit(editing, capPercent = null)

        assertEquals(
            GateOutcome.NotSaved(QuoteFinaliser.notSaved(QuoteFinaliser.TIMED_OUT, mayHaveLanded = true)),
            outcome
        )
    }

    @Test
    fun `saved even when closing the edit fails - the failure is logged`() = runBlocking {
        val outcome = gate(closeEdit = { throw IllegalStateException("disk full") }).saveEdit(editing, capPercent = null)

        assertTrue(outcome is GateOutcome.Saved)
        assertEquals("disk full", logged.single().message)
    }

    @Test
    fun `a draft that is not an edit is not saved as one`() = runBlocking {
        val outcome = gate().saveEdit(editing.copy(editOf = null), capPercent = null)

        assertEquals(GateOutcome.NotSaved(QuoteFinaliser.notSaved(QuotationEdit.NOT_AN_EDIT)), outcome)
        assertTrue(saved.isEmpty())
    }

    // --- through the real repository ------------------------------------------------------

    @Test
    fun `an edit whose answer was lost is answered as saved on the second press - never as a conflict with yourself`() =
        runBlocking {
            // R14b. The first save lands and its acknowledgement is lost; the
            // second finds revision 1, stamped by this person, holding exactly
            // this edit — and says "saved", writing nothing more.
            val store = FakeQuotationStore(mutableMapOf(NUMBERING to mapOf("prefix" to "SIE/QD", "fy" to "2025-26", "next" to 9, "pad" to 3)))
            val repository = QuotationWriteRepository(store, now = { 1_760_000_000_000L })
            repository.finalise(manager, editing.copy(editOf = null), emptyList(), null)
            val record = DocData("qd_1", store.docs.getValue("quotations/qd_1")).toQuotationRecord()
            val edit = QuotationEdit.draftFrom(record, 1_760_000_000_000L) { "ln_e" }
                .let { d -> d.setQuantity(d.lines.single().id, 4.0) }
            val gate = QuoteFinaliser(
                online = { true },
                finalise = { error("an edit is never finalised") },
                retire = { error("an edit is never retired") },
                confirmZeroRates = { true },
                describe = { it.message ?: "Something went wrong" },
                edit = { repository.edit(manager, it, emptyList()) },
                closeEdit = { closed += it }
            )

            store.loseNextResponse = true
            val first = gate.saveEdit(edit, capPercent = null)
            val second = gate.saveEdit(edit, capPercent = null)

            assertTrue(first.toString(), first is GateOutcome.NotSaved)
            assertEquals(GateOutcome.Saved("SIE/QD/2025-26/009", "Saved changes to SIE/QD/2025-26/009"), second)
            assertEquals(1, DocData("qd_1", store.docs.getValue("quotations/qd_1")).toQuotationRecord().revision)
            assertEquals(listOf("qd_1"), closed)
        }
}
