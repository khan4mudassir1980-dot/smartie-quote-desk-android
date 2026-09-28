package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationDiscountRecord
import `in`.smartie.quotedesk.data.model.QuotationInstallationRecord
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.QuotingRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import kotlin.math.abs

/** What saving an edit comes to, decided before anything reaches Firestore. */
sealed interface EditPlan {

    /**
     * The fields of **one `update()`, never a `set()`**. [DeleteField] removes
     * a key — an installation or a discount taken off, or a link that no
     * longer holds. Every key is in [QuotationEdit.EDITABLE].
     */
    data class Update(
        val quotationId: String,
        val number: String,
        val fields: Map<String, Any?>
    ) : EditPlan

    /**
     * **This very edit is already stored** — its acknowledgement was lost and
     * the person pressed Save again. Nothing is written; the answer is
     * "saved", as finalise's read-first answers a lost issue. Without it the
     * retry would be told the quotation "was changed by" — themselves.
     */
    data class AlreadySaved(val quotationId: String, val number: String) : EditPlan

    /** The edit must not be written; [message] is for the person. */
    data class Refused(val message: String) : EditPlan

    /** The stored quotation moved since the edit was opened. */
    data class Conflict(val conflict: EditConflict) : EditPlan
}

/**
 * Why an edit cannot be saved over what is stored now — the loser's side of
 * two people editing one quotation (hazard 3). The words are this app's own:
 * V8C4 has no edit.
 */
sealed interface EditConflict {
    val number: String

    /** Somebody saved an edit after this one was opened. */
    data class Changed(override val number: String, val by: String, val at: Long) : EditConflict

    /** Somebody cancelled it after this one was opened. */
    data class Cancelled(override val number: String, val by: String) : EditConflict

    /** It is not there any more. */
    data class Gone(override val number: String) : EditConflict

    /** The sentence the person sees; [timeText] formats [Changed.at]. */
    fun message(timeText: (Long) -> String): String = when (this) {
        is Changed ->
            "Not saved — $number was changed by ${by.ifBlank { SOMEBODY }} at ${timeText(at)} " +
                "after you opened it. Your changes are still here: discard them and edit again."
        is Cancelled ->
            "Not saved — $number was cancelled by ${by.ifBlank { SOMEBODY }}. " +
                "A cancelled quotation cannot be edited."
        is Gone -> "Not saved — $number could not be found."
    }

    private companion object {
        const val SOMEBODY = "somebody else"
    }
}

/**
 * Editing a finalised quotation — **new behaviour, not a port**: V8C4 cannot
 * edit one at all. The Owner's decision is the whole specification: the
 * creator, and an Owner or Administrator on anyone's; the **same number
 * overwritten**; no revision copy; a "Last edited by" stamp; `snap` never
 * re-frozen. What may change is the Owner's list, [EDITABLE].
 *
 * ## Never through the finalise gate
 *
 * Finalise reads the quotation first and, finding it, answers "already
 * issued" — so an edit sent that way would be thrown away without a word and
 * the person told "Finalised as X". An edit is its own write: [plan] builds
 * the fields of one `update()`, and `QuotationWriteRepository.edit` sends
 * them. It never reads or moves the counter.
 *
 * ## Rebuilding a draft from what is stored — [draftFrom]
 *
 * An edit writes every money figure back, so anything the rebuild gets wrong
 * is a silent change to a quotation a customer holds. Four were found and
 * are handled here:
 *
 * - **Carriage** is a line in `lines` (V8C4's `Transportation`, `manual:
 *   false`, no product). Rebuilt as an ordinary line it would count as
 *   products, and a percentage discount — which stops before transport — would
 *   grow. It comes back as the transport amount and note.
 * - **An area line's minimum is not stored**; `sqft` is the chargeable area
 *   after it. Rebuilt without one, the area would shrink and the line's
 *   quantity fall. The minimum is restored as the stored `sqft` whenever that
 *   exceeds the measured area rounded up; the geometry is dropped (a plain
 *   line, its stored quantity kept) whenever it does not add up.
 * - **A rate V8C4 had typed by hand** differs from its `origRate`; it comes
 *   back typed-by-hand, so a change of tier keeps it.
 * - **What the reader defaults**, an edit may not: an unknown tier is a fault
 *   recovered to Client, an unreadable installation or discount is a fault and
 *   is dropped, and an absent GST rate is "not set" — never 0%.
 *
 * Pure: everything is decided from its arguments.
 */
object QuotationEdit {

    /**
     * Every key an edit may write — **the rule's list** (`qnEditKeys` in
     * `firestore.rules`), which the emulator test "an edit cannot touch …"
     * pins from the other side. Change one, change both.
     */
    val EDITABLE: List<String> = listOf(
        "lines", "party", "partyId", "tier", "tierName", "gst", "gstPct",
        "subtotal", "total", "install", "disc", "discBase",
        "lastEditedBy", "lastEditedByUid", "lastEditedAt", "rev"
    )

    const val NOT_AN_EDIT = "This quotation is not being edited"
    const val NOT_YOURS =
        "Only the person who issued this quotation, an Owner or an Administrator can edit it"

    /** The draft an edit opens: the stored quotation, rebuilt. See the class KDoc. */
    fun draftFrom(
        record: QuotationRecord,
        at: Long,
        newLineId: () -> String = { Keys.generateId(QuoteDraft.LINE_PREFIX) }
    ): QuoteDraft {
        val faults = mutableSetOf<DraftFault>()
        val issuedTier = RateTierV2.entries.firstOrNull { it.wireValue.equals(record.storedTier, ignoreCase = true) }
        val tier = issuedTier ?: RateTierV2.CLIENT.also { faults += DraftFault.TIER }
        val carriage = carriageOf(record.lines)
        val installation = record.installation?.let { stored ->
            installationOf(stored) ?: null.also { faults += DraftFault.INSTALLATION }
        }
        val discount = record.discount?.let { stored ->
            discountOf(stored) ?: null.also { faults += DraftFault.DISCOUNT }
        }
        return QuoteDraft(
            id = record.id,
            tier = tier,
            lines = record.lines.filterNot { it === carriage }.map { draftLineOf(it, tier, newLineId()) },
            partyId = record.partyId,
            party = record.party,
            transport = carriage?.amount ?: 0.0,
            transportNote = carriage?.spec.orEmpty(),
            installation = installation,
            discount = discount,
            gstEnabled = record.gstEnabled,
            gstPercent = record.storedGstPercent,
            updatedAt = at,
            faults = faults,
            editOf = EditOrigin(
                quotationId = record.id,
                number = record.number,
                revision = record.revision,
                tier = issuedTier,
                discountAmount = record.discount?.amount,
                discountBase = record.discountBase
            )
        )
    }

    /**
     * What saving [edited] over [stored] comes to — [stored] being the
     * quotation read **inside the edit's transaction**, null when it is gone.
     * [quoting] is `/teamSettings/quoting` read in the same transaction;
     * [customers] the list the screen holds, for the link.
     */
    fun plan(
        edited: QuoteDraft,
        member: Member,
        quoting: QuotingRecord?,
        stored: QuotationRecord?,
        customers: List<PartyRecord>,
        at: Long
    ): EditPlan {
        val origin = edited.editOf ?: return EditPlan.Refused(NOT_AN_EDIT)
        if (stored == null) return EditPlan.Conflict(EditConflict.Gone(origin.number))
        if (stored.status.equals(STATUS_CANCELLED, ignoreCase = true)) {
            return EditPlan.Conflict(EditConflict.Cancelled(stored.number, stored.cancelledBy))
        }
        // Before any refusal: a quotation that moved is the news, whatever
        // else this edit might be refused for — unless what it moved to is
        // this edit itself, landed by an attempt whose answer was lost.
        if (stored.revision != origin.revision) {
            return if (alreadyLanded(edited, member, stored, origin, customers)) {
                EditPlan.AlreadySaved(stored.id, stored.number)
            } else {
                EditPlan.Conflict(EditConflict.Changed(stored.number, stored.lastEditedBy, stored.lastEditedAt))
            }
        }
        if (!Permissions.canEditQuotation(member, stored)) return EditPlan.Refused(NOT_YOURS)

        val resolved = edited.copy(partyId = QuoteParty.keptLink(edited.party, edited.partyId, customers))
        val totals = resolved.totals()
        // The cap stops an edit only when the discount goes up — against the
        // stored document, never the opened copy (the Owner, 2026-09-28).
        val raised = resolved.discount != null && QuoteDiscount.raised(
            beforeAmount = stored.discount?.amount,
            beforeBase = stored.discountBase,
            afterAmount = totals.discount,
            afterBase = totals.discountBase
        )
        val cap = if (raised) QuoteDiscount.capFor(member, quoting) else QuoteMath.NO_CAP
        resolved.refusal(cap)?.let { return EditPlan.Refused(it) }

        val lines = resolved.lines.map { line ->
            line.toRecord() ?: return EditPlan.Refused(QuoteDraft.LINE_NEEDS_RATE)
        } + listOfNotNull(QuotationWrite.transportLine(resolved, totals))

        val fields = buildMap<String, Any?> {
            put("lines", lines.map(QuotationWrite::lineData))
            put("party", QuotationWrite.partyData(resolved.party))
            when {
                resolved.partyId.isNotBlank() -> put("partyId", resolved.partyId)
                stored.partyId.isNotBlank() -> put("partyId", DeleteField)
            }
            put("tier", resolved.tier.wireValue)
            put("tierName", resolved.tier.label)
            put("gst", resolved.gstEnabled)
            put("gstPct", totals.gstPercent)
            put("subtotal", totals.subtotal)
            put("total", totals.total)
            val install = QuotationWrite.installationData(resolved, totals)
            when {
                install != null -> put("install", install)
                stored.installation != null -> put("install", DeleteField)
            }
            val disc = QuotationWrite.discountData(resolved, totals)
            when {
                disc != null -> {
                    put("disc", disc)
                    put("discBase", totals.discountBase)
                }
                stored.discount != null || stored.discountBase != null -> {
                    put("disc", DeleteField)
                    put("discBase", DeleteField)
                }
            }
            put("lastEditedBy", member.name)
            put("lastEditedByUid", member.uid)
            put("lastEditedAt", at)
            put("rev", origin.revision + 1)
        }
        return EditPlan.Update(stored.id, stored.number, fields)
    }

    private const val STATUS_CANCELLED = "Cancelled"

    /**
     * Whether [stored] is this edit, already written: one revision on from
     * the opening, stamped by this person, and holding exactly what this
     * draft would write — compared as quotation content (every line as it
     * would be stored, carriage, installation, discount, GST, the party and
     * its link), rebuilt through [draftFrom] so a stored figure and a typed
     * one are read the same way.
     */
    private fun alreadyLanded(
        edited: QuoteDraft,
        member: Member,
        stored: QuotationRecord,
        origin: EditOrigin,
        customers: List<PartyRecord>
    ): Boolean {
        if (stored.revision != origin.revision + 1 || stored.lastEditedByUid != member.uid) return false
        val written = draftFrom(stored, stored.lastEditedAt) { "" }
        val mine = edited.copy(partyId = QuoteParty.keptLink(edited.party, edited.partyId, customers))
        return contentOf(written) == contentOf(mine)
    }

    /**
     * A draft as the quotation it would store — ids and timestamps aside, and
     * every figure as stored: the party trimmed as `partyData` writes it,
     * carriage in whole rupees, its note only when there is carriage to
     * carry it.
     */
    private fun contentOf(draft: QuoteDraft): List<Any?> {
        val totals = draft.totals()
        return listOf(
            draft.tier, QuotationWrite.partyData(draft.party), draft.partyId,
            if (totals.transport > 0.0) draft.transportNote.trim() else "",
            draft.installation, draft.discount, draft.gstEnabled, draft.gstPercent,
            totals, draft.lines.map { it.toRecord() }
        )
    }

    /**
     * V8C4's carriage line, when there is exactly one: `Transportation`, no
     * product, not typed by hand, one of it. Its unit is V8C4's `""` — which
     * the reader, skipping a blank string, returns as its default `each`, so
     * both are accepted. Two such lines is not a shape V8C4 writes; neither is
     * taken, and both stay lines.
     */
    internal fun carriageOf(lines: List<QuotationLineRecord>): QuotationLineRecord? = lines
        .filter {
            it.title == QuotationWrite.TRANSPORT_TITLE && it.key.isBlank() && !it.manual &&
                it.quantity == 1.0 && (it.unit == QuotationWrite.TRANSPORT_UNIT || it.unit == ProductUnit.EACH)
        }
        .singleOrNull()

    private fun draftLineOf(line: QuotationLineRecord, tier: RateTierV2, id: String): DraftLine = DraftLine(
        id = id,
        title = line.title,
        key = line.key,
        spec = line.spec,
        unit = line.unit,
        quantity = line.quantity,
        rate = line.rate,
        originalRate = line.originalRate ?: line.rate,
        // The line's own tier is the quotation's, so nothing is "out of step"
        // and nothing reprices when the catalogue arrives.
        tier = tier,
        // A hand-typed rate, in V8C4's terms, is one that left its origRate.
        rateEdited = line.manual || (line.originalRate != null && line.originalRate != line.rate),
        manual = line.manual,
        area = areaOf(line)
    )

    /**
     * The opening, when the stored geometry adds up — else null, and the line
     * is edited as a plain line with its stored quantity (`docs/N5-plan.md`,
     * the line table: never re-derived by parsing the spec).
     */
    internal fun areaOf(line: QuotationLineRecord): AreaLine? {
        val geometry = line.geometry ?: return null
        val unit = DimensionUnit.entries.firstOrNull { it.wireValue.equals(geometry.unit.trim(), ignoreCase = true) }
            ?: return null
        // `qty` carries the rupees; geometry that disagrees with it is not
        // the opening this line charges for.
        if (abs(line.quantity - geometry.sqftPerDoor * geometry.count) > TOLERANCE) return null
        val measured = QuoteArea.chargeableSqft(
            AreaLine(geometry.width, geometry.height, unit, geometry.count)
        )
        val minimum = when {
            geometry.sqftPerDoor > measured + TOLERANCE -> geometry.sqftPerDoor
            abs(geometry.sqftPerDoor - measured) <= TOLERANCE -> null
            else -> return null
        }
        val area = AreaLine(geometry.width, geometry.height, unit, geometry.count, minimum)
        return area.takeIf { QuoteArea.refusal(it) == null }
    }

    private fun installationOf(stored: QuotationInstallationRecord): Installation? {
        val mode = InstallationMode.entries.firstOrNull { it.wireValue.equals(stored.mode, ignoreCase = true) }
            ?: return null
        val rate = stored.rate ?: return null
        val basis = stored.basis ?: if (mode == InstallationMode.FIXED) 0.0 else return null
        return Installation(mode, rate, basis)
    }

    private fun discountOf(stored: QuotationDiscountRecord): Discount? {
        val kind = DiscountKind.entries.firstOrNull { it.wireValue.equals(stored.kind, ignoreCase = true) }
            ?: return null
        return Discount(kind, stored.value ?: return null)
    }

    private const val TOLERANCE = 1e-6
}
