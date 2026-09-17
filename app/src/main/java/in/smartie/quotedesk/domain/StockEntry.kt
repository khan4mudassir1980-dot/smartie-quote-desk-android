package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord

/**
 * A stock row that does not exist yet: either a catalogue product being
 * tracked for the first time, or an item somebody typed in by hand.
 *
 * Pure, so every key and every refusal is unit-tested without Firebase.
 *
 * **Only the safe descriptive fields are carried across from the catalogue.**
 * A `/stock` document is readable by a Worker, who may never read `/products`,
 * so a price must never travel this way. [fromProduct] copies the name, the
 * model, the group, the unit and the category, and nothing else.
 */
data class StockEntry(
    val key: String,
    val group: String,
    val model: String,
    /**
     * The descriptive catalogue name, stored on the stock document as a
     * deliberate additive extension so a Worker sees more than a model code.
     * The PWA ignores fields it does not know.
     */
    val name: String = "",
    val manual: Boolean = false,
    val manualName: String = "",
    val manualModel: String = "",
    val categoryId: String = "",
    val unit: String = "each"
) {
    /** `/stock` ids replace only `/` — never the product scheme (audit C6). */
    val documentId: String get() = Keys.stockDocId(key)

    /**
     * Why this entry may not be created, or null when it may.
     *
     * [existingKeys] are the keys already tracked; a second row for the same
     * key would merge onto somebody else's count.
     */
    fun refusal(
        quantity: Double,
        reorderLevel: Double,
        existingKeys: Set<String> = emptySet()
    ): String? = when {
        key.isBlank() || model.isBlank() -> BLANK
        quantity < 0.0 -> NEGATIVE_QUANTITY
        reorderLevel < 0.0 -> NEGATIVE_REORDER
        key in existingKeys -> ALREADY_TRACKED
        else -> null
    }

    companion object {
        const val MANUAL_GROUP = "manualstock"

        const val BLANK = "Enter a model or an item name"
        const val NEGATIVE_QUANTITY = "Starting quantity cannot be negative"
        const val NEGATIVE_REORDER = "Reorder level cannot be negative"
        const val ALREADY_TRACKED = "This item is already in stock"

        /**
         * Track a catalogue product.
         *
         * The stock key is the PWA's `group|model`, which is **not** the
         * product document id — see [Keys.stockDocId] against
         * [Keys.productDocId].
         */
        fun fromProduct(product: ProductRecord): StockEntry = StockEntry(
            key = Keys.productKey(product.group, product.model),
            group = product.group,
            model = product.model,
            name = product.name.trim().ifBlank { product.model },
            manual = false,
            categoryId = product.categoryId,
            unit = product.unit.trim().ifBlank { "each" }
        )

        /**
         * An item somebody typed in. Either field alone is enough; the other
         * stands in for it.
         */
        fun manual(
            model: String,
            name: String,
            categoryId: String = "",
            unit: String = "each"
        ): StockEntry {
            val typedModel = model.trim()
            val typedName = name.trim()
            val display = typedModel.ifBlank { typedName }
            val slug = slug(display)
            return StockEntry(
                key = if (slug.isBlank()) "" else "$MANUAL_GROUP|$slug",
                group = MANUAL_GROUP,
                model = slug,
                name = typedName.ifBlank { display },
                manual = true,
                manualName = typedName,
                manualModel = display,
                categoryId = categoryId.trim(),
                unit = unit.trim().ifBlank { "each" }
            )
        }

        /** Letters and digits survive; everything else becomes one `_`. */
        fun slug(raw: String): String =
            raw.trim().lowercase()
                .map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("")
                .replace(Regex("_+"), "_")
                .trim('_')
    }
}
