package `in`.smartie.quotedesk.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import `in`.smartie.quotedesk.R
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.data.model.MemberRole
import `in`.smartie.quotedesk.data.model.UserProfile
import `in`.smartie.quotedesk.ui.screens.MoreScreen
import `in`.smartie.quotedesk.ui.screens.ProductsScreen
import `in`.smartie.quotedesk.ui.screens.PurchaseScreen
import `in`.smartie.quotedesk.ui.screens.QuotationsScreen
import `in`.smartie.quotedesk.ui.screens.SignInScreen
import `in`.smartie.quotedesk.ui.screens.StockScreen

@Composable
fun SmartieApp(container: AppContainer, sessionViewModel: SessionViewModel) {
    val session by sessionViewModel.session.collectAsStateWithLifecycle()
    val signIn by sessionViewModel.signIn.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity

    when (val current = session) {
        SessionState.Loading -> LoadingScreen()
        SessionState.SignedOut -> SignInScreen(
            busy = signIn.busy,
            error = signIn.error,
            onGoogleSignIn = { sessionViewModel.signIn(activity) },
        )
        is SessionState.Blocked -> BlockedScreen(
            profile = current.profile,
            onSignOut = { sessionViewModel.signOut(activity) },
        )
        is SessionState.Ready -> {
            val data: AppDataViewModel = viewModel(
                key = "app-data-${current.profile.uid}",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AppDataViewModel(container, current.profile) as T
                },
            )
            SignedInShell(
                profile = current.profile,
                data = data,
                onSignOut = { sessionViewModel.signOut(activity) },
            )
        }
    }
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
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("Opening SMARTIE Quote Desk…")
    }
}

@Composable
private fun BlockedScreen(profile: UserProfile, onSignOut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Access switched off", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "${profile.name}, an Owner or Administrator needs to switch your account on.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSignOut) { Text("Sign out") }
    }
}

private data class Destination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val allowedRoles: Set<MemberRole>,
)

private val destinations = listOf(
    Destination("products", "Products", Icons.Outlined.Category, setOf(MemberRole.OWNER, MemberRole.ADMIN, MemberRole.STAFF)),
    Destination("stock", "Our Stock", Icons.Outlined.Inventory2, MemberRole.entries.toSet()),
    Destination("purchase", "Purchase", Icons.Outlined.ShoppingCart, MemberRole.entries.toSet()),
    Destination("quotations", "Quotation", Icons.Outlined.Description, setOf(MemberRole.OWNER, MemberRole.ADMIN, MemberRole.STAFF)),
    Destination("more", "More", Icons.Outlined.Menu, MemberRole.entries.toSet()),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInShell(profile: UserProfile, data: AppDataViewModel, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val visibleDestinations = remember(profile.role) { destinations.filter { profile.role in it.allowedRoles } }
    val firstRoute = if (profile.role == MemberRole.WORKER) "stock" else "products"
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(data) {
        data.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = { SmartieHeader(profile) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                visibleDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = firstRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable("products") { ProductsScreen(data) }
            composable("stock") { StockScreen(data) }
            composable("purchase") { PurchaseScreen(data) }
            composable("quotations") { QuotationsScreen(data) }
            composable("more") { MoreScreen(data, onSignOut) }
        }
    }
}

@Composable
private fun SmartieHeader(profile: UserProfile) {
    Surface(shadowElevation = 2.dp) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
                )
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("SMARTIE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("Quote Desk", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                    Text(profile.role.label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.primary, thickness = 3.dp)
        }
    }
}
