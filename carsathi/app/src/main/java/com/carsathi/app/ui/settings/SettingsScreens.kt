package com.carsathi.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.carsathi.app.AppContainer
import com.carsathi.app.BuildConfig
import com.carsathi.app.data.catalog.CarCatalog
import com.carsathi.app.data.catalog.City
import com.carsathi.app.data.catalog.CityCatalog
import com.carsathi.app.data.db.AlertEntity
import com.carsathi.app.data.db.PaymentMethodEntity
import com.carsathi.app.data.db.TransactionEntity
import com.carsathi.app.data.db.UserEntity
import com.carsathi.app.data.repo.Passwords
import com.carsathi.app.ui.appViewModel
import com.carsathi.app.ui.components.*
import com.carsathi.app.ui.navigation.Routes
import com.carsathi.app.ui.navigation.resetTo
import com.carsathi.app.ui.theme.BrandBlue
import com.carsathi.app.ui.theme.CsTheme
import com.carsathi.app.util.Fmt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

// =============================================================================== Settings hub

@Composable
fun SettingsScreen(nav: NavHostController) {
    Scaffold(topBar = { BackTopBar("Settings", { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            MenuRow("Notifications, emails & SMS", { nav.navigate(Routes.NOTIF_SETTINGS) }, Icons.Default.Notifications)
            MenuRow("Password", { nav.navigate(Routes.PASSWORD) }, Icons.Default.Lock)
            MenuRow("Payment methods", { nav.navigate(Routes.PAYMENTS) }, Icons.Default.CreditCard)
            MenuRow("Payout methods", { nav.navigate(Routes.PAYOUTS) }, Icons.Default.AccountBalance)
            MenuRow("Payments & refunds", { nav.navigate(Routes.TRANSACTIONS) }, Icons.Default.ReceiptLong)
            MenuRow("Help centre", { nav.navigate(Routes.HELP) }, Icons.Default.HelpOutline)
            MenuRow("About carsathi", { nav.navigate(Routes.ABOUT) }, Icons.Default.Info)
        }
    }
}

// =============================================================================== Account settings VM

class AccountViewModel(private val c: AppContainer) : ViewModel() {
    val uid = c.session.userId
    val user: StateFlow<UserEntity?> = c.users.observe(uid).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val methods: StateFlow<List<PaymentMethodEntity>> = c.wallet.observeMethods(uid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val transactions: StateFlow<List<TransactionEntity>?> = c.wallet.observeTransactions(uid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val alerts: StateFlow<List<AlertEntity>> = c.alerts.observe(uid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    fun update(transform: (UserEntity) -> UserEntity) = viewModelScope.launch {
        user.value?.let { c.users.update(transform(it)) }
    }

    fun changePassword(current: String, new: String, confirm: String, done: () -> Unit) {
        error = null
        if (new != confirm) { error = "Passwords don't match"; return }
        Passwords.validate(new)?.let { error = it; return }
        viewModelScope.launch {
            val r = c.auth.changePassword(uid, current, new)
            if (r.isSuccess) done() else error = r.exceptionOrNull()?.message
        }
    }

    fun addUpi(id: String): Boolean {
        if (!Regex("^[\\w.\\-]{2,}@[a-zA-Z]{2,}$").matches(id.trim())) { error = "Enter a valid UPI ID"; return false }
        error = null
        viewModelScope.launch { c.wallet.addMethod(uid, "UPI", "UPI · ${id.trim()}", id.trim()) }
        return true
    }

    fun addCard(number: String, name: String): Boolean {
        val digits = number.filter { it.isDigit() }
        if (digits.length !in 13..19) { error = "Enter a valid card number"; return false }
        error = null
        val brand = when {
            digits.startsWith("4") -> "Visa"
            digits.startsWith("5") || digits.startsWith("2") -> "Mastercard"
            digits.startsWith("6") || digits.startsWith("8") -> "RuPay"
            else -> "Card"
        }
        viewModelScope.launch { c.wallet.addMethod(uid, "CARD", "$brand •••• ${digits.takeLast(4)}${if (name.isNotBlank()) " · $name" else ""}", digits) }
        return true
    }

    fun deleteMethod(id: Long) = viewModelScope.launch { c.wallet.deleteMethod(id) }

    fun createAlert(from: City, to: City, date: LocalDate?) = viewModelScope.launch {
        c.alerts.create(uid, from.name, to.name, date?.toEpochDay() ?: -1, 1)
    }

    fun deleteAlert(id: Long) = viewModelScope.launch { c.alerts.delete(id) }

    fun deleteAccount(done: () -> Unit) = viewModelScope.launch {
        c.auth.deleteAccount(uid)
        done()
    }
}

// =============================================================================== Password

@Composable
fun ChangePasswordScreen(nav: NavHostController) {
    val vm: AccountViewModel = appViewModel { AccountViewModel(it) }
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    StepScreen(
        title = if (done) "Password changed" else "Change your password",
        onBack = { nav.popBackStack() },
        bottom = {
            if (done) PrimaryButton("Done", { nav.popBackStack() })
            else PrimaryButton("Save password", { vm.changePassword(current, new, confirm) { done = true } })
        },
    ) {
        if (done) {
            InfoBanner("Your password has been updated.", Icons.Default.CheckCircle, CsTheme.colors.success)
            return@StepScreen
        }
        CsTextField(current, { current = it }, "Current password", leading = Icons.Default.Lock, isPassword = true, keyboardType = KeyboardType.Password)
        Spacer(Modifier.height(12.dp))
        CsTextField(new, { new = it }, "New password", leading = Icons.Default.VpnKey, isPassword = true, keyboardType = KeyboardType.Password)
        Spacer(Modifier.height(12.dp))
        CsTextField(confirm, { confirm = it }, "Confirm new password", leading = Icons.Default.VpnKey, isPassword = true, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
        Spacer(Modifier.height(8.dp))
        com.carsathi.app.ui.auth.PasswordRules(new)
        vm.error?.let { Text(it, color = CsTheme.colors.danger, modifier = Modifier.padding(top = 8.dp)) }
    }
}

// =============================================================================== Payment methods

@Composable
fun PaymentMethodsScreen(nav: NavHostController) {
    val vm: AccountViewModel = appViewModel { AccountViewModel(it) }
    val methods by vm.methods.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<String?>(null) }
    var upi by remember { mutableStateOf("") }
    var card by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Scaffold(topBar = { BackTopBar("Payment methods", { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
            Text(
                "Save a payment method to book rides faster. Card details are tokenised by our payment partner in the live version; this demo only stores the last 4 digits for display.",
                style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle, modifier = Modifier.padding(20.dp),
            )
            if (methods.isEmpty()) Text("No saved payment methods", color = CsTheme.colors.subtle, modifier = Modifier.padding(horizontal = 20.dp))
            methods.forEach { m ->
                MenuRow(m.label, null, if (m.type == "UPI") Icons.Default.QrCode2 else Icons.Default.CreditCard, trailing = {
                    IconButton(onClick = { vm.deleteMethod(m.id) }) { Icon(Icons.Default.Delete, "Remove", tint = CsTheme.colors.danger) }
                })
            }
            ThickDivider()
            when (adding) {
                "UPI" -> Column(Modifier.padding(20.dp)) {
                    CsTextField(upi, { upi = it }, "UPI ID (e.g. name@okhdfcbank)", leading = Icons.Default.AlternateEmail, keyboardType = KeyboardType.Email)
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Save UPI ID", { if (vm.addUpi(upi)) { upi = ""; adding = null } })
                }
                "CARD" -> Column(Modifier.padding(20.dp)) {
                    CsTextField(card, { card = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(23) }, "Card number", leading = Icons.Default.CreditCard, keyboardType = KeyboardType.Number)
                    Spacer(Modifier.height(8.dp))
                    CsTextField(name, { name = it }, "Name on card", capitalizeWords = true)
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Save card", { if (vm.addCard(card, name)) { card = ""; name = ""; adding = null } })
                }
                else -> {
                    MenuRow("Add a UPI ID", { adding = "UPI" }, Icons.Default.AddCircleOutline, titleColor = BrandBlue)
                    MenuRow("Add a debit / credit card", { adding = "CARD" }, Icons.Default.AddCircleOutline, titleColor = BrandBlue)
                }
            }
            vm.error?.let { Text(it, color = CsTheme.colors.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
    }
}

// =============================================================================== Payouts

@Composable
fun PayoutsScreen(nav: NavHostController) {
    val vm: AccountViewModel = appViewModel { AccountViewModel(it) }
    val user by vm.user.collectAsStateWithLifecycle()
    var upi by remember(user?.id) { mutableStateOf(user?.payoutUpi.orEmpty()) }
    var bank by remember(user?.id) { mutableStateOf(user?.payoutBank.orEmpty()) }
    var ifsc by remember(user?.id) { mutableStateOf(user?.payoutIfsc.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { BackTopBar("Payout methods", { nav.popBackStack() }) },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
                PrimaryButton("Save payout details", {
                    vm.error = null
                    when {
                        upi.isNotBlank() && !Regex("^[\\w.\\-]{2,}@[a-zA-Z]{2,}$").matches(upi.trim()) -> vm.error = "Enter a valid UPI ID"
                        bank.isNotBlank() && bank.filter { it.isDigit() }.length !in 9..18 -> vm.error = "Enter a valid account number"
                        bank.isNotBlank() && !Regex("^[A-Z]{4}0[A-Z0-9]{6}$").matches(ifsc.trim().uppercase()) -> vm.error = "Enter a valid IFSC code (e.g. HDFC0001234)"
                        else -> {
                            vm.update { it.copy(payoutUpi = upi.trim(), payoutBank = bank.filter { c -> c.isDigit() }, payoutIfsc = ifsc.trim().uppercase()) }
                            saved = true
                        }
                    }
                })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Where should we send the money passengers pay online for your rides?", style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.subtle)
            Spacer(Modifier.height(16.dp))
            Text("UPI (fastest)", style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
            Spacer(Modifier.height(8.dp))
            CsTextField(upi, { upi = it; saved = false }, "UPI ID", leading = Icons.Default.QrCode2, keyboardType = KeyboardType.Email)
            Spacer(Modifier.height(20.dp))
            Text("Bank transfer", style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
            Spacer(Modifier.height(8.dp))
            CsTextField(bank, { bank = it; saved = false }, "Account number", leading = Icons.Default.AccountBalance, keyboardType = KeyboardType.Number)
            Spacer(Modifier.height(8.dp))
            CsTextField(ifsc, { ifsc = it.uppercase().take(11); saved = false }, "IFSC code", leading = Icons.Default.Pin)
            Spacer(Modifier.height(12.dp))
            if (saved) InfoBanner("Payout details saved", Icons.Default.CheckCircle, CsTheme.colors.success)
            vm.error?.let { Text(it, color = CsTheme.colors.danger) }
            Spacer(Modifier.height(12.dp))
            InfoBanner("Payouts are sent 48 hours after each completed ride for bookings paid online.", Icons.Default.Schedule)
        }
    }
}

// =============================================================================== Transactions

@Composable
fun TransactionsScreen(nav: NavHostController) {
    val vm: AccountViewModel = appViewModel { AccountViewModel(it) }
    val list by vm.transactions.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Payments & refunds", { nav.popBackStack() }) }) { padding ->
        val txs = list ?: return@Scaffold
        if (txs.isEmpty()) {
            Box(Modifier.padding(padding)) { EmptyState(Icons.Default.ReceiptLong, "No transactions yet", "Your payments, refunds and payouts will be listed here.") }
            return@Scaffold
        }
        val paid = txs.filter { it.type == "PAYMENT" }.sumOf { it.amount } - txs.filter { it.type == "REFUND" }.sumOf { it.amount }
        val earned = txs.filter { it.type == "PAYOUT" }.sumOf { it.amount }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard("Spent on rides", Fmt.rupees(paid), Modifier.weight(1f))
                    SummaryCard("Earned as driver", Fmt.rupees(earned), Modifier.weight(1f))
                }
            }
            items(txs, key = { it.id }) { t ->
                val (icon, sign, color) = when (t.type) {
                    "REFUND" -> Triple(Icons.Default.Undo, "+", CsTheme.colors.success)
                    "PAYOUT" -> Triple(Icons.Default.AccountBalanceWallet, "+", CsTheme.colors.success)
                    else -> Triple(Icons.Default.Payment, "−", CsTheme.colors.heading)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = CsTheme.colors.subtle)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.description, style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                        Text("${Fmt.dateYear(t.createdAt)}${if (t.method.isNotBlank()) " · ${t.method}" else ""}", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
                    }
                    Text("$sign${Fmt.rupees(t.amount)}", style = MaterialTheme.typography.titleMedium, color = color)
                }
                ThinDivider()
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.heading)
            Text(value, style = MaterialTheme.typography.titleLarge, color = CsTheme.colors.heading)
        }
    }
}

// =============================================================================== Notification settings

@Composable
fun NotificationSettingsScreen(nav: NavHostController) {
    val vm: AccountViewModel = appViewModel { AccountViewModel(it) }
    val user by vm.user.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Notifications, emails & SMS", { nav.popBackStack() }) }) { padding ->
        val u = user ?: return@Scaffold
        Column(Modifier.padding(padding).padding(horizontal = 20.dp)) {
            SwitchRow("Push notifications", u.notifPush, { v -> vm.update { it.copy(notifPush = v) } }, "Booking requests, confirmations and messages", Icons.Default.NotificationsActive)
            ThinDivider()
            SwitchRow("Emails", u.notifEmail, { v -> vm.update { it.copy(notifEmail = v) } }, "Ride reminders and receipts", Icons.Default.Email)
            ThinDivider()
            SwitchRow("SMS", u.notifSms, { v -> vm.update { it.copy(notifSms = v) } }, "Important updates about your bookings", Icons.Default.Sms)
            ThinDivider()
            SwitchRow("Newsletter & offers", u.newsletter, { v -> vm.update { it.copy(newsletter = v) } }, "Travel tips and promotions", Icons.Default.LocalOffer)
        }
    }
}

// =============================================================================== Ride alerts

@Composable
fun AlertsScreen(nav: NavHostController) {
    val vm: AccountViewModel = appViewModel { AccountViewModel(it) }
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf<City?>(null) }
    var to by remember { mutableStateOf<City?>(null) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = { BackTopBar("Ride alerts", { nav.popBackStack() }) },
        floatingActionButton = {
            if (!creating) ExtendedFloatingActionButton(
                onClick = { creating = true }, containerColor = BrandBlue, contentColor = androidx.compose.ui.graphics.Color.White,
                icon = { Icon(Icons.Default.AddAlert, null) }, text = { Text("New alert") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                AnimatedVisibility(creating) {
                    Card(Modifier.padding(16.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = CsTheme.colors.surfaceAlt)) {
                        Column(Modifier.padding(8.dp)) {
                            MenuRow(from?.name ?: "Leaving from", { picker = "from" }, Icons.Default.TripOrigin)
                            MenuRow(to?.name ?: "Going to", { picker = "to" }, Icons.Default.Place)
                            MenuRow(date?.let { Fmt.dateLong(it) } ?: "Any date", { picker = "date" }, Icons.Default.Event)
                            Row(Modifier.padding(12.dp)) {
                                SecondaryButton("Cancel", { creating = false }, Modifier.weight(1f))
                                Spacer(Modifier.width(12.dp))
                                PrimaryButton("Create", {
                                    val f = from
                                    val t = to
                                    if (f != null && t != null && f != t) {
                                        vm.createAlert(f, t, date)
                                        creating = false; from = null; to = null; date = null
                                    }
                                }, Modifier.weight(1f), enabled = from != null && to != null && from != to)
                            }
                        }
                    }
                }
            }
            if (alerts.isEmpty() && !creating) {
                item { EmptyState(Icons.Default.NotificationsActive, "No ride alerts", "Create an alert and we'll notify you when a matching ride is published.") }
            }
            items(alerts, key = { it.id }) { a ->
                MenuRow(
                    "${a.fromCity} → ${a.toCity}",
                    {
                        val d = if (a.dateEpochDay >= 0) LocalDate.ofEpochDay(a.dateEpochDay).let { if (it.isBefore(LocalDate.now())) LocalDate.now() else it } else LocalDate.now()
                        nav.navigate(Routes.results(a.fromCity, a.toCity, d.toEpochDay(), a.seats))
                    },
                    Icons.Default.NotificationsActive,
                    if (a.dateEpochDay >= 0) Fmt.dateLong(LocalDate.ofEpochDay(a.dateEpochDay)) else "Any date",
                    trailing = { IconButton(onClick = { vm.deleteAlert(a.id) }) { Icon(Icons.Default.Delete, "Delete", tint = CsTheme.colors.danger) } },
                )
                ThinDivider()
            }
        }
    }
    when (picker) {
        "from" -> CityPickerDialog("Leaving from", { picker = null }, { from = it; picker = null })
        "to" -> CityPickerDialog("Going to", { picker = null }, { to = it; picker = null })
        "date" -> DatePickerSheet(date ?: LocalDate.now(), { picker = null }, { date = it }, maxDate = LocalDate.now().plusMonths(3))
    }
}

// =============================================================================== Help

private val faqs = listOf(
    "How does carsathi work?" to "Drivers publish the empty seats in their car for a trip they're already making. Passengers search, book a seat and share the cost of the journey. Everyone saves money and travels together.",
    "How do I book a ride?" to "Search for your departure, destination and date, pick a ride and tap Book (Instant Booking) or Request to book. You can pay by UPI, card or cash to the driver.",
    "How do I publish a ride?" to "Tap Publish, enter your departure and destination, pickup points, stopovers, date, time, seats and price. Your ride is online straight away.",
    "How is the price calculated?" to "We recommend a price per seat based on distance, to cover fuel and tolls. Drivers can adjust within limits — carsathi is about sharing costs, not making a profit.",
    "What is Instant Booking?" to "With Instant Booking, your seat is confirmed immediately. Otherwise the driver reviews each request and accepts or declines it.",
    "How do I cancel?" to "Open Your rides, choose the ride and tap Cancel booking (passengers) or Cancel ride (drivers). Online payments are refunded to your original payment method.",
    "How do ratings work?" to "After each ride, passengers and drivers can rate each other from 1 to 5 stars and leave a comment. Ratings are public and help build trust.",
    "Why should I verify my ID?" to "Verified members get a badge on their profile, which builds trust and leads to more bookings. Your document is never shown to other members.",
    "What are women-only rides?" to "Women drivers can publish rides that are only visible to and bookable by women members.",
    "How do payouts work?" to "Bookings paid online are transferred to your UPI ID or bank account after the ride is completed. Cash bookings are paid directly to you in the car.",
    "Is my data safe?" to "Your phone number is shared only with members you travel with. Never share OTPs or bank details in chat, and keep payments on carsathi.",
)

@Composable
fun HelpScreen(nav: NavHostController) {
    var open by remember { mutableStateOf<Int?>(null) }
    Scaffold(topBar = { BackTopBar("Help centre", { nav.popBackStack() }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                InfoBanner("In an emergency during a ride, call 112 (India emergency number).", Icons.Default.LocalHospital, CsTheme.colors.danger, Modifier.padding(16.dp))
            }
            items(faqs.size) { i ->
                val (q, a) = faqs[i]
                Column(Modifier.fillMaxWidth().clickable { open = if (open == i) null else i }.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(q, style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading, modifier = Modifier.weight(1f))
                        Icon(if (open == i) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = CsTheme.colors.subtle)
                    }
                    AnimatedVisibility(open == i) {
                        Text(a, style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                ThinDivider()
            }
            item {
                Column(Modifier.padding(20.dp)) {
                    Text("Still need help?", style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
                    Text("Write to support@carsathi.in — we usually reply within 24 hours.", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle)
                }
            }
        }
    }
}

// =============================================================================== Legal

@Composable
fun LegalScreen(nav: NavHostController, doc: String) {
    val (title, body) = when (doc) {
        "privacy" -> "Data protection" to """
            carsathi respects your privacy.

            What we collect: your name, email, phone number, date of birth, profile photo, travel preferences, vehicles, rides, bookings, messages and ratings.

            Why: to create your account, match drivers and passengers, process payments, keep the community safe and improve the service.

            Who can see it: other members see your public profile (first name, photo, age, bio, preferences, ratings, verifications). Your phone number is shared only with members you travel with. ID documents are never shown.

            Your rights: you can access, correct or delete your data at any time from your profile, including closing your account.

            In this version of the app, all data is stored locally on your device.
        """.trimIndent()
        "community" -> "Community guidelines" to """
            1. Be reliable — show up on time and cancel early if plans change.
            2. Be respectful — treat every member with courtesy, regardless of gender, religion, caste or background.
            3. Be safe — drive carefully, respect speed limits and wear seat belts.
            4. Share costs, not profits — prices should cover fuel and tolls only.
            5. Keep it on carsathi — book, pay and chat in the app.
            6. Rate honestly — ratings help everyone travel with confidence.
        """.trimIndent()
        else -> "Terms & Conditions" to """
            1. carsathi is a platform connecting drivers with empty seats and passengers travelling the same way. carsathi is not a transport provider.

            2. Members must be at least 18 years old and provide accurate information.

            3. Drivers must hold a valid driving licence, insurance and registration for the vehicle used, and may only share costs — not make a profit.

            4. Passengers pay the seat price plus a service fee. Online payments are refunded if the driver cancels or declines a request.

            5. Members agree to respect the Community guidelines. carsathi may suspend accounts that breach them.

            6. Ratings must be honest and relate to a shared ride.

            7. These terms are governed by the laws of India.
        """.trimIndent()
    }
    Scaffold(topBar = { BackTopBar(title, { nav.popBackStack() }) }) { padding ->
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = CsTheme.colors.heading,
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
        )
    }
}

// =============================================================================== About

@Composable
fun AboutScreen(nav: NavHostController) {
    Scaffold(topBar = { BackTopBar("About", { nav.popBackStack() }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            LogoMark(88.dp)
            Spacer(Modifier.height(16.dp))
            Wordmark(36)
            Text("Version ${BuildConfig.VERSION_NAME}", color = CsTheme.colors.subtle)
            Spacer(Modifier.height(24.dp))
            Text(
                "carsathi — your travel companion. Share rides between cities, split the cost of fuel and tolls, and meet great people along the way.",
                style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "${CityCatalog.cities.size} cities · ${CarCatalog.brandNames.size} car brands · ${CarCatalog.totalModels} car models",
                style = MaterialTheme.typography.titleSmall, color = BrandBlue,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Built with Kotlin, Jetpack Compose, Material 3, Room and OpenStreetMap. Map data © OpenStreetMap contributors.",
                style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle, textAlign = TextAlign.Center,
            )
        }
    }
}

// =============================================================================== Close account

@Composable
fun DeleteAccountScreen(nav: NavHostController) {
    val vm: AccountViewModel = appViewModel { AccountViewModel(it) }
    var confirm by remember { mutableStateOf(false) }
    var understood by remember { mutableStateOf(false) }
    StepScreen(
        title = "We're sorry to see you go",
        onBack = { nav.popBackStack() },
        bottom = { PrimaryButton("Close my account", { confirm = true }, enabled = understood, color = CsTheme.colors.danger) },
    ) {
        Text(
            "Closing your account permanently deletes your profile, vehicles, published rides, bookings, messages and ratings. This cannot be undone.",
            style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.clickable { understood = !understood }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(understood, { understood = it }, colors = CheckboxDefaults.colors(checkedColor = CsTheme.colors.danger))
            Text("I understand that this is permanent", color = CsTheme.colors.heading)
        }
    }
    if (confirm) {
        ConfirmDialog(
            "Close your account?", "All your data will be deleted from this device.", "Close account",
            onConfirm = { vm.deleteAccount { nav.resetTo(Routes.WELCOME) } },
            onDismiss = { confirm = false },
            destructive = true,
        )
    }
}
