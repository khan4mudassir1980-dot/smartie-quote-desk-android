package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.RateTierV2

/**
 * One line a quotation would carry. The rate is the catalogue rate at the
 * moment the line was added, so a later price change cannot rewrite what was
 * quoted; `null` is "Price not set" and stays null rather than becoming zero
 * (audit P2).
 */
data class DraftLine(
    /** The product's logical key, `group|seedModel`. */
    val key: String,
    val title: String,
    val spec: String = "",
    val unit: String = "each",
    val quantity: Double = 1.0,
    val rate: Double? = null,
    /** The catalogue rate this line started from, for restore and repricing. */
    val originalRate: Double? = rate,
    val tier: RateTierV2 = RateTierV2.CLIENT,
    /** A rate typed by hand survives a change of tier. */
    val rateEdited: Boolean = false
) {
    /** Until someone enters a rate, this line cannot be finalised (T-P2). */
    val needsRate: Boolean get() = rate == null

    /** Null rather than zero while the rate is unset, so no total pretends. */
    val amount: Double? get() = rate?.let { it * quantity }

    /**
     * The quotation line this would become, or null while the rate is unset.
     * `QuotationLineRecord.rate` is a plain `Double`, so an unrated line has no
     * honest representation there — it must be priced first, which is exactly
     * what T-P2 asks for.
     */
    fun toRecord(): QuotationLineRecord? = rate?.let { priced ->
        QuotationLineRecord(
            title = title,
            spec = spec,
            unit = unit,
            quantity = quantity,
            rate = priced,
            originalRate = originalRate,
            key = key,
            manual = false,
            amount = priced * quantity
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
 * The quotation being built on the Products tab: what the stepper adds and the
 * quote bar totals. Device-local and persisted, so a draft survives the app
 * being killed (audit C8).
 *
 * N2 accumulates it only. Turning a draft into a numbered, priced, shareable
 * quotation is N5's, which is why nothing here touches Firestore.
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

    fun quantityOf(key: String): Double = lines.firstOrNull { it.key == key }?.quantity ?: 0.0

    fun contains(key: String): Boolean = lines.any { it.key == key }

    /**
     * Adds a product, or raises the quantity when it is already on the
     * quotation — the PWA's "Already in the quotation — quantity raised".
     */
    fun add(product: ProductRecord, quantity: Double = 1.0): QuoteDraft {
        if (quantity <= 0.0) return this
        val existing = lines.firstOrNull { it.key == product.key }
        if (existing != null) return setQuantity(product.key, existing.quantity + quantity)
        val rate = product.priceFor(tier)
        return copy(
            lines = lines + DraftLine(
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

    /**
     * Sets a line's quantity. Zero removes it; a negative is refused and the
     * draft is returned untouched, as the PWA refuses it with "Quantity cannot
     * be negative" (7711).
     */
    fun setQuantity(key: String, quantity: Double): QuoteDraft = when {
        !isValidQuantity(quantity) -> this
        quantity == 0.0 -> copy(lines = lines.filterNot { it.key == key })
        else -> copy(lines = lines.map { if (it.key == key) it.copy(quantity = quantity) else it })
    }

    fun changeQuantity(key: String, delta: Double): QuoteDraft {
        val current = quantityOf(key)
        if (current == 0.0 && delta <= 0.0) return this
        return setQuantity(key, (current + delta).coerceAtLeast(0.0))
    }

    /** A rate typed by hand, which a later change of tier must not overwrite. */
    fun setRate(key: String, rate: Double?): QuoteDraft = copy(
        lines = lines.map {
            if (it.key == key) it.copy(rate = rate, rateEdited = true) else it
        }
    )

    fun remove(key: String): QuoteDraft = copy(lines = lines.filterNot { it.key == key })

    fun clear(): QuoteDraft = copy(lines = emptyList())

    /**
     * Switches tier and reprices every line whose rate was not typed by hand,
     * reporting how many moved and how many were left alone. [priceOf] returns
     * the catalogue rate for a logical key at the new tier, or null when the
     * product has no price at that tier.
     */
    fun withTier(newTier: RateTierV2, priceOf: (String) -> Double?): Repriced {
        if (newTier == tier) return Repriced(this, repriced = 0, kept = 0)
        var repriced = 0
        var kept = 0
        val updated = lines.map { line ->
            if (line.rateEdited) {
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
        fun isValidQuantity(quantity: Double): Boolean = quantity >= 0.0 && quantity.isFinite()
    }
}
