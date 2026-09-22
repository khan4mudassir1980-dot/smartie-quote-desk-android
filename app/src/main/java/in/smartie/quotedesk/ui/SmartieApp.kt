package `in`.smartie.quotedesk.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import `in`.smartie.quotedesk.BuildConfig
import `in`.smartie.quotedesk.R
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchasePeople
import `in`.smartie.quotedesk.domain.RoleTitles

import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.ui.components.ConnectivityBanner
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartieTopBar
import `in`.smartie.quotedesk.ui.more.AboutScreen
import `in`.smartie.quotedesk.ui.more.MoreMenu
import `in`.smartie.quotedesk.ui.more.MoreScreen
import `in`.smartie.quotedesk.ui.more.PlaceholderScreen
import `in`.smartie.quotedesk.ui.products.ProductsScreen
import `in`.smartie.quotedesk.ui.products.ProductsViewModel
import `in`.smartie.quotedesk.ui.purchase.PurchaseHistoryScreen
import `in`.smartie.quotedesk.ui.purchase.PurchaseViewModel
import `in`.smartie.quotedesk.ui.screens.PurchaseScreen
import `in`.smartie.quotedesk.ui.screens.QuotationsScreen
import `in`.smartie.quotedesk.ui.screens.SignInScreen
import `in`.smartie.quotedesk.ui.stock.StockScreen
import `in`.smartie.quotedesk.ui.stock.StockViewModel
import `in`.smartie.quotedesk.ui.team.TeamScreen
import `in`.smartie.quotedesk.ui.team.TeamViewModel
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.bottomNavMinHeight
import `in`.smartie.quotedesk.ui.theme.SmartieColors

@Composable
fun SmartieApp(container: AppContainer, sessionViewModel: SessionViewModel) {
    val session by sessionViewModel.session.collectAsStateWithLifecycle()
    val signIn by sessionViewModel.signIn.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()

    when (val current = session) {
        SessionState.Loading -> LoadingScreen()

        SessionState.SignedOut -> SignInScreen(
            state = signIn,
            onGoogleSignIn = { activity?.let(sessionViewModel::signInWithGoogle) },
            onEmailSignIn = sessionViewModel::signInWithEmail,
            onPasswordReset = sessionViewModel::sendPasswordReset,
        )

        is SessionState.Blocked -> MessageScreen(
            title = "Access switched off",
            message = "${current.member.name}, that account has been switched off. " +
                "Ask an Owner or Administrator to switch it on again.",
            onSignOut = { activity?.let(sessionViewModel::signOut) },
        )

        is SessionState.Failed -> MessageScreen(
            title = "Something went wrong",
            message = current.message,
            onSignOut = { activity?.let(sessionViewModel::signOut) },
        )

        is SessionState.Ready -> SignedInShell(
            member = current.member,
            container = container,
            onSignOut = { activity?.let(sessionViewModel::signOut) },
        )
    }
}

internal data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val isVisible: (Member) -> Boolean,
)

internal val bottomDestinations = listOf(
    BottomDestination("products", "Products", Icons.Outlined.Category, Permissions::canViewProducts),
    BottomDestination("stock", "Our Stock", Icons.Outlined.Inventory2, Permissions::canViewStock),
    BottomDestination("purchase", "Purchase", Icons.Outlined.ShoppingCart, Permissions::canViewPurchase),
    BottomDestination("quotations", "Quotation", Icons.Outlined.Description, Permissions::canQuote),
    BottomDestination("more", "More", Icons.Outlined.Menu) { true },
)

@Composable
private fun SignedInShell(member: Member, container: AppContainer, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val data: AppDataViewModel = viewModel(
        key = "app-data-${member.uid}",
        factory = AppDataViewModel.Factory(container, member),
    )
    val snackbar = remember { SnackbarHostState() }
    val online by data.online.collectAsStateWithLifecycle()
    val visible = bottomDestinations.filter { it.isVisible(member) }
    val startRoute = if (Permissions.canViewProducts(member)) "products" else "stock"
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val context = LocalContext.current

    LaunchedEffect(data) { data.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            SmartieTopBar(
                title = titleFor(route),
                subtitle = if (BuildConfig.IS_STAGING) "Staging" else null,
                onBack = if (route?.startsWith("more/") == true) {
                    { navController.popBackStack() }
                } else {
                    null
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            SmartieBottomBar(
                destinations = visible,
                selectedRoute = route,
                onSelect = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ConnectivityBanner(online = online)
            NavHost(navController = navController, startDestination = startRoute) {
                composable("products") {
                    val productsViewModel: ProductsViewModel = viewModel(
                        key = "products-${member.uid}",
                        factory = ProductsViewModel.Factory(container, member),
                    )
                    LaunchedEffect(productsViewModel) {
                        productsViewModel.messages.collect { snackbar.showSnackbar(it) }
                    }
                    ProductsScreen(data = data, viewModel = productsViewModel)
                }
                composable("stock") {
                    val stockViewModel: StockViewModel = viewModel(
                        key = "stock-${member.uid}",
                        factory = StockViewModel.Factory(container, member),
                    )
                    LaunchedEffect(stockViewModel) {
                        stockViewModel.messages.collect { snackbar.showSnackbar(it) }
                    }
                    StockScreen(data = data, viewModel = stockViewModel)
                }
                composable("purchase") {
                    val purchaseViewModel: PurchaseViewModel = viewModel(
                        key = "purchase-${member.uid}",
                        // The requirements flow the shell already holds, not a
                        // second listener: a second one would double the tab's
                        // cost against a shared daily quota to read rows the
                        // first already has.
                        factory = PurchaseViewModel.Factory(
                            container,
                            member,
                            data.requirements,
                        ),
                    )
                    LaunchedEffect(purchaseViewModel) {
                        purchaseViewModel.messages.collect { snackbar.showSnackbar(it) }
                    }
                    PurchaseScreen(viewModel = purchaseViewModel)
                }
                composable("quotations") { QuotationsScreen(data) }
                composable("more") {
                    MoreScreen(
                        member = member,
                        onOpen = { navController.navigate(it) },
                        onSignOut = onSignOut,
                    )
                }
                composable("more/team") {
                    val teamViewModel: TeamViewModel = viewModel(
                        key = "team-${member.uid}",
                        factory = TeamViewModel.Factory(container, member),
                    )
                    LaunchedEffect(teamViewModel) {
                        teamViewModel.messages.collect { snackbar.showSnackbar(it) }
                    }
                    TeamScreen(
                        viewer = member,
                        viewModel = teamViewModel,
                        onShareInvite = { context.shareInvite() },
                    )
                }
                composable("more/about") { AboutScreen() }
                composable("more/purchase-history") {
                    val requirements by data.requirements.collectAsStateWithLifecycle()
                    val people by data.members.collectAsStateWithLifecycle()
                    PurchaseHistoryScreen(
                        records = requirements,
                        viewer = member,
                        members = PurchasePeople.byUid(people)
                    )
                }
                MoreMenu.destinations
                    .filter { it.phase != null }
                    .forEach { destination ->
                        composable(destination.route) {
                            PlaceholderScreen(destination.label, destination.phase.orEmpty())
                        }
                    }
            }
        }
    }
}

private fun titleFor(route: String?): String = when (route) {
    "products" -> "Products"
    "stock" -> "Our Stock"
    "purchase" -> "Purchase"
    "quotations" -> "Quotation"
    "more" -> "More"
    null -> "SMARTIE Quote Desk"
    else -> MoreMenu.destinations.firstOrNull { it.route == route }?.label ?: "SMARTIE Quote Desk"
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
        )
        CircularProgressIndicator(Modifier.padding(top = 20.dp))
        Text(
            "Opening SMARTIE Quote Desk…",
            style = MaterialTheme.typography.bodyMedium,
            color = SmartieColors.Steel,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun MessageScreen(title: String, message: String, onSignOut: () -> Unit) {
    Surface(color = SmartieColors.Paper) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = SmartieColors.Ink)
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = SmartieColors.Ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
            )
            SmartieGhostButton(text = "Sign out", onClick = onSignOut)
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Share sheet for the invite; the link is a build setting, not a secret. */
private fun Context.shareInvite() {
    val link = BuildConfig.APP_SHARE_URL
    val text = buildString {
        append("Join SMARTIE Quote Desk. Open the app and choose Continue with Google; ")
        append("you enter as ${RoleTitles.STAFF} and an Owner or Administrator sets your role.")
        if (link.isNotBlank()) append("\n\n").append(link)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { startActivity(Intent.createChooser(intent, "Share app link")) }
}

/**
 * The bottom navigation.
 *
 * **The system navigation's inset is padding under the bar, never height
 * taken out of it.** `NavigationBar` applies `WindowInsets.navigationBars`
 * *inside* its own height, so a forced `.height(h)` on it leaves the items
 * `h - inset` to lay out in. That is what put the icons and labels behind
 * the system navigation on a second phone: three-button navigation is a
 * ~48dp inset, and out of an 80dp bar that leaves 32dp for a row that needs
 * more — while the same code looks right on a gesture-navigation phone
 * where the inset is a fraction of that.
 *
 * So the bar declares no insets of its own and the [Box] around it takes
 * the horizontal and bottom **safe-drawing** insets instead. That covers
 * three-button and gesture navigation alike, and a display cutout in
 * landscape, with nothing measured per device and no hard-coded fudge.
 *
 * The compact PWA height is a **minimum**, not a cap. Pinning the bar to it
 * is the same mistake in smaller print: an icon, its indicator and a label
 * need about 76dp, so a hard 56dp clips the label off the bottom. So the
 * bar takes whatever its content needs and never less than the compact
 * height — which grows with the effective font scale, as the label does.
 * `Scaffold` measures what this returns, inset included, so the last row of
 * a list and the stopped-item history below it clear the bar as well.
 */
@Composable
internal fun SmartieBottomBar(
    destinations: List<BottomDestination>,
    selectedRoute: String?,
    onSelect: (BottomDestination) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What the system navigation takes. A parameter only so a test can hand
     * it a three-button phone's 48dp and check the items are still their own
     * height rather than 48dp shorter; nothing passes it in the app.
     */
    insets: WindowInsets = WindowInsets.safeDrawing.only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
    ),
) {
    val dimens = LocalSmartieDimens.current
    val minHeight = bottomNavMinHeight(
        base = dimens.bottomNavHeight,
        fontScale = LocalDensity.current.fontScale,
    )
    Box(
        modifier
            .fillMaxWidth()
            // **Before** the inset padding, not after. A semantics node
            // reports the bounds at its own position in the chain, so a
            // description added below the padding would describe only the
            // inner region — and a test asking where the bar ends would be
            // told where its content ends, which is the very thing at issue.
            .semantics { contentDescription = BOTTOM_NAV_LABEL }
            .background(SmartieColors.Panel)
            .windowInsetsPadding(insets),
    ) {
        NavigationBar(
            containerColor = SmartieColors.Panel,
            // Consumed by the Box above. See the note on this function.
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.heightIn(min = minHeight),
        ) {
            destinations.forEach { destination ->
                NavigationBarItem(
                    selected = selectedRoute == destination.route,
                    onClick = { onSelect(destination) },
                    icon = { Icon(destination.icon, contentDescription = destination.label) },
                    label = {
                        Text(
                            destination.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }
}

/** Names the bar for a screen reader, and gives the tests its bounds. */
const val BOTTOM_NAV_LABEL: String = "Main navigation"
