package `in`.smartie.quotedesk.data.model

/**
 * Schema v2 records. These replace the beta models in `Models.kt` phase by
 * phase; the beta models stay until their screen is rebuilt.
 */

data class ProductRecord(
    val documentId: String,
    /** Logical key `group|seedModel`, shared with stock, pins and quote lines. */
    val key: String,
    val group: String,
    val seedModel: String,
    val model: String,
    val name: String = "",
    val unit: String = "each",
    val spec: String = "",
    val gst: Double = 18.0,
    /** null means "Price not set" and forces rate entry — never 0. */
    val dealer: Double? = null,
    val contractor: Double? = null,
    val client: Double? = null,
    val categoryId: String = "",
    val active: Boolean = true,
    val kg: Double? = null,
    val conflictResolved: Boolean = false,
    val seeded: Boolean = false,
    val reviewNote: String = "",
    val updatedAt: Long = 0L,
    val updatedBy: String = "",
    val updatedByUid: String = "",
    val legacyDocIds: List<String> = emptyList(),
    val schemaVersion: Int = 0
) {
    fun priceFor(tier: RateTierV2): Double? = when (tier) {
        RateTierV2.DEALER -> dealer
        RateTierV2.CONTRACTOR -> contractor
        RateTierV2.CLIENT -> client
    }
}

enum class RateTierV2(val wireValue: String, val label: String) {
    DEALER("dealer", "Dealer"),
    CONTRACTOR("contractor", "Contractor"),
    CLIENT("client", "Client");

    companion object {
        fun from(value: String?): RateTierV2 =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: DEALER
    }
}

data class StockRecord(
    val documentId: String,
    val key: String,
    val quantity: Double = 0.0,
    val reorderLevel: Double = 0.0,
    val archived: Boolean = false,
    val updatedAt: Long = 0L,
    val updatedBy: String = "",
    val updatedByUid: String = "",
    val lastAction: String = "",
    val pinned: Boolean = false,
    val pinOrder: Double = 0.0,
    val manual: Boolean = false,
    val manualName: String = "",
    val manualModel: String = "",
    val categoryId: String = "",
    val unit: String = "each",
    val linkedKey: String = "",
    val note: String = "",
    /** Denormalised so Workers can read names without reading prices. */
    val name: String = "",
    val model: String = "",
    val group: String = "",
    val schemaVersion: Int = 0
) {
    val isOut: Boolean get() = quantity <= 0.0
    val isLow: Boolean get() = !isOut && reorderLevel > 0.0 && quantity <= reorderLevel
}

data class StockMove(
    val id: String,
    val key: String,
    val group: String = "",
    val model: String = "",
    val name: String = "",
    val action: String = "",
    val previous: Double = 0.0,
    /** Signed. PWA writes `delta`; the beta wrote an absolute `qty`. */
    val delta: Double = 0.0,
    val next: Double = 0.0,
    val reorderLevel: Double = 0.0,
    val note: String = "",
    val by: String = "",
    val byUid: String = "",
    val at: Long = 0L
)

enum class UrgencyV2(val wireValue: String, val label: String) {
    CRITICAL("critical", "Very urgent"),
    URGENT("urgent", "Urgent"),
    NORMAL("normal", "Normal");

    companion object {
        fun from(value: String?): UrgencyV2 =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: NORMAL
    }
}

data class PurchaseRecord(
    val id: String,
    val name: String = "",
    val key: String = "",
    val quantity: Double = 0.0,
    val urgency: UrgencyV2 = UrgencyV2.NORMAL,
    val note: String = "",
    val status: String = "Needed",
    val by: String = "",
    val byUid: String = "",
    val updatedByName: String = "",
    val updatedByUid: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val received: Boolean = false,
    val receivedQuantity: Double? = null,
    val receivedBy: String = "",
    val receivedByUid: String = "",
    val receivedAt: Long = 0L,
    val stocked: Boolean = false,
    val stockedQuantity: Double? = null,
    val cancelledBy: String = "",
    val cancelledByUid: String = "",
    val cancelledAt: Long = 0L,
    /** PWA soft delete (`del:1`). Deleted items must never be shown. */
    val deleted: Boolean = false,
    val revision: Int = 0
) {
    val isClosed: Boolean get() = received || status == "Received" || status == "Cancelled"
    val isOpen: Boolean get() = !deleted && !isClosed
}

data class PartyRecord(
    val id: String,
    /** The party (firm) name — the beta wrongly stored the contact person here. */
    val name: String = "",
    val type: String = "",
    val city: String = "",
    val gstin: String = "",
    val contact: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: Long = 0L,
    val by: String = "",
    val byUid: String = "",
    val updatedAt: Long = 0L,
    val updatedByName: String = "",
    val updatedByUid: String = ""
)

data class QuotationPartySnapshot(
    val name: String = "",
    val site: String = "",
    val gstin: String = "",
    val contact: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val city: String = ""
)

data class QuotationLineRecord(
    val title: String = "",
    val spec: String = "",
    val unit: String = "each",
    val quantity: Double = 0.0,
    val rate: Double = 0.0,
    val originalRate: Double? = null,
    val key: String = "",
    val manual: Boolean = false,
    val amount: Double = 0.0
)

data class QuotationRecord(
    val id: String,
    val number: String = "",
    val at: Long = 0L,
    val by: String = "",
    val byUid: String = "",
    val tier: RateTierV2 = RateTierV2.DEALER,
    val tierName: String = "",
    val partyId: String = "",
    val party: QuotationPartySnapshot = QuotationPartySnapshot(),
    val lines: List<QuotationLineRecord> = emptyList(),
    val gstEnabled: Boolean = false,
    val gstPercent: Double = 0.0,
    val subtotal: Double = 0.0,
    val total: Double = 0.0,
    val status: String = "Finalised",
    val cancelledBy: String = "",
    val cancelledAt: Long = 0L,
    val snapshot: Map<String, Any?> = emptyMap(),
    val schemaVersion: Int = 0,
    /** True when the document was written by the native beta (audit D4). */
    val legacyBetaShape: Boolean = false
)

data class ProductCategoryRecord(
    val id: String,
    val name: String = "",
    val order: Double = 0.0,
    val archived: Boolean = false
)

data class NumberingRecord(
    val prefix: String = "",
    val financialYear: String = "",
    val next: Int = 1,
    val pad: Int = 3,
    val lastIssuedNumber: String = "",
    val lastIssuedAt: Long = 0L,
    val lastIssuedBy: String = "",
    val lastIssuedUid: String = "",
    val updatedAt: Long = 0L,
    val updatedBy: String = ""
)
