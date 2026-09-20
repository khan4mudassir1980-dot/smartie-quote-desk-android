package `in`.smartie.quotedesk.data.model

import `in`.smartie.quotedesk.data.mapping.Keys

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

    /**
     * The **immutable** identity this product's stock is keyed by.
     *
     * The V8C4 `skey(gid, m)` takes the *seed* catalogue model, not the
     * display model: `L.m` may have been edited, and renaming what a product
     * is called must never open a second stock row, orphan its movement
     * history or change its document id. So identity comes, in order, from:
     *
     * 1. the product's own stored [key] — already `group|seedModel`;
     * 2. `group|seedModel`, when the document carries a seed model;
     * 3. `group|model`, only for a legacy document that has neither.
     *
     * [name] and [model] are display fields and may change freely; this may
     * not. Use it wherever stock is joined to a product — never [model].
     */
    val stockKey: String
        get() = when {
            key.isNotBlank() -> key
            seedModel.isNotBlank() -> Keys.productKey(group, seedModel)
            else -> Keys.productKey(group, model)
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
    /**
     * Reserved for an explicit manual-to-catalogue linking feature that does
     * not exist yet. Ordinary catalogue stock leaves it **empty**: its link to
     * a product is [key] itself. Do not infer a meaning for it from a written
     * value — none has been established against the V8C4 source.
     */
    val linkedKey: String = "",
    val note: String = "",
    /**
     * The safe descriptive name, read from `name` when a document carries one
     * and else from `manualName`.
     *
     * The V8C4 stock document has no `name` field, so a row the PWA wrote
     * leaves this blank and the display falls back to [model]. N3 **does**
     * write it, as an approved additive extension, so a Worker — who may read
     * `/stock` but never `/products` — sees more than a model code. Nothing
     * else from the catalogue is copied here, and the rules refuse any stock
     * write carrying a price field (docs/N3-plan.md).
     */
    val name: String = "",
    val model: String = "",
    val group: String = "",
    /**
     * Whether a `/stockPhotos` document exists for this row. The bytes never
     * live here: the board reads every stock document, and the mobile SDKs
     * have no way to fetch a document without one of its fields.
     */
    val hasPhoto: Boolean = false,
    /**
     * Monotonic. Advances on every set, replace and remove, and is what tells
     * a cached image from a stale one without consulting a clock.
     */
    val photoRev: Double = 0.0,
    val schemaVersion: Int = 0
) {
    val isOut: Boolean get() = quantity <= 0.0
    val isLow: Boolean get() = !isOut && reorderLevel > 0.0 && quantity <= reorderLevel
}

/**
 * One stock item's photo, in its own document so the board never downloads
 * image bytes with the stock list.
 *
 * Not a data class: [bytes] would give it an identity comparison nobody
 * expects, so equality is written out below.
 */
class StockPhotoRecord(
    val documentId: String,
    val key: String,
    val bytes: ByteArray,
    val width: Int = 0,
    val height: Int = 0,
    /** Must equal the stock row's `photoRev` for this image to be shown. */
    val rev: Double = 0.0,
    val by: String = "",
    val byUid: String = "",
    val at: Long = 0L
) {
    override fun equals(other: Any?): Boolean = this === other || (
        other is StockPhotoRecord &&
            documentId == other.documentId && key == other.key &&
            width == other.width && height == other.height && rev == other.rev &&
            by == other.by && byUid == other.byUid && at == other.at &&
            bytes.contentEquals(other.bytes)
        )

    override fun hashCode(): Int {
        var result = documentId.hashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + rev.hashCode()
        return result
    }

    override fun toString(): String =
        "StockPhotoRecord($documentId, rev=$rev, ${width}x$height, ${bytes.size} bytes)"
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

/**
 * How badly something is needed. The wire values are the PWA's and never
 * change; the labels are the words the Owner uses for them on the card.
 *
 * [rank] is the shop floor's order — red first, then yellow, then green — and
 * it is **explicit rather than the declaration order**. `ordinal` would work
 * today and would silently reorder the whole Purchase board the first time
 * somebody inserted a fourth urgency or tidied these three into alphabetical
 * order. A number that has to be edited on purpose cannot do that.
 */
enum class UrgencyV2(val wireValue: String, val label: String, val rank: Int) {
    CRITICAL("critical", "Very urgent", 0),
    URGENT("urgent", "Can wait 1-2 days", 1),
    NORMAL("normal", "Needed, but not now", 2);

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

    /**
     * How many have arrived so far, **across every delivery**.
     *
     * `rcvQty` is a running total in this app, not the size of one delivery,
     * and a missing field means none have arrived — never zero received out
     * of zero required. A negative or unreadable stored value reads as none
     * rather than as a debt, because a V8C4 row is not this app's to trust.
     *
     * [isClosed] is deliberately **not** derived from this. A legacy row
     * carrying `received: true` with `rcvQty` below `qty` is ordinary — the
     * PWA overwrites the field with each delivery — and it stays closed.
     * Arithmetic must never reopen something a person marked finished.
     */
    val receivedTotal: Double
        get() = receivedQuantity?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    /** Still to come. Never negative, whatever a legacy row holds. */
    val remaining: Double get() = (quantity - receivedTotal).coerceAtLeast(0.0)

    /**
     * Everything asked for has arrived.
     *
     * A row with no usable `quantity` is never "fully received": there is no
     * total for a delivery to meet, and every write to such a row is refused
     * until an edit gives it one.
     */
    val isFullyReceived: Boolean
        get() = quantity > 0.0 && receivedTotal >= quantity - QUANTITY_TOLERANCE

    /** Some, but not all — the state the shop floor keeps seeing. */
    val isPartlyReceived: Boolean get() = receivedTotal > 0.0 && !isFullyReceived

    /**
     * Whether a delivery has been recorded against this requirement **at
     * all**, by the presence of any receipt field rather than by its value.
     *
     * The app writes all four together on a receipt and removes all four
     * together on a reopen, so any one of them present means a delivery was
     * recorded — whatever number it carries, and even if the PWA wrote a zero.
     * This is what closes the record to its creator; see `PurchaseAccess`.
     */
    val hasReceipt: Boolean
        get() = receivedQuantity != null ||
            receivedBy.isNotBlank() ||
            receivedByUid.isNotBlank() ||
            receivedAt > 0L

    companion object {
        /**
         * How close two quantities have to be to count as the same.
         *
         * Quantities are `Double`s typed by hand and then added, so three
         * deliveries of `0.1` against a requirement for `0.3` do not sum to
         * exactly `0.3` — and a requirement that will not close because of
         * the seventeenth decimal place is a defect on a shop floor. The
         * display rounds to three places, so a millionth is far below
         * anything anybody can enter or read.
         */
        const val QUANTITY_TOLERANCE: Double = 1e-6
    }
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

/**
 * One removal, as it survives the item.
 *
 * Deliberately small, and deliberately *not* a hidden copy of the stock row.
 * [quantity] is the last quantity, which the history shows; [at] and
 * [removedByUid] are internal — ordering needs the first and the rules need
 * the second — and **neither is ever rendered**. There is no note, no photo,
 * no reorder level, no pin and no price here, because the removal promised
 * those were permanently deleted.
 */
data class StoppedStockRecord(
    val id: String,
    val key: String,
    val name: String = "",
    val model: String = "",
    val unit: String = "each",
    val quantity: Double = 0.0,
    val manual: Boolean = false,
    /** Internal: latest-first ordering only. Never shown. */
    val at: Long = 0L,
    /** Internal: the rules require it. Never shown. */
    val removedByUid: String = ""
) {
    /** "Manual" or "Catalogue" — the only source wording the history shows. */
    val source: String get() = if (manual) "Manual" else "Catalogue"

    /** What to call the row: its name, else its model, else its key. */
    val label: String get() = name.ifBlank { model.ifBlank { key } }
}
