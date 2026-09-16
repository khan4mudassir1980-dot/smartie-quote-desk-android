package `in`.smartie.quotedesk.core

import android.content.Context
import `in`.smartie.quotedesk.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.data.repository.AuthRepository
import `in`.smartie.quotedesk.data.repository.PeopleRepository
import `in`.smartie.quotedesk.data.repository.CatalogueReadRepository
import `in`.smartie.quotedesk.data.repository.OperationsReadRepository
import `in`.smartie.quotedesk.data.repository.ProductPinsRepository

class AppContainer(
    context: Context,
    auth: FirebaseAuth,
    firestore: FirebaseFirestore
) {
    val devicePreferences = DevicePreferences(context.applicationContext)
    val connectivity = Connectivity(context.applicationContext)
    val errorReporter = ErrorReporter()
    val authRepository = AuthRepository(
        auth = auth,
        firestore = firestore,
        primaryOwnerEmailFallback = BuildConfig.PRIMARY_OWNER_EMAIL,
    )
    val peopleRepository = PeopleRepository(auth, firestore)
    val catalogueRepository = CatalogueReadRepository(firestore)
    val operationsRepository = OperationsReadRepository(firestore)
    val productPinsRepository = ProductPinsRepository(auth, firestore)
}
