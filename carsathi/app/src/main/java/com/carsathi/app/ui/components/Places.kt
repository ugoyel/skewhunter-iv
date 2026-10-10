package com.carsathi.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.carsathi.app.data.catalog.City
import com.carsathi.app.data.catalog.CityCatalog
import com.carsathi.app.data.model.PickedPlace
import com.carsathi.app.ui.theme.BrandBlue
import com.carsathi.app.ui.theme.CsTheme
import com.carsathi.app.util.Geocoding
import com.carsathi.app.util.LocResult
import com.carsathi.app.util.LocationHelper
import com.carsathi.app.util.Resolved
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File

// ------------------------------------------------------------------------------------------ map

/** An OpenStreetMap view tied to the composition's lifetime. */
@Composable
fun rememberMapView(): MapView {
    val context = LocalContext.current
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }
    return map
}

// ------------------------------------------------------------------------------------------ device location

class LocationRequester internal constructor() {
    var loading by mutableStateOf(false)
        internal set
    internal var launch: () -> Unit = {}

    fun request() = launch()
}

/** Asks for the location permission if needed, then reads the device location once. */
@Composable
fun rememberLocationRequester(onResult: (LocResult) -> Unit): LocationRequester {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requester = remember { LocationRequester() }
    val latestOnResult by rememberUpdatedState(onResult)
    val fetch: () -> Unit = {
        requester.loading = true
        scope.launch {
            val result = LocationHelper.locate(context)
            requester.loading = false
            latestOnResult(result)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) fetch() else latestOnResult(LocResult.Denied)
    }
    requester.launch = {
        if (LocationHelper.hasPermission(context)) fetch() else launcher.launch(LocationHelper.permissions)
    }
    return requester
}

/**
 * "Use current location" row. Resolves the device position to a covered city and an address.
 * With [within], the position must be in or around that city.
 */
@Composable
fun UseCurrentLocation(
    onPlace: (PickedPlace) -> Unit,
    within: City? = null,
    onUseNearestCity: ((City) -> Unit)? = null,
    title: String = "Use current location",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resolving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var nearest by remember { mutableStateOf<City?>(null) }
    val requester = rememberLocationRequester { result ->
        nearest = null
        message = when (result) {
            is LocResult.Found -> {
                resolving = true
                scope.launch {
                    when (val r = LocationHelper.resolve(context, result.lat, result.lng, within)) {
                        is Resolved.Ok -> onPlace(r.place)
                        is Resolved.TooFar -> {
                            message = if (within != null) {
                                "You're about ${r.km} km from ${within.name}. Pick a meeting point in ${within.name} instead."
                            } else {
                                "carsathi doesn't cover your area yet. The nearest city is ${r.nearest.name} (${r.nearestKm} km away)."
                            }
                            nearest = if (within == null) r.nearest else null
                        }
                    }
                    resolving = false
                }
                null
            }
            LocResult.Denied -> "Allow location access to use your current location (Settings → Apps → carsathi → Permissions)."
            LocResult.Disabled -> "Your phone's location is turned off. Turn on Location and try again."
            LocResult.Unavailable -> "We couldn't find your location right now. Try again, or choose a city."
        }
    }
    val busy = requester.loading || resolving
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !busy) { message = null; requester.request() }
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MyLocation, null, tint = BrandBlue)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = BrandBlue)
            Text(
                if (busy) "Finding where you are…" else "Detect your exact position with GPS",
                style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = BrandBlue, strokeWidth = 2.dp)
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.danger, modifier = Modifier.padding(horizontal = 4.dp))
    }
    val n = nearest
    if (n != null && onUseNearestCity != null) {
        LinkText("Use ${n.name} instead", { onUseNearestCity(n) })
    }
}

// ------------------------------------------------------------------------------------------ exact point

/**
 * Lets the member choose the exact pickup / drop-off point inside [city]: popular meeting points,
 * a typed address, the current location, or a pin on the map.
 */
@Composable
fun MeetingPointPicker(
    city: City,
    selected: PickedPlace?,
    onSelect: (PickedPlace) -> Unit,
    allowAnywhere: Boolean = false,
    showCurrentLocation: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember(city.name) { mutableStateOf("") }
    var showMap by remember { mutableStateOf(false) }
    var geocoding by remember { mutableStateOf(false) }
    val points = remember(city.name) { CityCatalog.meetingPoints(city) }
    val sel = selected?.takeIf { it.city.name == city.name }

    if (showCurrentLocation) {
        UseCurrentLocation(onPlace = onSelect, within = city, title = "Use my current location")
    }
    PickerRow(Icons.Default.Map, "Choose on the map", "Move a pin to the exact spot") { showMap = true }
    Spacer(Modifier.height(8.dp))
    CsTextField(
        query, { query = it },
        "Type the exact address or landmark",
        leading = Icons.Default.EditLocationAlt,
        imeAction = ImeAction.Done,
        capitalizeWords = true,
    )
    val typed = query.trim()
    if (typed.length >= 3) {
        PickerRow(
            Icons.Default.AddLocationAlt,
            "Use “$typed”",
            if (geocoding) "Locating address…" else "Set as the exact ${city.name} point",
        ) {
            if (!geocoding) {
                geocoding = true
                scope.launch {
                    val coords = Geocoding.forward(context, typed, city)
                    geocoding = false
                    onSelect(PickedPlace(city, typed, coords?.first ?: city.lat, coords?.second ?: city.lng))
                }
            }
        }
    }
    if (sel != null && !sel.isCityOnly && points.none { it.label == sel.address }) {
        ChoiceRow(sel.address, true, {}, subtitle = "Selected point", icon = Icons.Default.Place)
    }
    if (allowAnywhere) {
        ChoiceRow("Anywhere in ${city.name}", sel == null || sel.isCityOnly, { onSelect(PickedPlace.cityOnly(city)) }, icon = Icons.Default.LocationCity)
    }
    Text(
        "Popular meeting points",
        style = MaterialTheme.typography.labelMedium,
        color = CsTheme.colors.subtle,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    points.filter { typed.isEmpty() || it.label.contains(typed, ignoreCase = true) }.forEach { p ->
        ChoiceRow(p.label, sel?.address == p.label, { onSelect(PickedPlace(city, p.label, p.lat, p.lng)) }, icon = Icons.Default.Place)
    }

    if (showMap) {
        MapPointPickerDialog(
            title = "Exact point in ${city.name}",
            lat = sel?.lat ?: city.lat,
            lng = sel?.lng ?: city.lng,
            onDismiss = { showMap = false },
            onConfirm = { lat, lng ->
                showMap = false
                scope.launch {
                    val label = Geocoding.reverse(context, lat, lng)
                        ?: CityCatalog.nearestMeetingPoint(city, lat, lng, 2.0)?.let { "Pin near ${it.label}" }
                        ?: "Pinned point in ${city.name}"
                    onSelect(PickedPlace(city, label, lat, lng))
                }
            },
        )
    }
}

@Composable
private fun PickerRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = BrandBlue)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = BrandBlue)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
        }
        Icon(Icons.Default.ChevronRight, null, tint = CsTheme.colors.subtle)
    }
}

/** Full-screen version of [MeetingPointPicker], used by the search form. */
@Composable
fun MeetingPointDialog(
    city: City,
    title: String,
    selected: PickedPlace?,
    onDismiss: () -> Unit,
    onDone: (PickedPlace) -> Unit,
    allowAnywhere: Boolean = true,
    showCurrentLocation: Boolean = true,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BrandBlue) }
                    Text(title, style = MaterialTheme.typography.titleLarge, color = CsTheme.colors.heading)
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(horizontal = 16.dp),
                ) {
                    MeetingPointPicker(city, selected, onDone, allowAnywhere, showCurrentLocation)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Map with a fixed centre pin: the member pans the map under the pin to choose a precise point. */
@Composable
fun MapPointPickerDialog(title: String, lat: Double, lng: Double, onDismiss: () -> Unit, onConfirm: (Double, Double) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                val map = rememberMapView()
                AndroidView(
                    factory = {
                        map.apply {
                            controller.setZoom(15.0)
                            controller.setCenter(GeoPoint(lat, lng))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Pin whose tip marks the centre of the map
                Icon(
                    Icons.Default.LocationOn, "Selected point", tint = BrandBlue,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-24).dp).size(48.dp),
                )
                Box(Modifier.align(Alignment.Center).size(6.dp).clip(CircleShape).background(Color(0xFF054752)))
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BrandBlue) }
                    Spacer(Modifier.width(10.dp))
                    Surface(shape = RoundedCornerShape(50), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(6.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Move the map to place the pin on the exact meeting point", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.heading)
                        Text("Map data © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = CsTheme.colors.subtle)
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("Confirm this point", {
                            val c = map.mapCenter
                            onConfirm(c.latitude, c.longitude)
                        }, icon = Icons.Default.Check)
                    }
                }
            }
        }
    }
}
