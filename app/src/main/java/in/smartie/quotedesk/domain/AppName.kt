package `in`.smartie.quotedesk.domain

/**
 * The app's own name, wherever a person sees it: **"Quote Desk"** — the
 * Owner's decision of 2026-10-07; it was "SMARTIE Quote Desk".
 *
 * The launcher label is `@string/app_name`, in XML, which cannot read this
 * constant, so `AppNameTest` checks the two say the same. It is not the firm,
 * Smart India Enterprises, whose name stays where it is (the About screen,
 * the intro's line), and not the package, `in.smartie.quotedesk`, which never
 * changes.
 */
object AppName {
    const val NAME: String = "Quote Desk"

    /** What the Team screen's "Share app link" sends; the link follows when one is set. */
    fun invite(link: String): String = buildString {
        append("Join $NAME. Open the app and choose Continue with Google; ")
        append("you enter as ${RoleTitles.STAFF} and an Owner or Administrator sets your role.")
        if (link.isNotBlank()) append("\n\n").append(link)
    }
}
