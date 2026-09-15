package `in`.smartie.quotedesk.data.model

import com.google.firebase.firestore.DocumentSnapshot
import `in`.smartie.quotedesk.data.mapping.toDocData
import `in`.smartie.quotedesk.data.mapping.toTeamMember

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
    // Goes through the tolerant reader so a legacy profile with a missing
    // `active` flag or an unknown role still resolves (audit C7).
    val member = toDocData().toTeamMember()
    return UserProfile(
        uid = member.uid,
        name = member.name,
        email = member.email,
        photoUrl = member.photoUrl,
        role = MemberRole.from(member.roleWireValue),
        active = member.active,
        createdAt = member.createdAt,
        // Primary-owner identity is resolved from teamSettings/access at sign-in.
        // It is intentionally not derived from or stored against a public email.
        primaryOwner = false,
    )
}
