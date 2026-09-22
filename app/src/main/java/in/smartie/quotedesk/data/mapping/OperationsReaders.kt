package `in`.smartie.quotedesk.data.mapping

import `in`.smartie.quotedesk.data.model.NumberingRecord
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.data.model.UrgencyV2

fun DocData.toPurchaseRecord(): PurchaseRecord = PurchaseRecord(
    // **The document's own id, not the stored `id` field.** They are the same
    // on every row this app or the PWA can write — the rules refuse a create
    // or an update whose `id` is not the document's — but preferring the
    // field meant two documents could collapse onto one `PurchaseRecord.id`
    // and crash the board's `LazyColumn`, and a row where the two ever drifted
    // addressed a document that does not exist on every write. The document
    // id is the one identity Firestore guarantees, so it is the one used.
    id = id,
    name = string("name"),
    key = string("key"),
    quantity = double("qty", "quantity"),
    urgency = UrgencyV2.from(stringOrNull("urgency")),
    note = string("note"),
    status = string("status", default = "Needed"),
    by = string("by"),
    byUid = string("byUid"),
    updatedByName = string("upBy"),
    updatedByUid = string("upUid"),
    // `t` is the PWA's creation time; the beta never wrote one.
    createdAt = millis("t", "createdAt", "updated"),
    updatedAt = millis("updated", "serverAt", "updatedAt"),
    // PWA writes `received` as 0/1; the beta read it as Boolean only, so
    // received items kept showing as active.
    received = bool("received"),
    receivedQuantity = optionalDouble("rcvQty"),
    receivedBy = string("rcvBy"),
    receivedByUid = string("rcvUid"),
    receivedAt = millis("rcvAt"),
    stocked = bool("stocked"),
    stockedQuantity = optionalDouble("stockedQty"),
    cancelledBy = string("cancelledBy"),
    cancelledByUid = string("cancelledUid"),
    cancelledAt = millis("cancelledAt"),
    // Soft delete: `del:1`. A hard delete would let PWA devices resurrect it.
    deleted = bool("del", "deleted"),
    // `delBy` and `delAt` are this app's; a PWA removal wrote neither, so
    // History falls back to `deletedBy` and to the update time.
    removedBy = string("delBy"),
    removedByUid = string("deletedBy"),
    removedAt = millis("delAt"),
    revision = int("rev")
)

fun DocData.toPartyRecord(): PartyRecord {
    // The beta stored the party name in `company` and the contact person in
    // `name`. Read both so beta-written documents still show a party name.
    val betaCompany = stringOrNull("company")
    val storedName = stringOrNull("name")
    val name = betaCompany ?: storedName.orEmpty()
    val contact = stringOrNull("contact") ?: if (betaCompany != null) storedName.orEmpty() else ""
    return PartyRecord(
        id = string("id", default = id),
        name = name,
        type = string("type"),
        city = string("city"),
        gstin = string("gstin"),
        contact = contact,
        phone = string("phone"),
        email = string("email"),
        address = string("address"),
        notes = string("notes"),
        archived = bool("archived"),
        createdAt = millis("t", "createdAt"),
        by = string("by"),
        byUid = string("byUid"),
        updatedAt = millis("updated", "serverAt", "updatedAt"),
        updatedByName = string("upBy"),
        updatedByUid = string("upUid")
    )
}

private fun Map<String, Any?>.toQuotationLine(): QuotationLineRecord {
    val line = DocData("line", this)
    val quantity = line.double("qty", "quantity")
    val rate = line.double("rate")
    return QuotationLineRecord(
        // PWA: t/s/u/k. Beta: description/model/unit/productId.
        title = line.string("t", "description", "model", "name"),
        spec = line.string("s", "spec", "specification"),
        unit = line.string("u", "unit", default = "each"),
        quantity = quantity,
        rate = rate,
        originalRate = line.optionalDouble("origRate"),
        key = line.string("k", "productId", "key"),
        manual = line.bool("manual"),
        amount = line.optionalDouble("amt", "amount") ?: (quantity * rate)
    )
}

private fun Map<String, Any?>.toPartySnapshot(): QuotationPartySnapshot {
    val party = DocData("party", this)
    val betaCompany = party.stringOrNull("company")
    return QuotationPartySnapshot(
        name = betaCompany ?: party.string("name"),
        site = party.string("site"),
        gstin = party.string("gstin"),
        contact = party.string("contact"),
        phone = party.string("phone"),
        email = party.string("email"),
        address = party.string("address"),
        city = party.string("city")
    )
}

fun DocData.toQuotationRecord(): QuotationRecord {
    val partyMap = this["party"].asMapOrNull()
    val betaShape = partyMap?.containsKey("company") == true ||
        has("gstTotal") ||
        string("status") == "Issued"
    val party = partyMap?.toPartySnapshot()
        ?: QuotationPartySnapshot(name = string("partyName", "party"))
    val rawLines = this["lines"].asList().mapNotNull { it.asMapOrNull() }
    val lines = rawLines.map { it.toQuotationLine() }
    val subtotal = optionalDouble("subtotal") ?: lines.sumOf { it.amount }
    // The beta had no document-level GST: it stored a percentage per line.
    val betaLineGst = rawLines.firstNotNullOfOrNull { it["gst"].asDoubleOrNull() }
    return QuotationRecord(
        id = string("id", default = id),
        number = string("no", "number"),
        at = millis("at", "createdAt", "serverAt"),
        by = string("by", "createdBy"),
        byUid = string("byUid"),
        tier = RateTierV2.from(stringOrNull("tier")),
        tierName = string("tierName", default = RateTierV2.from(stringOrNull("tier")).label),
        partyId = string("partyId"),
        party = party,
        lines = lines,
        // PWA: one boolean `gst` plus `gstPct`. Beta: per-line gst + gstTotal.
        gstEnabled = first("gst").asBoolOrNull() ?: (double("gstTotal") > 0.0),
        gstPercent = optionalDouble("gstPct") ?: betaLineGst ?: 0.0,
        subtotal = subtotal,
        total = double("total"),
        status = string("status", default = "Finalised"),
        cancelledBy = string("cancelledBy"),
        cancelledAt = millis("cancelledAt"),
        snapshot = map("snap"),
        schemaVersion = int("schemaVersion"),
        legacyBetaShape = betaShape
    )
}

fun DocData.toNumberingRecord(): NumberingRecord {
    val lastIssued = map("lastIssued")
    return NumberingRecord(
        prefix = string("prefix"),
        financialYear = string("fy"),
        next = int("next", default = 1),
        pad = int("pad", default = 3),
        lastIssuedNumber = lastIssued["no"].asString(),
        lastIssuedAt = lastIssued["at"].asMillis(),
        lastIssuedBy = lastIssued["by"].asString(),
        lastIssuedUid = lastIssued["uid"].asString(),
        updatedAt = millis("updated", "updatedAt"),
        updatedBy = string("by", "updatedBy")
    )
}
