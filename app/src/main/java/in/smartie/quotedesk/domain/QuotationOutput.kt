package `in`.smartie.quotedesk.domain

/**
 * Where a quotation's PDF goes, decided without Android (N5.11 commit 8):
 * the cached file, the Downloads folder, and which WhatsApp a share opens.
 * `util/QuotationOutputs` does the work on these terms.
 */
object QuotationOutput {

    const val MIME = "application/pdf"

    /** `cacheDir/quotations` — the FileProvider's `shared_quotations` path. */
    const val CACHE_DIRECTORY = "quotations"

    /** On API 29 and later, `Download/SMARTIE`, through MediaStore, asking no permission. */
    const val DOWNLOAD_FOLDER = "SMARTIE"

    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    const val SEND_WITH = "Send with"
    const val WHATSAPP_LABEL = "WhatsApp"
    const val WHATSAPP_BUSINESS_LABEL = "WhatsApp Business"

    /**
     * The cached files to delete before [keep] is written: every other one.
     * The cache holds customers' quotations, so only the file in use stays
     * (the Owner's addition of 2026-10-06).
     */
    fun stale(cached: List<String>, keep: String): List<String> = cached.filter { it != keep }

    /** Where the WhatsApp button sends the PDF (the Owner's decision 1, and the approved "Send with"). */
    sealed interface ShareTarget {
        /** Exactly one WhatsApp is installed: straight to it. */
        data class App(val packageName: String) : ShareTarget

        /** Both are: "Send with" WhatsApp, WhatsApp Business or Cancel, and nothing is remembered. */
        data object Choose : ShareTarget

        /** Neither: the system share sheet. */
        data object Sheet : ShareTarget
    }

    fun shareTarget(installed: Set<String>): ShareTarget {
        val whatsapp = WHATSAPP in installed
        val business = WHATSAPP_BUSINESS in installed
        return when {
            whatsapp && business -> ShareTarget.Choose
            whatsapp -> ShareTarget.App(WHATSAPP)
            business -> ShareTarget.App(WHATSAPP_BUSINESS)
            else -> ShareTarget.Sheet
        }
    }
}
