package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Money

/**
 * What the GST control says, in each of the five states it can be in.
 *
 * **No rate is ever moved because the lines changed.** `gstSuggestion` is a
 * *pre-fill* and nothing more: it fills only while the rate is unresolved, so
 * a rate somebody typed is never silently overwritten when a product joins the
 * quotation. When a later product disagrees, the disagreement is **shown** and
 * the person decides — which is the same rule N5.8a's second amendment set for
 * every other money-affecting fallback, and the reason a damaged draft value
 * is surfaced rather than quietly resolved.
 *
 * V8C4 needs none of this: its rate is one company default. Ours has to be
 * settable, because the catalogue carries a rate per product and they do not
 * always agree. (The company default belongs in `teamSettings/company
 * .defaultGst`, which V8C4 already writes and this app may already read;
 * resolving from the lines is an honest stopgap until N6 reads it.)
 */
object QuoteGst {

    /**
     * The supporting line under the GST control.
     *
     * [rates] is every distinct GST rate the catalogue lines carry — see
     * [QuoteDraft.gstRates]. It is empty on a quotation of hand-typed lines,
     * which is a third case and not the same as disagreement.
     */
    fun note(enabled: Boolean, percent: Double?, rates: List<Double>): String = when {
        !enabled -> NO_GST
        percent == null && rates.size > 1 -> THEY_DISAGREE
        percent == null -> QuoteDraft.GST_NOT_SET
        // Set, and a product on the quotation is rated at something else.
        // A note, never an overwrite.
        rates.any { it != percent } -> ratedAt(rates.first { it != percent })
        rates.isNotEmpty() -> FROM_THE_PRODUCTS
        else -> SET_BY_HAND
    }

    const val NO_GST = "No GST on this quotation."
    const val THEY_DISAGREE =
        "These products have different GST rates — set the rate for this quotation."
    const val FROM_THE_PRODUCTS = "From the products on this quotation."
    const val SET_BY_HAND = "Set for this quotation."

    fun ratedAt(rate: Double): String =
        "A product on this quotation is rated at ${Money.formatQuantity(rate)}%. " +
            "Change the rate above if that is the one you want."
}
