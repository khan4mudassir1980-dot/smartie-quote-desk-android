package `in`.smartie.quotedesk.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.data.model.CreatedQuotation
import `in`.smartie.quotedesk.data.model.Customer
import `in`.smartie.quotedesk.data.model.QuotationLine
import `in`.smartie.quotedesk.data.model.QuotationSummary
import `in`.smartie.quotedesk.data.model.RateTier
import `in`.smartie.quotedesk.data.model.toCustomer
import `in`.smartie.quotedesk.data.model.toQuotationSummary
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class QuotationRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    fun observeQuotations(): Flow<List<QuotationSummary>> = callbackFlow {
        val registration = firestore.collection("quotations").addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else trySend(snapshot?.documents.orEmpty().mapNotNull {
                runCatching { it.toQuotationSummary() }.getOrNull()
            }.sortedByDescending { it.createdAt })
        }
        awaitClose { registration.remove() }
    }

    fun observeCustomers(): Flow<List<Customer>> = callbackFlow {
        val registration = firestore.collection("customers").addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else trySend(snapshot?.documents.orEmpty().mapNotNull {
                runCatching { it.toCustomer() }.getOrNull()
            }.sortedBy { it.name.lowercase() })
        }
        awaitClose { registration.remove() }
    }

    suspend fun saveCustomer(customer: Customer): Customer {
        val caller = requireNotNull(auth.currentUser)
        require(customer.name.isNotBlank()) { "Customer name is required." }
        val saved = customer.copy(id = customer.id.ifBlank { firestore.collection("customers").document().id })
        firestore.collection("customers").document(saved.id).set(mapOf(
            "id" to saved.id, "name" to saved.name.trim(), "company" to saved.company.trim(),
            "phone" to saved.phone.trim(), "email" to saved.email.trim(), "gstin" to saved.gstin.trim().uppercase(),
            "city" to saved.city.trim(), "address" to saved.address.trim(),
            "updated" to System.currentTimeMillis(), "upBy" to (caller.displayName ?: caller.email.orEmpty()),
            "upUid" to caller.uid,
        )).await()
        return saved
    }

    suspend fun createQuotation(
        customer: Customer,
        tier: RateTier,
        lines: List<QuotationLine>,
        additionalLabel: String,
        additionalAmount: Double,
        saveCustomer: Boolean,
    ): CreatedQuotation {
        require(customer.name.isNotBlank()) { "Customer or party name is required." }
        require(lines.isNotEmpty()) { "Add at least one item." }
        require(lines.all { it.quantity > 0 && it.rate >= 0 && it.gst in 0.0..28.0 }) { "Check item quantities, rates and GST." }
        require(additionalAmount >= 0) { "Additional charge cannot be negative." }
        val caller = requireNotNull(auth.currentUser)
        val savedCustomer = if (saveCustomer) saveCustomer(customer) else customer
        val quoteRef = firestore.collection("quotations").document()
        val numberRef = firestore.collection("teamSettings").document("numbering")
        val now = System.currentTimeMillis()
        val subtotal = lines.sumOf { it.amount }
        val gstTotal = lines.sumOf { it.gstAmount }
        val total = subtotal + gstTotal + additionalAmount
        var issuedNumber = ""

        firestore.runTransaction { transaction ->
            val numbering = transaction.get(numberRef)
            require(numbering.exists()) { "Quotation numbering is not configured. Open the web app Settings once and save shared numbering." }
            val next = (numbering.get("next") as? Number)?.toLong() ?: 1L
            val prefix = (numbering.get("prefix") as? String).orEmpty().ifBlank { "SIE/QD" }
            val fy = (numbering.get("fy") as? String).orEmpty()
            val pad = ((numbering.get("pad") as? Number)?.toInt() ?: 3).coerceIn(1, 8)
            issuedNumber = listOf(prefix, fy, next.toString().padStart(pad, '0')).filter { it.isNotBlank() }.joinToString("/")
            val customerMap = mapOf(
                "id" to savedCustomer.id, "name" to savedCustomer.name.trim(), "company" to savedCustomer.company.trim(),
                "phone" to savedCustomer.phone.trim(), "email" to savedCustomer.email.trim(), "gstin" to savedCustomer.gstin.trim(),
                "city" to savedCustomer.city.trim(), "site" to savedCustomer.city.trim(), "address" to savedCustomer.address.trim(),
            )
            val lineMaps = lines.map { line -> mapOf(
                "productId" to line.productId, "model" to line.model, "description" to line.description,
                "unit" to line.unit, "qty" to line.quantity, "rate" to line.rate, "gst" to line.gst,
                "amount" to line.amount, "gstAmount" to line.gstAmount,
            ) }
            transaction.update(numberRef, mapOf(
                "next" to next + 1,
                "lastIssued" to mapOf("no" to issuedNumber, "at" to now, "by" to (caller.displayName ?: caller.email.orEmpty()), "uid" to caller.uid),
            ))
            transaction.set(quoteRef, mapOf(
                "id" to quoteRef.id, "no" to issuedNumber, "party" to customerMap,
                "partyName" to savedCustomer.name.trim(), "partyId" to savedCustomer.id,
                "tier" to tier.wireValue, "lines" to lineMaps,
                "additionalLabel" to additionalLabel.trim(), "additionalAmount" to additionalAmount,
                "subtotal" to subtotal, "gstTotal" to gstTotal, "total" to total,
                "status" to "Issued", "at" to now,
                "by" to (caller.displayName ?: caller.email.orEmpty()), "byUid" to caller.uid,
            ))
        }.await()

        return CreatedQuotation(
            id = quoteRef.id, number = issuedNumber, customer = savedCustomer, tier = tier, lines = lines,
            additionalLabel = additionalLabel.trim(), additionalAmount = additionalAmount,
            subtotal = subtotal, gstTotal = gstTotal, total = total, createdAt = now,
            createdBy = caller.displayName ?: caller.email.orEmpty(),
        )
    }
}
