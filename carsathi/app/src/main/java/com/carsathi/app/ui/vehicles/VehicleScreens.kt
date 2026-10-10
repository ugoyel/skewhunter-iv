package com.carsathi.app.ui.vehicles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.carsathi.app.AppContainer
import com.carsathi.app.data.catalog.CarCatalog
import com.carsathi.app.data.db.CarEntity
import com.carsathi.app.ui.appViewModel
import com.carsathi.app.ui.components.*
import com.carsathi.app.ui.navigation.Routes
import com.carsathi.app.ui.theme.BrandBlue
import com.carsathi.app.ui.theme.CsTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class VehiclesViewModel(private val c: AppContainer) : ViewModel() {
    val cars: StateFlow<List<CarEntity>?> = c.cars.observe(c.session.userId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun delete(id: Long) = viewModelScope.launch { c.cars.delete(id) }
}

@Composable
fun VehiclesScreen(nav: NavHostController) {
    val vm: VehiclesViewModel = appViewModel { VehiclesViewModel(it) }
    val cars by vm.cars.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<CarEntity?>(null) }
    Scaffold(
        topBar = { BackTopBar("Vehicles", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.addVehicle()) },
                containerColor = BrandBlue, contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add vehicle") },
            )
        },
    ) { padding ->
        val list = cars ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.padding(padding)) {
                EmptyState(Icons.Default.DirectionsCar, "No vehicles yet", "Add your car so passengers know what to look for at pickup.", "Add vehicle") { nav.navigate(Routes.addVehicle()) }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { car ->
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = CsTheme.colors.surfaceAlt)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(CarCatalog.colorOf(car.color)).border(1.dp, CsTheme.colors.divider, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DirectionsCar, null, tint = if (car.color in listOf("White", "Silver", "Beige", "Yellow")) Color(0xFF054752) else Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(car.title, style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
                            Text("${car.color} · ${car.bodyType} · ${car.year}", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle)
                            Text("${car.plate} · ${car.fuel}", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
                        }
                        IconButton(onClick = { nav.navigate(Routes.addVehicle(car.id)) }) { Icon(Icons.Default.Edit, "Edit", tint = BrandBlue) }
                        IconButton(onClick = { toDelete = car }) { Icon(Icons.Default.Delete, "Delete", tint = CsTheme.colors.danger) }
                    }
                }
            }
        }
    }
    toDelete?.let { car ->
        ConfirmDialog("Delete ${car.title}?", "Rides already published with this car will keep their details.", "Delete", { vm.delete(car.id) }, { toDelete = null }, destructive = true)
    }
}

// =============================================================================== Add / edit vehicle

class AddVehicleViewModel(private val c: AppContainer, private val carId: Long) : ViewModel() {
    var step by mutableIntStateOf(0)
    var plate by mutableStateOf("")
    var brand by mutableStateOf("")
    var model by mutableStateOf("")
    var customModel by mutableStateOf(false)
    var bodyType by mutableStateOf("")
    var color by mutableStateOf("")
    var year by mutableIntStateOf(0)
    var fuel by mutableStateOf("")
    var query by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    private var existing: CarEntity? = null
    val editing = carId != 0L
    val totalSteps = 7

    init {
        if (editing) viewModelScope.launch {
            val car = c.cars.get(carId) ?: return@launch
            existing = car
            plate = car.plate; brand = car.brand; model = car.model; bodyType = car.bodyType; color = car.color; year = car.year; fuel = car.fuel
            customModel = CarCatalog.models(car.brand).none { it.name == car.model }
        }
    }

    fun next(done: () -> Unit) {
        error = null
        when (step) {
            0 -> {
                val p = plate.trim().uppercase()
                if (p.replace(" ", "").length < 6) { error = "Enter your full registration number"; return }
                plate = p
            }
            1 -> if (brand.isBlank()) { error = "Choose a brand"; return }
            2 -> {
                if (model.isBlank()) { error = "Choose or type your model"; return }
                CarCatalog.bodyTypeFor(brand, model)?.let { if (bodyType.isBlank()) bodyType = it }
            }
            3 -> if (bodyType.isBlank()) { error = "Choose a vehicle type"; return }
            4 -> if (color.isBlank()) { error = "Choose a colour"; return }
            5 -> if (year == 0) { error = "Choose the year of registration"; return }
            6 -> {
                if (fuel.isBlank()) { error = "Choose a fuel type"; return }
                save(done)
                return
            }
        }
        query = ""
        step++
    }

    private fun save(done: () -> Unit) {
        viewModelScope.launch {
            val base = existing
            val car = CarEntity(
                id = base?.id ?: 0, ownerId = c.session.userId, brand = brand, model = model.trim(), bodyType = bodyType,
                color = color, year = year, plate = plate, fuel = fuel, createdAt = base?.createdAt ?: System.currentTimeMillis(),
            )
            c.cars.save(car)
            done()
        }
    }
}

@Composable
fun AddVehicleScreen(nav: NavHostController, carId: Long) {
    val vm: AddVehicleViewModel = appViewModel(key = "car$carId") { AddVehicleViewModel(it, carId) }
    val back: () -> Unit = { if (vm.step == 0) nav.popBackStack() else { vm.error = null; vm.query = ""; vm.step-- } }
    BackHandler(enabled = vm.step > 0) { back() }
    val done: () -> Unit = { nav.popBackStack() }
    val title = when (vm.step) {
        0 -> "What's your licence plate number?"
        1 -> "What's your vehicle's brand?"
        2 -> "What's your vehicle's model?"
        3 -> "What kind of vehicle is it?"
        4 -> "What colour is your vehicle?"
        5 -> "When was it registered?"
        else -> "What fuel does it use?"
    }
    StepScreen(
        title = title,
        onBack = back,
        progress = (vm.step + 1f) / vm.totalSteps,
        onNext = if (vm.step == 6) null else ({ vm.next(done) }),
        bottom = if (vm.step == 6) ({ PrimaryButton(if (vm.editing) "Save vehicle" else "Add vehicle", { vm.next(done) }) }) else null,
    ) {
        when (vm.step) {
            0 -> {
                CsTextField(vm.plate, { vm.plate = it.uppercase().take(13); vm.error = null }, "e.g. DL 3C AB 1234", leading = Icons.Default.Pin, imeAction = ImeAction.Done, onIme = { vm.next(done) })
                Spacer(Modifier.height(8.dp))
                Text("Passengers use it to recognise your car at pickup. It's only shown to booked passengers.", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
            }
            1 -> {
                CsTextField(vm.query, { vm.query = it }, "Search ${CarCatalog.brandNames.size} brands", leading = Icons.Default.Search, capitalizeWords = true)
                Spacer(Modifier.height(8.dp))
                CarCatalog.brandNames.filter { it.contains(vm.query.trim(), ignoreCase = true) }.forEach { b ->
                    PickRow(b, vm.brand == b, "${CarCatalog.models(b).size} models") {
                        if (vm.brand != b) { vm.model = ""; vm.bodyType = "" }
                        vm.brand = b
                        vm.next(done)
                    }
                }
            }
            2 -> {
                val models = CarCatalog.models(vm.brand)
                if (!vm.customModel) {
                    CsTextField(vm.query, { vm.query = it }, "Search ${vm.brand} models", leading = Icons.Default.Search, capitalizeWords = true)
                    Spacer(Modifier.height(8.dp))
                    models.filter { it.name.contains(vm.query.trim(), ignoreCase = true) }.forEach { m ->
                        PickRow(m.name, vm.model == m.name, m.bodyType) {
                            vm.model = m.name
                            vm.bodyType = m.bodyType
                            vm.next(done)
                        }
                    }
                    LinkText("My model isn't listed", { vm.customModel = true; vm.model = "" })
                } else {
                    CsTextField(vm.model, { vm.model = it; vm.error = null }, "Model name", capitalizeWords = true, imeAction = ImeAction.Done, onIme = { vm.next(done) })
                    LinkText("Choose from the list instead", { vm.customModel = false })
                }
            }
            3 -> CarCatalog.bodyTypes.forEach { t ->
                ChoiceRow(t, vm.bodyType == t, { vm.bodyType = t; vm.error = null }, icon = when (t) {
                    "SUV", "Compact SUV" -> Icons.Default.AirportShuttle
                    "MUV", "Van" -> Icons.Default.AirportShuttle
                    "Pickup" -> Icons.Default.LocalShipping
                    else -> Icons.Default.DirectionsCar
                })
            }
            4 -> {
                CarCatalog.colors.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { cc ->
                            Column(
                                Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.color = cc.name; vm.error = null }.padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier.size(48.dp).clip(CircleShape).background(cc.color)
                                        .border(if (vm.color == cc.name) 3.dp else 1.dp, if (vm.color == cc.name) BrandBlue else CsTheme.colors.divider, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (vm.color == cc.name) Icon(Icons.Default.Check, null, tint = if (cc.name in listOf("White", "Silver", "Beige", "Yellow", "Gold")) Color.Black else Color.White)
                                }
                                Text(cc.name, style = MaterialTheme.typography.labelSmall, color = CsTheme.colors.heading, maxLines = 1)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.width(72.dp)) }
                    }
                }
            }
            5 -> {
                val now = LocalDate.now().year
                (now downTo now - 25).chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { y ->
                            FilterChip(
                                selected = vm.year == y,
                                onClick = { vm.year = y; vm.error = null },
                                label = { Text(y.toString()) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandBlue, selectedLabelColor = Color.White),
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            else -> {
                CarCatalog.fuelTypes.forEach { f ->
                    ChoiceRow(f, vm.fuel == f, { vm.fuel = f; vm.error = null }, icon = when (f) {
                        "Electric" -> Icons.Default.ElectricCar
                        "CNG", "LPG" -> Icons.Default.Eco
                        else -> Icons.Default.LocalGasStation
                    })
                }
                Spacer(Modifier.height(12.dp))
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CsTheme.colors.surfaceAlt)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Text("${vm.brand} ${vm.model}", style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
                        Text("${vm.color} · ${vm.bodyType} · ${vm.year} · ${vm.plate}", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle)
                    }
                }
            }
        }
        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = CsTheme.colors.danger)
        }
    }
}

@Composable
private fun PickRow(title: String, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) BrandBlue else CsTheme.colors.heading)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
        }
        Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.ChevronRight, null, tint = if (selected) BrandBlue else CsTheme.colors.subtle)
    }
    ThinDivider()
}
