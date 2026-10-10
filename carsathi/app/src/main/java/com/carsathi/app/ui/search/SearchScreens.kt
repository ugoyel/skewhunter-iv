package com.carsathi.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.carsathi.app.AppContainer
import com.carsathi.app.data.catalog.City
import com.carsathi.app.data.catalog.CityCatalog
import com.carsathi.app.data.db.SearchHistoryEntity
import com.carsathi.app.data.db.UserEntity
import com.carsathi.app.data.model.PickedPlace
import com.carsathi.app.data.model.RideCardModel
import com.carsathi.app.data.repo.SearchQuery
import com.carsathi.app.ui.appViewModel
import com.carsathi.app.ui.components.*
import com.carsathi.app.ui.navigation.Routes
import com.carsathi.app.ui.theme.BrandBlue
import com.carsathi.app.ui.theme.BrandBlueDark
import com.carsathi.app.ui.theme.CsTheme
import com.carsathi.app.util.Fmt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

// =============================================================================== Search home

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val uid = c.session.userId
    var from by mutableStateOf<PickedPlace?>(null)
    var to by mutableStateOf<PickedPlace?>(null)
    var date by mutableStateOf(LocalDate.now())
    var seats by mutableIntStateOf(1)
    var error by mutableStateOf<String?>(null)

    val history: StateFlow<List<SearchHistoryEntity>> = c.rides.observeHistory(uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val unreadNotifications: StateFlow<Int> = c.notifications.observeUnread(uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val user: StateFlow<UserEntity?> = c.users.observe(uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun swap() {
        val f = from
        from = to
        to = f
    }

    fun search(go: (String) -> Unit) {
        val f = from
        val t = to
        error = when {
            f == null -> "Where are you leaving from?"
            t == null -> "Where are you going?"
            f.city.name == t.city.name -> "Departure and destination must be in different cities"
            else -> null
        }
        if (f == null || t == null || f.city.name == t.city.name) return
        viewModelScope.launch {
            c.rides.recordSearch(uid, SearchQuery(f.city, t.city, date, seats))
            go(Routes.results(f, t, date.toEpochDay(), seats))
        }
    }

    fun clearHistory() {
        viewModelScope.launch { c.rides.clearHistory(uid) }
    }
}

@Composable
fun SearchScreen(nav: NavHostController) {
    val vm: SearchViewModel = appViewModel { SearchViewModel(it) }
    val history by vm.history.collectAsStateWithLifecycle()
    val unread by vm.unreadNotifications.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<String?>(null) }
    var exactFor by remember { mutableStateOf<Pair<String, City>?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var showSeats by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(BrandBlue, BrandBlueDark)))
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 120.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandLockup(markSize = 34.dp, fontSize = 24, onDark = true)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { nav.navigate(Routes.NOTIFICATIONS) }) {
                        BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
                            Icon(Icons.Outlined.Notifications, "Notifications", tint = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    if (user != null) "Where to, ${user!!.firstName}?" else "Your pick of rides at low prices",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                )
            }
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 150.dp)
                    .fillMaxWidth()
                    .statusBarsPadding(),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Box {
                    Column {
                        SearchField(Icons.Outlined.Circle, vm.from?.city?.name, "Leaving from", vm.from?.takeUnless { it.isCityOnly }?.address, reserveEnd = true) { picker = "from" }
                        ThinDivider(Modifier.padding(start = 56.dp, end = 64.dp))
                        SearchField(Icons.Outlined.Circle, vm.to?.city?.name, "Going to", vm.to?.takeUnless { it.isCityOnly }?.address, reserveEnd = true) { picker = "to" }
                        ThinDivider(Modifier.padding(horizontal = 16.dp))
                        Row {
                            Box(Modifier.weight(1f)) { SearchField(Icons.Default.CalendarMonth, Fmt.date(vm.date), "Date") { showDate = true } }
                            Box(Modifier.weight(1f)) {
                                SearchField(Icons.Default.Person, if (vm.seats == 1) "1 passenger" else "${vm.seats} passengers", "Passengers") { showSeats = true }
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .background(BrandBlue)
                                .clickable { vm.search { nav.navigate(it) } }
                                .semantics { contentDescription = "Search rides" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Search", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    IconButton(
                        onClick = vm::swap,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 8.dp),
                    ) { Icon(Icons.Default.SwapVert, "Swap", tint = BrandBlue) }
                }
            }
        }
        vm.error?.let {
            Text(it, color = CsTheme.colors.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }

        val recent = history.distinctBy { it.fromCity to it.toCity }.take(4)
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                SectionTitle("Recent searches", Modifier.weight(1f))
                LinkText("Clear", vm::clearHistory)
            }
            recent.forEach { h ->
                MenuRow(
                    title = "${h.fromCity} → ${h.toCity}",
                    subtitle = "${Fmt.date(LocalDate.ofEpochDay(h.dateEpochDay))} · ${h.seats} passenger${if (h.seats > 1) "s" else ""}",
                    icon = Icons.Default.History,
                    onClick = {
                        val date = LocalDate.ofEpochDay(h.dateEpochDay).let { if (it.isBefore(LocalDate.now())) LocalDate.now() else it }
                        vm.from = CityCatalog.find(h.fromCity)?.let { PickedPlace.cityOnly(it) }; vm.to = CityCatalog.find(h.toCity)?.let { PickedPlace.cityOnly(it) }; vm.date = date; vm.seats = h.seats
                        vm.search { nav.navigate(it) }
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionTitle("Popular rides")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(popularRoutes) { (f, t) ->
                Card(
                    onClick = {
                        vm.from = CityCatalog.find(f)?.let { PickedPlace.cityOnly(it) }; vm.to = CityCatalog.find(t)?.let { PickedPlace.cityOnly(it) }
                        vm.search { nav.navigate(it) }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CsTheme.colors.surfaceAlt),
                ) {
                    Column(Modifier.padding(16.dp).width(140.dp)) {
                        Icon(Icons.Default.DirectionsCar, null, tint = BrandBlue)
                        Spacer(Modifier.height(8.dp))
                        Text(f, style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                        Text("→ $t", style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        PromoCard(
            Icons.Default.Shield, "Your safety is our priority",
            "We check reviews, profiles and IDs so you know who you're travelling with. Book with confidence.",
        )
        PromoCard(
            Icons.Default.Savings, "Driving somewhere?",
            "Publish your ride and share the cost of fuel and tolls with verified passengers.",
            "Offer a ride",
        ) { nav.navigate(Routes.PUBLISH) }
        PromoCard(
            Icons.Default.NotificationsActive, "Can't find your ride?",
            "Create a ride alert and we'll tell you as soon as a matching ride is published.",
            "My ride alerts",
        ) { nav.navigate(Routes.ALERTS) }
        Spacer(Modifier.height(24.dp))
    }
        // Solid brand-coloured status bar so content never scrolls under the clock/icons
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(BrandBlue))
    }

    when (picker) {
        "from" -> CityPickerDialog(
            "Leaving from", { picker = null },
            { city -> picker = null; vm.error = null; exactFor = "from" to city },
            history.map { it.fromCity },
            onCurrentLocation = { place -> vm.from = place; vm.error = null; picker = null },
        )
        "to" -> CityPickerDialog(
            "Going to", { picker = null },
            { city -> picker = null; vm.error = null; exactFor = "to" to city },
            history.map { it.toCity },
        )
    }
    exactFor?.let { (side, city) ->
        val current = if (side == "from") vm.from else vm.to
        val setPlace: (PickedPlace) -> Unit = { if (side == "from") vm.from = it else vm.to = it }
        MeetingPointDialog(
            city = city,
            title = if (side == "from") "Pickup point in ${city.name}" else "Drop-off point in ${city.name}",
            selected = current?.takeIf { it.city.name == city.name },
            onDismiss = {
                // Keep the city even if no exact point was chosen
                if (current?.city?.name != city.name) setPlace(PickedPlace.cityOnly(city))
                exactFor = null
            },
            onDone = { setPlace(it); exactFor = null },
            showCurrentLocation = side == "from",
        )
    }
    if (showDate) DatePickerSheet(vm.date, { showDate = false }, { vm.date = it }, maxDate = LocalDate.now().plusMonths(3))
    if (showSeats) {
        AlertDialog(
            onDismissRequest = { showSeats = false },
            title = { Text("Number of seats to book") },
            text = { Counter(vm.seats, { vm.seats = it }, 1, 6) },
            confirmButton = { TextButton(onClick = { showSeats = false }) { Text("Done") } },
        )
    }
}

private val popularRoutes = listOf(
    "Delhi" to "Jaipur", "Mumbai" to "Pune", "Bengaluru" to "Mysuru", "Delhi" to "Chandigarh",
    "Bengaluru" to "Chennai", "Delhi" to "Agra", "Chennai" to "Puducherry", "Delhi" to "Dehradun",
)

@Composable
private fun SearchField(
    icon: ImageVector,
    value: String?,
    placeholder: String,
    detail: String? = null,
    reserveEnd: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = CsTheme.colors.subtle, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        // Leave room for the swap button on the "from" / "to" rows
        Column(Modifier.padding(end = if (reserveEnd) 40.dp else 0.dp)) {
            Text(
                value ?: placeholder,
                style = MaterialTheme.typography.titleMedium,
                color = if (value == null) CsTheme.colors.subtle else CsTheme.colors.heading,
                maxLines = 1,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandBlue,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PromoCard(icon: ImageVector, title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(18.dp)) {
            Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.heading)
                if (action != null && onAction != null) LinkText(action, onAction)
            }
        }
    }
}

// =============================================================================== Results

enum class SortOption(val label: String) {
    EARLIEST("Earliest departure"),
    PRICE("Lowest price"),
    CLOSEST("Close to departure point"),
    SHORTEST("Shortest ride"),
}

data class ResultFilters(
    val times: Set<Int> = emptySet(), // 0: before 06:00, 1: 06-12, 2: 12-18, 3: after 18
    val instantOnly: Boolean = false,
    val maxTwoInBack: Boolean = false,
    val verifiedOnly: Boolean = false,
    val womenOnly: Boolean = false,
    val smokingOk: Boolean = false,
    val petsOk: Boolean = false,
) {
    val count: Int get() = times.size + listOf(instantOnly, maxTwoInBack, verifiedOnly, womenOnly, smokingOk, petsOk).count { it }
}

class ResultsViewModel(
    private val c: AppContainer,
    fromName: String,
    toName: String,
    epochDay: Long,
    val seats: Int,
    val fromPoint: PickedPlace? = null,
    val toPoint: PickedPlace? = null,
) : ViewModel() {
    val from: City? = CityCatalog.find(fromName)
    val to: City? = CityCatalog.find(toName)
    var date by mutableStateOf(LocalDate.ofEpochDay(epochDay))
    var loading by mutableStateOf(true)
    var results by mutableStateOf<List<RideCardModel>>(emptyList())
    var otherDates by mutableStateOf<List<Pair<LocalDate, Int>>>(emptyList())
    var sort by mutableStateOf(SortOption.EARLIEST)
    var filters by mutableStateOf(ResultFilters())
    var viewer by mutableStateOf<UserEntity?>(null)
    var alertCreated by mutableStateOf(false)

    init { load() }

    fun load() {
        val f = from ?: return
        val t = to ?: return
        loading = true
        viewModelScope.launch {
            c.seeded.first { it }
            viewer = c.users.get(c.session.userId)
            val q = SearchQuery(f, t, date, seats, fromExact = fromPoint?.asCity(), toExact = toPoint?.asCity())
            results = c.rides.search(q, viewer)
            otherDates = if (results.isEmpty()) c.rides.countsAround(q, viewer) else emptyList()
            loading = false
        }
    }

    fun changeDate(d: LocalDate) {
        if (d.isBefore(LocalDate.now())) return
        date = d
        load()
    }

    fun visible(): List<RideCardModel> {
        val f = filters
        val list = results.filter { r ->
            val hour = Fmt.local(r.departureAt).hour
            val slot = when { hour < 6 -> 0; hour < 12 -> 1; hour < 18 -> 2; else -> 3 }
            (f.times.isEmpty() || slot in f.times) &&
                (!f.instantOnly || r.ride.instantBooking) &&
                (!f.maxTwoInBack || r.ride.maxTwoInBack) &&
                (!f.verifiedOnly || r.driver.idVerified) &&
                (!f.womenOnly || r.ride.womenOnly) &&
                (!f.smokingOk || r.driver.smoking > 0) &&
                (!f.petsOk || r.driver.pets > 0)
        }
        return when (sort) {
            SortOption.EARLIEST -> list.sortedBy { it.departureAt }
            SortOption.PRICE -> list.sortedBy { it.price }
            SortOption.CLOSEST -> list.sortedBy { it.fromDetourKm + it.toDetourKm }
            SortOption.SHORTEST -> list.sortedBy { it.durationMin }
        }
    }

    fun createAlert() {
        val f = from ?: return
        val t = to ?: return
        viewModelScope.launch {
            c.alerts.create(c.session.userId, f.name, t.name, date.toEpochDay(), seats)
            alertCreated = true
        }
    }
}

@Composable
fun SearchResultsScreen(
    nav: NavHostController,
    fromName: String,
    toName: String,
    epochDay: Long,
    seats: Int,
    fromPoint: PickedPlace? = null,
    toPoint: PickedPlace? = null,
) {
    val vm: ResultsViewModel = appViewModel { ResultsViewModel(it, fromName, toName, epochDay, seats, fromPoint, toPoint) }
    var showFilters by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    val visible = vm.visible()

    Scaffold(
        containerColor = CsTheme.colors.surfaceAlt,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 2.dp) {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BrandBlue) }
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { nav.popBackStack() }.padding(4.dp)) {
                            Text("$fromName → $toName", style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading, maxLines = 1)
                            val exact = listOfNotNull(
                                fromPoint?.takeUnless { it.isCityOnly }?.let { "From ${it.address}" },
                                toPoint?.takeUnless { it.isCityOnly }?.let { "to ${it.address}" },
                            ).joinToString(" ")
                            if (exact.isNotBlank()) {
                                Text(exact, style = MaterialTheme.typography.bodySmall, color = BrandBlue, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Text("${Fmt.date(vm.date)}, $seats passenger${if (seats > 1) "s" else ""}", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
                        }
                        BadgedBox(badge = { if (vm.filters.count > 0) Badge { Text(vm.filters.count.toString()) } }) {
                            IconButton(onClick = { showFilters = true }) { Icon(Icons.Default.Tune, "Filter", tint = BrandBlue) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.changeDate(vm.date.minusDays(1)) }, enabled = vm.date.isAfter(LocalDate.now())) {
                            Icon(Icons.Default.ChevronLeft, "Previous day")
                        }
                        Text(
                            Fmt.dateLong(vm.date),
                            style = MaterialTheme.typography.titleSmall,
                            color = CsTheme.colors.heading,
                            modifier = Modifier.weight(1f).clickable { showDate = true },
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        IconButton(onClick = { vm.changeDate(vm.date.plusDays(1)) }) { Icon(Icons.Default.ChevronRight, "Next day") }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SortOption.entries.toList()) { s ->
                            FilterChip(
                                selected = vm.sort == s,
                                onClick = { vm.sort = s },
                                label = { Text(s.label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandBlue, selectedLabelColor = Color.White),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            vm.from == null || vm.to == null -> EmptyState(Icons.Default.LocationOff, "Unknown city", "Please search again.")
            vm.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BrandBlue) }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (visible.isNotEmpty()) {
                    item {
                        Text(
                            "${visible.size} ride${if (visible.size > 1) "s" else ""} available",
                            style = MaterialTheme.typography.titleMedium,
                            color = CsTheme.colors.heading,
                        )
                    }
                    items(visible, key = { it.ride.id }) { r ->
                        RideCard(r) { nav.navigate(Routes.ride(r.ride.id, fromName, toName, seats)) }
                    }
                } else {
                    item {
                        EmptyState(
                            Icons.Default.DirectionsCar,
                            if (vm.results.isEmpty()) "No rides yet for this day" else "No rides match your filters",
                            if (vm.results.isEmpty()) "Try another date, or create a ride alert to be notified when a driver publishes this trip."
                            else "Try removing some filters.",
                        )
                    }
                    if (vm.otherDates.isNotEmpty()) {
                        item { Text("Rides on other dates", style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading) }
                        items(vm.otherDates) { (d, n) ->
                            Card(onClick = { vm.changeDate(d) }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Event, null, tint = BrandBlue)
                                    Spacer(Modifier.width(12.dp))
                                    Text(Fmt.dateLong(d), style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading, modifier = Modifier.weight(1f))
                                    Tag("$n ride${if (n > 1) "s" else ""}")
                                }
                            }
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.NotificationsActive, null, tint = BrandBlue)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (vm.alertCreated) "Ride alert created" else "Create a ride alert", style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                                Text(
                                    if (vm.alertCreated) "We'll notify you about new rides for $fromName → $toName." else "Get notified when new rides are published.",
                                    style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle,
                                )
                            }
                            if (!vm.alertCreated) TextButton(onClick = vm::createAlert) { Text("Create", color = BrandBlue) }
                            else Icon(Icons.Default.CheckCircle, null, tint = CsTheme.colors.success)
                        }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }

    if (showDate) DatePickerSheet(vm.date, { showDate = false }, { vm.changeDate(it) }, maxDate = LocalDate.now().plusMonths(3))
    if (showFilters) {
        FilterSheet(vm.filters, vm.viewer?.gender == "F", onDismiss = { showFilters = false }, onApply = { vm.filters = it; showFilters = false })
    }
}

@Composable
private fun FilterSheet(initial: ResultFilters, canWomenOnly: Boolean, onDismiss: () -> Unit, onApply: (ResultFilters) -> Unit) {
    var f by remember { mutableStateOf(initial) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter", style = MaterialTheme.typography.headlineSmall, color = CsTheme.colors.heading, modifier = Modifier.weight(1f))
                LinkText("Clear all", { f = ResultFilters() })
            }
            Spacer(Modifier.height(8.dp))
            Text("Departure time", style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
            listOf("Before 06:00", "06:00 – 12:00", "12:01 – 18:00", "After 18:00").forEachIndexed { i, label ->
                Row(
                    Modifier.fillMaxWidth().clickable { f = f.copy(times = if (i in f.times) f.times - i else f.times + i) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading, modifier = Modifier.weight(1f))
                    Checkbox(i in f.times, { f = f.copy(times = if (it) f.times + i else f.times - i) }, colors = CheckboxDefaults.colors(checkedColor = BrandBlue))
                }
            }
            ThinDivider(Modifier.padding(vertical = 8.dp))
            Text("Trust & comfort", style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
            SwitchRow("Instant booking", f.instantOnly, { f = f.copy(instantOnly = it) }, icon = Icons.Default.Bolt)
            SwitchRow("Max. 2 in the back", f.maxTwoInBack, { f = f.copy(maxTwoInBack = it) }, icon = Icons.Default.AirlineSeatReclineNormal)
            SwitchRow("Verified ID drivers", f.verifiedOnly, { f = f.copy(verifiedOnly = it) }, icon = Icons.Default.Verified)
            if (canWomenOnly) SwitchRow("Women only", f.womenOnly, { f = f.copy(womenOnly = it) }, icon = Icons.Default.Woman)
            SwitchRow("Smoking allowed", f.smokingOk, { f = f.copy(smokingOk = it) }, icon = Icons.Default.SmokingRooms)
            SwitchRow("Pets allowed", f.petsOk, { f = f.copy(petsOk = it) }, icon = Icons.Default.Pets)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Show rides", { onApply(f) })
            Spacer(Modifier.height(16.dp))
        }
    }
}
