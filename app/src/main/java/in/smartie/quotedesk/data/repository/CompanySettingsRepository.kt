package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.data.docDataFlow
import `in`.smartie.quotedesk.data.mapping.toCompanySettings
import `in`.smartie.quotedesk.data.model.CompanySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `teamSettings/company`, **read-only** (N5.11 commit 2): the firm's details,
 * bank, terms, notes and images the quotation PDF prints. Nothing in this app
 * writes the document; N6 builds the screen that does.
 *
 * The rules let a member who is not Staff read it — the same people who may
 * see a quotation — so a Staff account never attaches this listener.
 *
 * **Nothing until the first snapshot; null when the document is absent.**
 * The difference matters to the PDF: settings that have never loaded refuse
 * to print, so a blank letterhead is never produced by accident, while an
 * absent or incomplete document prints what there is, with the notice.
 */
class CompanySettingsRepository(private val firestore: FirebaseFirestore) {

    private val company get() = firestore.collection("teamSettings").document("company")

    fun observe(): Flow<CompanySettings?> = company.docDataFlow().map { it?.toCompanySettings() }
}
