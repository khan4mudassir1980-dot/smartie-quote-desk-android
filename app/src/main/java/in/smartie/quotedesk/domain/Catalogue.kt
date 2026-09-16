package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.ProductCategoryRecord
import `in`.smartie.quotedesk.data.model.ProductRecord

/** A product as the catalogue shows it, with the shelf it was filed on. */
data class CatalogueEntry(
    val product: ProductRecord,
    val categoryId: String,
    val categoryName: String,
    val pinned: Boolean = false
)

/** One collapsible shelf. Its count is what the summary shows. */
data class CatalogueShelf(
    val category: ProductCategoryRecord,
    val entries: List<CatalogueEntry>
) {
    val id: String get() = category.id
    val name: String get() = category.name
    val count: Int get() = entries.size
}

/**
 * What the Products tab draws. Either shelves and a pinned section, or — while
 * a search is running — one flat list of hits.
 */
data class CatalogueView(
    val query: String = "",
    val pinned: List<CatalogueEntry> = emptyList(),
    val shelves: List<CatalogueShelf> = emptyList(),
    val results: List<CatalogueEntry> = emptyList(),
    /** Hits before the display cap; the PWA counts them all. */
    val matchCount: Int = 0
) {
    val searching: Boolean get() = query.isNotBlank()
    val isEmpty: Boolean
        get() = if (searching) results.isEmpty()
        else pinned.isEmpty() && shelves.all { it.entries.isEmpty() }
}

/**
 * Arranging the catalogue: shelving, pinned products, search and the load
 * filter, all as the PWA does it (`catalogue()` 7480-7494, `draw()` 7634-7665,
 * `match` 3085-3090).
 *
 * Pure: it takes the products, the live shelves and the pins, and returns what
 * to draw. Nothing here touches Firestore, so every rule below is unit-tested.
 */
object Catalogue {

    /** The rules cap `teamSettings/productPins` at fifteen; so does the UI. */
    const val MAX_PINS: Int = 15

    /** The PWA renders at most 300 search hits, but counts them all. */
    const val MAX_RESULTS: Int = 300

    fun build(
        products: List<ProductRecord>,
        categories: List<ProductCategoryRecord>,
        pinnedKeys: List<String> = emptyList(),
        query: String = "",
        minimumKg: Double? = null
    ): CatalogueView {
        val live = ProductCategories.merge(categories)
        val shelved = shelve(products, live, minimumKg)

        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            val needle = trimmed.lowercase()
            val hits = shelved.filter { it.matches(needle) }
            return CatalogueView(
                query = trimmed,
                results = hits.take(MAX_RESULTS),
                matchCount = hits.size
            )
        }

        val pinned = resolvePins(pinnedKeys, shelved)
        val pinnedKeySet = pinned.mapTo(mutableSetOf()) { it.product.key }
        val filtering = minimumKg != null && minimumKg > 0.0
        val byShelf = shelved.filterNot { it.product.key in pinnedKeySet }.groupBy { it.categoryId }

        val shelves = live
            .map { category -> CatalogueShelf(category, byShelf[category.id].orEmpty()) }
            // An empty shelf still shows, with a count of zero, so the
            // catalogue reads the same as the PWA's. Under a load filter that
            // would leave a page of empty accordions, so those are hidden.
            .filterNot { filtering && it.entries.isEmpty() }

        return CatalogueView(pinned = pinned, shelves = shelves)
    }

    /**
     * Every sellable product, filed on a shelf, with the duplicates the PWA
     * suppresses removed: one logical key appears once, and so does one
     * display model, even when two price groups carry it (7485-7488).
     */
    private fun shelve(
        products: List<ProductRecord>,
        live: List<ProductCategoryRecord>,
        minimumKg: Double?
    ): List<CatalogueEntry> {
        val seenKeys = mutableSetOf<String>()
        val seenModels = mutableSetOf<String>()
        val entries = mutableListOf<CatalogueEntry>()
        for (product in products) {
            if (!product.active) continue
            if (minimumKg != null && minimumKg > 0.0 && (product.kg == null || product.kg < minimumKg)) {
                continue
            }
            val modelKey = normalise(product.model)
            if (product.key in seenKeys || (modelKey.isNotEmpty() && modelKey in seenModels)) continue
            seenKeys += product.key
            if (modelKey.isNotEmpty()) seenModels += modelKey
            val shelf = ProductCategories.shelfFor(product.categoryId, live)
            entries += CatalogueEntry(
                product = product,
                categoryId = shelf,
                categoryName = ProductCategories.nameFor(shelf, live)
            )
        }
        return entries
    }

    /**
     * Pins in the order they were saved, dropping any whose product has gone,
     * and never more than the cap (7655-7657).
     */
    private fun resolvePins(
        pinnedKeys: List<String>,
        shelved: List<CatalogueEntry>
    ): List<CatalogueEntry> {
        if (pinnedKeys.isEmpty()) return emptyList()
        val byKey = shelved.associateBy { it.product.key }
        return pinnedKeys.asSequence()
            .distinct()
            .mapNotNull { byKey[it] }
            .map { it.copy(pinned = true) }
            .take(MAX_PINS)
            .toList()
    }

    /** Model, name, specification and shelf name, as the PWA searches (7643). */
    private fun CatalogueEntry.matches(needle: String): Boolean {
        val haystack = buildString {
            append(product.model).append(' ')
            append(product.name).append(' ')
            append(product.spec).append(' ')
            append(categoryName)
        }
        return haystack.lowercase().contains(needle)
    }

    /** `norm` (5681): letters and digits only, so spacing cannot hide a twin. */
    private fun normalise(value: String): String =
        value.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
}
