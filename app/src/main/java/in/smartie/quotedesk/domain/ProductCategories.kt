package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.ProductCategoryRecord

/**
 * The shelves the catalogue is arranged on.
 *
 * The twelve defaults live in the PWA's own source (`index.html:7346-7359`)
 * and `teamSettings/categories` holds only what an administrator has since
 * changed. The PWA merges the two on every render (`categories()` 7416-7423),
 * so the native app does the same: a product lands on the right shelf whether
 * or not migration step M2.2 has written the defaults to Firestore, and a
 * shelf never shows as a raw id (audit P5).
 */
object ProductCategories {

    /** `index.html:7346-7359`, ids and orders verbatim. */
    val DEFAULTS: List<ProductCategoryRecord> = listOf(
        ProductCategoryRecord("cat-sliding", "Sliding Gate Motors", 10.0),
        ProductCategoryRecord("cat-swing", "Swing Gate Motors", 20.0),
        ProductCategoryRecord("cat-shutter", "Shutter Motors", 30.0),
        ProductCategoryRecord("cat-hsd", "High-Speed Door Motors", 40.0),
        ProductCategoryRecord("cat-boom", "Boom Barriers", 50.0),
        ProductCategoryRecord("cat-garage", "Garage Door Motors", 60.0),
        ProductCategoryRecord("cat-glass", "Automatic/Glass Door Systems", 70.0),
        ProductCategoryRecord("cat-acc", "Gate Motor Accessories", 80.0),
        ProductCategoryRecord("cat-sensor", "Sensors & Safety Devices", 90.0),
        ProductCategoryRecord("cat-control", "Control Boards, Receivers & Remotes", 100.0),
        ProductCategoryRecord("cat-hardware", "Gate Hardware", 110.0),
        ProductCategoryRecord("cat-other", "Other Products", 900.0)
    )

    /**
     * Shelves that earlier versions used. A product filed on one of them keeps
     * working and lands on its nearest surviving shelf; the product's own id
     * never changes, so stock and past quotations still point at it
     * (`index.html:7364-7369`).
     */
    val ALIASES: Map<String, String> = mapOf(
        "cat-kits" to "cat-hardware",
        "cat-profile" to "cat-hardware",
        "cat-service" to "cat-other"
    )

    /** Where a product goes when its shelf is unknown, archived or missing. */
    const val FALLBACK_ID: String = "cat-other"

    /** The PWA's wording when even the fallback shelf is not configured. */
    private const val FALLBACK_NAME: String = "Other Products / Needs categorisation"

    /** An id an override or a product may still carry, mapped to today's. */
    fun alias(id: String?): String = id?.trim().orEmpty().let { ALIASES[it] ?: it }

    /**
     * The live shelves, in display order: the administrator's overrides laid
     * over the defaults, unknown shelves appended, archived ones dropped.
     *
     * An override that omits a field keeps the default's: the PWA's
     * `Object.assign` does that, and `toProductCategories` cannot tell an
     * absent `name` or `order` from a blank one, so a name equal to the id and
     * an order of zero are both read as "not supplied". Every category the PWA
     * and the migration write carries all three fields, so this only matters
     * for a hand-edited document.
     */
    fun merge(overrides: List<ProductCategoryRecord>): List<ProductCategoryRecord> {
        val byId = overrides.associateBy { alias(it.id) }
        val base = DEFAULTS.map { default ->
            val override = byId[default.id] ?: return@map default
            default.copy(
                name = override.name.takeIf { it.isNotBlank() && it != override.id } ?: default.name,
                order = override.order.takeIf { it != 0.0 } ?: default.order,
                archived = override.archived
            )
        }
        val defaultIds = DEFAULTS.mapTo(mutableSetOf(), ProductCategoryRecord::id)
        val extra = overrides
            .filter { alias(it.id) !in defaultIds }
            .map { it.copy(id = alias(it.id), order = if (it.order == 0.0) 500.0 else it.order) }
        return (base + extra)
            .filterNot { it.archived }
            .sortedWith(compareBy({ it.order }, { it.name.lowercase() }))
    }

    /**
     * The shelf a product is displayed on: its own category once aliased, or
     * the fallback when that shelf has been archived or never existed
     * (`categoryOf` 7431-7438).
     */
    fun shelfFor(categoryId: String?, live: List<ProductCategoryRecord>): String {
        val aliased = alias(categoryId)
        return if (aliased.isNotEmpty() && live.any { it.id == aliased }) aliased else FALLBACK_ID
    }

    /** A shelf's display name, never a raw id (`categoryById` 7425-7427). */
    fun nameFor(categoryId: String?, live: List<ProductCategoryRecord>): String {
        val aliased = alias(categoryId)
        return live.firstOrNull { it.id == aliased }?.name
            ?: DEFAULTS.firstOrNull { it.id == aliased }?.name
            ?: FALLBACK_NAME
    }
}
