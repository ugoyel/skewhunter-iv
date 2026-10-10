package com.carsathi.app.ui.publish

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.carsathi.app.AppContainer
import com.carsathi.app.data.catalog.City
import com.carsathi.app.data.catalog.CityCatalog
import com.carsathi.app.data.catalog.Pricing
import com.carsathi.app.data.db.CarEntity
import com.carsathi.app.data.db.RideEntity
import com.carsathi.app.data.db.UserEntity
import com.carsathi.app.data.model.PickedPlace
import com.carsathi.app.data.repo.RideDraft
import com.carsathi.app.ui.appViewModel
import com.carsathi.app.ui.components.*
import com.carsathi.app.ui.navigation.Routes
import com.carsathi.app.ui.navigation.goToTab
import com.carsathi.app.ui.theme.BrandBlue
import com.carsathi.app.ui.theme.CsTheme
import com.carsathi.app.util.Fmt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal enum class Step { FROM, PICKUP, TO, DROPOFF, STOPOVERS, DATE, TIME, CAR, SEATS, BOOKING, PRICE, COMMENT, RETURN }

class PublishViewModel(private val c: AppContainer) : ViewModel() {
    val uid = c.session.userId
    var stepIndex by mutableIntStateOf(0)
    var from by mutableStateOf<City?>(null)
    var fromPoint by mutableStateOf<PickedPlace?>(null)
    var to by mutableStateOf<City?>(null)
    var toPoint by mutableStateOf<PickedPlace?>(null)
    var stopovers by mutableStateOf<List<City>>(emptyList())
    var date by mutableStateOf(LocalDate.now().plusDays(1))
    var hour by mutableIntStateOf(8)
    var minute by mutableIntStateOf(0)
    var carId by mutableStateOf<Long?>(null)
    var seats by mutableIntStateOf(3)
    var maxTwoInBack by mutableStateOf(true)
    var womenOnly by mutableStateOf(false)
    var instant by mutableStateOf(true)
    var price by mutableIntStateOf(0)
    var priceTouched by mutableStateOf(false)
    var comment by mutableStateOf("")
    var addReturn by mutableStateOf(false)
    var returnDate by mutableStateOf(LocalDate.now().plusDays(2))
    var returnHour by mutableIntStateOf(18)
    var returnMinute by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var publishing by mutableStateOf(false)

    val cars: StateFlow<List<CarEntity>> = c.cars.observe(uid).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val user: StateFlow<UserEntity?> = c.users.observe(uid).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal val steps = Step.entries.toList()
    internal val step get() = steps[stepIndex]

    val distanceKm: Int
        get() {
            val f = from ?: return 0
            val t = to ?: return 0
            return c.rides.routeKm(fromPoint?.asCity() ?: f, stopovers, toPoint?.asCity() ?: t)
        }

    fun suggestedStopovers(): List<City> {
        val f = from ?: return emptyList()
        val t = to ?: return emptyList()
        return CityCatalog.citiesOnTheWay(f, t).take(8)
    }

    private fun departureMillis(d: LocalDate, h: Int, m: Int): Long =
        d.atTime(LocalTime.of(h, m)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun back(exit: () -> Unit) {
        error = null
        if (stepIndex == 0) exit() else stepIndex--
    }

    fun next(published: (Long) -> Unit) {
        error = null
        when (step) {
            Step.FROM -> if (from == null) { error = "Choose where you're leaving from"; return }
            Step.PICKUP -> if (fromPoint?.city?.name != from?.name) {
                error = "Choose, type or pin the exact pickup point"; return
            }
            Step.TO -> {
                if (to == null) { error = "Choose your destination"; return }
                if (to?.name == from?.name) { error = "Destination must be different from departure"; return }
            }
            Step.DROPOFF -> if (toPoint?.city?.name != to?.name) {
                error = "Choose, type or pin the exact drop-off point"; return
            }
            Step.STOPOVERS -> Unit
            Step.DATE -> Unit
            Step.TIME -> if (departureMillis(date, hour, minute) < System.currentTimeMillis() + 30 * 60_000) {
                error = "Departure must be at least 30 minutes from now"; return
            }
            Step.CAR -> Unit
            Step.SEATS -> Unit
            Step.BOOKING -> if (!priceTouched) price = Pricing.recommended(distanceKm)
            Step.PRICE -> Unit
            Step.COMMENT -> Unit
            Step.RETURN -> {
                if (addReturn && departureMillis(returnDate, returnHour, returnMinute) <= departureMillis(date, hour, minute) + 60 * 60_000) {
                    error = "Your return must be after you arrive"; return
                }
                publish(published)
                return
            }
        }
        stepIndex++
    }

    private fun publish(published: (Long) -> Unit) {
        val fp = fromPoint ?: return
        val tp = toPoint ?: return
        val f = fp.asCity()
        val t = tp.asCity()
        publishing = true
        viewModelScope.launch {
            val draft = RideDraft(
                from = f, fromAddress = fp.address, to = t, toAddress = tp.address, stopovers = stopovers,
                departureAt = departureMillis(date, hour, minute), seats = seats, maxTwoInBack = maxTwoInBack,
                instantBooking = instant, womenOnly = womenOnly, pricePerSeat = price, carId = carId, comment = comment,
            )
            val ride = c.rides.publish(uid, draft)
            c.simulator.onRidePublished(ride)
            if (addReturn) {
                val back = c.rides.publish(
                    uid,
                    draft.copy(
                        from = t, fromAddress = tp.address, to = f, toAddress = fp.address, stopovers = stopovers.reversed(),
                        departureAt = departureMillis(returnDate, returnHour, returnMinute),
                    ),
                )
                c.simulator.onRidePublished(back)
            }
            publishing = false
            published(ride.id)
        }
    }
}

@Composable
fun PublishScreen(nav: NavHostController) {
    val vm: PublishViewModel = appViewModel { PublishViewModel(it) }
    val cars by vm.cars.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    var cityQuery by remember(vm.stepIndex) { mutableStateOf("") }
    var pickStopover by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showReturnDate by remember { mutableStateOf(false) }
    var showReturnTime by remember { mutableStateOf(false) }
    BackHandler { vm.back { nav.popBackStack() } }
    LaunchedEffect(cars) { if (vm.carId == null && cars.isNotEmpty()) vm.carId = cars.first().id }

    val title = when (vm.step) {
        Step.FROM -> "Where are you leaving from?"
        Step.PICKUP -> "Where exactly will you pick passengers up?"
        Step.TO -> "Where are you heading?"
        Step.DROPOFF -> "Where will you drop passengers off?"
        Step.STOPOVERS -> "Add stopovers to get more passengers"
        Step.DATE -> "When are you going?"
        Step.TIME -> "At what time will you pick passengers up?"
        Step.CAR -> "Which car are you driving?"
        Step.SEATS -> "So how many carsathi passengers can you take?"
        Step.BOOKING -> "Can passengers book instantly?"
        Step.PRICE -> "Set your price per seat"
        Step.COMMENT -> "Anything to add about your ride?"
        Step.RETURN -> "Coming back as well?"
    }
    val isLast = vm.step == Step.RETURN
    StepScreen(
        title = title,
        onBack = { vm.back { nav.popBackStack() } },
        progress = (vm.stepIndex + 1f) / vm.steps.size,
        onNext = if (isLast || vm.step == Step.FROM || vm.step == Step.TO) null else ({ vm.next { nav.navigate(Routes.published(it)) { popUpTo(Routes.PUBLISH) { inclusive = true } } } }),
        bottom = if (isLast) ({
            PrimaryButton(
                if (vm.addReturn) "Publish both rides" else "Publish ride",
                { vm.next { id -> nav.navigate(Routes.published(id)) { popUpTo(Routes.PUBLISH) { inclusive = true } } } },
                loading = vm.publishing,
            )
        }) else null,
    ) {
        when (vm.step) {
            Step.FROM, Step.TO -> {
                if (vm.step == Step.FROM && user?.phoneVerified == false) {
                    InfoBanner("Tip: verify your phone number so passengers can trust and reach you.", Icons.Default.Phone)
                    Spacer(Modifier.height(12.dp))
                }
                if (vm.step == Step.FROM && cityQuery.isBlank()) {
                    UseCurrentLocation(
                        onPlace = { place -> vm.from = place.city; vm.fromPoint = place; vm.next {} },
                        onUseNearestCity = { city -> vm.from = city; vm.fromPoint = null; vm.next {} },
                    )
                    ThinDivider()
                    Spacer(Modifier.height(8.dp))
                }
                CsTextField(cityQuery, { cityQuery = it }, "Search a city", leading = Icons.Default.Search, imeAction = ImeAction.Search, capitalizeWords = true)
                Spacer(Modifier.height(8.dp))
                val selected = if (vm.step == Step.FROM) vm.from else vm.to
                if (selected != null && cityQuery.isBlank()) {
                    CityOption(selected, true) { vm.next {} }
                }
                CityCatalog.search(cityQuery, 12).filter { it.name != selected?.name }.forEach { city ->
                    CityOption(city, false) {
                        if (vm.step == Step.FROM) {
                            if (vm.from?.name != city.name) vm.fromPoint = null
                            vm.from = city
                        } else {
                            if (vm.to?.name != city.name) vm.toPoint = null
                            vm.to = city
                        }
                        vm.next {}
                    }
                }
            }
            Step.PICKUP, Step.DROPOFF -> {
                val pickup = vm.step == Step.PICKUP
                val city = if (pickup) vm.from else vm.to
                if (city != null) {
                    Text(
                        if (pickup) "Choose a spot in ${city.name} that's easy to find and safe to stop at."
                        else "Where in ${city.name} will passengers get out?",
                        color = CsTheme.colors.subtle,
                    )
                    Spacer(Modifier.height(8.dp))
                    MeetingPointPicker(
                        city = city,
                        selected = if (pickup) vm.fromPoint else vm.toPoint,
                        onSelect = { p -> if (pickup) vm.fromPoint = p else vm.toPoint = p; vm.error = null },
                        showCurrentLocation = pickup,
                    )
                }
            }
            Step.STOPOVERS -> {
                val suggested = vm.suggestedStopovers()
                Text("Passengers on the way can book a seat for part of your journey.", color = CsTheme.colors.subtle)
                Spacer(Modifier.height(12.dp))
                (suggested + vm.stopovers.filter { it !in suggested }).forEach { city ->
                    val checked = city in vm.stopovers
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            vm.stopovers = if (checked) vm.stopovers - city else (vm.stopovers + city).sortedBy { s ->
                                com.carsathi.app.data.catalog.Geo.distanceKm(vm.from!!.lat, vm.from!!.lng, s.lat, s.lng)
                            }
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Place, null, tint = CsTheme.colors.subtle)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(city.name, style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
                            Text(city.state, style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
                        }
                        Checkbox(checked, null, colors = CheckboxDefaults.colors(checkedColor = BrandBlue))
                    }
                }
                if (suggested.isEmpty() && vm.stopovers.isEmpty()) {
                    Text("No cities on this route yet — you can add one yourself.", color = CsTheme.colors.subtle)
                }
                LinkText("+ Add a city", { pickStopover = true })
            }
            Step.DATE -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items((0..13).map { LocalDate.now().plusDays(it.toLong()) }) { d ->
                        val sel = d == vm.date
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (sel) BrandBlue else CsTheme.colors.surfaceAlt)
                                .clickable { vm.date = d }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, color = if (sel) Color.White else CsTheme.colors.subtle, style = MaterialTheme.typography.labelMedium)
                            Text(d.dayOfMonth.toString(), color = if (sel) Color.White else CsTheme.colors.heading, style = MaterialTheme.typography.titleLarge)
                            Text(d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, color = if (sel) Color.White else CsTheme.colors.subtle, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(Fmt.dateLong(vm.date), style = MaterialTheme.typography.headlineSmall, color = BrandBlue)
                LinkText("Pick another date", { showDate = true })
            }
            Step.TIME -> {
                val tp = rememberTimePickerState(initialHour = vm.hour, initialMinute = vm.minute, is24Hour = true)
                LaunchedEffect(tp.hour, tp.minute) { vm.hour = tp.hour; vm.minute = tp.minute }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = tp) }
            }
            Step.CAR -> {
                if (cars.isEmpty()) {
                    InfoBanner("Add your car so passengers know what to look for.", Icons.Default.DirectionsCar)
                    Spacer(Modifier.height(12.dp))
                }
                cars.forEach { car ->
                    ChoiceRow(car.title, vm.carId == car.id, { vm.carId = car.id }, subtitle = "${car.color} · ${car.plate}", icon = Icons.Default.DirectionsCar)
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Add a car", { nav.navigate(Routes.addVehicle()) }, icon = Icons.Default.Add)
            }
            Step.SEATS -> {
                Counter(vm.seats, { vm.seats = it }, 1, 8)
                Spacer(Modifier.height(24.dp))
                SwitchRow(
                    "Max. 2 in the back", vm.maxTwoInBack, { vm.maxTwoInBack = it },
                    subtitle = "Think comfort, keep the middle seat empty!", icon = Icons.Default.AirlineSeatReclineNormal,
                )
                if (user?.gender == "F") {
                    SwitchRow("Women only", vm.womenOnly, { vm.womenOnly = it }, subtitle = "Only women can see and book this ride", icon = Icons.Default.Woman)
                }
            }
            Step.BOOKING -> {
                ChoiceRow("Yes, enable Instant Booking", vm.instant, { vm.instant = true }, subtitle = "Passengers are confirmed straight away. Recommended — you get more bookings.", icon = Icons.Default.Bolt)
                ThinDivider()
                ChoiceRow("No, I'll review every request", !vm.instant, { vm.instant = false }, subtitle = "You accept or decline each booking request.", icon = Icons.Default.HourglassTop)
            }
            Step.PRICE -> PriceStep(vm)
            Step.COMMENT -> {
                CsTextField(
                    vm.comment, { vm.comment = it.take(500) },
                    "e.g. Flexible about where and when to meet? Not taking the motorway? Got limited space in your boot?",
                    singleLine = false, minLines = 5, imeAction = ImeAction.Default,
                )
                Text("${vm.comment.length}/500", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle, modifier = Modifier.align(Alignment.End))
            }
            Step.RETURN -> {
                ChoiceRow("Yes, sure!", vm.addReturn, { vm.addReturn = true }, icon = Icons.Default.SwapHoriz)
                ThinDivider()
                ChoiceRow("No, thanks", !vm.addReturn, { vm.addReturn = false }, icon = Icons.Default.Close)
                if (vm.addReturn) {
                    Spacer(Modifier.height(12.dp))
                    MenuRow("Return date", { showReturnDate = true }, Icons.Default.Event, Fmt.dateLong(vm.returnDate))
                    MenuRow("Return time", { showReturnTime = true }, Icons.Default.Schedule, "%02d:%02d".format(vm.returnHour, vm.returnMinute))
                }
                Spacer(Modifier.height(16.dp))
                RideSummary(vm)
            }
        }
        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = CsTheme.colors.danger)
        }
    }

    if (pickStopover) {
        CityPickerDialog("Add a stopover", { pickStopover = false }, { city ->
            if (city != vm.from && city != vm.to && city !in vm.stopovers) {
                vm.stopovers = (vm.stopovers + city).sortedBy { s ->
                    com.carsathi.app.data.catalog.Geo.distanceKm(vm.from!!.lat, vm.from!!.lng, s.lat, s.lng)
                }
            }
            pickStopover = false
        })
    }
    if (showDate) DatePickerSheet(vm.date, { showDate = false }, { vm.date = it }, maxDate = LocalDate.now().plusMonths(3))
    if (showReturnDate) DatePickerSheet(vm.returnDate, { showReturnDate = false }, { vm.returnDate = it }, minDate = vm.date, maxDate = LocalDate.now().plusMonths(3))
    if (showReturnTime) TimePickerSheet(vm.returnHour, vm.returnMinute, { showReturnTime = false }) { h, m -> vm.returnHour = h; vm.returnMinute = m }
}

@Composable
private fun CityOption(city: City, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.LocationOn, null, tint = if (selected) BrandBlue else CsTheme.colors.subtle)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(city.name, style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
            Text(city.state, style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
        }
        Icon(Icons.Default.ChevronRight, null, tint = CsTheme.colors.subtle)
    }
}

@Composable
private fun PriceStep(vm: PublishViewModel) {
    val km = vm.distanceKm
    val rec = Pricing.recommended(km)
    val min = Pricing.min(km)
    val max = Pricing.max(km)
    val ratio = if (rec == 0) 1.0 else vm.price.toDouble() / rec
    val (label, color) = when {
        ratio < 0.9 -> "Great deal — passengers will love it" to BrandBlue
        ratio <= 1.1 -> "Perfect price for this ride!" to CsTheme.colors.success
        ratio <= 1.3 -> "A bit high — fewer passengers may book" to CsTheme.colors.warning
        else -> "Too high — passengers may not book" to CsTheme.colors.danger
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.Default.Remove, enabled = vm.price - 10 >= min, size = 52.dp) { vm.price -= 10; vm.priceTouched = true }
            Text(
                Fmt.rupees(vm.price),
                fontSize = 48.sp,
                style = MaterialTheme.typography.displaySmall,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 180.dp),
            )
            CircleIconButton(Icons.Default.Add, enabled = vm.price + 10 <= max, size = 52.dp) { vm.price += 10; vm.priceTouched = true }
        }
        Spacer(Modifier.height(12.dp))
        Tag(label, color)
        Spacer(Modifier.height(16.dp))
        Text(
            "Recommended price: ${Fmt.rupees(rec)} per seat for ${km} km (allowed ${Fmt.rupees(min)} – ${Fmt.rupees(max)}). Prices cover fuel and tolls — carsathi is about sharing costs, not making a profit.",
            style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle, textAlign = TextAlign.Center,
        )
        if (vm.stopovers.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Passengers riding part of the way pay a share of this price based on distance.", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun RideSummary(vm: PublishViewModel) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CsTheme.colors.surfaceAlt)) {
        Column(Modifier.padding(16.dp)) {
            Text("Your ride", style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
            Spacer(Modifier.height(8.dp))
            Text("${vm.from?.name} → ${vm.to?.name}", style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
            Text(
                "Pickup: ${vm.fromPoint?.address ?: "-"}\nDrop-off: ${vm.toPoint?.address ?: "-"}",
                style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle,
            )
            if (vm.stopovers.isNotEmpty()) Text("via ${vm.stopovers.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
            Text("${Fmt.dateLong(vm.date)} at %02d:%02d".format(vm.hour, vm.minute), style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.heading)
            Text("${vm.seats} seat${if (vm.seats > 1) "s" else ""} · ${Fmt.rupees(vm.price)} per seat · ${if (vm.instant) "Instant booking" else "Manual approval"}", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.heading)
            Text("${vm.distanceKm} km", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
        }
    }
}

// =============================================================================== Published

@Composable
fun PublishedScreen(nav: NavHostController, rideId: Long) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Celebration, null, tint = BrandBlue, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Your ride is online!", style = MaterialTheme.typography.headlineMedium, color = CsTheme.colors.heading, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Passengers can now find and book it. We'll notify you about every booking and message.",
            style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.subtle, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton("See my ride", { nav.navigate(Routes.manage(rideId)) { popUpTo(Routes.SEARCH) } })
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Go to Your rides", { nav.goToTab(Routes.RIDES) })
    }
}

// =============================================================================== Edit ride

class EditRideViewModel(private val c: AppContainer, private val rideId: Long) : ViewModel() {
    var ride by mutableStateOf<RideEntity?>(null)
    var booked by mutableIntStateOf(0)
    var date by mutableStateOf(LocalDate.now())
    var hour by mutableIntStateOf(8)
    var minute by mutableIntStateOf(0)
    var seats by mutableIntStateOf(3)
    var price by mutableIntStateOf(100)
    var instant by mutableStateOf(true)
    var maxTwo by mutableStateOf(false)
    var comment by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            val r = c.rides.get(rideId) ?: return@launch
            val t = Fmt.local(r.departureAt)
            ride = r
            date = t.toLocalDate(); hour = t.hour; minute = t.minute
            seats = r.seatsTotal; price = r.pricePerSeat; instant = r.instantBooking; maxTwo = r.maxTwoInBack; comment = r.comment
            booked = c.db.bookings().byRide(rideId).filter { it.status == "CONFIRMED" || it.status == "PENDING" }.sumOf { it.seats }
        }
    }

    fun save(done: () -> Unit) {
        val r = ride ?: return
        val dep = date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (dep < System.currentTimeMillis()) { error = "Departure must be in the future"; return }
        if (seats < booked) { error = "You already have $booked seat(s) booked"; return }
        viewModelScope.launch {
            c.rides.update(r.copy(departureAt = dep, seatsTotal = seats, pricePerSeat = price, instantBooking = instant, maxTwoInBack = maxTwo, comment = comment.trim()))
            if (dep != r.departureAt) {
                c.db.bookings().byRide(rideId).filter { it.status == "CONFIRMED" || it.status == "PENDING" }.forEach { b ->
                    c.notifications.notify(b.passengerId, "RIDE_UPDATED", "Your ride was rescheduled", "${r.fromCity} → ${r.toCity} now leaves ${Fmt.dayTime(dep)}.", rideId)
                }
            }
            done()
        }
    }
}

@Composable
fun EditRideScreen(nav: NavHostController, rideId: Long) {
    val vm: EditRideViewModel = appViewModel { EditRideViewModel(it, rideId) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val r = vm.ride
    Scaffold(
        topBar = { BackTopBar("Edit your ride", { nav.popBackStack() }) },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().padding(16.dp)) { PrimaryButton("Save changes", { vm.save { nav.popBackStack() } }) }
        },
    ) { padding ->
        if (r == null) return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
            Text("${r.fromCity} → ${r.toCity}", style = MaterialTheme.typography.headlineSmall, color = CsTheme.colors.heading, modifier = Modifier.padding(20.dp))
            MenuRow("Date", { showDate = true }, Icons.Default.Event, Fmt.dateLong(vm.date))
            MenuRow("Time", { showTime = true }, Icons.Default.Schedule, "%02d:%02d".format(vm.hour, vm.minute))
            ThickDivider()
            SectionTitle("Seats")
            Counter(vm.seats, { vm.seats = it }, maxOf(1, vm.booked), 8, big = false)
            if (vm.booked > 0) Text("${vm.booked} already booked", color = CsTheme.colors.subtle, modifier = Modifier.padding(horizontal = 20.dp))
            ThickDivider()
            SectionTitle("Price per seat")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.Default.Remove, enabled = vm.price - 10 >= Pricing.min(r.distanceKm), size = 40.dp) { vm.price -= 10 }
                Text(Fmt.rupees(vm.price), style = MaterialTheme.typography.headlineSmall, color = CsTheme.colors.heading, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 120.dp))
                CircleIconButton(Icons.Default.Add, enabled = vm.price + 10 <= Pricing.max(r.distanceKm), size = 40.dp) { vm.price += 10 }
            }
            ThickDivider()
            Column(Modifier.padding(horizontal = 20.dp)) {
                SwitchRow("Instant booking", vm.instant, { vm.instant = it }, icon = Icons.Default.Bolt)
                SwitchRow("Max. 2 in the back", vm.maxTwo, { vm.maxTwo = it }, icon = Icons.Default.AirlineSeatReclineNormal)
                Spacer(Modifier.height(8.dp))
                CsTextField(vm.comment, { vm.comment = it.take(500) }, "Ride details", singleLine = false, minLines = 3)
                vm.error?.let { Text(it, color = CsTheme.colors.danger, modifier = Modifier.padding(top = 8.dp)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showDate) DatePickerSheet(vm.date, { showDate = false }, { vm.date = it }, maxDate = LocalDate.now().plusMonths(3))
    if (showTime) TimePickerSheet(vm.hour, vm.minute, { showTime = false }) { h, m -> vm.hour = h; vm.minute = m }
}
