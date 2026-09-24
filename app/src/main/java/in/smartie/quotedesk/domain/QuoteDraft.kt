package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
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
            amount = QuoteMath.rupees(priced * quantity)
        )
    }
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
    val tier: RateTierV2 = RateTierV2.CLIENT,
    val lines: List<DraftLine> = emptyList()
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
            if (line.rateEdited || line.manual || line.key.isBlank()) {
                kept++
                line
            } else {
                val rate = priceOf(line.key)
                repriced++
                line.copy(rate = rate, originalRate = rate, tier = newTier)
            }
        }
        return Repriced(copy(tier = newTier, lines = updated), repriced, kept)
    }

    companion object {
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
