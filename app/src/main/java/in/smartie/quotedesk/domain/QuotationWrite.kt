package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.NumberingRecord
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.QuotingRecord

/** What finalising a draft comes to, decided before anything reaches Firestore. */
sealed interface QuotationPlan {

    /**
     * Both halves, for one transaction: the quotation, and the counter moved
     * on by exactly one. Neither is ever written without the other.
     */
    data class Write(
        val quotationId: String,
        val number: String,
        val quotation: Map<String, Any?>,
        /**
         * Merged into `/teamSettings/numbering`. **`next` and `lastIssued`
         * and nothing else**, because the issue branch of the rule is
         * `touched().hasOnly(['next','lastIssued'])`.
         */
        val counter: Map<String, Any?>
    ) : QuotationPlan

    /**
     * This draft's quotation already exists. **Nothing is written**, and
     * [number] is the one it was issued with — the answer a retry after a lost
     * response must give, rather than a second number.
     */
    data class AlreadyIssued(val quotationId: String, val number: String) : QuotationPlan

    /** The write must not be attempted; [message] is for the person. */
    data class Refused(val message: String) : QuotationPlan
}

/**
 * Turning a draft into a numbered quotation, in the shape V8C4 already reads.
 *
 * ## Why a duplicate call cannot burn a second number
 *
 * **The quotation's document id is the draft's own id**, which
 * `DevicePreferences.currentDraftId` mints once and keeps across process
 * death. Every attempt at one quotation therefore targets the same document,
 * and the transaction reads that document first: when it exists, [plan]
 * answers [QuotationPlan.AlreadyIssued] with the stored number and the counter
 * is not touched. That check comes **before every refusal**, because a
 * quotation that was issued stays issued even if the person's role, the cap or
 * the counter has changed since — refusing it then would tell somebody a
 * quotation the customer already holds did not happen.
 *
 * Had the id been minted here instead, each attempt would target a fresh
 * document, both would succeed, and one job would carry two numbers. The
 * rules are a second defence today — a `set` on an existing id is evaluated
 * as an update, which only cancel may make — **and N5.10 removes it** when it
 * widens that branch for edit. See `docs/PROJECT-STATUS.md`.
 *
 * ## Who the quotation is for
 *
 * The Owner's ruling of 2026-09-25: a party **name** is required, a saved
 * customer is not. When the draft names a saved customer, the party is
 * **re-resolved** from the `/customers` record the caller read, not trusted
 * from the draft, whose snapshot was taken whenever the customer was picked
 * and may be days stale. The site is kept from the draft, because the site is
 * where this job is and the customer record does not hold one. With no saved
 * customer the typed snapshot is written as it stands and `partyId` is
 * **absent** — there is nothing to look up, now or in N5.10.
 *
 * ## `snap` is frozen here, and only here
 *
 * It records what the terms, validity and bank block were **at issue**, and
 * N5.10 never re-freezes it. [plan] writes the map it is given exactly as
 * given and derives nothing into it. **What V8C4 puts in `snap{}` is not
 * recorded anywhere in this repository** — the only example is a test fixture
 * this project wrote itself — so its contents are the caller's to supply once
 * that is known, and it is an open question in `docs/PROJECT-STATUS.md`.
 *
 * ## What is written that V8C4 does not write
 *
 * `install`, `disc` and `discBase` (`docs/N5-plan.md`, the data shape), and an
 * area line's `w` / `h` / `dim` / `sqft` / `nos` — a convenience the PWA
 * strips on its next save, never the source of any amount
 * (`QuotationLineGeometry`).
 * On the counter, `lastIssued.src` is `"android"`, which the rule reads and
 * never requires.
 *
 * Pure: every figure and every refusal is decided from its arguments, so the
 * whole of it is unit-tested without Firebase.
 */
object QuotationWrite {

    /** The issuer marker. The rule bounds it at 16 characters. */
    const val SOURCE = "android"

    const val STATUS_FINALISED = "Finalised"

    /**
     * Transport is a **line**, not a field, because V8C4 pushes it into
     * `lines[]` under this title and counts it in the subtotal — and it is an
     * **ordinary** line, not a manual one. See [transportLine].
     */
    const val TRANSPORT_TITLE = "Transportation"

    /**
     * **Empty, as V8C4 stores it.** `normLine` sets `u = l.u || ""` and the
     * transport line passes none. The `no` a PWA page prints beside it is
     * `qLabel`'s display fallback (`l.u || "no"`), never a stored value —
     * which is where `3db056b`'s `"no"` came from, via the plan. The `lot`
     * in `fixtures/quotations.json` is invented and moves with N5.12.
     */
    const val TRANSPORT_UNIT = ""

    const val NOT_ALLOWED = "This account cannot issue quotations"
    const val NO_IDENTITY =
        "This draft could not be identified, so it cannot be issued safely - reopen it and try again"
    const val ID_TAKEN =
        "Another quotation already uses this draft's identity - nothing was issued"
    const val NUMBERING_NOT_SET =
        "Quotation numbering has not been set up - the Owner sets it in Settings"
    const val CUSTOMER_GONE =
        "The saved customer on this quotation could not be found - choose them again, or type the name"

    /**
     * What issuing [draft] comes to.
     *
     * [existing] is this draft's quotation document if it is already there —
     * read **first**, inside the transaction. [customer] is
     * `/customers/{draft.partyId}` as read now, and is ignored when the draft
     * names no saved customer. [counter] is `/teamSettings/numbering`, null
     * when it has never been seeded.
     */
    fun plan(
        draft: QuoteDraft,
        member: Member,
        quoting: QuotingRecord?,
        counter: NumberingRecord?,
        existing: QuotationRecord?,
        customer: PartyRecord?,
        snap: Map<String, Any?>,
        at: Long
    ): QuotationPlan {
        if (draft.id.isBlank()) return QuotationPlan.Refused(NO_IDENTITY)

        // Before every refusal: see the class KDoc.
        if (existing != null) {
            // Somebody else's document under this id is a collision, not our
            // quotation. Reporting its number as ours would let the caller
            // clear a draft that was never issued.
            if (existing.byUid != member.uid) return QuotationPlan.Refused(ID_TAKEN)
            return QuotationPlan.AlreadyIssued(draft.id, existing.number)
        }

        if (!Permissions.canQuote(member)) return QuotationPlan.Refused(NOT_ALLOWED)
        if (counter == null || !isIssuable(counter)) return QuotationPlan.Refused(NUMBERING_NOT_SET)

        val resolved = if (draft.partyId.isBlank()) {
            draft
        } else {
            draft.withParty(customer ?: return QuotationPlan.Refused(CUSTOMER_GONE))
        }

        val cap = QuoteDiscount.capFor(member, quoting)
        resolved.discount?.let { taken ->
            // "Not configured" and "configured at zero" are different
            // sentences; `refusal` below cannot tell them apart, so the
            // unconfigured case is answered first. A zero or negative
            // discount falls through to the ordinary gates.
            if (cap == null && taken.amountOn(resolved.discountBase) > 0.0) {
                return QuotationPlan.Refused(QuoteDiscount.CAP_NOT_SET)
            }
        }
        resolved.refusal(cap ?: 0.0)?.let { return QuotationPlan.Refused(it) }

        val totals = resolved.totals()
        val lines = resolved.lines.map { line ->
            line.toRecord() ?: return QuotationPlan.Refused(QuoteDraft.LINE_NEEDS_RATE)
        } + listOfNotNull(transportLine(resolved, totals))

        val number = Numbering.format(counter)
        return QuotationPlan.Write(
            quotationId = draft.id,
            number = number,
            quotation = quotationData(resolved, member, totals, lines, number, snap, at),
            counter = mapOf(
                "next" to counter.next + 1,
                "lastIssued" to mapOf(
                    "no" to number,
                    "at" to at,
                    "by" to member.name,
                    "uid" to member.uid,
                    "src" to SOURCE
                )
            )
        )
    }

    /**
     * A counter a number can honestly be taken from. The reader defaults a
     * missing prefix or year to blank, and `Numbering.format` would quietly
     * drop a blank segment and issue `2025-26/009`.
     */
    private fun isIssuable(counter: NumberingRecord): Boolean =
        counter.prefix.isNotBlank() && counter.financialYear.isNotBlank() && counter.next >= 1

    /**
     * Carriage, exactly as V8C4 stores it — the Owner's reading of
     * `quoteLines()` and `normLine`, 2026-09-25:
     * `{t: "Transportation", s: <note>, u: "", qty: 1, rate: amt,
     * origRate: amt, k: null, manual: false, amt}`.
     *
     * **`manual` is false.** It is not typed by hand — it comes from the
     * transport box — and `true` would tag it "typed by hand" in V8C4 and in
     * this app's own detail screen alike. `3db056b` wrote `true`, following a
     * plan line that said "transport becomes a manual line"; corrected in
     * N5.9a commit 3b.
     */
    private fun transportLine(draft: QuoteDraft, totals: QuoteTotals): QuotationLineRecord? {
        if (totals.transport <= 0.0) return null
        return QuotationLineRecord(
            title = TRANSPORT_TITLE,
            spec = draft.transportNote.trim(),
            unit = TRANSPORT_UNIT,
            quantity = 1.0,
            // The rounded figure, so `qty × rate` is the stored amount.
            rate = totals.transport,
            originalRate = totals.transport,
            key = "",
            manual = false,
            amount = totals.transport
        )
    }

    private fun quotationData(
        draft: QuoteDraft,
        member: Member,
        totals: QuoteTotals,
        lines: List<QuotationLineRecord>,
        number: String,
        snap: Map<String, Any?>,
        at: Long
    ): Map<String, Any?> = buildMap {
        // The five the create rule checks: `id == id`, `no is string`,
        // `byUid == mine()`, `at is number`, `total is number`.
        put("id", draft.id)
        put("no", number)
        put("at", at)
        put("by", member.name)
        put("byUid", member.uid)
        put("tier", draft.tier.wireValue)
        put("tierName", draft.tier.label)
        if (draft.partyId.isNotBlank()) put("partyId", draft.partyId)
        put("party", partyData(draft.party))
        put("lines", lines.map(::lineData))
        put("gst", draft.gstEnabled)
        put("gstPct", totals.gstPercent)
        put("subtotal", totals.subtotal)
        put("total", totals.total)
        put("status", STATUS_FINALISED)
        put("snap", snap)
        draft.installation?.let { charge ->
            put(
                "install",
                mapOf(
                    "mode" to charge.mode.wireValue,
                    "rate" to charge.rate,
                    "amt" to totals.installation,
                    "basis" to charge.basis
                )
            )
        }
        // Only a discount actually taken. `discBase` exists so the rule can
        // check the cap, and is bounded there by `subtotal + disc.amt`.
        draft.discount?.takeIf { totals.discount > 0.0 }?.let { taken ->
            put(
                "disc",
                mapOf(
                    "kind" to taken.kind.wireValue,
                    "value" to taken.value,
                    "amt" to totals.discount
                )
            )
            put("discBase", totals.discountBase)
        }
    }

    private fun partyData(party: QuotationPartySnapshot): Map<String, Any?> = mapOf(
        "name" to party.name.trim(),
        "site" to party.site.trim(),
        "gstin" to party.gstin.trim(),
        "contact" to party.contact.trim(),
        "phone" to party.phone.trim(),
        "email" to party.email.trim(),
        "address" to party.address.trim(),
        "city" to party.city.trim()
    )

    /**
     * **V8C4's nine keys, on every line, always:** `t`, `s`, `u`, `qty`,
     * `rate`, `origRate`, `k`, `manual`, `amt` — the stored mapping at V8C4
     * 6223-6224 picks exactly these, so every line the PWA writes carries all
     * nine. Writing the same set makes a native line match the PWA's byte for
     * byte, which costs nothing.
     *
     * - `origRate` defaults to `rate`, as `normLine` defaults it.
     * - `k` is **null** on a line with no product, as V8C4 stores the
     *   Transportation line. That the same holds for every product-less line
     *   is an inference — `normLine` builds them all — not a separate reading.
     * - `amt` is always written, though V8C4's `amtOf` would fall back to
     *   `qty × rate` without it.
     *
     * The area geometry after them is this app's own and optional.
     */
    internal fun lineData(line: QuotationLineRecord): Map<String, Any?> = buildMap {
        put("t", line.title)
        put("s", line.spec)
        put("u", line.unit)
        put("qty", line.quantity)
        put("rate", line.rate)
        put("origRate", line.originalRate ?: line.rate)
        put("k", line.key.ifBlank { null })
        put("manual", line.manual)
        put("amt", line.amount)
        line.geometry?.let { opening ->
            put("w", opening.width)
            put("h", opening.height)
            put("dim", opening.unit)
            put("sqft", opening.sqftPerDoor)
            put("nos", opening.count)
        }
    }
}
