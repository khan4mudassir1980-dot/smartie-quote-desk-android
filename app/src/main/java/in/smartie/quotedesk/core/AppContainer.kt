package `in`.smartie.quotedesk.core

import android.content.Context
import `in`.smartie.quotedesk.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.data.repository.AuthRepository
import `in`.smartie.quotedesk.data.repository.PeopleRepository
import `in`.smartie.quotedesk.data.repository.CatalogueReadRepository
import `in`.smartie.quotedesk.data.repository.OperationsReadRepository
import `in`.smartie.quotedesk.data.repository.FirestoreStockPhotoStore
import `in`.smartie.quotedesk.data.repository.DiskStockPhotoFiles
import `in`.smartie.quotedesk.data.repository.FirestoreStockStore
import `in`.smartie.quotedesk.data.repository.FirestorePurchaseStore
import `in`.smartie.quotedesk.data.repository.ProductPinsRepository
import `in`.smartie.quotedesk.data.repository.FirestorePartyStore
import `in`.smartie.quotedesk.data.repository.PartyWriteRepository
import `in`.smartie.quotedesk.data.repository.PurchaseWriteRepository
import `in`.smartie.quotedesk.data.repository.StockPhotoRepository
import `in`.smartie.quotedesk.data.repository.StockWriteRepository
import `in`.smartie.quotedesk.data.repository.StoppedStockRepository
import java.io.File

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
    val stockWriteRepository = StockWriteRepository(FirestoreStockStore(firestore))

    /** N4's writer. The read side stays `operationsRepository`. */
    val purchaseWriteRepository = PurchaseWriteRepository(FirestorePurchaseStore(firestore))

    /** N5.5's writer for `/customers`. The read side stays `operationsRepository`. */
    val partyWriteRepository = PartyWriteRepository(FirestorePartyStore(firestore))

    /** Clearing stopped-item history; the only write it has. */
    val stoppedStockRepository = StoppedStockRepository(firestore)

    /**
     * One per application, so both photo caches are shared by every screen: a
     * photo fetched once is not fetched again anywhere.
     *
     * The files live under `noBackupFilesDir`, which is app-private and
     * **excluded from Android's automatic backup**. That is deliberate: these
     * are a local copy of something Firestore already holds, so backing them
     * up would spend the person's backup quota to restore a cache that the
     * first sync would rebuild anyway — and would carry stock photos into a
     * Google backup nobody asked for.
     */
    val stockPhotoRepository = StockPhotoRepository(
        photos = FirestoreStockPhotoStore(firestore),
        files = DiskStockPhotoFiles(
            File(context.applicationContext.noBackupFilesDir, "stock-photos")
        )
    )
}
