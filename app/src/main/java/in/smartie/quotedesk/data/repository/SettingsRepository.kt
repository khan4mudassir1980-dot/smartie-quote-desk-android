package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import `in`.smartie.quotedesk.data.docDataFlow
import `in`.smartie.quotedesk.data.mapping.toNumberingRecord
import `in`.smartie.quotedesk.data.mapping.toQuotingRecord
import `in`.smartie.quotedesk.data.model.NumberingRecord
import `in`.smartie.quotedesk.data.model.QuotingRecord
import `in`.smartie.quotedesk.domain.DiscountCap
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Numbering
import `in`.smartie.quotedesk.domain.NumberingAuthor
import `in`.smartie.quotedesk.domain.NumberingDraft
import `in`.smartie.quotedesk.domain.NumberingPlan
import `in`.smartie.quotedesk.domain.Permissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * The two settings documents N5.6 owns: the quotation counter and the Manager
 * discount cap.
 *
 * Deliberately thin. Every decision about *what* may be written is
 * [Numbering]'s and [DiscountCap]'s, where it is unit-tested without Firebase,
 * and every decision about *who may* is [Permissions]' and the rules'. What is
 * here is a listener, a merge write, and the mapping between them.
 *
 * **Both documents may be absent**, and neither absence is an error. A project
 * whose counter has not been seeded shows the Owner an empty form rather than
 * a failure, and a missing discount cap reads as zero — which is exactly what
 * the N5.9 quotation rule does with it, so the screen and the server never
 * disagree about what a blank setting means.
 *
 * **Nothing here issues a number.** `lastIssued` is never written: this batch
 * configures the counter and reads it, and N5.9 builds the writer that takes a
 * number from it.
 */
class SettingsRepository(
    private val firestore: FirebaseFirestore,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val numbering get() = firestore.collection("teamSettings").document("numbering")
    private val quoting get() = firestore.collection("teamSettings").document("quoting")

    /** Null until the first snapshot arrives, or when the counter is unseeded. */
    fun observeNumbering(): Flow<NumberingRecord?> =
        numbering.docDataFlow().map { it?.toNumberingRecord() }

    fun observeQuoting(): Flow<QuotingRecord?> =
        quoting.docDataFlow().map { it?.toQuotingRecord() }

    /**
     * Saves the counter's configuration.
     *
     * [stored] is what the screen was showing. The rules re-check the same
     * property against what is actually stored, so a stale screen cannot slip
     * a lower `next` past them — it is refused by the server rather than by
     * this argument.
     */
    suspend fun saveNumbering(
        member: Member,
        stored: NumberingRecord,
        draft: NumberingDraft
    ): Boolean = commit(
        numbering,
        Numbering.save(
            stored = stored,
            draft = draft,
            author = authorOf(member),
            at = now(),
            canConfigure = Permissions.canConfigureNumbering(member)
        )
    )

    suspend fun saveDiscountCap(member: Member, percent: Double): Boolean = commit(
        quoting,
        DiscountCap.save(
            percent = percent,
            author = authorOf(member),
            at = now(),
            canConfigure = Permissions.canSetDiscountCap(member)
        )
    )

    private suspend fun commit(
        document: DocumentReference,
        plan: NumberingPlan
    ): Boolean = when (plan) {
        is NumberingPlan.Refused -> throw IllegalStateException(plan.message)
        NumberingPlan.NoChange -> false
        is NumberingPlan.Write -> {
            // A merge, as `fbSaveNumbering` does: the counter carries
            // `lastIssued` and this write must leave it exactly where the
            // person who issued that number left it.
            document.set(plan.data, SetOptions.merge()).await()
            true
        }
    }

    private fun authorOf(member: Member) =
        NumberingAuthor(name = member.name.ifBlank { member.email }, uid = member.uid)
}
