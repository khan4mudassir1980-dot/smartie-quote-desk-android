package `in`.smartie.quotedesk.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import `in`.smartie.quotedesk.ui.screens.PurchaseScreen
import `in`.smartie.quotedesk.ui.screens.QuotationsScreen
import `in`.smartie.quotedesk.ui.screens.SignInScreen
import `in`.smartie.quotedesk.ui.stock.StockScreen
import `in`.smartie.quotedesk.ui.stock.StockViewModel
import `in`.smartie.quotedesk.ui.team.TeamScreen
import `in`.smartie.quotedesk.ui.team.TeamViewModel
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
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

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val isVisible: (Member) -> Boolean,
)

private val bottomDestinations = listOf(
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
    val dimens = LocalSmartieDimens.current
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
            NavigationBar(
                containerColor = SmartieColors.Panel,
                modifier = Modifier.height(dimens.bottomNavHeight + 24.dp),
            ) {
                visible.forEach { destination ->
                    NavigationBarItem(
                        selected = route == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
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
                composable("purchase") { PurchaseScreen(data) }
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
