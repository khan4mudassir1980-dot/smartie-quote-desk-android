package `in`.smartie.quotedesk.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.ProductPins
import kotlinx.coroutines.tasks.await

/**
 * Writing the shared pinned shelf.
 *
 * The only product-side write phase N2 adds. It is a **merge** write, so the
 * `updatedBy` and `updatedAt` the PWA shows are not dropped the way the native
 * beta dropped them (audit P4), and the ordering decisions are taken in
 * [ProductPins] where they are unit-tested.
 */
class ProductPinsRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {

    private val document
        get() = firestore.collection("teamSettings").document("productPins")

    /**
     * Saves [keys] as the pinned shelf. Refuses anyone who may not manage pins
     * and anything above the cap, so a doomed write never reaches the rules.
     */
    suspend fun save(viewer: Member, keys: List<String>) {
        require(Permissions.canManageCategoriesAndPins(viewer)) { NOT_ALLOWED }
        require(keys.size <= ProductPins.MAX) { ProductPins.FULL_MESSAGE }
        document.set(
            mapOf(
                "keys" to keys,
                "updatedAt" to System.currentTimeMillis(),
                "updatedBy" to viewer.name.ifBlank { viewer.email },
                "updatedByUid" to auth.currentUser?.uid.orEmpty(),
            ),
            SetOptions.merge()
        ).await()
    }

    private companion object {
        const val NOT_ALLOWED = "Only an Owner or Administrator can manage pinned products"
    }
}
