package com.carsathi.app.ui.ride

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.carsathi.app.AppContainer
import com.carsathi.app.data.catalog.CarCatalog
import com.carsathi.app.data.catalog.CityCatalog
import com.carsathi.app.data.catalog.Pricing
import com.carsathi.app.data.db.PaymentMethodEntity
import com.carsathi.app.data.db.UserEntity
import com.carsathi.app.data.model.PrefLabels
import com.carsathi.app.data.model.RideDetail
import com.carsathi.app.data.model.RidePoint
import com.carsathi.app.data.model.matchSegment
import com.carsathi.app.data.model.points
import com.carsathi.app.ui.appViewModel
import com.carsathi.app.ui.components.*
import com.carsathi.app.ui.navigation.Routes
import com.carsathi.app.ui.navigation.goToTab
import com.carsathi.app.ui.theme.BrandBlue
import com.carsathi.app.ui.theme.CsTheme
import com.carsathi.app.ui.theme.Petrol
import com.carsathi.app.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** Works out which part of the itinerary a passenger travels (boarding / alighting indexes). */
fun segmentFor(points: List<RidePoint>, fromName: String, toName: String): Pair<Int, Int> {
    val f = CityCatalog.find(fromName)
    val t = CityCatalog.find(toName)
    if (f == null || t == null) return 0 to points.lastIndex
    val m = matchSegment(points, f, t) ?: return 0 to points.lastIndex
    return m.fromIndex to m.toIndex
}

// =============================================================================== Ride details

class RideDetailViewModel(private val c: AppContainer, rideId: Long) : ViewModel() {
    val uid = c.session.userId
    val detail: StateFlow<RideDetail?> = c.rides.observeDetail(rideId, uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val viewer: StateFlow<UserEntity?> = c.users.observe(uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun contact(driverId: Long, rideId: Long, go: (Long) -> Unit) {
        viewModelScope.launch { go(c.chat.openConversation(rideId, uid, driverId)) }
    }

    fun cancelBooking(bookingId: Long) {
        viewModelScope.launch { c.rides.cancelBooking(bookingId) }
    }

    fun report(reason: String) {
        viewModelScope.launch {
            c.notifications.notify(uid, "REPORT", "Thanks for your report", "Our Trust & Safety team will review it within 24 hours. Reason: $reason", push = false)
        }
    }
}

@Composable
fun RideDetailScreen(nav: NavHostController, rideId: Long, fromName: String, toName: String, seats: Int) {
    val vm: RideDetailViewModel = appViewModel { RideDetailViewModel(it, rideId) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val viewer by vm.viewer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmCancel by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val d = detail

    Scaffold(
        topBar = {
            BackTopBar("", { nav.popBackStack() }) {
                if (d != null) IconButton(onClick = { shareRide(context, d) }) { Icon(Icons.Default.Share, "Share", tint = BrandBlue) }
            }
        },
        bottomBar = {
            if (d != null) RideBottomBar(nav, d, viewer, fromName, toName, seats, onCancel = { confirmCancel = true })
        },
    ) { padding ->
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BrandBlue) }
            return@Scaffold
        }
        val booking = d.myBooking
        val (board, alight) = when {
            booking != null -> segmentFor(d.points, booking.fromCity, booking.toCity)
            fromName.isNotBlank() -> segmentFor(d.points, fromName, toName)
            else -> 0 to d.points.lastIndex
        }
        val segKm = d.points[alight].km - d.points[board].km
        val price = Pricing.segmentPrice(d.ride.pricePerSeat, segKm, d.ride.distanceKm)
        val passengers = booking?.seats ?: seats

        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                Fmt.dayLong(d.points[board].timeAt(d.ride.departureAt)),
                style = MaterialTheme.typography.headlineMedium,
                color = CsTheme.colors.heading,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (d.ride.status == "CANCELLED") {
                InfoBanner("This ride has been cancelled by the driver.", Icons.Default.Cancel, CsTheme.colors.danger, Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Itinerary(d.points, d.ride.departureAt, board, alight, onPointClick = { nav.navigate(Routes.map(d.ride.id)) })
            }
            Row(Modifier.padding(horizontal = 20.dp)) {
                LinkText("See the route on the map", { nav.navigate(Routes.map(d.ride.id)) })
            }
            ThickDivider()
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Total price for $passengers passenger${if (passengers > 1) "s" else ""}",
                    style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading, modifier = Modifier.weight(1f),
                )
                Text(Fmt.rupees(price * passengers), style = MaterialTheme.typography.headlineSmall, color = CsTheme.colors.heading)
            }
            ThickDivider()
            Row(
                Modifier.fillMaxWidth().clickable { nav.navigate(Routes.user(d.driver.id)) }.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.driver.displayName, style = MaterialTheme.typography.titleLarge, color = CsTheme.colors.heading)
                    RatingInline(d.driverRating, d.driverRatingCount)
                }
                Avatar(d.driver, 56.dp, showVerified = true)
                Icon(Icons.Default.ChevronRight, null, tint = CsTheme.colors.subtle)
            }
            if (d.driver.idVerified) DetailRow(Icons.Default.Verified, "Verified profile", BrandBlue)
            if (d.ride.comment.isNotBlank()) {
                Text(
                    "“${d.ride.comment}”",
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    color = CsTheme.colors.heading,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp)).background(CsTheme.colors.surfaceAlt).padding(14.dp),
                )
            }
            if (d.ride.instantBooking) DetailRow(Icons.Default.Bolt, "Your booking will be confirmed instantly")
            else DetailRow(Icons.Default.HourglassTop, "Your booking won't be confirmed until the driver approves your request")
            if (d.ride.maxTwoInBack) DetailRow(Icons.Default.AirlineSeatReclineNormal, "Max. 2 in the back")
            if (d.ride.womenOnly) DetailRow(Icons.Default.Woman, "Women only ride", Color(0xFFD63384))
            DetailRow(
                if (d.driver.smoking == 0) Icons.Default.SmokeFree else Icons.Default.SmokingRooms,
                PrefLabels.smoking[d.driver.smoking.coerceIn(0, 2)],
            )
            DetailRow(Icons.Default.Pets, PrefLabels.pets[d.driver.pets.coerceIn(0, 2)])
            DetailRow(if (d.driver.music == 0) Icons.Default.MusicOff else Icons.Default.MusicNote, PrefLabels.music[d.driver.music.coerceIn(0, 2)])
            DetailRow(Icons.Default.RecordVoiceOver, PrefLabels.chattiness[d.driver.chattiness.coerceIn(0, 2)])
            d.car?.let { car ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DirectionsCar, null, tint = CsTheme.colors.subtle)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(car.title, style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                        Text("${car.color} · ${car.bodyType} · ${car.fuel}", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
                    }
                    Box(Modifier.size(18.dp).clip(CircleShape).background(CarCatalog.colorOf(car.color)).then(Modifier.background(Color.Transparent)))
                }
            }
            if (!d.isMine && d.myBooking?.status == "CONFIRMED" && d.driver.phone.isNotBlank()) {
                ThinDivider(Modifier.padding(horizontal = 20.dp))
                MenuRow(
                    "Call ${d.driver.firstName} · ${d.driver.phone}",
                    icon = Icons.Default.Call,
                    titleColor = BrandBlue,
                    onClick = { dial(context, d.driver.phone) },
                )
            }
            if (!d.isMine) {
                ThinDivider(Modifier.padding(horizontal = 20.dp))
                MenuRow(
                    "Contact ${d.driver.firstName}",
                    icon = Icons.Default.ChatBubbleOutline,
                    titleColor = BrandBlue,
                    onClick = { vm.contact(d.driver.id, d.ride.id) { nav.navigate(Routes.chat(it)) } },
                )
            }
            ThickDivider()
            SectionTitle("Passengers")
            if (d.passengers.isEmpty()) {
                Text("No passengers yet — be the first!", color = CsTheme.colors.subtle, modifier = Modifier.padding(horizontal = 20.dp))
            }
            d.passengers.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.navigate(Routes.user(p.user.id)) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.user.displayName, style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                        Text("${p.booking.fromCity} → ${p.booking.toCity}", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
                    }
                    Avatar(p.user, 40.dp)
                }
            }
            Text(
                "${d.seatsLeft.coerceAtLeast(0)} of ${d.ride.seatsTotal} seats available",
                style = MaterialTheme.typography.bodyMedium,
                color = CsTheme.colors.subtle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            ThickDivider()
            if (!d.isMine) {
                MenuRow("Report this ride", icon = Icons.Default.Flag, titleColor = CsTheme.colors.danger, onClick = { showReport = true })
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmCancel && d?.myBooking != null) {
        ConfirmDialog(
            "Cancel your booking?",
            "Cancelling will free your seat. Online payments are fully refunded to your original payment method.",
            "Cancel booking",
            onConfirm = { vm.cancelBooking(d.myBooking.id) },
            onDismiss = { confirmCancel = false },
            destructive = true,
        )
    }
    if (showReport) ReportDialog(onDismiss = { showReport = false }, onReport = { vm.report(it) })
}

@Composable
private fun DetailRow(icon: ImageVector, text: String, tint: Color = CsTheme.colors.subtle) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading)
    }
}

@Composable
private fun RideBottomBar(
    nav: NavHostController,
    d: RideDetail,
    viewer: UserEntity?,
    fromName: String,
    toName: String,
    seats: Int,
    onCancel: () -> Unit,
) {
    val booking = d.myBooking
    val past = d.ride.departureAt < System.currentTimeMillis()
    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            when {
                d.isMine -> PrimaryButton("Manage your ride", { nav.navigate(Routes.manage(d.ride.id)) }, icon = Icons.Default.Settings)
                booking != null && (booking.status == "CONFIRMED" || booking.status == "PENDING") -> {
                    InfoBanner(
                        if (booking.status == "CONFIRMED") "Your booking is confirmed · ${booking.seats} seat${if (booking.seats > 1) "s" else ""} · ${Fmt.rupees(booking.total)}"
                        else "Request sent — waiting for the driver to reply",
                        if (booking.status == "CONFIRMED") Icons.Default.CheckCircle else Icons.Default.HourglassTop,
                        if (booking.status == "CONFIRMED") CsTheme.colors.success else BrandBlue,
                    )
                    if (!past) {
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("Cancel booking", onCancel, danger = true)
                    }
                }
                d.ride.status != "ACTIVE" -> InfoBanner("This ride is no longer available", Icons.Default.Block, CsTheme.colors.danger)
                past -> InfoBanner("This ride has already departed", Icons.Default.History)
                d.ride.womenOnly && viewer?.gender != "F" -> InfoBanner("This ride is for women only", Icons.Default.Woman)
                d.seatsLeft < seats -> InfoBanner("Not enough seats left on this ride", Icons.Default.EventBusy, CsTheme.colors.danger)
                else -> {
                    val from = fromName.ifBlank { d.ride.fromCity }
                    val to = toName.ifBlank { d.ride.toCity }
                    PrimaryButton(
                        if (d.ride.instantBooking) "Book" else "Request to book",
                        { nav.navigate(Routes.book(d.ride.id, from, to, seats)) },
                        icon = if (d.ride.instantBooking) Icons.Default.Bolt else Icons.Default.Send,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onReport: (String) -> Unit) {
    val reasons = listOf("Fake or misleading ride", "Price is too high", "Inappropriate content", "Safety concern", "Other")
    var reason by remember { mutableStateOf(reasons.first()) }
    var sent by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (sent) "Report sent" else "Report this ride") },
        text = {
            if (sent) Text("Thank you. Our Trust & Safety team will look into it.")
            else Column { reasons.forEach { r -> ChoiceRow(r, reason == r, { reason = r }) } }
        },
        confirmButton = {
            TextButton(onClick = { if (sent) onDismiss() else { onReport(reason); sent = true } }) { Text(if (sent) "Close" else "Send report") }
        },
        dismissButton = { if (!sent) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun dial(context: Context, phone: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })))
    } catch (_: android.content.ActivityNotFoundException) {
    }
}

private fun shareRide(context: Context, d: RideDetail) {
    val text = "Join my ride on carsathi: ${d.ride.fromCity} → ${d.ride.toCity}, ${Fmt.dayTime(d.ride.departureAt)}, " +
        "${Fmt.rupees(d.ride.pricePerSeat)} per seat with ${d.driver.displayName}."
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share ride"))
}

// =============================================================================== Booking / checkout

class BookingViewModel(private val c: AppContainer, private val rideId: Long, val fromName: String, val toName: String, initialSeats: Int) : ViewModel() {
    val uid = c.session.userId
    var detail by mutableStateOf<RideDetail?>(null)
    var seats by mutableIntStateOf(initialSeats)
    var method by mutableStateOf("UPI")
    var upiId by mutableStateOf("")
    var cardNumber by mutableStateOf("")
    var cardExpiry by mutableStateOf("")
    var cardCvv by mutableStateOf("")
    var message by mutableStateOf("")
    var processing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var board by mutableIntStateOf(0)
    var alight by mutableIntStateOf(0)
    val savedMethods: StateFlow<List<PaymentMethodEntity>> = c.wallet.observeMethods(uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            c.rides.observeDetail(rideId, uid).collect { d ->
                if (d != null && detail == null) {
                    val (b, a) = segmentFor(d.points, fromName, toName)
                    board = b
                    alight = a
                }
                detail = d
            }
        }
    }

    val pricePerSeat: Int
        get() {
            val d = detail ?: return 0
            return Pricing.segmentPrice(d.ride.pricePerSeat, d.points[alight].km - d.points[board].km, d.ride.distanceKm)
        }
    val subtotal get() = pricePerSeat * seats
    val fee get() = Pricing.serviceFee(subtotal)

    fun useSaved(m: PaymentMethodEntity) {
        method = m.type
        if (m.type == "UPI") upiId = m.details else cardNumber = m.details
    }

    fun confirm(done: (String) -> Unit) {
        val d = detail ?: return
        error = null
        when (method) {
            "UPI" -> if (!Regex("^[\\w.\\-]{2,}@[a-zA-Z]{2,}$").matches(upiId.trim())) { error = "Enter a valid UPI ID, e.g. name@okaxis"; return }
            "CARD" -> {
                val digits = cardNumber.filter { it.isDigit() }
                if (digits.length !in 13..19 || !luhn(digits)) { error = "Enter a valid card number"; return }
                if (!Regex("^(0[1-9]|1[0-2])/\\d{2}$").matches(cardExpiry)) { error = "Expiry must be MM/YY"; return }
                if (cardCvv.length !in 3..4) { error = "Enter the CVV"; return }
            }
        }
        if (seats > d.seatsLeft) { error = "Only ${d.seatsLeft} seat(s) left"; return }
        processing = true
        viewModelScope.launch {
            delay(if (method == "CASH") 400L else 1_500L)
            val passenger = c.users.get(uid) ?: return@launch
            val booking = c.rides.book(
                d.ride, passenger, seats, d.points[board].city, d.points[alight].city, pricePerSeat, method, message,
            )
            if (message.isNotBlank()) {
                c.chat.openConversation(d.ride.id, uid, d.ride.driverId).let { c.simulator.onMessageSent(it, uid, message) }
            }
            c.simulator.onBookingCreated(booking, d.ride)
            processing = false
            done(booking.status)
        }
    }

    private fun luhn(n: String): Boolean {
        var sum = 0
        n.reversed().forEachIndexed { i, ch ->
            var v = ch - '0'
            if (i % 2 == 1) { v *= 2; if (v > 9) v -= 9 }
            sum += v
        }
        return sum % 10 == 0
    }
}

@Composable
fun BookingScreen(nav: NavHostController, rideId: Long, fromName: String, toName: String, seats: Int) {
    val vm: BookingViewModel = appViewModel { BookingViewModel(it, rideId, fromName, toName, seats) }
    val saved by vm.savedMethods.collectAsStateWithLifecycle()
    val d = vm.detail
    Scaffold(
        topBar = { BackTopBar("Check your booking", { nav.popBackStack() }) },
        bottomBar = {
            if (d != null) Surface(shadowElevation = 8.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                    vm.error?.let {
                        Text(it, color = CsTheme.colors.danger, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    PrimaryButton(
                        when {
                            vm.processing && vm.method != "CASH" -> "Processing payment…"
                            d.ride.instantBooking -> "Book for ${Fmt.rupees(vm.subtotal + vm.fee)}"
                            else -> "Send request · ${Fmt.rupees(vm.subtotal + vm.fee)}"
                        },
                        { vm.confirm { status -> nav.navigate(Routes.booked(rideId, status)) { popUpTo(Routes.RIDE) { inclusive = false } } } },
                        loading = vm.processing,
                    )
                }
            }
        },
    ) { padding ->
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BrandBlue) }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
            Text(Fmt.dayLong(d.points[vm.board].timeAt(d.ride.departureAt)), style = MaterialTheme.typography.headlineSmall, color = CsTheme.colors.heading, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.padding(horizontal = 20.dp)) {
                MiniItinerary(
                    Fmt.time(d.points[vm.board].timeAt(d.ride.departureAt)),
                    Fmt.time(d.points[vm.alight].timeAt(d.ride.departureAt)),
                    Fmt.duration(d.points[vm.alight].minutes - d.points[vm.board].minutes),
                    d.points[vm.board].city,
                    d.points[vm.alight].city,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(d.driver, 32.dp)
                Spacer(Modifier.width(10.dp))
                Text("Driven by ${d.driver.displayName}", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle)
            }
            ThickDivider()
            SectionTitle("Seats")
            Counter(vm.seats, { vm.seats = it }, 1, d.seatsLeft.coerceIn(1, 6), big = false)
            Spacer(Modifier.height(8.dp))
            ThickDivider()
            SectionTitle("Price details")
            PriceLine("${Fmt.rupees(vm.pricePerSeat)} × ${vm.seats} seat${if (vm.seats > 1) "s" else ""}", Fmt.rupees(vm.subtotal))
            PriceLine("Service fee", Fmt.rupees(vm.fee))
            ThinDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            PriceLine("Total", Fmt.rupees(vm.subtotal + vm.fee), bold = true)
            ThickDivider()
            SectionTitle("Payment method")
            if (saved.isNotEmpty()) {
                saved.forEach { m ->
                    ChoiceRow(m.label, (vm.method == m.type) && (vm.upiId == m.details || vm.cardNumber == m.details), { vm.useSaved(m) }, icon = if (m.type == "UPI") Icons.Default.QrCode2 else Icons.Default.CreditCard)
                }
                ThinDivider(Modifier.padding(horizontal = 20.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                ChoiceRow("UPI", vm.method == "UPI", { vm.method = "UPI" }, subtitle = "GPay, PhonePe, Paytm, BHIM", icon = Icons.Default.QrCode2)
                if (vm.method == "UPI") CsTextField(vm.upiId, { vm.upiId = it; vm.error = null }, "UPI ID (e.g. name@okaxis)", leading = Icons.Default.AlternateEmail, keyboardType = KeyboardType.Email)
                ChoiceRow("Debit / credit card", vm.method == "CARD", { vm.method = "CARD" }, subtitle = "Visa, Mastercard, RuPay", icon = Icons.Default.CreditCard)
                if (vm.method == "CARD") {
                    CsTextField(vm.cardNumber, { vm.cardNumber = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(23); vm.error = null }, "Card number", leading = Icons.Default.CreditCard, keyboardType = KeyboardType.Number)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        CsTextField(vm.cardExpiry, { vm.cardExpiry = it.take(5); vm.error = null }, "MM/YY", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                        Spacer(Modifier.width(8.dp))
                        CsTextField(vm.cardCvv, { vm.cardCvv = it.filter { ch -> ch.isDigit() }.take(4); vm.error = null }, "CVV", Modifier.weight(1f), keyboardType = KeyboardType.NumberPassword, isPassword = true)
                    }
                }
                ChoiceRow("Cash to driver", vm.method == "CASH", { vm.method = "CASH" }, subtitle = "Pay the seat price in cash on the day. Service fee is paid online.", icon = Icons.Default.Payments)
            }
            ThickDivider()
            SectionTitle("Message to ${d.driver.firstName} (optional)")
            CsTextField(vm.message, { vm.message = it }, "Say hello and share any details about your trip", Modifier.padding(horizontal = 16.dp), singleLine = false, minLines = 3)
            Spacer(Modifier.height(12.dp))
            InfoBanner(
                if (d.ride.instantBooking) "This ride has Instant Booking: your seat is confirmed right away."
                else "The driver must accept your request. You won't be charged if it's declined.",
                if (d.ride.instantBooking) Icons.Default.Bolt else Icons.Default.Info,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PriceLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(label, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading, modifier = Modifier.weight(1f))
        Text(value, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading)
    }
}

@Composable
fun BookedScreen(nav: NavHostController, rideId: Long, status: String) {
    val confirmed = status == "CONFIRMED"
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(120.dp).clip(CircleShape).background(if (confirmed) CsTheme.colors.success.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (confirmed) Icons.Default.CheckCircle else Icons.Default.Send, null, tint = if (confirmed) CsTheme.colors.success else BrandBlue, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(if (confirmed) "Your booking is confirmed!" else "Request sent!", style = MaterialTheme.typography.headlineMedium, color = CsTheme.colors.heading, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            if (confirmed) "Have a great trip. You can chat with your driver from your Inbox."
            else "The driver usually replies within a few hours. We'll notify you as soon as they respond.",
            style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.subtle, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton("See your rides", { nav.goToTab(Routes.RIDES) })
        Spacer(Modifier.height(12.dp))
        SecondaryButton("View ride", { nav.navigate(Routes.ride(rideId)) { popUpTo(Routes.SEARCH) } })
    }
}

// =============================================================================== Map

@Composable
fun MapScreen(nav: NavHostController, rideId: Long) {
    val c = com.carsathi.app.ui.appContainer()
    val ride by produceState<com.carsathi.app.data.db.RideEntity?>(null, rideId) { value = c.rides.get(rideId) }
    val context = LocalContext.current
    Scaffold(topBar = { BackTopBar(ride?.let { "${it.fromCity} → ${it.toCity}" } ?: "Route", { nav.popBackStack() }) }) { padding ->
        val r = ride
        if (r == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BrandBlue) }
            return@Scaffold
        }
        val points = remember(r) { r.points() }
        val lineColor = Petrol.toArgb()
        Box(Modifier.padding(padding).fillMaxSize()) {
            val mapView = remember {
                Configuration.getInstance().apply {
                    userAgentValue = context.packageName
                    osmdroidBasePath = File(context.cacheDir, "osmdroid")
                    osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
                }
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(7.0)
                }
            }
            DisposableEffect(mapView) {
                mapView.onResume()
                onDispose {
                    mapView.onPause()
                    mapView.onDetach()
                }
            }
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) { map ->
                map.overlays.clear()
                val geo = points.map { GeoPoint(it.lat, it.lng) }
                val line = Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = lineColor
                    outlinePaint.strokeWidth = 12f
                }
                map.overlays.add(line)
                points.forEach { p ->
                    map.overlays.add(
                        Marker(map).apply {
                            position = GeoPoint(p.lat, p.lng)
                            title = "${p.city} · ${Fmt.time(p.timeAt(r.departureAt))}"
                            snippet = p.address
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        },
                    )
                }
                map.addOnFirstLayoutListener { _, _, _, _, _ ->
                    if (geo.size >= 2) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo).increaseByScale(1.3f), false)
                    else if (geo.isNotEmpty()) map.controller.setCenter(geo.first())
                }
                map.invalidate()
            }
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().navigationBarsPadding(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(6.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("${r.distanceKm} km · ${Fmt.duration(r.durationMin)} · ${points.size - 2} stopover${if (points.size - 2 == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                    Text(points.joinToString(" → ") { it.city }, style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
                    Text("Map data © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = CsTheme.colors.subtle)
                }
            }
        }
    }
}
