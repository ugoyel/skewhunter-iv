package com.carsathi.app.ui.trips

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.carsathi.app.AppContainer
import com.carsathi.app.data.model.PassengerInfo
import com.carsathi.app.data.model.RideDetail
import com.carsathi.app.data.model.TripItem
import com.carsathi.app.ui.appViewModel
import com.carsathi.app.ui.components.*
import com.carsathi.app.ui.navigation.Routes
import com.carsathi.app.ui.theme.BrandBlue
import com.carsathi.app.ui.theme.CsTheme
import com.carsathi.app.util.Fmt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// =============================================================================== Your rides

class TripsViewModel(c: AppContainer) : ViewModel() {
    val trips: StateFlow<List<TripItem>?> = c.rides.observeTrips(c.session.userId, c.ratings)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun YourRidesScreen(nav: NavHostController) {
    val vm: TripsViewModel = appViewModel { TripsViewModel(it) }
    val trips by vm.trips.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Text("Your rides", style = MaterialTheme.typography.headlineMedium, color = CsTheme.colors.heading, modifier = Modifier.padding(20.dp))
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, contentColor = BrandBlue) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Upcoming") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Past") })
                }
            }
        },
    ) { padding ->
        val all = trips
        if (all == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BrandBlue) }
            return@Scaffold
        }
        val list = if (tab == 0) all.filter { !it.isPast } else all.filter { it.isPast }.reversed()
        if (list.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                if (tab == 0) {
                    EmptyState(
                        Icons.Default.DirectionsCar, "Your future travel plans will appear here",
                        "Find the perfect ride from thousands of destinations, or publish to share your travel costs.",
                        "Find a ride",
                    ) { nav.navigate(Routes.SEARCH) }
                    Box(Modifier.padding(horizontal = 32.dp)) { SecondaryButton("Offer a ride", { nav.navigate(Routes.PUBLISH) }) }
                } else {
                    EmptyState(Icons.Default.History, "No past rides yet", "Rides you've taken or driven will show up here so you can rate your travel companions.")
                }
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list, key = { (if (it.asDriver) "d" else "p") + (it.booking?.id ?: it.ride.id) }) { t ->
                TripCard(t, onClick = {
                    if (t.asDriver) nav.navigate(Routes.manage(t.ride.id))
                    else nav.navigate(Routes.ride(t.ride.id, t.booking?.fromCity.orEmpty(), t.booking?.toCity.orEmpty(), t.booking?.seats ?: 1))
                }, onRate = {
                    if (t.asDriver) nav.navigate(Routes.manage(t.ride.id))
                    else nav.navigate(Routes.rate(t.ride.id, t.ride.driverId))
                })
            }
        }
    }
}

@Composable
private fun TripCard(t: TripItem, onClick: () -> Unit, onRate: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.dayLong(t.ride.departureAt), style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading, modifier = Modifier.weight(1f))
                StatusTag(t)
            }
            Spacer(Modifier.height(12.dp))
            val from = t.booking?.fromCity ?: t.ride.fromCity
            val to = t.booking?.toCity ?: t.ride.toCity
            MiniItinerary(Fmt.time(t.ride.departureAt), Fmt.time(t.ride.arrivalAt), Fmt.duration(t.ride.durationMin), from, to)
            Spacer(Modifier.height(12.dp))
            ThinDivider()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.asDriver) {
                    Icon(Icons.Default.Group, null, tint = CsTheme.colors.subtle, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${t.confirmedPassengers}/${t.ride.seatsTotal} seats booked", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle, modifier = Modifier.weight(1f))
                    if (t.pendingRequests > 0 && !t.isPast) Tag("${t.pendingRequests} request${if (t.pendingRequests > 1) "s" else ""}", CsTheme.colors.warning, Icons.Default.NotificationImportant)
                } else {
                    Avatar(t.counterpart, 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(t.counterpart?.displayName ?: "Driver", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.heading, modifier = Modifier.weight(1f))
                    t.booking?.let { Text(Fmt.rupees(it.total), style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading) }
                }
            }
            if (t.needsRating) {
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (t.asDriver) "Rate your passengers" else "Rate your driver", onRate, icon = Icons.Default.Star)
            }
        }
    }
}

@Composable
private fun StatusTag(t: TripItem) {
    if (t.ride.status == "CANCELLED") { Tag("Cancelled", CsTheme.colors.danger); return }
    if (t.asDriver) { Tag("You're driving", BrandBlue, Icons.Default.DirectionsCar); return }
    when (t.booking?.status) {
        "CONFIRMED" -> Tag(if (t.isPast) "Completed" else "Confirmed", CsTheme.colors.success, Icons.Default.CheckCircle)
        "PENDING" -> Tag("Pending", CsTheme.colors.warning, Icons.Default.HourglassTop)
        "DECLINED" -> Tag("Declined", CsTheme.colors.danger)
        "CANCELLED_PASSENGER" -> Tag("You cancelled", CsTheme.colors.subtle)
        "CANCELLED_DRIVER" -> Tag("Cancelled by driver", CsTheme.colors.danger)
        else -> Unit
    }
}

// =============================================================================== Manage ride (driver)

class ManageRideViewModel(private val c: AppContainer, private val rideId: Long) : ViewModel() {
    val uid = c.session.userId
    val detail: StateFlow<RideDetail?> = c.rides.observeDetail(rideId, uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val rated: StateFlow<Set<Pair<Long, Long>>> = c.ratings.observeGivenKeys(uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun accept(id: Long) = viewModelScope.launch { c.rides.accept(id) }
    fun decline(id: Long) = viewModelScope.launch { c.rides.decline(id) }
    fun cancelRide() = viewModelScope.launch { c.rides.cancelRide(rideId) }
    fun chat(passengerId: Long, go: (Long) -> Unit) {
        viewModelScope.launch { go(c.chat.openConversation(rideId, passengerId, uid)) }
    }
}

@Composable
fun ManageRideScreen(nav: NavHostController, rideId: Long) {
    val vm: ManageRideViewModel = appViewModel { ManageRideViewModel(it, rideId) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val rated by vm.rated.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmCancel by remember { mutableStateOf(false) }
    val d = detail
    Scaffold(topBar = { BackTopBar("Manage your ride", { nav.popBackStack() }) }) { padding ->
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BrandBlue) }
            return@Scaffold
        }
        val past = d.ride.arrivalAt < System.currentTimeMillis()
        val active = d.ride.status == "ACTIVE"
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(Fmt.dayLong(d.ride.departureAt), style = MaterialTheme.typography.headlineMedium, color = CsTheme.colors.heading, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !active -> Tag("Cancelled", CsTheme.colors.danger)
                    past -> Tag("Completed", CsTheme.colors.success)
                    else -> Tag("Published", BrandBlue, Icons.Default.Public)
                }
                Tag(if (d.ride.instantBooking) "Instant booking" else "Manual approval", CsTheme.colors.subtle)
                Tag("${Fmt.rupees(d.ride.pricePerSeat)}/seat", CsTheme.colors.subtle)
            }
            Box(Modifier.padding(20.dp)) { Itinerary(d.points, d.ride.departureAt) }
            ThickDivider()

            if (d.pending.isNotEmpty() && active && !past) {
                SectionTitle("Booking requests (${d.pending.size})")
                d.pending.forEach { p -> RequestRow(p, onAccept = { vm.accept(p.booking.id) }, onDecline = { vm.decline(p.booking.id) }, onProfile = { nav.navigate(Routes.user(p.user.id)) }) }
                ThickDivider()
            }

            SectionTitle("Passengers · ${d.passengers.sumOf { it.booking.seats }}/${d.ride.seatsTotal} seats")
            if (d.passengers.isEmpty()) {
                Text(
                    if (past) "Nobody travelled with you on this ride." else "No passengers yet. Share your ride to fill your seats faster!",
                    color = CsTheme.colors.subtle, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            d.passengers.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.user, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.user.displayName, style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                        Text(
                            "${p.booking.fromCity} → ${p.booking.toCity} · ${p.booking.seats} seat${if (p.booking.seats > 1) "s" else ""} · ${paymentLabel(p.booking.paymentMethod)}",
                            style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle,
                        )
                    }
                    if (past) {
                        if ((d.ride.id to p.user.id) in rated) Tag("Rated", CsTheme.colors.success, Icons.Default.Check)
                        else TextButton(onClick = { nav.navigate(Routes.rate(d.ride.id, p.user.id)) }) { Text("Rate", color = BrandBlue) }
                    } else {
                        if (p.user.phone.isNotBlank()) {
                            IconButton(onClick = { com.carsathi.app.ui.ride.dial(context, p.user.phone) }) {
                                Icon(Icons.Default.Call, "Call", tint = BrandBlue)
                            }
                        }
                        IconButton(onClick = { vm.chat(p.user.id) { nav.navigate(Routes.chat(it)) } }) {
                            Icon(Icons.Default.ChatBubbleOutline, "Message", tint = BrandBlue)
                        }
                    }
                }
            }
            ThickDivider()
            if (active && !past) {
                MenuRow("Edit ride", { nav.navigate(Routes.editRide(d.ride.id)) }, Icons.Default.Edit, "Date, time, seats, price and more")
                MenuRow("Share ride", {
                    val text = "I'm driving ${d.ride.fromCity} → ${d.ride.toCity} on ${Fmt.dayTime(d.ride.departureAt)}. Book a seat on carsathi for ${Fmt.rupees(d.ride.pricePerSeat)}!"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share ride"))
                }, Icons.Default.Share)
            }
            MenuRow("See ride as passengers see it", { nav.navigate(Routes.ride(d.ride.id)) }, Icons.Default.Visibility)
            MenuRow("See route on map", { nav.navigate(Routes.map(d.ride.id)) }, Icons.Default.Map)
            if (active && !past) {
                MenuRow("Cancel ride", { confirmCancel = true }, Icons.Default.Cancel, titleColor = CsTheme.colors.danger)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmCancel) {
        ConfirmDialog(
            "Cancel this ride?",
            "All passengers will be notified and refunded. Frequent cancellations are visible on your profile.",
            "Cancel ride",
            onConfirm = { vm.cancelRide() },
            onDismiss = { confirmCancel = false },
            destructive = true,
        )
    }
}

fun paymentLabel(method: String) = when (method) {
    "CASH" -> "Cash"
    "UPI" -> "Paid by UPI"
    "CARD" -> "Paid by card"
    else -> method
}

@Composable
private fun RequestRow(p: PassengerInfo, onAccept: () -> Unit, onDecline: () -> Unit, onProfile: () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CsTheme.colors.surfaceAlt),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.user, 44.dp, showVerified = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    LinkText(p.user.displayName, onProfile, color = CsTheme.colors.heading)
                    RatingInline(p.rating, p.ratingCount, small = true)
                }
                Text(Fmt.rupees(p.booking.pricePerSeat * p.booking.seats), style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
            }
            Text(
                "${p.booking.fromCity} → ${p.booking.toCity} · ${p.booking.seats} seat${if (p.booking.seats > 1) "s" else ""}",
                style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle, modifier = Modifier.padding(top = 6.dp),
            )
            if (p.booking.message.isNotBlank()) {
                Text("“${p.booking.message}”", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.heading, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50)) { Text("Decline", color = CsTheme.colors.danger) }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onAccept, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)) {
                    Text("Accept", color = Color.White)
                }
            }
        }
    }
}
