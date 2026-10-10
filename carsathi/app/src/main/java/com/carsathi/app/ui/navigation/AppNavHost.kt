package com.carsathi.app.ui.navigation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.carsathi.app.AppContainer
import com.carsathi.app.data.model.TripItem
import com.carsathi.app.ui.appContainer
import com.carsathi.app.ui.appViewModel
import com.carsathi.app.ui.auth.ForgotPasswordScreen
import com.carsathi.app.ui.auth.LoginScreen
import com.carsathi.app.ui.auth.SignUpScreen
import com.carsathi.app.ui.auth.WelcomeScreen
import com.carsathi.app.ui.inbox.ChatScreen
import com.carsathi.app.ui.inbox.InboxScreen
import com.carsathi.app.ui.inbox.NotificationsScreen
import com.carsathi.app.ui.profile.EditProfileScreen
import com.carsathi.app.ui.profile.PreferencesScreen
import com.carsathi.app.ui.profile.ProfileScreen
import com.carsathi.app.ui.profile.PublicProfileScreen
import com.carsathi.app.ui.profile.RateScreen
import com.carsathi.app.ui.profile.RatingsScreen
import com.carsathi.app.ui.profile.VerificationScreen
import com.carsathi.app.ui.publish.EditRideScreen
import com.carsathi.app.ui.publish.PublishScreen
import com.carsathi.app.ui.publish.PublishedScreen
import com.carsathi.app.ui.ride.BookedScreen
import com.carsathi.app.ui.ride.BookingScreen
import com.carsathi.app.ui.ride.MapScreen
import com.carsathi.app.ui.ride.RideDetailScreen
import com.carsathi.app.ui.search.SearchResultsScreen
import com.carsathi.app.ui.search.SearchScreen
import com.carsathi.app.ui.settings.AboutScreen
import com.carsathi.app.ui.settings.AlertsScreen
import com.carsathi.app.ui.settings.ChangePasswordScreen
import com.carsathi.app.ui.settings.DeleteAccountScreen
import com.carsathi.app.ui.settings.HelpScreen
import com.carsathi.app.ui.settings.LegalScreen
import com.carsathi.app.ui.settings.NotificationSettingsScreen
import com.carsathi.app.ui.settings.PaymentMethodsScreen
import com.carsathi.app.ui.settings.PayoutsScreen
import com.carsathi.app.ui.settings.SettingsScreen
import com.carsathi.app.ui.settings.TransactionsScreen
import com.carsathi.app.ui.trips.ManageRideScreen
import com.carsathi.app.ui.trips.YourRidesScreen
import com.carsathi.app.ui.vehicles.AddVehicleScreen
import com.carsathi.app.ui.vehicles.VehiclesScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val c: AppContainer) : ViewModel() {
    val unreadMessages: StateFlow<Int> = c.session.userState.filterNotNull()
        .flatMapLatest { if (it > 0) c.chat.observeUnread(it) else flowOf(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pendingRequests: StateFlow<Int> = c.session.userState.filterNotNull()
        .flatMapLatest { uid ->
            if (uid > 0) c.rides.observeTrips(uid, c.ratings) else flowOf(emptyList<TripItem>())
        }
        .map { trips -> trips.filter { !it.isPast }.sumOf { it.pendingRequests } + trips.count { it.needsRating } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun markNotificationPromptShown() {
        viewModelScope.launch { c.session.setAskedNotifications() }
    }
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabItems = listOf(
    TabItem(Routes.SEARCH, "Search", Icons.Outlined.Search),
    TabItem(Routes.PUBLISH, "Publish", Icons.Outlined.AddCircleOutline),
    TabItem(Routes.RIDES, "Your rides", Icons.Outlined.DirectionsCar),
    TabItem(Routes.INBOX, "Inbox", Icons.Outlined.ChatBubbleOutline),
    TabItem(Routes.PROFILE, "Profile", Icons.Outlined.AccountCircle),
)

fun NavHostController.goToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

fun NavHostController.resetTo(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
fun CarSathiRoot(startSignedIn: Boolean, openNotifications: Boolean) {
    val nav = rememberNavController()
    val container = appContainer()
    val main: MainViewModel = appViewModel { MainViewModel(it) }
    val unread by main.unreadMessages.collectAsStateWithLifecycle()
    val pending by main.pendingRequests.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in Routes.tabs

    // Ask for the notification permission once the member is inside the app (Android 13+)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(showBar) {
        if (showBar && Build.VERSION.SDK_INT >= 33 && !container.session.askedNotifications()) {
            main.markNotificationPromptShown()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(openNotifications) {
        if (openNotifications && startSignedIn) nav.navigate(Routes.NOTIFICATIONS)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    tabItems.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { if (tab.route == Routes.PUBLISH) nav.navigate(Routes.PUBLISH) else nav.goToTab(tab.route) },
                            icon = {
                                val badge = when (tab.route) {
                                    Routes.INBOX -> unread
                                    Routes.RIDES -> pending
                                    else -> 0
                                }
                                if (badge > 0) {
                                    BadgedBox(badge = { Badge { Text(badge.toString()) } }) { Icon(tab.icon, tab.label) }
                                } else {
                                    Icon(tab.icon, tab.label)
                                }
                            },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = if (startSignedIn) Routes.SEARCH else Routes.WELCOME,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.WELCOME) { WelcomeScreen(nav) }
            composable(Routes.LOGIN) { LoginScreen(nav) }
            composable(Routes.SIGNUP) { SignUpScreen(nav) }
            composable(Routes.FORGOT) { ForgotPasswordScreen(nav) }

            composable(Routes.SEARCH) { SearchScreen(nav) }
            composable(Routes.PUBLISH) { PublishScreen(nav) }
            composable(Routes.RIDES) { YourRidesScreen(nav) }
            composable(Routes.INBOX) { InboxScreen(nav) }
            composable(Routes.PROFILE) { ProfileScreen(nav) }

            composable(
                Routes.RESULTS,
                arguments = listOf(
                    navArgument("from") { type = NavType.StringType },
                    navArgument("to") { type = NavType.StringType },
                    navArgument("date") { type = NavType.LongType },
                    navArgument("seats") { type = NavType.IntType },
                ),
            ) { e ->
                val a = e.arguments
                SearchResultsScreen(
                    nav,
                    a?.getString("from").orEmpty(),
                    a?.getString("to").orEmpty(),
                    a?.getLong("date") ?: 0L,
                    a?.getInt("seats") ?: 1,
                )
            }
            composable(
                Routes.RIDE,
                arguments = listOf(
                    navArgument("rideId") { type = NavType.LongType },
                    navArgument("from") { type = NavType.StringType; defaultValue = "" },
                    navArgument("to") { type = NavType.StringType; defaultValue = "" },
                    navArgument("seats") { type = NavType.IntType; defaultValue = 1 },
                ),
            ) { e ->
                val a = e.arguments
                RideDetailScreen(nav, a?.getLong("rideId") ?: 0L, a?.getString("from").orEmpty(), a?.getString("to").orEmpty(), a?.getInt("seats") ?: 1)
            }
            composable(
                Routes.BOOK,
                arguments = listOf(
                    navArgument("rideId") { type = NavType.LongType },
                    navArgument("from") { type = NavType.StringType; defaultValue = "" },
                    navArgument("to") { type = NavType.StringType; defaultValue = "" },
                    navArgument("seats") { type = NavType.IntType; defaultValue = 1 },
                ),
            ) { e ->
                val a = e.arguments
                BookingScreen(nav, a?.getLong("rideId") ?: 0L, a?.getString("from").orEmpty(), a?.getString("to").orEmpty(), a?.getInt("seats") ?: 1)
            }
            composable(
                Routes.BOOKED,
                arguments = listOf(navArgument("rideId") { type = NavType.LongType }, navArgument("status") { type = NavType.StringType }),
            ) { e -> BookedScreen(nav, e.arguments?.getLong("rideId") ?: 0L, e.arguments?.getString("status").orEmpty()) }
            composable(Routes.MANAGE, arguments = listOf(navArgument("rideId") { type = NavType.LongType })) { e ->
                ManageRideScreen(nav, e.arguments?.getLong("rideId") ?: 0L)
            }
            composable(Routes.EDIT_RIDE, arguments = listOf(navArgument("rideId") { type = NavType.LongType })) { e ->
                EditRideScreen(nav, e.arguments?.getLong("rideId") ?: 0L)
            }
            composable(Routes.PUBLISHED, arguments = listOf(navArgument("rideId") { type = NavType.LongType })) { e ->
                PublishedScreen(nav, e.arguments?.getLong("rideId") ?: 0L)
            }
            composable(Routes.MAP, arguments = listOf(navArgument("rideId") { type = NavType.LongType })) { e ->
                MapScreen(nav, e.arguments?.getLong("rideId") ?: 0L)
            }
            composable(Routes.CHAT, arguments = listOf(navArgument("conversationId") { type = NavType.LongType })) { e ->
                ChatScreen(nav, e.arguments?.getLong("conversationId") ?: 0L)
            }
            composable(Routes.USER, arguments = listOf(navArgument("userId") { type = NavType.LongType })) { e ->
                PublicProfileScreen(nav, e.arguments?.getLong("userId") ?: 0L)
            }
            composable(Routes.RATINGS, arguments = listOf(navArgument("userId") { type = NavType.LongType })) { e ->
                RatingsScreen(nav, e.arguments?.getLong("userId") ?: 0L)
            }
            composable(
                Routes.RATE,
                arguments = listOf(navArgument("rideId") { type = NavType.LongType }, navArgument("userId") { type = NavType.LongType }),
            ) { e -> RateScreen(nav, e.arguments?.getLong("rideId") ?: 0L, e.arguments?.getLong("userId") ?: 0L) }

            composable(Routes.VEHICLES) { VehiclesScreen(nav) }
            composable(Routes.ADD_VEHICLE, arguments = listOf(navArgument("carId") { type = NavType.LongType; defaultValue = 0L })) { e ->
                AddVehicleScreen(nav, e.arguments?.getLong("carId") ?: 0L)
            }
            composable(Routes.EDIT_PROFILE) { EditProfileScreen(nav) }
            composable(Routes.PREFERENCES) { PreferencesScreen(nav) }
            composable(Routes.VERIFY) { VerificationScreen(nav) }
            composable(Routes.NOTIFICATIONS) { NotificationsScreen(nav) }
            composable(Routes.ALERTS) { AlertsScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.PASSWORD) { ChangePasswordScreen(nav) }
            composable(Routes.PAYMENTS) { PaymentMethodsScreen(nav) }
            composable(Routes.PAYOUTS) { PayoutsScreen(nav) }
            composable(Routes.TRANSACTIONS) { TransactionsScreen(nav) }
            composable(Routes.NOTIF_SETTINGS) { NotificationSettingsScreen(nav) }
            composable(Routes.HELP) { HelpScreen(nav) }
            composable(Routes.LEGAL, arguments = listOf(navArgument("doc") { type = NavType.StringType })) { e ->
                LegalScreen(nav, e.arguments?.getString("doc").orEmpty())
            }
            composable(Routes.ABOUT) { AboutScreen(nav) }
            composable(Routes.DELETE) { DeleteAccountScreen(nav) }
        }
    }
}
