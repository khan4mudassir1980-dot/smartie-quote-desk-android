package `in`.smartie.quotedesk.domain

/**
 * When the Products catalogue offers a way back to the top.
 *
 * Pure, so the rule is held to account by a plain unit test rather than by
 * a screenshot: a long shelf list is a long way back, and "near the top" has
 * to mean the same thing whatever the cards happen to be.
 */
object ScrollToTop {

    /**
     * How many items have to be above the fold before the control appears.
     *
     * The catalogue opens with the search box and the filters, so this is a
     * screenful of actual cards rather than a nudge. A control that shows up
     * after a few pixels of movement is noise under the thumb.
     */
    const val APPEAR_AFTER_ITEMS: Int = 3

    /** The description a screen reader reads, and what the tests look for. */
    const val LABEL: String = "Back to top"

    /**
     * Whether to show it, from what the list reports.
     *
     * One condition, not two: it appears above the threshold and is gone
     * below it, so it cannot flicker while a finger rests mid-scroll. At the
     * absolute top — item 0, no offset — it is always hidden.
     */
    fun visible(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int = 0): Boolean =
        firstVisibleItemIndex > APPEAR_AFTER_ITEMS ||
            (firstVisibleItemIndex == APPEAR_AFTER_ITEMS && firstVisibleItemScrollOffset > 0)
}
