package `in`.smartie.quotedesk.core

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.data.repository.AuthRepository
import `in`.smartie.quotedesk.data.repository.PeopleRepository
import `in`.smartie.quotedesk.data.repository.ProductRepository
import `in`.smartie.quotedesk.data.repository.PurchaseRepository
import `in`.smartie.quotedesk.data.repository.QuotationRepository
import `in`.smartie.quotedesk.data.repository.StockRepository

class AppContainer(auth: FirebaseAuth, firestore: FirebaseFirestore) {
    val authRepository = AuthRepository(auth, firestore)
    val peopleRepository = PeopleRepository(auth, firestore)
    val productRepository = ProductRepository(auth, firestore)
    val stockRepository = StockRepository(auth, firestore)
    val purchaseRepository = PurchaseRepository(auth, firestore)
    val quotationRepository = QuotationRepository(auth, firestore)
}
