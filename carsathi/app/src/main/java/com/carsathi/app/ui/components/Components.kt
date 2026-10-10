package com.carsathi.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.carsathi.app.R
import com.carsathi.app.data.catalog.City
import com.carsathi.app.data.catalog.CityCatalog
import com.carsathi.app.data.db.UserEntity
import com.carsathi.app.data.model.RideCardModel
import com.carsathi.app.data.model.RidePoint
import com.carsathi.app.data.model.initials
import com.carsathi.app.ui.theme.BrandBlue
import com.carsathi.app.ui.theme.CsTheme
import com.carsathi.app.ui.theme.Petrol
import com.carsathi.app.ui.theme.StarYellow
import com.carsathi.app.util.Fmt
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ------------------------------------------------------------------------------------------ brand

@Composable
fun LogoMark(size: Dp = 44.dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(BrandBlue),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "carsathi logo",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun Wordmark(fontSize: Int = 28, onDark: Boolean = false) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (onDark) Color.White else CsTheme.colors.heading)) { append("car") }
            withStyle(SpanStyle(color = if (onDark) Color.White else BrandBlue)) { append("sathi") }
        },
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp,
    )
}

@Composable
fun BrandLockup(markSize: Dp = 44.dp, fontSize: Int = 28, onDark: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LogoMark(markSize)
        Spacer(Modifier.width(10.dp))
        Wordmark(fontSize, onDark)
    }
}

// ------------------------------------------------------------------------------------------ buttons

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        } else {
            if (icon != null) {
                Icon(icon, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, danger: Boolean = false) {
    val c = if (danger) CsTheme.colors.danger else MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.5.dp, c.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = BrandBlue) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

@Composable
fun NextFab(onClick: () -> Unit, enabled: Boolean = true) {
    val bg = if (enabled) BrandBlue else CsTheme.colors.divider
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next", tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

// ------------------------------------------------------------------------------------------ scaffolds

@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BrandBlue) }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Full-screen "one question per screen" step, like BlaBlaCar's sign-up & publish flows. */
@Composable
fun StepScreen(
    title: String,
    onBack: () -> Unit,
    progress: Float? = null,
    nextEnabled: Boolean = true,
    onNext: (() -> Unit)? = null,
    bottom: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BrandBlue) }
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = BrandBlue,
                        trackColor = CsTheme.colors.divider,
                    )
                }
            }
        },
        bottomBar = {
            if (bottom != null) {
                Box(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) { bottom() }
            } else if (onNext != null) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp), contentAlignment = Alignment.CenterEnd) {
                    NextFab(onClick = onNext, enabled = nextEnabled)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = CsTheme.colors.heading)
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------ inputs

@Composable
fun CsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onIme: (() -> Unit)? = null,
    isPassword: Boolean = false,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    supporting: String? = null,
    focusRequester: FocusRequester? = null,
    capitalizeWords: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        singleLine = singleLine,
        minLines = minLines,
        leadingIcon = if (leading != null) {
            { Icon(leading, null, tint = CsTheme.colors.subtle) }
        } else null,
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "Hide" else "Show", tint = CsTheme.colors.subtle)
                }
            }
        } else null,
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
            capitalization = if (capitalizeWords) androidx.compose.ui.text.input.KeyboardCapitalization.Words else androidx.compose.ui.text.input.KeyboardCapitalization.None,
        ),
        keyboardActions = KeyboardActions(onAny = { onIme?.invoke() }),
        isError = error != null,
        supportingText = when {
            error != null -> { { Text(error) } }
            supporting != null -> { { Text(supporting) } }
            else -> null
        },
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = CsTheme.colors.surfaceAlt,
            unfocusedContainerColor = CsTheme.colors.surfaceAlt,
            errorContainerColor = CsTheme.colors.surfaceAlt,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedLabelColor = BrandBlue,
            cursorColor = BrandBlue,
        ),
    )
}

@Composable
fun Counter(value: Int, onChange: (Int) -> Unit, min: Int = 1, max: Int = 8, big: Boolean = true) {
    val btn = if (big) 52.dp else 36.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        CircleIconButton(Icons.Default.Remove, enabled = value > min, size = btn) { onChange(value - 1) }
        Text(
            value.toString(),
            style = if (big) MaterialTheme.typography.displaySmall.copy(fontSize = 56.sp) else MaterialTheme.typography.headlineSmall,
            color = CsTheme.colors.heading,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = if (big) 120.dp else 56.dp),
        )
        CircleIconButton(Icons.Default.Add, enabled = value < max, size = btn) { onChange(value + 1) }
    }
}

@Composable
fun CircleIconButton(icon: ImageVector, enabled: Boolean = true, size: Dp = 44.dp, onClick: () -> Unit) {
    val c = if (enabled) BrandBlue else CsTheme.colors.divider
    Box(
        Modifier.size(size).clip(CircleShape).border(2.dp, c, CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = c) }
}

@Composable
fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null, icon: ImageVector? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = CsTheme.colors.subtle)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle)
        }
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = BrandBlue))
    }
}

@Composable
fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, icon: ImageVector? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = CsTheme.colors.subtle)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = BrandBlue, checkedThumbColor = Color.White),
        )
    }
}

// ------------------------------------------------------------------------------------------ rows

@Composable
fun MenuRow(
    title: String,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (titleColor != Color.Unspecified) titleColor else CsTheme.colors.subtle)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (titleColor != Color.Unspecified) titleColor else CsTheme.colors.heading,
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.Default.ChevronRight, null, tint = CsTheme.colors.subtle)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = CsTheme.colors.heading,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
fun ThickDivider() {
    Box(Modifier.fillMaxWidth().height(8.dp).background(CsTheme.colors.surfaceAlt))
}

@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = CsTheme.colors.divider)
}

@Composable
fun Tag(text: String, color: Color = BrandBlue, icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun InfoBanner(text: String, icon: ImageVector = Icons.Default.Info, color: Color = BrandBlue, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.heading)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(44.dp)) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = CsTheme.colors.heading, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = CsTheme.colors.subtle, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            PrimaryButton(action, onAction, Modifier.widthIn(max = 320.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------ people

private val avatarPalette = listOf(
    Color(0xFF00AFF5), Color(0xFF054752), Color(0xFF2ED1A2), Color(0xFFF28C28),
    Color(0xFF6A3D9A), Color(0xFFD7263D), Color(0xFF1E6FD9), Color(0xFF14A37F),
)

@Composable
fun Avatar(user: UserEntity?, size: Dp = 48.dp, showVerified: Boolean = false) {
    Box(Modifier.size(size)) {
        val path = user?.photoPath
        if (path != null && File(path).exists()) {
            AsyncImage(
                model = File(path),
                contentDescription = user?.fullName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            val bg = avatarPalette[((user?.id ?: 0L) % avatarPalette.size).toInt()]
            Box(Modifier.fillMaxSize().clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                Text(
                    user?.initials() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.38f).sp,
                )
            }
        }
        if (showVerified && user?.idVerified == true) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.36f).clip(CircleShape).background(Color.White).padding(1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Verified, "Verified", tint = BrandBlue, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun RatingInline(avg: Double, count: Int, small: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Star, null, tint = StarYellow, modifier = Modifier.size(if (small) 14.dp else 18.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            if (count == 0) "New member" else "${Fmt.rating(avg)}/5 · $count rating${if (count == 1) "" else "s"}",
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = CsTheme.colors.subtle,
        )
    }
}

@Composable
fun Stars(stars: Int, size: Dp = 18.dp) {
    Row {
        repeat(5) { i ->
            Icon(
                if (i < stars) Icons.Default.Star else Icons.Default.StarBorder, null,
                tint = if (i < stars) StarYellow else CsTheme.colors.divider,
                modifier = Modifier.size(size),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------ rides

@Composable
fun RideCard(model: RideCardModel, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                MiniItinerary(
                    depTime = Fmt.time(model.departureAt),
                    arrTime = Fmt.time(model.arrivalAt),
                    duration = Fmt.duration(model.durationMin),
                    fromCity = model.from.city,
                    toCity = model.to.city,
                    fromDetour = model.fromDetourKm,
                    toDetour = model.toDetourKm,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Fmt.rupees(model.price),
                    style = MaterialTheme.typography.titleLarge,
                    color = CsTheme.colors.heading,
                )
            }
            Spacer(Modifier.height(12.dp))
            ThinDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(model.driver, 36.dp, showVerified = true)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.driver.displayName, style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                    RatingInline(model.rating, model.ratingCount, small = true)
                }
                if (model.ride.instantBooking) Icon(Icons.Default.Bolt, "Instant booking", tint = CsTheme.colors.subtle, modifier = Modifier.size(20.dp))
                if (model.ride.maxTwoInBack) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.AirlineSeatReclineNormal, "Max. 2 in the back", tint = CsTheme.colors.subtle, modifier = Modifier.size(20.dp))
                }
                if (model.ride.womenOnly) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Woman, "Women only", tint = Color(0xFFD63384), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (model.seatsLeft == 1) "1 seat left" else "${model.seatsLeft} seats",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (model.seatsLeft == 1) CsTheme.colors.danger else CsTheme.colors.subtle,
                )
            }
        }
    }
}

@Composable
fun MiniItinerary(
    depTime: String,
    arrTime: String,
    duration: String,
    fromCity: String,
    toCity: String,
    modifier: Modifier = Modifier,
    fromDetour: Int = 0,
    toDetour: Int = 0,
) {
    Row(modifier.height(IntrinsicSize.Min)) {
        Column(Modifier.width(52.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(depTime, style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
            Text(duration, style = MaterialTheme.typography.labelSmall, color = CsTheme.colors.subtle)
            Text(arrTime, style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
        }
        Column(
            Modifier.width(18.dp).fillMaxHeight().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TimelineDot()
            Box(Modifier.width(3.dp).weight(1f).background(Petrol))
            TimelineDot()
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            CityWithDetour(fromCity, fromDetour)
            Spacer(Modifier.height(22.dp))
            CityWithDetour(toCity, toDetour)
        }
    }
}

@Composable
private fun CityWithDetour(city: String, detourKm: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(city, style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detourKm >= 3) {
            Spacer(Modifier.width(6.dp))
            val c = when {
                detourKm < 10 -> CsTheme.colors.success
                detourKm < 20 -> CsTheme.colors.warning
                else -> CsTheme.colors.danger
            }
            Icon(Icons.Default.DirectionsWalk, null, tint = c, modifier = Modifier.size(16.dp))
            Text("${detourKm}km", style = MaterialTheme.typography.labelSmall, color = c)
        }
    }
}

@Composable
fun TimelineDot(filled: Boolean = false, color: Color = Petrol) {
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(if (filled) color else MaterialTheme.colorScheme.surface)
            .border(2.5.dp, color, CircleShape),
    )
}

/** Full itinerary used on the ride details page (times, cities, addresses, stopovers). */
@Composable
fun Itinerary(points: List<RidePoint>, departureAt: Long, boardIndex: Int = 0, alightIndex: Int = points.lastIndex, onPointClick: ((RidePoint) -> Unit)? = null) {
    Column {
        points.forEachIndexed { i, p ->
            val inSegment = i in boardIndex..alightIndex
            val color = if (inSegment) Petrol else CsTheme.colors.divider
            val textColor = if (inSegment) CsTheme.colors.heading else CsTheme.colors.subtle
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(if (onPointClick != null) Modifier.clickable { onPointClick(p) } else Modifier)) {
                Text(
                    Fmt.time(p.timeAt(departureAt)),
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                    modifier = Modifier.width(56.dp).padding(top = 2.dp),
                )
                Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(5.dp))
                    TimelineDot(filled = i == boardIndex || i == alightIndex, color = color)
                    if (i < points.lastIndex) {
                        val nextIn = (i + 1) in boardIndex..alightIndex && inSegment
                        Box(Modifier.width(3.dp).weight(1f).background(if (nextIn) Petrol else CsTheme.colors.divider))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = if (i < points.lastIndex) 22.dp else 0.dp)) {
                    Text(p.city, style = MaterialTheme.typography.titleMedium, color = textColor)
                    if (p.address.isNotBlank() && p.address != p.city) {
                        Text(p.address, style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle)
                    }
                }
                if (onPointClick != null) Icon(Icons.Default.ChevronRight, null, tint = CsTheme.colors.subtle)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ pickers

/** Full screen city search, like BlaBlaCar's "Leaving from" / "Going to" search. */
@Composable
fun CityPickerDialog(title: String, onDismiss: () -> Unit, onPick: (City) -> Unit, recent: List<String> = emptyList()) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val results = remember(query) { CityCatalog.search(query) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BrandBlue) }
                    Text(title, style = MaterialTheme.typography.titleLarge, color = CsTheme.colors.heading)
                }
                CsTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Enter a city",
                    leading = Icons.Default.Search,
                    imeAction = ImeAction.Search,
                    focusRequester = focus,
                    capitalizeWords = true,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
                LazyColumn(Modifier.fillMaxSize().imePadding()) {
                    val recentCities = if (query.isBlank()) recent.mapNotNull { CityCatalog.find(it) }.distinct().take(4) else emptyList()
                    if (recentCities.isNotEmpty()) {
                        item { Text("Recent", style = MaterialTheme.typography.labelMedium, color = CsTheme.colors.subtle, modifier = Modifier.padding(start = 20.dp, top = 16.dp)) }
                        items(recentCities) { c -> CityRow(c, Icons.Default.History) { onPick(c) } }
                    }
                    if (query.isBlank()) {
                        item { Text("Popular cities", style = MaterialTheme.typography.labelMedium, color = CsTheme.colors.subtle, modifier = Modifier.padding(start = 20.dp, top = 16.dp)) }
                    }
                    items(results) { c -> CityRow(c, Icons.Default.LocationOn) { onPick(c) } }
                    if (results.isEmpty()) {
                        item {
                            Text(
                                "No city found. carsathi currently covers ${CityCatalog.cities.size} cities across India.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CsTheme.colors.subtle,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CityRow(c: City, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = CsTheme.colors.subtle)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
            Text(c.state, style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
        }
        Icon(Icons.Default.ChevronRight, null, tint = CsTheme.colors.subtle)
    }
}

private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

@Composable
fun DatePickerSheet(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
    minDate: LocalDate? = LocalDate.now(),
    maxDate: LocalDate? = null,
) {
    val minMs = minDate?.utcMillis()
    val maxMs = maxDate?.utcMillis()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.utcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                (minMs == null || utcTimeMillis >= minMs) && (maxMs == null || utcTimeMillis <= maxMs)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, showModeToggle = true)
    }
}

@Composable
fun TimePickerSheet(hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Pick-up time", style = MaterialTheme.typography.titleLarge, color = CsTheme.colors.heading, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(state.hour, state.minute); onDismiss() }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) CsTheme.colors.danger else BrandBlue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
