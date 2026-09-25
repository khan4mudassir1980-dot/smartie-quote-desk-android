package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationLineGeometry
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2

/**
 * One line a quotation would carry.
 *
 * **[id] is the line's identity, and [key] is only a reference to a product.**
 * Until N5.8a every operation matched on [key], which meant two hand-typed
 * lines could not coexist (both have none), two openings of the same shutter
 * merged into one wrong figure, and `remove` deleted every line sharing a
 * product. The id is minted **once, by the caller, when the line is created**
 * — never inside a save — which is the N4.4 B2 lesson in its proper shape:
 * there the non-unique identity wrote a twin, here it collapsed two distinct
 * lines into one. Same cause, opposite symptom.
 *
 * The rate is the catalogue rate at the moment the line was added, so a later
 * price change cannot rewrite what was quoted; `null` is "Price not set" and
 * stays null rather than becoming zero (audit P2).
 */
data class DraftLine(
    /** Device-local identity. Never written to Firestore. */
    val id: String,
    val title: String,
    /** The product's logical key, `group|seedModel`. Blank on a manual line. */
    val key: String = "",
    val spec: String = "",
    val unit: String = ProductUnit.EACH,
    val quantity: Double = 1.0,
    val rate: Double? = null,
    /** The catalogue rate this line started from, for restore and repricing. */
    val originalRate: Double? = rate,
    val tier: RateTierV2 = RateTierV2.CLIENT,
    /** A rate typed by hand survives a change of tier. */
    val rateEdited: Boolean = false,
    /** A one-off line, never in the catalogue. V8C4 stores this as `manual`. */
    val manual: Boolean = false,
    /**
     * The opening this line prices, when it is priced by the square foot.
     *
     * Held for display and for the spec string. [quantity] carries the
     * **total** chargeable area it implies, because that is what V8C4 prints
     * against the rate.
     */
    val area: AreaLine? = null
) {
    /** Until someone enters a rate, this line cannot be finalised (T-P2). */
    val needsRate: Boolean get() = rate == null

    /**
     * Null rather than zero while the rate is unset, so no total pretends.
     *
     * Whole rupees, like every other stored figure: the products total is the
     * sum of these, so the printed page adds up to what each line says.
     */
    val amount: Double? get() = rate?.let { QuoteMath.rupees(it * quantity) }

    /** True when this line prices an opening rather than a count of things. */
    val isArea: Boolean get() = area != null

    /**
     * Whether the **catalogue** sets this line's rate, rather than a person.
     *
     * The one rule both [QuoteDraft.withTier] and [QuoteDraft.alignLinesToTier]
     * price by, written once so they cannot drift apart. A hand-typed rate and
     * a manual line are a person's decision; a line with no product key has
     * nothing to look a rate up by.
     */
    internal val cataloguePriced: Boolean
        get() = !rateEdited && !manual && key.isNotBlank()

    /**
     * This line at [newTier] — or **itself, identically**, when the catalogue is
     * not what sets its rate.
     *
     * Returning `this` rather than an equal copy is load-bearing: both callers
     * count what moved with `===`, and a `copy()` that changed nothing would be
     * reported as a reprice.
     *
     * A product that has left the catalogue prices to null, which reads as
     * "Rate needed" and blocks finalising. That is the honest answer — we
     * cannot say what it costs at this tier — and it is the overquote-safe one.
     */
    internal fun at(newTier: RateTierV2, priceOf: (String) -> Double?): DraftLine =
        if (!cataloguePriced) this
        else priceOf(key).let { copy(rate = it, originalRate = it, tier = newTier) }

    /**
     * The quotation line this would become, or null while the rate is unset.
     * `QuotationLineRecord.rate` is a plain `Double`, so an unrated line has no
     * honest representation there — it must be priced first, which is exactly
     * what T-P2 asks for.
     */
    fun toRecord(): QuotationLineRecord? = rate?.let { priced ->
        QuotationLineRecord(
            title = title,
            // An area line's spec carries the opening, so the PWA prints the
            // working even though it knows nothing about area.
            spec = area?.let { QuoteArea.describeGeometry(it) } ?: spec,
            unit = unit,
            quantity = quantity,
            rate = priced,
            originalRate = originalRate,
            key = key,
            manual = manual,
            amount = QuoteMath.rupees(priced * quantity),
            // Written for N5.10 to reopen the form with; never relied on.
            geometry = area?.let {
                QuotationLineGeometry(
                    width = it.width,
                    height = it.height,
                    unit = it.unit.wireValue,
                    sqftPerDoor = QuoteArea.chargeableSqft(it),
                    count = it.count
                )
            }
        )
    }
}

/**
 * Something stored that could not be read, and that decides money.
 *
 * **No money-affecting fallback is silent.** `RateTierV2.from` falls back to
 * `DEALER` and `InstallationMode.from` to `FIXED`; both are right for a
 * document V8C4 wrote, which carries its own figures, and both are wrong for
 * our own draft. A value we cannot read means the stored text is damaged, and
 * choosing quietly moves money in the **underquote** direction — an overquote
 * gets argued down in conversation, an underquote goes out, is accepted and is
 * honoured. So the draft keeps what it can and says what it could not read.
 */
enum class DraftFault(val message: String) {
    /**
     * Recovered to Client — the **higher**-priced of the two offered tiers,
     * never the first in the enum, which is Dealer. Nothing is repriced by
     * this: every line stores its own rate, so the tier only decides the next
     * reprice and the name printed on the quotation.
     */
    TIER("The rate type on this quotation could not be read - choose Dealer or Client again"),

    /**
     * Dropped, because installation modes have no order: a `pct` charge of 8
     * read as `fixed` would bill 8 rupees instead of 1,552. Dropping it lowers
     * the total, so it blocks finalising rather than merely warning.
     */
    INSTALLATION("The installation charge could not be read - enter it again"),

    /**
     * Dropped for the same reason, and it is the sharpest of the three: a
     * stored value of 2000 read as a percentage instead of rupees is not a
     * discount, it is the whole quotation given away. Not named in the brief;
     * included because it is the same class of silent money-affecting
     * fallback as the other two.
     */
    DISCOUNT("The discount could not be read - enter it again")
}

/** The tiers a new quotation may be built at. */
object QuoteTier {
    /**
     * **Contractor is not offered, and is never deleted.** `RateTierV2` keeps
     * all three because a quotation already issued at the contractor tier must
     * still display in full; the builder simply cannot produce one. The same
     * shape N5.7 used for product units: the reading type stays complete while
     * the writing path is narrowed.
     */
    val OFFERED: List<RateTierV2> = listOf(RateTierV2.DEALER, RateTierV2.CLIENT)

    fun offers(tier: RateTierV2): Boolean = tier in OFFERED
}

/** The outcome of changing tier, worded as the PWA reports it (2269-2288). */
data class Repriced(
    val draft: QuoteDraft,
    val repriced: Int,
    val kept: Int
)

/**
 * The quotation being built: what the stepper adds and the quote bar totals.
 * Device-local and persisted, so a draft survives the app being killed
 * (audit C8).
 *
 * Nothing here touches Firestore. Turning a draft into a numbered, priced,
 * shareable quotation is N5.9's.
 */
data class QuoteDraft(
    /** Device-local identity, so N5.9 clears exactly the draft it issued. */
    val id: String = "",
    val tier: RateTierV2 = RateTierV2.CLIENT,
    val lines: List<DraftLine> = emptyList(),
    /** The saved customer, when there is one. Blank for a walk-in. */
    val partyId: String = "",
    /**
     * Who the quotation is for, as the screen shows it.
     *
     * **This snapshot is what the quotation keeps**, as it stands at
     * finalise; it is never rewritten from a customer record. [partyId] is
     * only a cross-reference, and finalise re-derives it from this form
     * (`QuoteParty.linkFor`): a customer picked and then typed over is not
     * carried into the record.
     */
    val party: QuotationPartySnapshot = QuotationPartySnapshot(),
    /** Carriage. Stored as a **line** at finalise, inside the subtotal. */
    val transport: Double = 0.0,
    /**
     * What the carriage was for — V8C4's own field, placeholder
     * "e.g. Mumbai to Vadodara". It reaches the printed quotation.
     *
     * A gap in N5.8a, which carried the amount and nowhere to put this. It
     * needs no new wire field: at finalise transport becomes an ordinary line
     * titled `Transportation` (not a manual one — `QuotationWrite`), and this
     * becomes that line's `s` — the spec,
     * which V8C4 already prints and which the read side already renders as a
     * row's secondary text.
     */
    val transportNote: String = "",
    /** Null is "no installation", which is not the same as zero. */
    val installation: Installation? = null,
    val discount: Discount? = null,
    val gstEnabled: Boolean = true,
    /**
     * **Null means "not resolved yet", not "no GST".**
     *
     * A draft that silently charged 0% would go out under-priced and nobody
     * would notice until the customer did, so an unresolved rate blocks
     * finalising instead. [gstSuggestion] is where a resolved one comes from.
     */
    val gstPercent: Double? = null,
    val updatedAt: Long = 0L,
    /** What could not be read back off the device. Never silently resolved. */
    val faults: Set<DraftFault> = emptySet()
) {
    val lineCount: Int get() = lines.size

    /** Lines with no rate contribute nothing; they are counted separately. */
    val total: Double get() = lines.sumOf { it.amount ?: 0.0 }

    val needsRateCount: Int get() = lines.count { it.needsRate }

    val isEmpty: Boolean get() = lines.isEmpty()

    fun line(id: String): DraftLine? = lines.firstOrNull { it.id == id }

    /**
     * The catalogue line for a product, which is the one the stepper drives.
     *
     * Manual and area lines are excluded deliberately: several of those may
     * share a product, and a stepper that drove "whichever one came first"
     * would change a figure the person was not looking at.
     */
    fun catalogueLine(key: String): DraftLine? =
        lines.firstOrNull { it.key == key && !it.manual && it.area == null }

    fun quantityOf(key: String): Double = catalogueLine(key)?.quantity ?: 0.0

    fun contains(key: String): Boolean = lines.any { it.key == key }

    /**
     * Adds a product, or raises the quantity when it is already on the
     * quotation — the PWA's "Already in the quotation — quantity raised".
     *
     * **Merging is right here and wrong everywhere else.** Two taps on the same
     * catalogue row mean two of the thing; two openings of the same shutter do
     * not, which is why [addArea] never merges.
     */
    fun add(
        product: ProductRecord,
        quantity: Double = 1.0,
        id: String = Keys.generateId(LINE_PREFIX)
    ): QuoteDraft {
        if (quantity <= 0.0) return this
        val existing = catalogueLine(product.key)
        if (existing != null) return setQuantity(existing.id, existing.quantity + quantity)
        val rate = product.priceFor(tier)
        return copy(
            lines = lines + DraftLine(
                id = id,
                key = product.key,
                title = product.model.ifBlank { product.seedModel },
                spec = product.spec,
                unit = product.unit,
                quantity = quantity,
                rate = rate,
                originalRate = rate,
                tier = tier
            )
        )
    }

    /** A one-off line nobody will find in the catalogue. Never merged. */
    fun addManual(
        id: String,
        title: String,
        quantity: Double = 1.0,
        rate: Double? = null,
        unit: String = ProductUnit.EACH,
        spec: String = ""
    ): QuoteDraft = copy(
        lines = lines + DraftLine(
            id = id,
            title = title.trim(),
            spec = spec.trim(),
            unit = ProductUnit.normalise(unit),
            quantity = quantity,
            rate = rate,
            originalRate = rate,
            tier = tier,
            rateEdited = true,
            manual = true
        )
    )

    /**
     * An opening priced by the square foot. **Never merged**, because two
     * openings of the same product are two lines with two different areas.
     *
     * [quantity] is set to the total chargeable area the opening implies, so
     * `qty × rate` is the amount and V8C4 prints the line correctly.
     */
    fun addArea(
        id: String,
        area: AreaLine,
        rate: Double?,
        title: String,
        key: String = "",
        manual: Boolean = key.isBlank()
    ): QuoteDraft = copy(
        lines = lines + DraftLine(
            id = id,
            key = key,
            title = title.trim(),
            unit = ProductUnit.AREA,
            quantity = QuoteArea.totalSqft(area),
            rate = rate,
            originalRate = rate,
            tier = tier,
            rateEdited = manual,
            manual = manual,
            area = area
        )
    )

    /** A changed opening, with the chargeable area recomputed from it. */
    fun setArea(id: String, area: AreaLine): QuoteDraft = copy(
        lines = lines.map {
            if (it.id == id) it.copy(area = area, quantity = QuoteArea.totalSqft(area)) else it
        }
    )

    /**
     * The stepper on a catalogue card, which knows a product and not a line.
     *
     * Kept so the Products tab is untouched by line identity: a card drives
     * the one catalogue line for its product, and never a manual or area line
     * that happens to share it.
     */
    fun changeCatalogueQuantity(key: String, delta: Double): QuoteDraft {
        val existing = catalogueLine(key) ?: return this
        return changeQuantity(existing.id, delta)
    }

    fun setCatalogueQuantity(key: String, quantity: Double): QuoteDraft {
        val existing = catalogueLine(key) ?: return this
        return setQuantity(existing.id, quantity)
    }

    /**
     * Sets a line's quantity. Zero removes it; a negative is refused and the
     * draft is returned untouched, as the PWA refuses it with "Quantity cannot
     * be negative" (7711).
     */
    fun setQuantity(id: String, quantity: Double): QuoteDraft = when {
        !isValidQuantity(quantity) -> this
        quantity == 0.0 -> copy(lines = lines.filterNot { it.id == id })
        else -> copy(lines = lines.map { if (it.id == id) it.copy(quantity = quantity) else it })
    }

    fun changeQuantity(id: String, delta: Double): QuoteDraft {
        val current = line(id)?.quantity ?: return this
        return setQuantity(id, (current + delta).coerceAtLeast(0.0))
    }

    /** A rate typed by hand, which a later change of tier must not overwrite. */
    fun setRate(id: String, rate: Double?): QuoteDraft = copy(
        lines = lines.map {
            if (it.id == id) it.copy(rate = rate, rateEdited = true) else it
        }
    )

    fun remove(id: String): QuoteDraft = copy(lines = lines.filterNot { it.id == id })

    fun clear(): QuoteDraft = copy(lines = emptyList())

    /**
     * Switches tier and reprices every line whose rate was not typed by hand,
     * reporting how many moved and how many were left alone. [priceOf] returns
     * the catalogue rate for a logical key at the new tier, or null when the
     * product has no price at that tier.
     *
     * **A manual line is never repriced**, whatever its `rateEdited` says: it
     * has no product key, so `priceOf` would answer null and a hand-typed rate
     * would be wiped by a change of tier.
     */
    fun withTier(newTier: RateTierV2, priceOf: (String) -> Double?): Repriced {
        if (newTier == tier) return Repriced(this, repriced = 0, kept = 0)
        var repriced = 0
        var kept = 0
        val updated = lines.map { line ->
            val moved = line.at(newTier, priceOf)
            if (moved === line) kept++ else repriced++
            moved
        }
        return Repriced(copy(tier = newTier, lines = updated), repriced, kept)
    }

    /**
     * Lines whose own [DraftLine.tier] disagrees with the draft's, and that
     * the catalogue could put right.
     *
     * How that happens at all: `QuoteDrafts.resume` adopts the *stored* tier
     * while a line tapped in before the store answered was priced at whatever
     * the screen was showing — the default Client. So the draft says Dealer
     * and one line is Client-priced.
     */
    val hasLinesOutOfStep: Boolean
        get() = lines.any { it.cataloguePriced && it.tier != tier }

    /**
     * Those lines brought back into step, at the draft's own tier.
     *
     * **[withTier] cannot do this, and must not be changed so it can.** It
     * short-circuits when the tier is not changing — right for a tier picker,
     * and exactly wrong here, where the tier is already correct and the
     * *lines* are not. An earlier draft of `QuoteDrafts.resume` told its caller
     * to "just call `withTier(resolved.tier, …)`", which is a no-op for
     * precisely that reason: the fix would have shipped doing nothing.
     *
     * [Repriced.kept] counts out-of-step lines left alone because a person
     * typed their rate. Those keep their own tier: the rate was struck at it,
     * and recording something else would misstate what was quoted.
     *
     * A no-op on any draft that was never resumed, so it is safe to call
     * whenever the catalogue arrives.
     */
    fun alignLinesToTier(priceOf: (String) -> Double?): Repriced {
        var repriced = 0
        var kept = 0
        val updated = lines.map { line ->
            if (line.tier == tier) return@map line
            if (!line.cataloguePriced) {
                kept++
                return@map line
            }
            repriced++
            line.at(tier, priceOf)
        }
        return Repriced(copy(lines = updated), repriced, kept)
    }

    // --- who it is for --------------------------------------------------------------

    /**
     * The customer this quotation is for, chosen from the saved ones.
     *
     * **A site already typed is kept, never overwritten.** The site is where
     * this job is and the customer is who pays for it; somebody who writes the
     * site down first and then picks the firm has not asked for the site to be
     * forgotten. `QuoteParty.snapshotOf` returns a blank site for that reason,
     * and this is where the one on the draft survives.
     */
    fun withParty(record: PartyRecord): QuoteDraft = copy(
        partyId = record.id,
        party = QuoteParty.snapshotOf(record).copy(site = party.site)
    )

    // --- what installation is charged against ---------------------------------------

    /**
     * Openings on this quotation, for a per-door installation charge.
     *
     * **Area lines only, and it can legitimately be zero.** A shutter's `nos`
     * is a door count; a catalogue line's quantity is a count of things, which
     * is not the same question — two motors are not two doors. A quotation
     * with no openings therefore offers a basis of zero, and the basis box is
     * editable precisely so the person can say what it actually is. Guessing
     * from the line quantities would put a figure there that looks derived and
     * is not.
     */
    val doorCount: Double get() = lines.sumOf { it.area?.count ?: 0.0 }

    /**
     * The chargeable area on this quotation, for a per-square-foot charge.
     *
     * An area line's stored quantity **is** its total chargeable area, which
     * is the tie `setArea` keeps and the card has no stepper in order not to
     * break. So this is a sum of quantities and not a recomputation.
     */
    val chargeableSqft: Double get() = lines.filter { it.isArea }.sumOf { it.quantity }

    /** What the basis box starts at for [mode], before anybody edits it. */
    fun defaultBasisFor(mode: InstallationMode): Double = Installation.defaultBasis(
        mode = mode,
        doors = doorCount,
        chargeableSqft = chargeableSqft,
        products = products
    )

    // --- what it comes to ---------------------------------------------------------

    /** Every priced line added up, in whole rupees. */
    val products: Double get() = QuoteMath.rupees(total)

    /** The figure a discount is taken against, and the one the rules bound. */
    val discountBase: Double get() = QuoteMath.rupees(products + (installation?.amount ?: 0.0))

    /**
     * The figures this draft would print.
     *
     * **Only safe once [refusal] has answered null.** `QuoteMath.totals` has
     * no floor: given a negative transport or installation it computes a
     * negative subtotal without complaint. See the invariant on `QuoteMath`.
     */
    fun toCharges(): QuoteCharges = QuoteCharges(
        products = products,
        installation = installation,
        discount = discount,
        transport = transport,
        // An unresolved rate charges nothing and blocks finalising, rather
        // than quietly charging zero as though somebody had chosen it.
        gstEnabled = gstEnabled && gstPercent != null,
        gstPercent = gstPercent ?: 0.0
    )

    fun totals(): QuoteTotals = QuoteMath.totals(toCharges())

    /**
     * The GST the catalogue lines agree on, or null when they disagree or
     * there are none to ask.
     *
     * A quotation carries one rate, so agreement is the only case that can be
     * resolved without asking somebody. [gstOf] answers a product's rate by
     * its logical key.
     */
    /**
     * Every distinct GST rate the catalogue lines carry.
     *
     * Empty on a quotation of nothing but hand-typed lines, which is not the
     * same as the products disagreeing — `QuoteGst.note` tells the two apart.
     */
    fun gstRates(gstOf: (String) -> Double?): List<Double> = lines
        .filter { !it.manual && it.key.isNotBlank() }
        .mapNotNull { gstOf(it.key) }
        .distinct()

    fun gstSuggestion(gstOf: (String) -> Double?): Double? = lines
        .filter { !it.manual && it.key.isNotBlank() }
        .mapNotNull { gstOf(it.key) }
        .distinct()
        .singleOrNull()

    /**
     * Why this draft cannot be finalised, or null when it can.
     *
     * **This is the single gate.** Every money field that can go negative is
     * bounded here, and the non-negative invariant `QuoteMath` documents
     * depends on this having been called — the arithmetic itself has no floor.
     */
    fun refusal(capPercent: Double): String? {
        faults.firstOrNull()?.let { return it.message }
        if (!QuoteTier.offers(tier)) return TIER_NOT_OFFERED
        if (isEmpty) return NO_LINES
        if (lines.any { it.needsRate }) return LINE_NEEDS_RATE

        if (!transport.isFinite() || transport < 0.0) return NEGATIVE_TRANSPORT
        installation?.let { charge ->
            if (!charge.rate.isFinite() || charge.rate < 0.0) return NEGATIVE_INSTALLATION
            if (!charge.basis.isFinite() || charge.basis < 0.0) return NEGATIVE_INSTALLATION
        }
        discount?.let { taken ->
            QuoteMath.discountRefusal(taken, discountBase, capPercent)?.let { return it }
        }

        if (gstEnabled && gstPercent == null) return GST_NOT_SET
        // A NAME, not a saved customer: the Owner's ruling of 2026-09-25. A
        // walk-in or a first enquiry is quoted without being filed, as V8C4
        // quotes one, and forcing every quotation through Parties first would
        // make this app harder to use than the one it replaces.
        if (party.name.isBlank()) return NO_PARTY
        return null
    }

    companion object {
        const val TIER_NOT_OFFERED = "A new quotation is priced at Dealer or Client rates"
        const val NO_LINES = "Add something to the quotation first"
        const val LINE_NEEDS_RATE = "Every line needs a rate before this can be issued"
        const val NEGATIVE_TRANSPORT = "Transport cannot be negative"
        const val NEGATIVE_INSTALLATION = "An installation charge cannot be negative"
        const val GST_NOT_SET = "Set the GST rate for this quotation"
        const val NO_PARTY = "Enter who this quotation is for"

        /**
         * Whether this charge may be stored at all — the second of the
         * invariant's three gates, applied before `refusal` rather than only
         * at the point of issue. `QuoteMath.totals` has no floor, so a
         * negative rate or basis would produce a negative subtotal without
         * complaint.
         */
        fun isValidInstallation(charge: Installation): Boolean =
            charge.rate.isFinite() && charge.rate >= 0.0 &&
                charge.basis.isFinite() && charge.basis >= 0.0

        /**
         * Line ids read as `ln_…`, the same shape as every other id the app
         * mints.
         *
         * [add] defaults one because a catalogue line **merges** by product
         * key: a second tap raises the quantity rather than making a twin, so
         * a fresh id per tap is simply unused. [addManual] and [addArea] do
         * **not** default one, because nothing merges them — the caller holds
         * one id for as long as the person is filling one sheet in, exactly as
         * `PartyWrite.create` does, so a retry lands on the same line instead
         * of writing a second (the N4.4 B2 lesson).
         */
        const val LINE_PREFIX = "ln_"

        fun isValidQuantity(quantity: Double): Boolean = quantity >= 0.0 && quantity.isFinite()
    }
}
