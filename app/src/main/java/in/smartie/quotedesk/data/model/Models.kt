package `in`.smartie.quotedesk.data.model

/**
 * What remains of the beta models: the quotation shapes the existing PDF
 * writer still uses. Phase N5 replaces both with the PWA-compatible
 * quotation record in `Records.kt`.
 */
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
