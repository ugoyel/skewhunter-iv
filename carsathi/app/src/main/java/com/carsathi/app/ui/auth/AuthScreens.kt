package com.carsathi.app.ui.auth

import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.carsathi.app.AppContainer
import com.carsathi.app.data.repo.Passwords
import com.carsathi.app.data.repo.SignUpData
import com.carsathi.app.data.seed.DemoSeeder
import com.carsathi.app.ui.appViewModel
import com.carsathi.app.ui.components.*
import com.carsathi.app.ui.navigation.Routes
import com.carsathi.app.ui.navigation.resetTo
import com.carsathi.app.ui.theme.BrandBlue
import com.carsathi.app.ui.theme.BrandBlueDark
import com.carsathi.app.ui.theme.CsTheme
import com.carsathi.app.ui.theme.Petrol
import com.carsathi.app.util.Fmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.random.Random

fun isValidEmail(s: String) = Patterns.EMAIL_ADDRESS.matcher(s.trim()).matches()

fun isValidIndianPhone(s: String): Boolean {
    val digits = s.filter { it.isDigit() }.removePrefix("91")
    return digits.length == 10 && digits.first() in "6789"
}

fun formatPhone(s: String): String {
    val d = s.filter { it.isDigit() }.removePrefix("91").takeLast(10)
    return "+91 ${d.take(5)} ${d.drop(5)}"
}

// =============================================================================== Welcome

@Composable
fun WelcomeScreen(nav: NavHostController) {
    val vm: LoginViewModel = appViewModel { LoginViewModel(it) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Brush.verticalGradient(listOf(BrandBlue, BrandBlueDark)))
                .statusBarsPadding()
                .padding(28.dp),
        ) {
            BrandLockup(markSize = 48.dp, fontSize = 30, onDark = true)
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(
                    "Your pick of rides at low prices",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Share the journey and the cost. Find a seat in a car going your way, or fill your empty seats.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f),
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WelcomePill(Icons.Default.Savings, "Save on travel")
                    WelcomePill(Icons.Default.VerifiedUser, "Verified members")
                    WelcomePill(Icons.Default.Eco, "Greener trips")
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp)) {
            PrimaryButton("Sign up with email", { nav.navigate(Routes.SIGNUP) }, icon = Icons.Default.Email)
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Log in", { nav.navigate(Routes.LOGIN) })
            Spacer(Modifier.height(8.dp))
            LinkText(
                "Explore with the demo account →",
                onClick = {
                    scope.launch {
                        if (vm.loginDemo()) nav.resetTo(Routes.SEARCH)
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            vm.error?.let { Text(it, color = CsTheme.colors.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally)) }
        }
    }
}

@Composable
private fun WelcomePill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

// =============================================================================== Login

class LoginViewModel(private val c: AppContainer) : ViewModel() {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    suspend fun login(): Boolean {
        error = null
        if (!isValidEmail(email)) { error = "Please enter a valid email address"; return false }
        if (password.isBlank()) { error = "Please enter your password"; return false }
        loading = true
        c.seeded.first { it }
        val r = c.auth.login(email, password)
        loading = false
        r.exceptionOrNull()?.let { error = it.message }
        return r.isSuccess
    }

    suspend fun loginDemo(): Boolean {
        email = DemoSeeder.DEMO_EMAIL
        password = DemoSeeder.DEMO_PASSWORD
        return login()
    }
}

@Composable
fun LoginScreen(nav: NavHostController) {
    val vm: LoginViewModel = appViewModel { LoginViewModel(it) }
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = { scope.launch { if (vm.login()) nav.resetTo(Routes.SEARCH) } }
    StepScreen(
        title = "What's your email and password?",
        onBack = { nav.popBackStack() },
        bottom = { PrimaryButton("Log in", submit, loading = vm.loading) },
    ) {
        CsTextField(vm.email, { vm.email = it; vm.error = null }, "Email", leading = Icons.Default.Email, keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        CsTextField(
            vm.password, { vm.password = it; vm.error = null }, "Password", leading = Icons.Default.Lock,
            isPassword = true, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, onIme = submit,
        )
        vm.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = CsTheme.colors.danger, style = MaterialTheme.typography.bodyMedium)
        }
        LinkText("Forgot password?", { nav.navigate(Routes.FORGOT) })
        Spacer(Modifier.height(24.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Just looking around?", style = MaterialTheme.typography.titleSmall, color = Petrol)
                Text(
                    "Use the demo account: ${DemoSeeder.DEMO_EMAIL} / ${DemoSeeder.DEMO_PASSWORD}",
                    style = MaterialTheme.typography.bodyMedium, color = Petrol,
                )
                LinkText("Fill in demo account", { vm.email = DemoSeeder.DEMO_EMAIL; vm.password = DemoSeeder.DEMO_PASSWORD })
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Not a member yet?", color = CsTheme.colors.subtle)
            LinkText("Sign up", { nav.navigate(Routes.SIGNUP) { popUpTo(Routes.WELCOME) } })
        }
    }
}

// =============================================================================== Sign up

class SignUpViewModel(private val c: AppContainer) : ViewModel() {
    var step by mutableIntStateOf(0)
    var email by mutableStateOf("")
    var firstName by mutableStateOf("")
    var lastName by mutableStateOf("")
    var birthDate by mutableStateOf<LocalDate?>(null)
    var gender by mutableStateOf("")
    var password by mutableStateOf("")
    var phone by mutableStateOf("")
    var otpSent by mutableStateOf<String?>(null)
    var otp by mutableStateOf("")
    var phoneVerified by mutableStateOf(false)
    var acceptTerms by mutableStateOf(false)
    var newsletter by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)

    val totalSteps = 7

    fun next(onDone: () -> Unit) {
        error = null
        viewModelScope.launch {
            when (step) {
                0 -> {
                    if (!isValidEmail(email)) { error = "Please enter a valid email address"; return@launch }
                    if (c.auth.emailExists(email)) { error = "This email is already registered. Try logging in."; return@launch }
                }
                1 -> if (firstName.isBlank() || lastName.isBlank()) { error = "Please enter your first and last name"; return@launch }
                2 -> {
                    val dob = birthDate ?: run { error = "Please choose your date of birth"; return@launch }
                    if (dob.isAfter(LocalDate.now().minusYears(18))) { error = "You must be at least 18 to use carsathi"; return@launch }
                }
                3 -> if (gender.isEmpty()) { error = "Please choose an option"; return@launch }
                4 -> Passwords.validate(password)?.let { error = it; return@launch }
                5 -> if (phone.isNotBlank() && !phoneVerified) { error = "Verify your number or clear it to skip"; return@launch }
                6 -> {
                    if (!acceptTerms) { error = "Please accept the Terms and Privacy Policy"; return@launch }
                    loading = true
                    val r = c.auth.signUp(
                        SignUpData(
                            email = email, firstName = firstName, lastName = lastName,
                            birthDate = birthDate!!.toEpochDay(), gender = gender, password = password,
                            phone = if (phoneVerified) formatPhone(phone) else "", phoneVerified = phoneVerified, newsletter = newsletter,
                        ),
                    )
                    loading = false
                    if (r.isSuccess) onDone() else error = r.exceptionOrNull()?.message
                    return@launch
                }
            }
            step++
        }
    }

    fun sendOtp() {
        error = null
        if (!isValidIndianPhone(phone)) { error = "Enter a valid 10-digit Indian mobile number"; return }
        otpSent = Random.nextInt(1000, 9999).toString()
        otp = ""
    }

    fun verifyOtp() {
        if (otp == otpSent) { phoneVerified = true; error = null } else error = "That code isn't right. Try again."
    }
}

@Composable
fun SignUpScreen(nav: NavHostController) {
    val vm: SignUpViewModel = appViewModel { SignUpViewModel(it) }
    var showDate by remember { mutableStateOf(false) }
    val back: () -> Unit = { if (vm.step == 0) nav.popBackStack() else { vm.error = null; vm.step-- } }
    BackHandler(enabled = vm.step > 0) { back() }
    val done: () -> Unit = { nav.resetTo(Routes.SEARCH) }
    val progress = (vm.step + 1f) / vm.totalSteps
    val title = when (vm.step) {
        0 -> "What's your email?"
        1 -> "What's your name?"
        2 -> "What's your date of birth?"
        3 -> "How would you like to be addressed?"
        4 -> "Define your password"
        5 -> "Add your mobile number"
        else -> "Almost there!"
    }
    StepScreen(
        title = title,
        onBack = back,
        progress = progress,
        onNext = if (vm.step == 6) null else ({ vm.next(done) }),
        bottom = if (vm.step == 6) ({ PrimaryButton("Create my account", { vm.next(done) }, loading = vm.loading) }) else null,
    ) {
        when (vm.step) {
            0 -> CsTextField(vm.email, { vm.email = it; vm.error = null }, "Email", leading = Icons.Default.Email, keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, onIme = { vm.next(done) })
            1 -> {
                CsTextField(vm.firstName, { vm.firstName = it; vm.error = null }, "First name", capitalizeWords = true)
                Spacer(Modifier.height(12.dp))
                CsTextField(vm.lastName, { vm.lastName = it; vm.error = null }, "Last name", capitalizeWords = true, imeAction = ImeAction.Done, onIme = { vm.next(done) })
            }
            2 -> {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CsTheme.colors.surfaceAlt)
                        .clickable { showDate = true }.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Cake, null, tint = CsTheme.colors.subtle)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        vm.birthDate?.let { Fmt.dateLong(it) + " " + it.year } ?: "DD/MM/YYYY",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (vm.birthDate == null) CsTheme.colors.subtle else CsTheme.colors.heading,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("You need to be 18 or over to use carsathi.", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
                if (showDate) {
                    DatePickerSheet(
                        initial = vm.birthDate ?: LocalDate.now().minusYears(25),
                        onDismiss = { showDate = false },
                        onPick = { vm.birthDate = it; vm.error = null },
                        minDate = LocalDate.now().minusYears(100),
                        maxDate = LocalDate.now().minusYears(18),
                    )
                }
            }
            3 -> {
                ChoiceRow("Mr", vm.gender == "M", { vm.gender = "M"; vm.error = null })
                ThinDivider()
                ChoiceRow("Ms / Mrs", vm.gender == "F", { vm.gender = "F"; vm.error = null }, subtitle = "Lets you offer and book women-only rides")
                ThinDivider()
                ChoiceRow("I'd rather not say", vm.gender == "U", { vm.gender = "U"; vm.error = null })
            }
            4 -> {
                CsTextField(vm.password, { vm.password = it; vm.error = null }, "Password", leading = Icons.Default.Lock, isPassword = true, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, onIme = { vm.next(done) })
                Spacer(Modifier.height(12.dp))
                PasswordRules(vm.password)
            }
            5 -> {
                Text(
                    "Members can call you about your rides. We never share your number publicly. You can skip this for now.",
                    style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle,
                )
                Spacer(Modifier.height(16.dp))
                CsTextField(
                    vm.phone, { vm.phone = it; vm.phoneVerified = false; vm.otpSent = null; vm.error = null }, "Mobile number (+91)",
                    leading = Icons.Default.Phone, keyboardType = KeyboardType.Phone,
                )
                Spacer(Modifier.height(12.dp))
                OtpBlock(
                    phoneVerified = vm.phoneVerified, otpSent = vm.otpSent, otp = vm.otp,
                    onOtp = { vm.otp = it.filter(Char::isDigit).take(4) }, onSend = vm::sendOtp, onVerify = vm::verifyOtp,
                    canSend = vm.phone.isNotBlank(),
                )
            }
            else -> {
                Text("Hi ${vm.firstName}! Please review and accept our terms to create your account.", style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.heading)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.acceptTerms = !vm.acceptTerms }) {
                    Checkbox(vm.acceptTerms, { vm.acceptTerms = it }, colors = CheckboxDefaults.colors(checkedColor = BrandBlue))
                    Text("I accept the Terms & Conditions and the Privacy Policy", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.heading)
                }
                Row(Modifier.padding(start = 12.dp)) {
                    LinkText("Read terms", { nav.navigate(Routes.legal("terms")) })
                    Spacer(Modifier.width(12.dp))
                    LinkText("Read privacy policy", { nav.navigate(Routes.legal("privacy")) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.newsletter = !vm.newsletter }) {
                    Checkbox(vm.newsletter, { vm.newsletter = it }, colors = CheckboxDefaults.colors(checkedColor = BrandBlue))
                    Text("Send me travel tips and offers by email", style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.heading)
                }
            }
        }
        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = CsTheme.colors.danger, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PasswordRules(password: String) {
    @Composable
    fun rule(ok: Boolean, text: String) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                tint = if (ok) CsTheme.colors.success else CsTheme.colors.subtle, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle)
        }
    }
    rule(password.length >= 8, "At least 8 characters")
    rule(password.any { it.isLetter() }, "At least 1 letter")
    rule(password.any { it.isDigit() }, "At least 1 number")
}

@Composable
fun OtpBlock(
    phoneVerified: Boolean,
    otpSent: String?,
    otp: String,
    onOtp: (String) -> Unit,
    onSend: () -> Unit,
    onVerify: () -> Unit,
    canSend: Boolean,
) {
    when {
        phoneVerified -> InfoBanner("Mobile number verified", Icons.Default.CheckCircle, CsTheme.colors.success)
        otpSent != null -> {
            InfoBanner("Demo mode — no SMS is sent. Your code is $otpSent", Icons.Default.Sms)
            Spacer(Modifier.height(12.dp))
            CsTextField(otp, onOtp, "4-digit code", leading = Icons.Default.Pin, keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, onIme = onVerify)
            Spacer(Modifier.height(8.dp))
            Row {
                PrimaryButton("Verify", onVerify, Modifier.weight(1f), enabled = otp.length == 4)
                Spacer(Modifier.width(12.dp))
                SecondaryButton("Resend", onSend, Modifier.weight(1f))
            }
        }
        else -> SecondaryButton("Send verification code", { if (canSend) onSend() }, icon = Icons.Default.Sms)
    }
}

// =============================================================================== Forgot password

class ForgotViewModel(private val c: AppContainer) : ViewModel() {
    var step by mutableIntStateOf(0)
    var email by mutableStateOf("")
    var code by mutableStateOf("")
    var sentCode by mutableStateOf("")
    var password by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var done by mutableStateOf(false)

    fun send() {
        error = null
        viewModelScope.launch {
            if (!isValidEmail(email)) { error = "Please enter a valid email address"; return@launch }
            if (!c.auth.emailExists(email)) { error = "No account found with this email"; return@launch }
            sentCode = Random.nextInt(100000, 999999).toString()
            step = 1
        }
    }

    fun reset() {
        error = null
        if (code != sentCode) { error = "The code is incorrect"; return }
        Passwords.validate(password)?.let { error = it; return }
        viewModelScope.launch {
            val r = c.auth.resetPassword(email, password)
            if (r.isSuccess) done = true else error = r.exceptionOrNull()?.message
        }
    }
}

@Composable
fun ForgotPasswordScreen(nav: NavHostController) {
    val vm: ForgotViewModel = appViewModel { ForgotViewModel(it) }
    if (vm.done) {
        StepScreen(title = "Password updated", onBack = { nav.popBackStack() }, bottom = { PrimaryButton("Back to log in", { nav.popBackStack() }) }) {
            InfoBanner("You can now log in with your new password.", Icons.Default.CheckCircle, CsTheme.colors.success)
        }
        return
    }
    StepScreen(
        title = if (vm.step == 0) "Forgot your password?" else "Reset your password",
        onBack = { if (vm.step == 1) vm.step = 0 else nav.popBackStack() },
        bottom = {
            if (vm.step == 0) PrimaryButton("Send reset code", vm::send) else PrimaryButton("Reset password", vm::reset)
        },
    ) {
        if (vm.step == 0) {
            Text("Enter the email linked to your account and we'll send you a code to reset your password.", color = CsTheme.colors.subtle)
            Spacer(Modifier.height(16.dp))
            CsTextField(vm.email, { vm.email = it }, "Email", leading = Icons.Default.Email, keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, onIme = vm::send)
        } else {
            InfoBanner("Demo mode — no email is sent. Your reset code is ${vm.sentCode}", Icons.Default.MarkEmailRead)
            Spacer(Modifier.height(16.dp))
            CsTextField(vm.code, { vm.code = it.filter(Char::isDigit).take(6) }, "6-digit code", leading = Icons.Default.Pin, keyboardType = KeyboardType.Number)
            Spacer(Modifier.height(12.dp))
            CsTextField(vm.password, { vm.password = it }, "New password", leading = Icons.Default.Lock, isPassword = true, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
            Spacer(Modifier.height(8.dp))
            PasswordRules(vm.password)
        }
        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = CsTheme.colors.danger)
        }
    }
}
