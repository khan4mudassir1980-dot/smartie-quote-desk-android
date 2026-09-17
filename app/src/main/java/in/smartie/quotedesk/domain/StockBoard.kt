package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.StockRecord

/** Which rows a tile shows when it is selected. */
enum class StockFilter { ALL, LOW, OUT, PINNED }

/**
 * The three states a row can be in.
 *
 * Derived from the **stored** numbers only. A pending delta never reaches
 * here: nothing has been taken off the shelf until Done returns, so a pending
 * −5 must not make a row read `Out of stock`. The native beta derived its
 * status from `quantity + delta` and got exactly that wrong.
 */
enum class StockStatus(val label: String) {
    OUT("Out of stock"),
    LOW("Low"),
    IN("In stock");

    companion object {
        /**
         * Out **only** at zero; Low at or below the reorder level.
         *
         * A reorder level of zero never makes a row Low — otherwise every
         * empty row would be both Out and Low at once.
         */
        fun of(quantity: Double, reorderLevel: Double): StockStatus = when {
            quantity <= 0.0 -> OUT
            reorderLevel > 0.0 && quantity <= reorderLevel -> LOW
            else -> IN
        }
    }
}

/** One row of the board, with any delta keyed in but not yet committed. */
data class StockRow(
    val record: StockRecord,
    val pending: Double = 0.0
) {
    val key: String get() = record.key

    val name: String get() = StockBoard.displayName(record)

    val model: String get() = StockBoard.displayModel(record)

    /** Always the stored quantity. A pending delta never moves it. */
    val quantity: Double get() = record.quantity

    /** What the quantity would become if the pending delta committed. */
    val projected: Double get() = record.quantity + pending

    /** From the stored quantity only — never from [pending]. */
    val status: StockStatus get() = StockStatus.of(record.quantity, record.reorderLevel)

    val hasPending: Boolean get() = pending != 0.0

    val pinned: Boolean get() = record.pinned
}

/** What the Our Stock tab draws. */
data class StockView(
    val rows: List<StockRow> = emptyList(),
    val tracked: Int = 0,
    val low: Int = 0,
    val out: Int = 0,
    val pinnedCount: Int = 0,
    val query: String = "",
    val filter: StockFilter = StockFilter.ALL
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    val searching: Boolean get() = query.isNotBlank()
}

/**
 * Arranging Our Stock: the tile counts, the filters, the search and the order.
 *
 * Pure, in the shape [Catalogue] uses for Products, so no rule is re-derived
 * in a composable and every one of them is unit-tested without Android.
 */
object StockBoard {

    fun build(
        stock: List<StockRecord>,
        query: String = "",
        filter: StockFilter = StockFilter.ALL,
        pending: Map<String, Double> = emptyMap()
    ): StockView {
        // Counts are of everything tracked, not of what survives the search,
        // so the tiles hold still while somebody types.
        val tracked = stock.size
        val low = stock.count { StockStatus.of(it.quantity, it.reorderLevel) == StockStatus.LOW }
        val out = stock.count { StockStatus.of(it.quantity, it.reorderLevel) == StockStatus.OUT }
        val pinnedCount = stock.count { it.pinned }

        val needle = query.trim().lowercase()
        val rows = stock
            .filter { matchesFilter(it, filter) }
            .filter { needle.isEmpty() || matchesQuery(it, needle) }
            .sortedWith(ordering())
            .map { StockRow(it, pending[it.key] ?: 0.0) }

        return StockView(
            rows = rows,
            tracked = tracked,
            low = low,
            out = out,
            pinnedCount = pinnedCount,
            query = query.trim(),
            filter = filter
        )
    }

    /**
     * Pinned first, oldest pin first, then everything else by name.
     *
     * Ascending [StockRecord.pinOrder] means a new pin is **appended**, which
     * is the convention N2 settled for product pins (audit P4).
     */
    private fun ordering(): Comparator<StockRecord> =
        compareByDescending<StockRecord> { it.pinned }
            .thenBy { if (it.pinned) it.pinOrder else 0.0 }
            .thenBy { displayName(it).lowercase() }
            .thenBy { it.key }

    private fun matchesFilter(record: StockRecord, filter: StockFilter): Boolean =
        when (filter) {
            StockFilter.ALL -> true
            StockFilter.LOW -> StockStatus.of(record.quantity, record.reorderLevel) == StockStatus.LOW
            StockFilter.OUT -> StockStatus.of(record.quantity, record.reorderLevel) == StockStatus.OUT
            StockFilter.PINNED -> record.pinned
        }

    private fun matchesQuery(record: StockRecord, needle: String): Boolean =
        displayName(record).lowercase().contains(needle) ||
            displayModel(record).lowercase().contains(needle) ||
            record.key.lowercase().contains(needle) ||
            record.note.lowercase().contains(needle)

    /**
     * What to call this row.
     *
     * `name` is the descriptive catalogue name, written by the native app as a
     * deliberate additive extension so a Worker — who may read `/stock` but
     * never `/products` — sees more than a model code. A document the PWA
     * wrote carries no `name`, and a manual item carries `manualName`, so the
     * fallback chain has to reach the model and finally the key.
     */
    fun displayName(record: StockRecord): String =
        record.name.ifBlank { record.manualName }
            .ifBlank { displayModel(record) }

    fun displayModel(record: StockRecord): String =
        record.model.ifBlank { record.manualModel }
            .ifBlank { record.key.substringAfterLast('|') }
            .ifBlank { record.key }
}
