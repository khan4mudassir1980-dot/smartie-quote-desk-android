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

private fun DocumentSnapshot.stringValue(field: String): String? = get(field) as? String
private fun DocumentSnapshot.numberValue(field: String): Number? = get(field) as? Number
private fun DocumentSnapshot.booleanValue(field: String): Boolean? = get(field) as? Boolean

internal fun DocumentSnapshot.toUserProfile(): UserProfile? {
    if (!exists()) return null
    return UserProfile(
        uid = id,
        name = stringValue("name").orEmpty(),
        email = stringValue("email").orEmpty(),
        photoUrl = stringValue("photoURL").orEmpty(),
        role = MemberRole.from(stringValue("role")),
        active = booleanValue("active") != false,
        createdAt = numberValue("createdAt")?.toLong() ?: 0,
        // Primary-owner identity is resolved from teamSettings/access at sign-in.
        // It is intentionally not derived from or stored against a public email.
        primaryOwner = false,
    )
}

internal fun DocumentSnapshot.toProduct() = Product(
    id = stringValue("id") ?: stringValue("key") ?: id,
    documentId = id,
    model = stringValue("model").orEmpty(),
    name = stringValue("name").orEmpty(),
    group = stringValue("group").orEmpty(),
    categoryId = stringValue("categoryId").orEmpty(),
    specification = stringValue("spec").orEmpty(),
    unit = stringValue("unit") ?: "each",
    gst = numberValue("gst")?.toDouble() ?: 18.0,
    dealer = numberValue("dealer")?.toDouble(),
    contractor = numberValue("contractor")?.toDouble(),
    client = numberValue("client")?.toDouble(),
    active = booleanValue("active") != false,
    seedModel = stringValue("seedModel").orEmpty(),
)

internal fun DocumentSnapshot.toStockItem() = StockItem(
    key = stringValue("key") ?: id,
    quantity = numberValue("q")?.toDouble() ?: 0.0,
    reorderLevel = numberValue("min")?.toDouble() ?: 0.0,
    updatedAt = numberValue("t")?.toLong() ?: 0,
    updatedBy = stringValue("by").orEmpty(),
    updatedByUid = stringValue("byUid").orEmpty(),
    note = stringValue("stockNote").orEmpty(),
    lastAction = stringValue("lastAction").orEmpty(),
    group = stringValue("group").orEmpty(),
    model = stringValue("model").orEmpty(),
    pinned = booleanValue("pinned") == true,
    pinOrder = numberValue("pinOrder")?.toDouble() ?: 0.0,
    manual = booleanValue("manual") == true,
    manualName = stringValue("manualName").orEmpty(),
    manualModel = stringValue("manualModel").orEmpty(),
    categoryId = stringValue("categoryId").orEmpty(),
    unit = stringValue("unit") ?: "each",
)

internal fun DocumentSnapshot.toStockMovement() = StockMovement(
    id = stringValue("id") ?: id,
    key = stringValue("key").orEmpty(),
    action = stringValue("action").orEmpty(),
    previous = numberValue("prev")?.toDouble() ?: 0.0,
    next = numberValue("next")?.toDouble() ?: 0.0,
    quantity = numberValue("qty")?.toDouble() ?: 0.0,
    note = stringValue("note").orEmpty(),
    at = numberValue("at")?.toLong() ?: 0,
    by = stringValue("by").orEmpty(),
)

internal fun DocumentSnapshot.toPurchaseRequirement() = PurchaseRequirement(
    id = stringValue("id") ?: id,
    name = stringValue("name").orEmpty(),
    quantity = numberValue("qty")?.toDouble() ?: 1.0,
    urgency = Urgency.from(stringValue("urgency")),
    status = stringValue("status") ?: "Needed",
    note = stringValue("note").orEmpty(),
    addedBy = stringValue("by").orEmpty(),
    addedByUid = stringValue("byUid").orEmpty(),
    updatedAt = numberValue("updated")?.toLong() ?: 0,
    received = booleanValue("received") == true,
)

internal fun DocumentSnapshot.toQuotationSummary(): QuotationSummary {
    val partyValue = get("party")
    val partyFromMap = (partyValue as? Map<*, *>)?.let { party ->
        (party["company"] as? String).orEmpty().ifBlank { (party["name"] as? String).orEmpty() }
    }.orEmpty()
    return QuotationSummary(
        id = stringValue("id") ?: id,
        number = stringValue("no").orEmpty(),
        partyName = stringValue("partyName").orEmpty().ifBlank {
            (partyValue as? String).orEmpty().ifBlank { partyFromMap }
        },
        total = (get("total") as? Number)?.toDouble() ?: 0.0,
        createdAt = (get("at") as? Number)?.toLong() ?: 0,
        status = stringValue("status") ?: "Saved",
    )
}

internal fun DocumentSnapshot.toCustomer() = Customer(
    id = stringValue("id") ?: id,
    name = stringValue("name").orEmpty(),
    company = stringValue("company").orEmpty(),
    phone = stringValue("phone").orEmpty(),
    email = stringValue("email").orEmpty(),
    gstin = stringValue("gstin").orEmpty(),
    city = stringValue("city").orEmpty(),
    address = stringValue("address").orEmpty(),
)
