package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Money

/** How installation is charged on a quotation. */
enum class InstallationMode(val wireValue: String, val label: String) {
    FIXED("fixed", "Fixed amount"),
    PER_DOOR("door", "Per door"),
    PER_SQFT("sqft", "Per sq ft"),
    PERCENT("pct", "% of products");

    companion object {
        fun from(value: String?): InstallationMode =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: FIXED
    }
}

/**
 * Installation, optional and one per quotation.
 *
 * [basis] is what the rate is applied to — the door count, the chargeable
 * square feet, or the products figure — and it is **defaulted from the lines
 * and then editable**, which is why it is stored rather than re-derived. A
 * quotation reopened a month later must show the figure the price was actually
 * struck on, not whatever the lines would produce today.
 *
 * A fixed amount ignores [basis] entirely; it *is* the rate.
 */
data class Installation(
    val mode: InstallationMode,
    val rate: Double,
    val basis: Double = 0.0
) {
    /** In whole rupees, like every other stored figure. */
    val amount: Double
        get() = QuoteMath.rupees(
            when (mode) {
                InstallationMode.FIXED -> rate
                InstallationMode.PER_DOOR, InstallationMode.PER_SQFT -> rate * basis
                InstallationMode.PERCENT -> basis * rate / 100.0
            }
        )

    companion object {
        /**
         * What the basis starts at, before anybody edits it: the door count
         * for a per-door charge, the chargeable area for a per-square-foot
         * one, and the products figure for a percentage.
         */
        fun defaultBasis(
            mode: InstallationMode,
            doors: Double,
            chargeableSqft: Double,
            products: Double
        ): Double = when (mode) {
            InstallationMode.FIXED -> 0.0
            InstallationMode.PER_DOOR -> doors
            InstallationMode.PER_SQFT -> chargeableSqft
            InstallationMode.PERCENT -> products
        }
    }
}

/** A discount typed as a percentage or as an amount. */
enum class DiscountKind(val wireValue: String) {
    PERCENT("pct"),
    RUPEES("amt");

    companion object {
        fun from(value: String?): DiscountKind =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: PERCENT
    }
}

/**
 * The one discount a quotation may carry.
 *
 * **It never touches transport.** Carriage is what it costs to get the goods
 * to site, so discounting it means quoting a delivery below what it is about
 * to cost. The discount applies to products plus installation and stops there.
 */
data class Discount(val kind: DiscountKind, val value: Double) {
    /** In whole rupees, against [base] — products plus installation. */
    fun amountOn(base: Double): Double = QuoteMath.rupees(
        when (kind) {
            DiscountKind.PERCENT -> base * value / 100.0
            DiscountKind.RUPEES -> value
        }
    )
}

/** Everything a quotation charges for, before the totals are worked out. */
data class QuoteCharges(
    /** Every line added up, area lines included, in whole rupees. */
    val products: Double,
    val installation: Installation? = null,
    val discount: Discount? = null,
    val transport: Double = 0.0,
    val gstEnabled: Boolean = false,
    val gstPercent: Double = 0.0
)

/**
 * The figures a quotation stores and prints, in the order they are built.
 *
 * [discountBase] is products plus installation — the figure the discount was
 * taken against — and it is **stored** rather than re-derived, because the
 * Firestore rules check a Manager's cap against it and a rule cannot sum an
 * array. [subtotal] is what GST is charged on, which is also what V8C4 calls
 * `subtotal`.
 */
data class QuoteTotals(
    val products: Double,
    val installation: Double,
    val discountBase: Double,
    val discount: Double,
    val transport: Double,
    val subtotal: Double,
    val gstPercent: Double,
    val gst: Double,
    val total: Double
)

/**
 * Adding a quotation up.
 *
 * **The order is fixed and it is not the obvious one:**
 *
 * 1. products + installation
 * 2. − discount
 * 3. + transport
 * 4. + GST on that whole amount
 *
 * So GST falls on transport as well, which is V8C4's behaviour and is kept
 * deliberately: the finalised fixture `q_pwa_finalised` carries a
 * "Transportation" line inside its subtotal with 18% charged on the lot.
 * Discount, by contrast, stops before transport.
 *
 * **Every figure is rounded to whole rupees as it is computed**, and each
 * later figure is built from the already-rounded earlier ones. That is not
 * cosmetic. It keeps the printed page adding up, it keeps V8C4 and the native
 * app showing the same numbers, and it makes
 * `discountBase == subtotal − transport + discount` exact integer arithmetic —
 * which is the bound the security rules use to stop an inflated base clearing
 * a Manager's cap.
 */
object QuoteMath {

    /**
     * **THE NON-NEGATIVE INVARIANT: no stored figure may be negative, and
     * THREE gates hold it, not one.**
     *
     * `totals` below has **no floor**. It computes
     * `discountBase - discount + transport` from whatever it is given, so a
     * negative discount, a negative transport or a negative installation each
     * produce a negative subtotal without complaint. The invariant lives
     * entirely in the gates:
     *
     * 1. **discount** — [discountRefusal], which bounds it against the base;
     * 2. **transport** — `QuoteDraft.refusal`;
     * 3. **installation** — `QuoteDraft.refusal`, on both rate and basis.
     *
     * It matters beyond tidiness. Every figure here is rounded HALF_UP, which
     * rounds a half **away from zero**, while V8C4 prints with JavaScript's
     * `Math.round`, which rounds a half toward **+infinity**. The two agree on
     * every non-negative value and disagree on negative halves — so while the
     * invariant holds, the app and the PWA print the same number, and the
     * moment it does not, they quietly stop agreeing.
     *
     * **Anyone adding a fourth money field adds a fourth gate.**
     */
    /** Owner and Administrator are uncapped; this is what that reads as. */
    const val NO_CAP: Double = 100.0

    fun totals(charges: QuoteCharges): QuoteTotals {
        val products = rupees(charges.products)
        val installation = charges.installation?.amount ?: 0.0
        val discountBase = rupees(products + installation)
        val discount = charges.discount?.amountOn(discountBase) ?: 0.0
        val transport = rupees(charges.transport)

        // Built from the rounded figures above and never recomputed from the
        // unrounded ones, so the rules' bound holds on whole rupees.
        val subtotal = discountBase - discount + transport
        val gstPercent = if (charges.gstEnabled) charges.gstPercent else 0.0
        val gst = if (charges.gstEnabled) rupees(subtotal * gstPercent / 100.0) else 0.0

        return QuoteTotals(
            products = products,
            installation = installation,
            discountBase = discountBase,
            discount = discount,
            transport = transport,
            subtotal = subtotal,
            gstPercent = gstPercent,
            gst = gst,
            total = subtotal + gst
        )
    }

    /**
     * Why this discount may not be applied, or null when it may.
     *
     * The refusal **names the number the person may have** rather than
     * silently clamping what they typed: a quotation that went out at a
     * discount nobody chose is worse than one that would not save.
     *
     * [capPercent] is the Owner's limit for a Manager and [NO_CAP] for an
     * Owner or an Administrator. The cap bounds this one discount against
     * products plus installation; it does **not** bound a hand-typed line
     * rate, so it is a guardrail and not a proof.
     */
    fun discountRefusal(discount: Discount, base: Double, capPercent: Double): String? {
        val typed = discount.value
        if (!typed.isFinite() || typed < 0.0) return NEGATIVE_DISCOUNT
        if (discount.kind == DiscountKind.PERCENT && typed > 100.0) return OVER_A_HUNDRED

        val amount = discount.amountOn(base)
        if (amount > base) return moreThanTheQuotation(base)

        val allowed = rupees(base * capPercent / 100.0)
        if (amount > allowed) return overTheCap(capPercent, allowed)
        return null
    }

    /** Whole rupees, HALF_UP, exactly as `Money` formats them. */
    fun rupees(value: Double): Double = Money.round(value, 0).toDouble()

    const val NEGATIVE_DISCOUNT = "A discount cannot be negative"
    const val OVER_A_HUNDRED = "A discount cannot be more than 100%"

    fun moreThanTheQuotation(base: Double): String =
        "A discount cannot be more than the quotation — ${Money.formatRupees(base, decimals = 0)}"

    fun overTheCap(capPercent: Double, allowed: Double): String =
        "The most you can discount is ${Money.formatQuantity(capPercent)}% " +
            "(${Money.formatRupees(allowed, decimals = 0)})"
}
