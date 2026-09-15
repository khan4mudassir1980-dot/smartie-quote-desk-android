package `in`.smartie.quotedesk.core

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.data.repository.AuthRepository
import `in`.smartie.quotedesk.data.repository.PeopleRepository
import `in`.smartie.quotedesk.data.repository.CatalogueReadRepository
import `in`.smartie.quotedesk.data.repository.OperationsReadRepository

class AppContainer(
    context: Context,
    auth: FirebaseAuth,
    firestore: FirebaseFirestore
) {
    val devicePreferences = DevicePreferences(context.applicationContext)
    val connectivity = Connectivity(context.applicationContext)
    val errorReporter = ErrorReporter()
    val authRepository = AuthRepository(auth, firestore)
    val peopleRepository = PeopleRepository(auth, firestore)
    val catalogueRepository = CatalogueReadRepository(firestore)
    val operationsRepository = OperationsReadRepository(firestore)
}
