package `in`.smartie.quotedesk.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.ui.AppDataViewModel
import `in`.smartie.quotedesk.ui.quotations.QuotationActions
import `in`.smartie.quotedesk.ui.quotations.QuotationListScreen
import `in`.smartie.quotedesk.ui.quotations.QuotationsViewModel

/**
 * The Quotations tab.
 *
 * Nothing of its own since N5.4: the list, the role filter and the detail view
 * live in `QuotationListScreen`, which More → Quotation history uses as well.
 * Two lists of the same documents that applied different filters is exactly
 * the defect N4.4 found on the Purchase board, and keeping one implementation
 * is how it cannot happen again.
 *
 * The banner stays until the cutover batch: issuing a quotation is still the
 * PWA's job, and the tab says so.
 */
@Composable
fun QuotationsScreen(
    data: AppDataViewModel,
    viewModel: QuotationsViewModel,
    /** Edit (N5.10): the caller moves to the builder on the Products tab. */
    onEdit: (QuotationRecord) -> Unit,
    /** Duplicate (N5.10): the caller moves to the builder on the Products tab. */
    onDuplicate: (QuotationRecord) -> Unit,
) {
    val quotations by data.quotations.collectAsStateWithLifecycle()
    val cancelling by viewModel.cancelling.collectAsStateWithLifecycle()
    val cancelFailure by viewModel.failure.collectAsStateWithLifecycle()
    val copyStart by viewModel.copyStart.collectAsStateWithLifecycle()
    val preparing by viewModel.preparing.collectAsStateWithLifecycle()
    val pdfFailure by viewModel.pdfFailure.collectAsStateWithLifecycle()
    val company by data.company.collectAsStateWithLifecycle()
    val products by data.products.collectAsStateWithLifecycle()

    QuotationListScreen(
        records = quotations,
        viewer = data.member,
        loading = quotations.isEmpty(),
        banner = true,
        cancelling = cancelling,
        cancelFailure = cancelFailure,
        copyStart = copyStart,
        preparing = preparing,
        pdfFailure = pdfFailure,
        actions = QuotationActions(
            onEdit = onEdit,
            onCancel = viewModel::cancel,
            onDuplicate = onDuplicate,
            // N5.11: issued already, so straight to the PDF, with the company
            // settings and the catalogue it is made from.
            onOutput = { record, action -> viewModel.output(record, action, company, products) }
        )
    )
}
