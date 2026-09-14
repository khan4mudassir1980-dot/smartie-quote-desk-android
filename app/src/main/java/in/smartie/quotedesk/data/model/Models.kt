package `in`.smartie.quotedesk.data.model

import com.google.firebase.firestore.DocumentSnapshot

enum class MemberRole(val wireValue: String, val label: String) {
    OWNER("owner", "Owner / Administrator"),
    ADMIN("admin", "Administrator"),
    STAFF("staff", "Staff"),
    WORKER("worker", "Worker");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.wireValue == value } ?: WORKER
    }
}

data class UserProfile(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val role: MemberRole = MemberRole.WORKER,
    val active: Boolean = true,
    val createdAt: Long = 0,
    val primaryOwner: Boolean = false,
) {
    val isOriginalOwner: Boolean get() = primaryOwner
    val isOwner: Boolean get() = isOriginalOwner || role == MemberRole.OWNER
    val isAdmin: Boolean get() = isOwner || role == MemberRole.ADMIN
    val canWriteStock: Boolean get() = isAdmin || role == MemberRole.STAFF
    val canQuote: Boolean get() = role != MemberRole.WORKER
}

data class Product(
    val id: String = "",
    val documentId: String = "",
    val model: String = "",
    val name: String = "",
    val group: String = "",
    val categoryId: String = "",
    val specification: String = "",
    val unit: String = "each",
    val gst: Double = 18.0,
    val dealer: Double? = null,
    val contractor: Double? = null,
    val client: Double? = null,
    val active: Boolean = true,
    val seedModel: String = "",
)

data class ProductCategory(
    val id: String = "",
    val name: String = "",
    val order: Double = 0.0,
    val archived: Boolean = false,
)

data class StockItem(
    val key: String = "",
    val quantity: Double = 0.0,
    val reorderLevel: Double = 0.0,
    val updatedAt: Long = 0,
    val updatedBy: String = "",
    val updatedByUid: String = "",
    val note: String = "",
    val lastAction: String = "",
    val group: String = "",
    val model: String = "",
    val pinned: Boolean = false,
    val pinOrder: Double = 0.0,
    val manual: Boolean = false,
    val manualName: String = "",
    val manualModel: String = "",
    val categoryId: String = "",
    val unit: String = "each",
) {
    val isOut: Boolean get() = quantity <= 0
    val isLow: Boolean get() = quantity > 0 && quantity <= reorderLevel
    val displayModel: String get() = manualModel.ifBlank { model.ifBlank { key.substringAfterLast('|') } }
    val displayName: String get() = manualName.ifBlank { displayModel }
}

data class StockMovement(
    val id: String = "",
    val key: String = "",
    val action: String = "",
    val previous: Double = 0.0,
    val next: Double = 0.0,
    val quantity: Double = 0.0,
    val note: String = "",
    val at: Long = 0,
    val by: String = "",
)

enum class Urgency(val wireValue: String, val label: String) {
    CRITICAL("critical", "Very urgent"),
    URGENT("urgent", "Urgent"),
    NORMAL("normal", "Normal");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.wireValue == value } ?: NORMAL
    }
}

data class PurchaseRequirement(
    val id: String = "",
    val name: String = "",
    val quantity: Double = 1.0,
    val urgency: Urgency = Urgency.NORMAL,
    val status: String = "Needed",
    val note: String = "",
    val addedBy: String = "",
    val addedByUid: String = "",
    val updatedAt: Long = 0,
    val received: Boolean = false,
)

data class QuotationSummary(
    val id: String = "",
    val number: String = "",
    val partyName: String = "",
    val total: Double = 0.0,
    val createdAt: Long = 0,
    val status: String = "Saved",
)

enum class RateTier(val wireValue: String, val label: String) {
    DEALER("dealer", "Dealer"),
    CONTRACTOR("contractor", "Contractor"),
    CLIENT("client", "Client");
}

data class QuotationLine(
    val productId: String = "",
    val model: String = "",
    val description: String = "",
    val unit: String = "each",
    val quantity: Double = 1.0,
    val rate: Double = 0.0,
    val gst: Double = 18.0,
) {
    val amount: Double get() = quantity * rate
    val gstAmount: Double get() = amount * gst / 100.0
}

data class Customer(
    val id: String = "",
    val name: String = "",
    val company: String = "",
    val phone: String = "",
    val email: String = "",
    val gstin: String = "",
    val city: String = "",
    val address: String = "",
)

data class CreatedQuotation(
    val id: String,
    val number: String,
    val customer: Customer,
    val tier: RateTier,
    val lines: List<QuotationLine>,
    val additionalLabel: String,
    val additionalAmount: Double,
    val subtotal: Double,
    val gstTotal: Double,
    val total: Double,
    val createdAt: Long,
    val createdBy: String,
)

internal fun DocumentSnapshot.toUserProfile(): UserProfile? {
    if (!exists()) return null
    return UserProfile(
        uid = id,
        name = getString("name").orEmpty(),
        email = getString("email").orEmpty(),
        photoUrl = getString("photoURL").orEmpty(),
        role = MemberRole.from(getString("role")),
        active = getBoolean("active") != false,
        createdAt = getLong("createdAt") ?: 0,
        // Primary-owner identity is resolved from teamSettings/access at sign-in.
        // It is intentionally not derived from or stored against a public email.
        primaryOwner = false,
    )
}

internal fun DocumentSnapshot.toProduct() = Product(
    id = getString("id") ?: getString("key") ?: id,
    documentId = id,
    model = getString("model").orEmpty(),
    name = getString("name").orEmpty(),
    group = getString("group").orEmpty(),
    categoryId = getString("categoryId").orEmpty(),
    specification = getString("spec").orEmpty(),
    unit = getString("unit") ?: "each",
    gst = getDouble("gst") ?: getLong("gst")?.toDouble() ?: 18.0,
    dealer = getDouble("dealer") ?: getLong("dealer")?.toDouble(),
    contractor = getDouble("contractor") ?: getLong("contractor")?.toDouble(),
    client = getDouble("client") ?: getLong("client")?.toDouble(),
    active = getBoolean("active") != false,
    seedModel = getString("seedModel").orEmpty(),
)

internal fun DocumentSnapshot.toStockItem() = StockItem(
    key = getString("key") ?: id,
    quantity = getDouble("q") ?: getLong("q")?.toDouble() ?: 0.0,
    reorderLevel = getDouble("min") ?: getLong("min")?.toDouble() ?: 0.0,
    updatedAt = getLong("t") ?: 0,
    updatedBy = getString("by").orEmpty(),
    updatedByUid = getString("byUid").orEmpty(),
    note = getString("stockNote").orEmpty(),
    lastAction = getString("lastAction").orEmpty(),
    group = getString("group").orEmpty(),
    model = getString("model").orEmpty(),
    pinned = getBoolean("pinned") == true,
    pinOrder = getDouble("pinOrder") ?: getLong("pinOrder")?.toDouble() ?: 0.0,
    manual = getBoolean("manual") == true,
    manualName = getString("manualName").orEmpty(),
    manualModel = getString("manualModel").orEmpty(),
    categoryId = getString("categoryId").orEmpty(),
    unit = getString("unit") ?: "each",
)

internal fun DocumentSnapshot.toStockMovement() = StockMovement(
    id = getString("id") ?: id,
    key = getString("key").orEmpty(),
    action = getString("action").orEmpty(),
    previous = getDouble("prev") ?: getLong("prev")?.toDouble() ?: 0.0,
    next = getDouble("next") ?: getLong("next")?.toDouble() ?: 0.0,
    quantity = getDouble("qty") ?: getLong("qty")?.toDouble() ?: 0.0,
    note = getString("note").orEmpty(),
    at = getLong("at") ?: 0,
    by = getString("by").orEmpty(),
)

internal fun DocumentSnapshot.toPurchaseRequirement() = PurchaseRequirement(
    id = getString("id") ?: id,
    name = getString("name").orEmpty(),
    quantity = getDouble("qty") ?: getLong("qty")?.toDouble() ?: 1.0,
    urgency = Urgency.from(getString("urgency")),
    status = getString("status") ?: "Needed",
    note = getString("note").orEmpty(),
    addedBy = getString("by").orEmpty(),
    addedByUid = getString("byUid").orEmpty(),
    updatedAt = getLong("updated") ?: 0,
    received = getBoolean("received") == true,
)

internal fun DocumentSnapshot.toQuotationSummary() = QuotationSummary(
    id = getString("id") ?: id,
    number = getString("no").orEmpty(),
    partyName = getString("party") ?: getString("partyName").orEmpty(),
    total = getDouble("total") ?: getLong("total")?.toDouble() ?: 0.0,
    createdAt = getLong("at") ?: 0,
    status = getString("status") ?: "Saved",
)

internal fun DocumentSnapshot.toCustomer() = Customer(
    id = getString("id") ?: id,
    name = getString("name").orEmpty(),
    company = getString("company").orEmpty(),
    phone = getString("phone").orEmpty(),
    email = getString("email").orEmpty(),
    gstin = getString("gstin").orEmpty(),
    city = getString("city").orEmpty(),
    address = getString("address").orEmpty(),
)
