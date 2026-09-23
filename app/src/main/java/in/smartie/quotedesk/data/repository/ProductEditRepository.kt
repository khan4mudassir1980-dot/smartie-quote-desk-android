package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.ProductAuthor
import `in`.smartie.quotedesk.domain.ProductDraft
import `in`.smartie.quotedesk.domain.ProductPlan
import `in`.smartie.quotedesk.domain.ProductWrite

/**
 * Saving one product.
 *
 * Deliberately thin, as [SettingsRepository] is: every decision about *what*
 * may be written is [ProductWrite]'s, where it is unit-tested without
 * Firebase, and every decision about *who may* is [Permissions]' and the
 * rules'. What is here is the pair of reads, the merge between them, and the
 * write.
 *
 * **Two reads, and which is which matters.** `source` is the document the
 * record on screen was read from. `target` is the document the write must land
 * on — `group__model`, the only id V8C4 computes for a product. They are the
 * same document for everything the importer wrote, and differ only for a
 * product still sitting at the legacy `group|model` id. Overlaying the target
 * on the source means the target's values win where it has them, and the
 * source fills the gaps — so a canonical document created from a legacy one
 * arrives complete rather than losing the shelf it was on.
 */
class ProductEditRepository(
    private val store: ProductStore,
    private val now: () -> Long = System::currentTimeMillis
) {

    /**
     * Returns true when something was written, false when nothing had changed.
     *
     * Throws [IllegalStateException] carrying the person's own words when the
     * write must not be attempted — a refusal the screen shows rather than
     * letting the rules bounce it as a bare permission error.
     */
    suspend fun save(
        member: Member,
        record: ProductRecord,
        draft: ProductDraft,
        /**
         * The draft as the sheet opened, which is what decides whether a
         * field was edited. It defaults to [record]'s own values because the
         * sheet opens from exactly that record — passing it explicitly is for
         * the tests, which need to state the "opened with" side directly.
         */
        loaded: ProductDraft = ProductWrite.draftOf(record)
    ): Boolean = store.transaction { transaction ->
        val source = transaction.read(record.documentId)?.fields.orEmpty()

        // The record's group and seed model are already the derived ones —
        // `toProductRecord` resolves them from the stored fields, the `id`/`key`
        // field and the document id, in that order. Where they are readable,
        // the canonical document is computed from them; where they are not,
        // the source document is read alone and `ProductWrite.plan` refuses
        // with a sentence rather than this code guessing.
        val canonical = if (record.group.isNotBlank() && record.seedModel.isNotBlank()) {
            Keys.productDocId(record.group, record.seedModel)
        } else {
            record.documentId
        }
        val target = if (canonical == record.documentId) {
            source
        } else {
            transaction.read(canonical)?.fields.orEmpty()
        }

        val plan = ProductWrite.plan(
            record = record,
            loaded = loaded,
            draft = draft,
            stored = source + target,
            author = ProductAuthor(
                name = member.name.ifBlank { member.email },
                uid = member.uid
            ),
            at = now(),
            canEdit = Permissions.canEditProducts(member)
        )

        when (plan) {
            is ProductPlan.Refused -> throw IllegalStateException(plan.message)
            ProductPlan.NoChange -> false
            is ProductPlan.Write -> {
                transaction.write(plan.docId, plan.data)
                true
            }
        }
    }
}
