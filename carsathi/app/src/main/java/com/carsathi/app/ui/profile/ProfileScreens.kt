package com.carsathi.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.carsathi.app.AppContainer
import com.carsathi.app.data.catalog.CarCatalog
import com.carsathi.app.data.db.CarEntity
import com.carsathi.app.data.db.RatingStat
import com.carsathi.app.data.db.RideEntity
import com.carsathi.app.data.db.UserEntity
import com.carsathi.app.data.model.PrefLabels
import com.carsathi.app.data.model.RatingItem
import com.carsathi.app.data.model.RatingLabels
import com.carsathi.app.data.model.age
import com.carsathi.app.data.model.experienceLevel
import com.carsathi.app.ui.appViewModel
import com.carsathi.app.ui.auth.OtpBlock
import com.carsathi.app.ui.auth.formatPhone
import com.carsathi.app.ui.auth.isValidEmail
import com.carsathi.app.ui.auth.isValidIndianPhone
import com.carsathi.app.ui.components.*
import com.carsathi.app.ui.navigation.Routes
import com.carsathi.app.ui.navigation.resetTo
import com.carsathi.app.ui.theme.BrandBlue
import com.carsathi.app.ui.theme.CsTheme
import com.carsathi.app.ui.theme.StarYellow
import com.carsathi.app.util.Fmt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.random.Random

// =============================================================================== My profile

class ProfileViewModel(private val c: AppContainer, userId: Long? = null) : ViewModel() {
    val uid = userId ?: c.session.userId
    val isMe = uid == c.session.userId
    val user: StateFlow<UserEntity?> = c.users.observe(uid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val stat: StateFlow<RatingStat?> = c.users.observeRatingStat(uid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val cars: StateFlow<List<CarEntity>> = c.cars.observe(uid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var rides by mutableIntStateOf(0)
    var published by mutableIntStateOf(0)

    init {
        viewModelScope.launch {
            val u = c.users.get(uid) ?: return@launch
            rides = c.users.rideCount(u)
            published = c.db.rides().countByDriver(uid) + u.pastRides / 2
        }
    }

    fun logout(done: () -> Unit) = viewModelScope.launch { c.auth.logout(); done() }

    fun report(reason: String) = viewModelScope.launch {
        c.notifications.notify(c.session.userId, "REPORT", "Thanks for your report", "We'll review this member's profile. Reason: $reason", push = false)
    }
}

@Composable
fun ProfileScreen(nav: NavHostController) {
    val vm: ProfileViewModel = appViewModel { ProfileViewModel(it) }
    val user by vm.user.collectAsStateWithLifecycle()
    val stat by vm.stat.collectAsStateWithLifecycle()
    val cars by vm.cars.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmLogout by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, contentColor = BrandBlue) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("About you", style = MaterialTheme.typography.titleSmall) })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Account", style = MaterialTheme.typography.titleSmall) })
                }
            }
        },
    ) { padding ->
        val u = user ?: return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (tab == 0) {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.navigate(Routes.user(u.id)) }.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(u.firstName, style = MaterialTheme.typography.headlineMedium, color = CsTheme.colors.heading)
                        Text(experienceLevel(vm.rides, stat?.avg ?: 0.0), style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.subtle)
                    }
                    Avatar(u, 72.dp, showVerified = true)
                    Icon(Icons.Default.ChevronRight, null, tint = CsTheme.colors.subtle)
                }
                Row(Modifier.padding(horizontal = 12.dp)) {
                    LinkText("Edit profile picture & details", { nav.navigate(Routes.EDIT_PROFILE) })
                }
                ThickDivider()
                SectionTitle("Verify your profile")
                VerifyRow("Verify ID", when (u.idStatus) { "VERIFIED" -> true; else -> false }, if (u.idStatus == "PENDING") "Under review" else null) { nav.navigate(Routes.VERIFY) }
                VerifyRow("Confirm email ${u.email}", u.emailVerified) { nav.navigate(Routes.VERIFY) }
                VerifyRow(if (u.phone.isBlank()) "Add phone number" else "Confirm phone ${u.phone}", u.phoneVerified) { nav.navigate(Routes.VERIFY) }
                ThickDivider()
                SectionTitle("About you")
                MenuRow(if (u.bio.isBlank()) "Add a mini bio" else u.bio, { nav.navigate(Routes.EDIT_PROFILE) }, Icons.Default.Edit)
                MenuRow("Edit travel preferences", { nav.navigate(Routes.PREFERENCES) }, Icons.Default.Tune)
                PrefSummary(u)
                ThickDivider()
                SectionTitle("Ratings")
                MenuRow(
                    if ((stat?.cnt ?: 0) == 0) "No ratings yet" else "${Fmt.rating(stat!!.avg)}/5 · ${stat!!.cnt} ratings",
                    { nav.navigate(Routes.ratings(u.id)) },
                    Icons.Default.Star,
                )
                ThickDivider()
                SectionTitle("Vehicles")
                cars.forEach { car ->
                    MenuRow(car.title, { nav.navigate(Routes.addVehicle(car.id)) }, Icons.Default.DirectionsCar, "${car.color} · ${car.plate}")
                }
                MenuRow("Add vehicle", { nav.navigate(Routes.addVehicle()) }, Icons.Default.AddCircleOutline, titleColor = BrandBlue)
                ThickDivider()
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatBlock("${vm.rides}", "rides")
                    StatBlock("${vm.published}", "published")
                    StatBlock(Fmt.monthYear(u.memberSince), "member since")
                }
            } else {
                MenuRow("Ratings", { nav.navigate(Routes.ratings(u.id)) }, Icons.Default.Star)
                MenuRow("Ride alerts", { nav.navigate(Routes.ALERTS) }, Icons.Default.NotificationsActive)
                MenuRow("Notifications, emails & SMS", { nav.navigate(Routes.NOTIF_SETTINGS) }, Icons.Default.Notifications)
                MenuRow("Password", { nav.navigate(Routes.PASSWORD) }, Icons.Default.Lock)
                MenuRow("Verification", { nav.navigate(Routes.VERIFY) }, Icons.Default.VerifiedUser)
                MenuRow("Vehicles", { nav.navigate(Routes.VEHICLES) }, Icons.Default.DirectionsCar)
                ThickDivider()
                MenuRow("Payment methods", { nav.navigate(Routes.PAYMENTS) }, Icons.Default.CreditCard)
                MenuRow("Payout methods", { nav.navigate(Routes.PAYOUTS) }, Icons.Default.AccountBalance)
                MenuRow("Payments & refunds", { nav.navigate(Routes.TRANSACTIONS) }, Icons.Default.ReceiptLong)
                ThickDivider()
                MenuRow("Help centre", { nav.navigate(Routes.HELP) }, Icons.Default.HelpOutline)
                MenuRow("Terms & Conditions", { nav.navigate(Routes.legal("terms")) }, Icons.Default.Description)
                MenuRow("Data protection", { nav.navigate(Routes.legal("privacy")) }, Icons.Default.PrivacyTip)
                MenuRow("Community guidelines", { nav.navigate(Routes.legal("community")) }, Icons.Default.Groups)
                MenuRow("About carsathi", { nav.navigate(Routes.ABOUT) }, Icons.Default.Info)
                ThickDivider()
                MenuRow("Log out", { confirmLogout = true }, Icons.Default.Logout, titleColor = BrandBlue)
                MenuRow("Close my account", { nav.navigate(Routes.DELETE) }, Icons.Default.DeleteForever, titleColor = CsTheme.colors.danger)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirmLogout) {
        ConfirmDialog("Log out?", "You can log back in at any time.", "Log out", onConfirm = {
            vm.logout { nav.resetTo(Routes.WELCOME) }
        }, onDismiss = { confirmLogout = false })
    }
}

@Composable
private fun StatBlock(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = CsTheme.colors.heading)
        Text(label, style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
    }
}

@Composable
private fun VerifyRow(text: String, done: Boolean, status: String? = null, onClick: () -> Unit) {
    MenuRow(
        text,
        if (done) null else onClick,
        if (done) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
        subtitle = status,
        titleColor = if (done) CsTheme.colors.heading else BrandBlue,
        trailing = if (done) ({ Icon(Icons.Default.Verified, null, tint = BrandBlue) }) else null,
    )
}

@Composable
fun PrefSummary(u: UserEntity) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        PrefLine(Icons.Default.RecordVoiceOver, PrefLabels.chattiness[u.chattiness.coerceIn(0, 2)])
        PrefLine(if (u.music == 0) Icons.Default.MusicOff else Icons.Default.MusicNote, PrefLabels.music[u.music.coerceIn(0, 2)])
        PrefLine(if (u.smoking == 0) Icons.Default.SmokeFree else Icons.Default.SmokingRooms, PrefLabels.smoking[u.smoking.coerceIn(0, 2)])
        PrefLine(Icons.Default.Pets, PrefLabels.pets[u.pets.coerceIn(0, 2)])
    }
}

@Composable
private fun PrefLine(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = CsTheme.colors.subtle, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.heading)
    }
}

// =============================================================================== Public profile

@Composable
fun PublicProfileScreen(nav: NavHostController, userId: Long) {
    val vm: ProfileViewModel = appViewModel(key = "user$userId") { ProfileViewModel(it, userId) }
    val user by vm.user.collectAsStateWithLifecycle()
    val stat by vm.stat.collectAsStateWithLifecycle()
    val cars by vm.cars.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf(false) }
    Scaffold(topBar = { BackTopBar("", { nav.popBackStack() }) }) { padding ->
        val u = user ?: return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(u, 104.dp, showVerified = true)
                Spacer(Modifier.height(12.dp))
                Text(u.displayName, style = MaterialTheme.typography.headlineMedium, color = CsTheme.colors.heading)
                val age = u.age()
                Text(
                    listOfNotNull(age?.let { "$it y/o" }, experienceLevel(vm.rides, stat?.avg ?: 0.0)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.subtle,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { nav.navigate(Routes.ratings(u.id)) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Star, null, tint = StarYellow)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if ((stat?.cnt ?: 0) == 0) "No ratings yet" else "${Fmt.rating(stat!!.avg)}/5 · ${stat!!.cnt} ratings",
                        style = MaterialTheme.typography.titleSmall, color = BrandBlue,
                    )
                }
            }
            ThickDivider()
            SectionTitle("${u.firstName} has a verified profile")
            TrustLine(u.idVerified, "Verified ID")
            TrustLine(u.emailVerified, "Confirmed email")
            TrustLine(u.phoneVerified, "Confirmed phone number")
            if (u.bio.isNotBlank()) {
                ThickDivider()
                SectionTitle("About ${u.firstName}")
                Text(u.bio, style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading, modifier = Modifier.padding(horizontal = 20.dp))
            }
            ThickDivider()
            SectionTitle("Travel preferences")
            PrefSummary(u)
            if (cars.isNotEmpty()) {
                ThickDivider()
                SectionTitle("Vehicle")
                cars.forEach { car ->
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(CarCatalog.colorOf(car.color)))
                        Spacer(Modifier.width(12.dp))
                        Text("${car.title} · ${car.color}", style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading)
                    }
                }
            }
            ThickDivider()
            Column(Modifier.padding(20.dp)) {
                Text("${vm.published} rides published", style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading)
                Text("Member since ${Fmt.monthYear(u.memberSince)}", style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading)
            }
            if (!vm.isMe) {
                ThickDivider()
                MenuRow("Report this member", { report = true }, Icons.Default.Flag, titleColor = CsTheme.colors.danger)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (report) {
        val reasons = listOf("Fake profile", "Inappropriate behaviour", "Didn't show up", "Asked for payment outside carsathi", "Other")
        var reason by remember { mutableStateOf(reasons.first()) }
        AlertDialog(
            onDismissRequest = { report = false },
            title = { Text("Report this member") },
            text = { Column { reasons.forEach { r -> ChoiceRow(r, reason == r, { reason = r }) } } },
            confirmButton = { TextButton(onClick = { vm.report(reason); report = false }) { Text("Send report") } },
            dismissButton = { TextButton(onClick = { report = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrustLine(ok: Boolean, text: String) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Default.Verified else Icons.Default.RemoveCircleOutline, null, tint = if (ok) BrandBlue else CsTheme.colors.subtle)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (ok) CsTheme.colors.heading else CsTheme.colors.subtle)
    }
}

// =============================================================================== Edit profile

class EditProfileViewModel(private val c: AppContainer) : ViewModel() {
    val uid = c.session.userId
    var user by mutableStateOf<UserEntity?>(null)
    var firstName by mutableStateOf("")
    var lastName by mutableStateOf("")
    var bio by mutableStateOf("")
    var email by mutableStateOf("")
    var gender by mutableStateOf("U")
    var birth by mutableStateOf(LocalDate.now().minusYears(25))
    var photoPath by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var saved by mutableStateOf(false)

    init {
        viewModelScope.launch {
            val u = c.users.get(uid) ?: return@launch
            user = u
            firstName = u.firstName; lastName = u.lastName; bio = u.bio; email = u.email; gender = u.gender
            if (u.birthDate != 0L) birth = LocalDate.ofEpochDay(u.birthDate)
            photoPath = u.photoPath
        }
    }

    fun setPhoto(uri: android.net.Uri) {
        viewModelScope.launch { c.users.savePhoto(uid, uri)?.let { photoPath = it } }
    }

    fun save(done: () -> Unit) {
        val u = user ?: return
        error = null
        if (firstName.isBlank() || lastName.isBlank()) { error = "Please enter your name"; return }
        if (!isValidEmail(email)) { error = "Please enter a valid email"; return }
        viewModelScope.launch {
            val newEmail = email.trim().lowercase()
            if (newEmail != u.email) {
                val other = c.db.users().byEmail(newEmail)
                if (other != null && other.id != u.id) { error = "This email is already used by another account"; return@launch }
            }
            c.users.update(
                u.copy(
                    firstName = firstName.trim(), lastName = lastName.trim(), bio = bio.trim(), email = newEmail,
                    emailVerified = if (newEmail != u.email) false else u.emailVerified, gender = gender,
                    birthDate = birth.toEpochDay(), photoPath = photoPath,
                ),
            )
            done()
        }
    }
}

@Composable
fun EditProfileScreen(nav: NavHostController) {
    val vm: EditProfileViewModel = appViewModel { EditProfileViewModel(it) }
    var showDate by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::setPhoto) }
    Scaffold(
        topBar = { BackTopBar("Personal details", { nav.popBackStack() }) },
        bottomBar = { Box(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) { PrimaryButton("Save", { vm.save { nav.popBackStack() } }) } },
    ) { padding ->
        val u = vm.user ?: return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Avatar(u.copy(photoPath = vm.photoPath), 112.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(36.dp).clip(CircleShape).background(BrandBlue),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.PhotoCamera, "Change photo", tint = Color.White, modifier = Modifier.size(20.dp)) }
                }
            }
            Text(
                "Profiles with a clear photo get more bookings",
                style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            CsTextField(vm.firstName, { vm.firstName = it }, "First name", capitalizeWords = true)
            Spacer(Modifier.height(10.dp))
            CsTextField(vm.lastName, { vm.lastName = it }, "Last name", capitalizeWords = true)
            Spacer(Modifier.height(10.dp))
            MenuRow("Date of birth", { showDate = true }, Icons.Default.Cake, Fmt.dateLong(vm.birth) + " " + vm.birth.year)
            Text("How would you like to be addressed?", style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading, modifier = Modifier.padding(top = 8.dp))
            Row {
                listOf("M" to "Mr", "F" to "Ms / Mrs", "U" to "Rather not say").forEach { (k, label) ->
                    FilterChip(
                        selected = vm.gender == k, onClick = { vm.gender = k }, label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrandBlue, selectedLabelColor = Color.White),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            CsTextField(vm.email, { vm.email = it }, "Email", leading = Icons.Default.Email, keyboardType = KeyboardType.Email)
            Spacer(Modifier.height(10.dp))
            CsTextField(vm.bio, { vm.bio = it.take(300) }, "Mini bio — e.g. \"I love road trips and podcasts\"", singleLine = false, minLines = 3, imeAction = ImeAction.Default)
            vm.error?.let { Text(it, color = CsTheme.colors.danger, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showDate) {
        DatePickerSheet(vm.birth, { showDate = false }, { vm.birth = it }, minDate = LocalDate.now().minusYears(100), maxDate = LocalDate.now().minusYears(18))
    }
}

// =============================================================================== Preferences

@Composable
fun PreferencesScreen(nav: NavHostController) {
    val vm: EditProfileViewModel = appViewModel { EditProfileViewModel(it) }
    val c = com.carsathi.app.ui.appContainer()
    val scope = rememberCoroutineScope()
    val u = vm.user
    Scaffold(topBar = { BackTopBar("Travel preferences", { nav.popBackStack() }) }) { padding ->
        if (u == null) return@Scaffold
        var prefs by remember(u) { mutableStateOf(listOf(u.chattiness, u.music, u.smoking, u.pets)) }
        fun set(i: Int, v: Int) {
            prefs = prefs.toMutableList().also { it[i] = v }
            val updated = u.copy(chattiness = prefs[0], music = prefs[1], smoking = prefs[2], pets = prefs[3])
            scope.launch { c.users.update(updated) }
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            listOf(
                Triple("Chattiness", Icons.Default.RecordVoiceOver, PrefLabels.chattiness),
                Triple("Music", Icons.Default.MusicNote, PrefLabels.music),
                Triple("Smoking", Icons.Default.SmokeFree, PrefLabels.smoking),
                Triple("Pets", Icons.Default.Pets, PrefLabels.pets),
            ).forEachIndexed { i, (title, icon, options) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    Icon(icon, null, tint = BrandBlue)
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, color = CsTheme.colors.heading)
                }
                options.forEachIndexed { j, label -> ChoiceRow(label, prefs[i] == j, { set(i, j) }) }
                ThinDivider()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// =============================================================================== Verification

class VerificationViewModel(private val c: AppContainer) : ViewModel() {
    val uid = c.session.userId
    val user: StateFlow<UserEntity?> = c.users.observe(uid).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    var emailCode by mutableStateOf<String?>(null)
    var emailInput by mutableStateOf("")
    var phone by mutableStateOf("")
    var otpSent by mutableStateOf<String?>(null)
    var otp by mutableStateOf("")
    var docType by mutableStateOf("Aadhaar card")
    var error by mutableStateOf<String?>(null)

    fun sendEmailCode() { emailCode = Random.nextInt(100000, 999999).toString(); emailInput = "" }

    fun verifyEmail() = viewModelScope.launch {
        val u = user.value ?: return@launch
        if (emailInput == emailCode) { c.users.update(u.copy(emailVerified = true)); emailCode = null; error = null } else error = "Incorrect email code"
    }

    fun sendOtp() {
        if (!isValidIndianPhone(phone)) { error = "Enter a valid 10-digit Indian mobile number"; return }
        error = null
        otpSent = Random.nextInt(1000, 9999).toString()
    }

    fun verifyOtp() = viewModelScope.launch {
        val u = user.value ?: return@launch
        if (otp == otpSent) {
            c.users.update(u.copy(phone = formatPhone(phone), phoneVerified = true)); otpSent = null; error = null
        } else error = "Incorrect code"
    }

    fun submitId(uri: android.net.Uri) = viewModelScope.launch {
        val u = user.value ?: return@launch
        c.users.savePhoto(uid, uri, "id") ?: run { error = "Couldn't read that photo"; return@launch }
        c.users.update(u.copy(idStatus = "PENDING", idDocType = docType))
        c.simulator.onIdSubmitted(uid)
    }
}

@Composable
fun VerificationScreen(nav: NavHostController) {
    val vm: VerificationViewModel = appViewModel { VerificationViewModel(it) }
    val user by vm.user.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.submitId(it) } }
    Scaffold(topBar = { BackTopBar("Verify your profile", { nav.popBackStack() }) }) { padding ->
        val u = user ?: return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp)) {
            Text(
                "Verified profiles build trust. Members with a verified ID get up to 3× more bookings.",
                style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.subtle,
            )
            Spacer(Modifier.height(16.dp))

            VerificationCard("Government ID", Icons.Default.Badge, u.idStatus == "VERIFIED") {
                when (u.idStatus) {
                    "VERIFIED" -> Text("${u.idDocType.ifBlank { "ID" }} verified ✅", color = CsTheme.colors.heading)
                    "PENDING" -> {
                        InfoBanner("We're reviewing your ${u.idDocType}. This usually takes a few seconds in the demo.", Icons.Default.HourglassTop)
                    }
                    else -> {
                        listOf("Aadhaar card", "Driving licence", "PAN card", "Passport", "Voter ID").forEach { d ->
                            ChoiceRow(d, vm.docType == d, { vm.docType = d })
                        }
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton("Upload a photo of your ${vm.docType}", {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, icon = Icons.Default.UploadFile)
                        Text("Your document is stored only on this device in the demo version.", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            VerificationCard("Email", Icons.Default.Email, u.emailVerified) {
                Text(u.email, color = CsTheme.colors.heading)
                if (!u.emailVerified) {
                    Spacer(Modifier.height(8.dp))
                    val code = vm.emailCode
                    if (code == null) {
                        SecondaryButton("Send confirmation code", vm::sendEmailCode)
                    } else {
                        InfoBanner("Demo mode — no email is sent. Your code is $code", Icons.Default.MarkEmailRead)
                        Spacer(Modifier.height(8.dp))
                        CsTextField(vm.emailInput, { vm.emailInput = it.filter(Char::isDigit).take(6) }, "6-digit code", keyboardType = KeyboardType.Number)
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton("Confirm email", { vm.verifyEmail() })
                    }
                }
            }

            VerificationCard("Phone number", Icons.Default.Phone, u.phoneVerified) {
                if (u.phoneVerified) {
                    Text(u.phone, color = CsTheme.colors.heading)
                } else {
                    CsTextField(vm.phone, { vm.phone = it; vm.otpSent = null }, "Mobile number (+91)", leading = Icons.Default.Phone, keyboardType = KeyboardType.Phone)
                    Spacer(Modifier.height(8.dp))
                    OtpBlock(
                        phoneVerified = false, otpSent = vm.otpSent, otp = vm.otp, onOtp = { vm.otp = it.filter(Char::isDigit).take(4) },
                        onSend = vm::sendOtp, onVerify = { vm.verifyOtp() }, canSend = vm.phone.isNotBlank(),
                    )
                }
            }
            vm.error?.let { Text(it, color = CsTheme.colors.danger, modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VerificationCard(title: String, icon: ImageVector, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CsTheme.colors.surfaceAlt),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = BrandBlue)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading, modifier = Modifier.weight(1f))
                if (done) Tag("Verified", CsTheme.colors.success, Icons.Default.Check)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

// =============================================================================== Ratings

class RatingsViewModel(c: AppContainer, val userId: Long) : ViewModel() {
    val isMe = userId == c.session.userId
    val received: StateFlow<List<RatingItem>?> = c.ratings.observeReceived(userId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val given: StateFlow<List<RatingItem>> = c.ratings.observeGiven(userId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val user: StateFlow<UserEntity?> = c.users.observe(userId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun RatingsScreen(nav: NavHostController, userId: Long) {
    val vm: RatingsViewModel = appViewModel(key = "ratings$userId") { RatingsViewModel(it, userId) }
    val received by vm.received.collectAsStateWithLifecycle()
    val given by vm.given.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(topBar = { BackTopBar(if (vm.isMe) "Ratings" else "${user?.firstName ?: ""}'s ratings", { nav.popBackStack() }) }) { padding ->
        val rec = received ?: return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (vm.isMe) {
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, contentColor = BrandBlue) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Received (${rec.size})") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Given (${given.size})") })
                }
            }
            val list = if (tab == 0) rec else given
            if (tab == 0 && rec.isNotEmpty()) RatingSummary(rec.map { it.rating.stars })
            if (list.isEmpty()) {
                EmptyState(Icons.Default.StarBorder, "No ratings yet", if (tab == 0) "Ratings appear here after shared rides." else "Rate your travel companions after each ride.")
            }
            list.forEach { r ->
                val person = if (tab == 0) r.author else r.subject
                Column(Modifier.fillMaxWidth().clickable(enabled = person != null) { person?.let { nav.navigate(Routes.user(it.id)) } }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(person, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(person?.displayName ?: "Former member", style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                            Text(Fmt.monthYear(r.rating.createdAt) + " · as " + if (r.rating.toRole == "DRIVER") "driver" else "passenger", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stars(r.rating.stars)
                        Spacer(Modifier.width(8.dp))
                        Text(RatingLabels.label(r.rating.stars), style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                    }
                    if (r.rating.comment.isNotBlank()) Text(r.rating.comment, style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading, modifier = Modifier.padding(top = 4.dp))
                }
                ThinDivider()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RatingSummary(stars: List<Int>) {
    val avg = stars.average()
    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(110.dp)) {
            Text(Fmt.rating(avg), fontSize = 44.sp, style = MaterialTheme.typography.displaySmall, color = CsTheme.colors.heading)
            Stars(avg.toInt(), 16.dp)
            Text("${stars.size} ratings", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            (5 downTo 1).forEach { s ->
                val n = stars.count { it == s }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(RatingLabels.label(s), style = MaterialTheme.typography.labelSmall, color = CsTheme.colors.subtle, modifier = Modifier.width(110.dp))
                    LinearProgressIndicator(
                        progress = { if (stars.isEmpty()) 0f else n.toFloat() / stars.size },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = StarYellow,
                        trackColor = CsTheme.colors.divider,
                    )
                    Text(" $n", style = MaterialTheme.typography.labelSmall, color = CsTheme.colors.subtle, modifier = Modifier.width(28.dp))
                }
            }
        }
    }
    ThinDivider()
}

// =============================================================================== Rate a member

class RateViewModel(private val c: AppContainer, val rideId: Long, val userId: Long) : ViewModel() {
    val uid = c.session.userId
    var ride by mutableStateOf<RideEntity?>(null)
    var subject by mutableStateOf<UserEntity?>(null)
    var stars by mutableIntStateOf(0)
    var comment by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var already by mutableStateOf(false)

    init {
        viewModelScope.launch {
            ride = c.rides.get(rideId)
            subject = c.users.get(userId)
            already = c.ratings.hasRated(rideId, uid, userId)
        }
    }

    val role: String get() = if (ride?.driverId == userId) "DRIVER" else "PASSENGER"

    fun submit(done: () -> Unit) {
        if (stars == 0) { error = "Choose a rating"; return }
        if (comment.trim().length < 5) { error = "Tell others a bit more about your experience"; return }
        viewModelScope.launch {
            c.ratings.rate(rideId, uid, userId, stars, comment, role)
            c.simulator.onRated(rideId, uid, userId, role)
            val me = c.users.get(uid)
            val s = subject
            if (s != null && !s.isDemo) {
                c.notifications.notify(s.id, "RATING", "New rating received", "${me?.displayName ?: "A member"} rated you $stars★.", s.id)
            }
            done()
        }
    }
}

@Composable
fun RateScreen(nav: NavHostController, rideId: Long, userId: Long) {
    val vm: RateViewModel = appViewModel(key = "rate$rideId-$userId") { RateViewModel(it, rideId, userId) }
    val s = vm.subject
    StepScreen(
        title = if (vm.role == "DRIVER") "How was your ride with ${s?.firstName ?: "your driver"}?" else "How was ${s?.firstName ?: "your passenger"} as a passenger?",
        onBack = { nav.popBackStack() },
        bottom = { if (!vm.already) PrimaryButton("Submit rating", { vm.submit { nav.popBackStack() } }) },
    ) {
        if (vm.already) {
            InfoBanner("You've already rated ${s?.firstName} for this ride.", Icons.Default.CheckCircle, CsTheme.colors.success)
            return@StepScreen
        }
        vm.ride?.let { Text("${it.fromCity} → ${it.toCity} · ${Fmt.day(it.departureAt)}", color = CsTheme.colors.subtle) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            (1..5).forEach { i ->
                Icon(
                    if (i <= vm.stars) Icons.Default.Star else Icons.Default.StarBorder, "$i stars",
                    tint = if (i <= vm.stars) StarYellow else CsTheme.colors.divider,
                    modifier = Modifier.size(52.dp).clip(CircleShape).clickable { vm.stars = i; vm.error = null }.padding(4.dp),
                )
            }
        }
        if (vm.stars > 0) {
            Text(
                RatingLabels.label(vm.stars), style = MaterialTheme.typography.titleLarge, color = CsTheme.colors.heading,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
        CsTextField(vm.comment, { vm.comment = it.take(400); vm.error = null }, "Share some details about your experience", singleLine = false, minLines = 4, imeAction = ImeAction.Default)
        vm.error?.let { Text(it, color = CsTheme.colors.danger, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(8.dp))
        Text("Ratings are public and help keep the community trustworthy.", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
    }
}
