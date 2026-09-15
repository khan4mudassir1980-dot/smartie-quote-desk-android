package `in`.smartie.quotedesk.data.mapping

import `in`.smartie.quotedesk.data.model.ProductCategoryRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.StockMove
import `in`.smartie.quotedesk.data.model.StockRecord

fun DocData.toProductRecord(): ProductRecord {
    val key = stringOrNull("id", "key") ?: id
    val split = Keys.splitProductKey(key) ?: Keys.splitProductKey(id)
    val group = stringOrNull("group") ?: split?.first.orEmpty()
    val seedModel = stringOrNull("seedModel") ?: split?.second ?: stringOrNull("model").orEmpty()
    return ProductRecord(
        documentId = id,
        key = if (group.isNotEmpty() && seedModel.isNotEmpty()) Keys.productKey(group, seedModel) else key,
        group = group,
        seedModel = seedModel,
        model = string("model", default = seedModel),
        name = string("name"),
        unit = string("unit", default = "each"),
        spec = string("spec", "specification"),
        gst = double("gst", default = 18.0),
        // A blank price is a deliberate "not set"; it must not become zero.
        dealer = optionalDouble("dealer"),
        contractor = optionalDouble("contractor"),
        client = optionalDouble("client"),
        categoryId = string("categoryId"),
        active = bool("active", default = true),
        kg = optionalDouble("kg"),
        conflictResolved = bool("conflictResolved"),
        seeded = bool("seeded"),
        reviewNote = string("reviewNote", "f", "conflict"),
        updatedAt = millis("updated", "serverAt", "updatedAt"),
        updatedBy = string("by"),
        updatedByUid = string("byUid"),
        legacyDocIds = this["legacyDocIds"].asStringList(),
        schemaVersion = int("schemaVersion")
    )
}

fun DocData.toProductCategory(): ProductCategoryRecord = ProductCategoryRecord(
    id = string("id", default = id),
    name = string("name", default = id),
    order = double("order"),
    archived = bool("archived")
)

/** `teamSettings/categories` holds `{map:{id:{…}}}`. */
fun DocData.toProductCategories(): List<ProductCategoryRecord> =
    map("map").entries.mapNotNull { (categoryId, value) ->
        val fields = value.asMapOrNull() ?: return@mapNotNull null
        DocData(categoryId, fields).toProductCategory()
    }.sortedWith(compareBy({ it.order }, { it.name.lowercase() }))

fun DocData.toStockRecord(): StockRecord {
    val key = stringOrNull("key") ?: id
    val split = Keys.splitProductKey(key)
    return StockRecord(
        documentId = id,
        key = key,
        // Imported rows store numbers as strings; strict casts turned them to 0.
        quantity = double("q", "quantity"),
        reorderLevel = double("min", "reorderLevel"),
        // `off` is written as 0/1; archived items must stay hidden.
        archived = bool("off", "archived"),
        updatedAt = millis("t", "serverAt", "updatedAt"),
        updatedBy = string("by"),
        updatedByUid = string("byUid"),
        lastAction = string("lastAction"),
        pinned = bool("pinned"),
        pinOrder = double("pinOrder"),
        manual = bool("manual"),
        manualName = string("manualName"),
        manualModel = string("manualModel"),
        categoryId = string("categoryId"),
        unit = string("unit", default = "each"),
        linkedKey = string("linkedKey"),
        note = string("stockNote", "note"),
        name = string("name", "manualName"),
        model = string("model", "manualModel", default = split?.second.orEmpty()),
        group = string("group", default = split?.first.orEmpty()),
        schemaVersion = int("schemaVersion")
    )
}

fun DocData.toStockMove(): StockMove {
    val previous = double("prev")
    val next = double("next")
    val storedDelta = optionalDouble("delta")
    return StockMove(
        id = string("id", default = id),
        key = string("key"),
        group = string("group"),
        model = string("model"),
        name = string("name"),
        action = string("action"),
        previous = previous,
        // PWA documents carry a signed `delta`; the beta wrote an absolute
        // `qty` and no delta, so fall back to next - prev before `qty`.
        delta = storedDelta ?: (next - previous).takeIf { it != 0.0 } ?: double("qty"),
        next = next,
        reorderLevel = double("min"),
        note = string("note"),
        by = string("by"),
        byUid = string("byUid"),
        at = millis("at", "serverAt")
    )
}
