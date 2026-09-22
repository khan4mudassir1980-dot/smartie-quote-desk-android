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
            phase = "N5",
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
            description = "Every quotation, with search and filters",
            phase = "N5",
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
            description = "Company, bank, numbering, terms and device preferences",
            phase = "N6",
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
