package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import `in`.smartie.quotedesk.data.mapping.toCompanySettings
import `in`.smartie.quotedesk.data.mapping.toDocData
import `in`.smartie.quotedesk.data.model.CompanySettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * `teamSettings/company`, **read-only** (N5.11 commit 2): the firm's details,
 * bank, terms, notes and images the quotation PDF prints. Nothing in this app
 * writes the document; N6 builds the screen that does.
 *
 * The rules let a member who is not Staff read it — the same people who may
 * see a quotation — so a Staff account never attaches this listener.
 *
 * **Nothing until the first answer; null when the document is absent.**
 * The difference matters to the PDF: settings that have never loaded refuse
 * to print, so a blank letterhead is never produced by accident, while an
 * absent or incomplete document prints what there is, with the notice.
 *
 * **"Absent" is believed only from the server** (N5.11 commit 9). A phone
 * that has never synced the document finds nothing in its own cache, and
 * that is not an answer: the server may hold one. So an absent document read
 * from the cache is passed over, and the listener follows metadata changes
 * so the server's own answer — which may be "absent" too — still arrives
 * when the phone connects. A document that exists is believed from the
 * cache: it was synced from the server at some point.
 */
class CompanySettingsRepository(private val firestore: FirebaseFirestore) {

    private val company get() = firestore.collection("teamSettings").document("company")

    fun observe(): Flow<CompanySettings?> = callbackFlow {
        val registration = company.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            when {
                error != null -> close(error)
                snapshot == null -> Unit
                snapshot.exists() -> trySend(snapshot.toDocData().toCompanySettings())
                !snapshot.metadata.isFromCache -> trySend(null)
            }
        }
        awaitClose { registration.remove() }
    }.distinctUntilChanged()
}
