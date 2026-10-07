package `in`.smartie.quotedesk.domain

/**
 * The app's own name, wherever a person sees it: **"Quote Desk"** — the
 * Owner's decision of 2026-10-07; it was "SMARTIE Quote Desk".
 *
 * The launcher label is `@string/app_name`, in XML, which cannot read these
 * constants, so `AppNameTest` checks that every build says the right one:
 * production [NAME], staging [STAGING_LABEL] (`src/staging/res`), and none the
 * old name. It is not the firm, Smart India Enterprises, whose name stays where
 * it is (the About screen, the intro's line), and not the package,
 * `in.smartie.quotedesk`, which never changes.
 */
object AppName {
    const val NAME: String = "Quote Desk"

    /**
     * The staging build's launcher label (the Owner, 2026-10-07): a test build
     * must never be mistaken for the real one on the home screen. Inside the
     * app staging is still "Quote Desk", with "Staging" under each title.
     */
    const val STAGING_LABEL: String = "$NAME Staging"

    /** What the Team screen's "Share app link" sends; the link follows when one is set. */
    fun invite(link: String): String = buildString {
        append("Join $NAME. Open the app and choose Continue with Google; ")
        append("you enter as ${RoleTitles.STAFF} and an Owner or Administrator sets your role.")
        if (link.isNotBlank()) append("\n\n").append(link)
    }
}
