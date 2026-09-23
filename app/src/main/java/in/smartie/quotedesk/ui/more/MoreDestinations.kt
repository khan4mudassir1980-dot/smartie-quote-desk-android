package `in`.smartie.quotedesk.ui.more

import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions

/**
 * The More menu, in the PWA's order (`index.html:7754-7762`), with Sign out
 * at the end.
 *
 * One deliberate divergence from the PWA: `renderNav` leaves Team in the
 * drawer for a Worker, and shows the whole menu to everyone else, Staff
 * included. Here the entry follows [Permissions.canViewTeam] like every other
 * entry follows its own rule, so only an Owner or Administrator is offered it.
 * The audit's role matrix already reads that way — Staff and Worker have no
 * team controls — and offering a card that only ever opens a refusal is worse
 * than not offering it. Do not "restore parity" by widening this again.
 */
data class MoreDestination(
    val route: String,
    val label: String,
    val description: String,
    /** The parity phase that replaces the placeholder, or null when it is built. */
    val phase: String? = null,
    val isVisible: (Member) -> Boolean,
)

object MoreMenu {

    val destinations: List<MoreDestination> = listOf(
        MoreDestination(
            route = "more/parties",
            label = "Parties",
            description = "Saved customers and their details",
            // Built in N5.3, and read-only: the list, search and a detail
            // view. Adding or correcting a party is N5.5. No placeholder,
            // because the screen exists — the screen itself says what it
            // cannot yet do rather than the menu implying it does nothing.
            phase = null,
            isVisible = Permissions::canUseParties,
        ),
        MoreDestination(
            route = "more/products-admin",
            label = "Products & Categories",
            description = "Add, edit, archive products and categories",
            phase = "N6",
            isVisible = Permissions::canEditProducts,
        ),
        MoreDestination(
            route = "more/quotation-history",
            label = "Quotation history",
            description = "Every quotation, with its lines and totals",
            // Built in N5.4, and read-only. An Owner or Administrator sees
            // every quotation; a Manager sees the ones they issued.
            phase = null,
            isVisible = Permissions::canViewQuotationHistory,
        ),
        MoreDestination(
            route = "more/purchase-history",
            label = "Purchase history",
            description = "What arrived, and what was taken off the list",
            // Built, so no placeholder. Every role gets it: a Staff account
            // sees the rows it raised, and everybody else sees everyone's.
            // The filtering is `PurchaseHistory`'s, in the app — that file
            // says plainly why the rules do not do it and what would have to
            // be true first.
            isVisible = { it.active },
        ),
        MoreDestination(
            route = "more/stock-history",
            label = "Stock movement history",
            description = "Every stock change, who made it and when",
            phase = "N3",
            isVisible = Permissions::canViewStockHistory,
        ),
        MoreDestination(
            route = "more/team",
            label = "Team",
            description = "People, roles and team activity",
            phase = null,
            isVisible = Permissions::canViewTeam,
        ),
        MoreDestination(
            route = "more/settings",
            label = "Settings",
            description = "Quotation numbering and the Manager discount limit",
            // Built in N5.6, and partial: the quotation counter and the
            // discount cap only. Company details, bank details, terms and
            // device preferences arrive later. No placeholder, because the
            // screen exists — it says for itself what it does not yet hold,
            // rather than the menu implying it does nothing.
            //
            // Everyone who can quote may open it; only the Owner is offered
            // a control, which is what the deployed rules say too.
            phase = null,
            isVisible = Permissions::canViewSettings,
        ),
        MoreDestination(
            route = "more/about",
            label = "About & legal",
            description = "Version, licences and credits",
            phase = null,
            isVisible = { it.active },
        ),
    )

    fun visibleTo(member: Member): List<MoreDestination> =
        destinations.filter { it.isVisible(member) }
}
