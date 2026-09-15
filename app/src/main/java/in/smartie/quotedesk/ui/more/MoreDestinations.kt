package `in`.smartie.quotedesk.ui.more

import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions

/**
 * The More menu, in the PWA's order (`index.html:7754-7762`), with Sign out
 * at the end. A Worker sees only Team and About & legal, as `renderNav` does.
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
            description = "Received, cancelled and reopened requirements",
            phase = "N4",
            // A Worker adds requirements from the Purchase tab but, as in the
            // PWA, sees only Team and About & legal in this menu.
            isVisible = Permissions::canEditPurchase,
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
            isVisible = { it.active },
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
