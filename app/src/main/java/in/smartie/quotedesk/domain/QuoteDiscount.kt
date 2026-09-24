package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.QuotingRecord

/**
 * How much of a discount this person may give, and why not more.
 *
 * **A cap that was never configured and a cap of zero both permit no
 * discount, and only one of them is somebody's mistake.** `observeQuoting`
 * emits a **null** record when `/teamSettings/quoting` is absent, which is
 * not the same as a record holding `0.0` — so the two are told apart here and
 * given different words. A Manager who reads "the limit has not been set" can
 * ask the Owner to set it; one who reads "the most you can discount is 0%"
 * would reasonably conclude the Owner meant it.
 *
 * Owner and Administrator are uncapped, which reads as [QuoteMath.NO_CAP].
 * The cap is a guardrail and not a proof: it bounds this one discount against
 * products plus installation, and it does **not** bound a hand-typed line
 * rate. The rules apply the same bound server-side, which is what actually
 * stops it.
 */
object QuoteDiscount {

    /**
     * The percentage this member may discount within, or **null when the
     * Owner has not configured one** and this member needs one.
     *
     * [quoting] is null while the settings document has not arrived *or* does
     * not exist. Those are indistinguishable from here, which is the safe way
     * round: a cap that has not loaded yet must not read as permission.
     */
    fun capFor(member: Member, quoting: QuotingRecord?): Double? = when {
        Permissions.isAdmin(member) -> QuoteMath.NO_CAP
        !Permissions.canQuote(member) -> 0.0
        else -> quoting?.managerDiscountPct
    }

    /**
     * Why this discount may not be applied, or null when it may.
     *
     * Delegates to [QuoteMath.discountRefusal] for everything except the
     * unconfigured cap, which that function cannot express: it takes a
     * percentage, and "there is no percentage" is not one.
     */
    fun refusal(discount: Discount, base: Double, cap: Double?): String? {
        if (cap == null) return CAP_NOT_SET
        return QuoteMath.discountRefusal(discount, base, cap)
    }

    const val CAP_NOT_SET =
        "The discount limit has not been set. Ask the Owner to set it in Settings."
}
