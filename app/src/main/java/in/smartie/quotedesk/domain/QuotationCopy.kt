package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.QuotationRecord

/** What pressing Duplicate comes to on this device, before anything is copied. */
sealed interface CopyStart {

    /** An edit is open: Duplicate is not offered until it is saved or discarded. */
    data class EditOpen(val number: String) : CopyStart

    /** The quotation being worked on has lines: V8C4's question first. */
    data object AskFirst : CopyStart

    /** Nothing would be lost: the copy replaces an empty draft without asking. */
    data object Go : CopyStart
}

/** What opening a copy comes to, decided against the stored drafts. */
sealed interface CopyOpening {

    /** Store [draft] as the current one, removing [replacing] (the draft in progress) if any. */
    data class Show(val draft: QuoteDraft, val replacing: String?) : CopyOpening

    /** Nothing is copied; [message] is for the person. */
    data class Refused(val message: String) : CopyOpening
}

/**
 * **Duplicate** — V8C4's `duplicateQuote`, with its carriage restored (N5.10,
 * amendment C and the Owner's Q5).
 *
 * **Offered on every issued quotation, cancelled ones included**: cancel and
 * then duplicate is how a quotation is reissued. Never while an edit is open.
 *
 * **What a copy carries:** the lines, the party **with its link**, and the
 * tier — V8C4's list. **Not** installation or discount. **Carriage is
 * restored** to the transport amount and note, where V8C4 would leave its
 * Transportation line as an ordinary line and let a percentage discount grow
 * — a deliberate divergence (Q5), by the same recogniser an edit uses.
 * **GST is not carried** (the Owner, 2026-09-28): the copy starts at the
 * builder's default — Include GST on, the rate resolved from the products or
 * asked for.
 *
 * **The rates are the ones quoted, until somebody changes the tier.** Each
 * line's own tier is the copy's, so the catalogue arriving reprices nothing;
 * one tap on Dealer / Client reprices every catalogue-priced line to today's
 * catalogue. A contractor quotation's copy keeps its tier, which a new
 * quotation may not be issued at, so Finalise asks for Dealer or Client — the
 * lines are never relabelled at a tier they were not priced at.
 *
 * **Always a new id** — never the quotation's, which finalise would answer
 * "already issued", and never the replaced draft's.
 *
 * Pure: everything is decided from its arguments.
 */
object QuotationCopy {

    /** V8C4's question, asked when the quotation being worked on has lines. */
    const val REPLACE_QUESTION = "Replace the quotation you are working on with a copy of this one?"

    /**
     * Until N5.11. V8C4 says "…it takes a new number when you download, print
     * or share", which this app cannot do yet; its words come back with
     * N5.11 (recorded in `docs/PROJECT-STATUS.md`).
     */
    const val COPIED = "Copied into a new draft — it takes a new number when it is finalised"

    /**
     * Offered on every quotation the person can see — cancelled ones
     * included — to anyone who may quote.
     */
    fun offered(member: Member): Boolean = Permissions.canQuote(member)

    /** What pressing Duplicate would come to, given this device's [drafts]. */
    fun start(drafts: QuoteDrafts): CopyStart {
        drafts.drafts.firstOrNull { it.isEdit }?.editOf?.let { return CopyStart.EditOpen(it.number) }
        return if (drafts.current?.isEmpty == false) CopyStart.AskFirst else CopyStart.Go
    }

    /** [record] copied into a new draft under [newId]. See the class KDoc for what is carried. */
    fun copyOf(
        record: QuotationRecord,
        at: Long,
        newId: String,
        newLineId: () -> String = { Keys.generateId(QuoteDraft.LINE_PREFIX) }
    ): QuoteDraft {
        require(newId.isNotBlank() && newId != record.id) { "A copy needs an id of its own" }
        val rebuilt = QuotationEdit.draftFrom(record, at, newLineId)
        return QuoteDraft(
            id = newId,
            tier = rebuilt.tier,
            lines = rebuilt.lines,
            partyId = rebuilt.partyId,
            party = rebuilt.party,
            transport = rebuilt.transport,
            transportNote = rebuilt.transportNote,
            updatedAt = at,
            // Installation and the discount are not copied, so neither is a
            // fault in reading them; an unreadable tier still is.
            faults = rebuilt.faults.filter { it == DraftFault.TIER }.toSet(),
            copiedFrom = CopyOrigin(number = record.number, at = record.at)
        )
    }

    /**
     * Opening a copy of [record] against the drafts [stored] on this device:
     * refused while an edit is open, else the copy replaces the draft in
     * progress (the person has answered [REPLACE_QUESTION], or it was empty).
     */
    fun opening(
        stored: QuoteDrafts,
        record: QuotationRecord,
        at: Long,
        newId: String,
        newLineId: () -> String = { Keys.generateId(QuoteDraft.LINE_PREFIX) }
    ): CopyOpening {
        stored.drafts.firstOrNull { it.isEdit }?.editOf?.let {
            return CopyOpening.Refused(QuotationEdit.finishEditFirst(it.number))
        }
        val replacing = stored.current?.id
        require(newId != replacing) { "A copy never takes the replaced draft's id" }
        return CopyOpening.Show(copyOf(record, at, newId, newLineId), replacing)
    }
}
