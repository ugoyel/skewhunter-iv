package com.carsathi.app.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.carsathi.app.AppContainer
import com.carsathi.app.data.db.ConversationEntity
import com.carsathi.app.data.db.MessageEntity
import com.carsathi.app.data.db.NotificationEntity
import com.carsathi.app.data.db.RideEntity
import com.carsathi.app.data.db.UserEntity
import com.carsathi.app.data.model.ConversationItem
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

// =============================================================================== Inbox

class InboxViewModel(c: AppContainer) : ViewModel() {
    val conversations: StateFlow<List<ConversationItem>?> = c.chat.observeConversations(c.session.userId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun InboxScreen(nav: NavHostController) {
    val vm: InboxViewModel = appViewModel { InboxViewModel(it) }
    val convs by vm.conversations.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Inbox", style = MaterialTheme.typography.headlineMedium, color = CsTheme.colors.heading, modifier = Modifier.weight(1f))
                IconButton(onClick = { nav.navigate(Routes.NOTIFICATIONS) }) { Icon(Icons.Default.Notifications, "Notifications", tint = BrandBlue) }
            }
        },
    ) { padding ->
        val list = convs
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BrandBlue) }
            list.isEmpty() -> Box(Modifier.padding(padding)) {
                EmptyState(Icons.Default.ChatBubbleOutline, "No messages yet", "When you book or publish a ride, you can chat with your travel companions here.")
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.conversation.id }) { item ->
                    ConversationRow(item) { nav.navigate(Routes.chat(item.conversation.id)) }
                    ThinDivider(Modifier.padding(start = 84.dp))
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(item: ConversationItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(item.other, 52.dp, showVerified = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.other.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (item.unread > 0) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = CsTheme.colors.heading,
                    modifier = Modifier.weight(1f),
                )
                Text(Fmt.ago(item.conversation.lastAt), style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle)
            }
            item.ride?.let {
                Text("${it.fromCity} → ${it.toCity} · ${Fmt.day(it.departureAt)}", style = MaterialTheme.typography.labelMedium, color = BrandBlue)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.conversation.lastMessage.ifBlank { "Say hello 👋" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.unread > 0) CsTheme.colors.heading else CsTheme.colors.subtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.unread > 0) {
                    Box(Modifier.padding(start = 8.dp).size(22.dp).clip(CircleShape).background(BrandBlue), contentAlignment = Alignment.Center) {
                        Text(item.unread.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// =============================================================================== Chat

class ChatViewModel(private val c: AppContainer, private val conversationId: Long) : ViewModel() {
    val uid = c.session.userId
    var other by mutableStateOf<UserEntity?>(null)
    var ride by mutableStateOf<RideEntity?>(null)
    var conversation by mutableStateOf<ConversationEntity?>(null)
    var input by mutableStateOf("")
    val messages: StateFlow<List<MessageEntity>> = c.chat.observeMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val conv = c.chat.conversation(conversationId) ?: return@launch
            conversation = conv
            other = c.users.get(if (conv.passengerId == uid) conv.driverId else conv.passengerId)
            ride = c.rides.get(conv.rideId)
        }
        viewModelScope.launch {
            messages.collect { c.chat.markRead(conversationId, uid) }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        viewModelScope.launch {
            c.chat.send(conversationId, uid, text)
            c.simulator.onMessageSent(conversationId, uid, text)
            val o = other
            if (o != null && !o.isDemo) {
                val me = c.users.get(uid)
                c.notifications.notify(o.id, "MESSAGE", "New message from ${me?.displayName ?: "a member"}", text, conversationId)
            }
        }
    }
}

@Composable
fun ChatScreen(nav: NavHostController, conversationId: Long) {
    val vm: ChatViewModel = appViewModel { ChatViewModel(it, conversationId) }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
    val quick = listOf("Hi! 👋", "I'm on my way", "Running 5 minutes late", "Where exactly is the pickup?", "Thank you!")

    Scaffold(
        topBar = {
            Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.background) {
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BrandBlue) }
                    val o = vm.other
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = o != null) { o?.let { nav.navigate(Routes.user(it.id)) } }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(o, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(o?.displayName ?: "", style = MaterialTheme.typography.titleMedium, color = CsTheme.colors.heading)
                            vm.ride?.let { Text("${it.fromCity} → ${it.toCity} · ${Fmt.dayTime(it.departureAt)}", style = MaterialTheme.typography.bodySmall, color = CsTheme.colors.subtle, maxLines = 1) }
                        }
                    }
                    vm.ride?.let { r ->
                        IconButton(onClick = { if (r.driverId == vm.uid) nav.navigate(Routes.manage(r.id)) else nav.navigate(Routes.ride(r.id)) }) {
                            Icon(Icons.Default.DirectionsCar, "Ride", tint = BrandBlue)
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding()) {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quick) { q -> SuggestionChip(onClick = { vm.input = q }, label = { Text(q) }) }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = vm.input,
                        onValueChange = { vm.input = it },
                        placeholder = { Text("Write a message…") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = CsTheme.colors.surfaceAlt,
                            unfocusedContainerColor = CsTheme.colors.surfaceAlt,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(if (vm.input.isBlank()) CsTheme.colors.divider else BrandBlue).clickable(enabled = vm.input.isNotBlank()) { vm.send() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Send, "Send", tint = Color.White) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                InfoBanner(
                    "For your safety, keep conversations and payments on carsathi. Never share bank details or OTPs.",
                    Icons.Default.Shield,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(messages, key = { it.id }) { m -> Bubble(m, mine = m.senderId == vm.uid) }
        }
    }
}

@Composable
private fun Bubble(m: MessageEntity, mine: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                .background(if (mine) BrandBlue else CsTheme.colors.surfaceAlt)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(m.text, color = if (mine) Color.White else CsTheme.colors.heading, style = MaterialTheme.typography.bodyLarge)
        }
        Text(Fmt.chatTime(m.sentAt), style = MaterialTheme.typography.labelSmall, color = CsTheme.colors.subtle, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// =============================================================================== Notifications

class NotificationsViewModel(private val c: AppContainer) : ViewModel() {
    val uid = c.session.userId
    val items: StateFlow<List<NotificationEntity>?> = c.notifications.observe(uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun markAll() = viewModelScope.launch { c.notifications.markAllRead(uid) }

    fun open(n: NotificationEntity, go: (String) -> Unit) {
        viewModelScope.launch {
            c.notifications.markRead(n.id)
            val route = when (n.type) {
                "MESSAGE" -> Routes.chat(n.refId)
                "RATING" -> Routes.ratings(uid)
                "ALERT" -> Routes.ALERTS
                "VERIFICATION" -> Routes.VERIFY
                "WELCOME" -> Routes.PROFILE
                "PAYMENT" -> Routes.TRANSACTIONS
                "REPORT" -> null
                else -> c.rides.get(n.refId)?.let { r -> if (r.driverId == uid) Routes.manage(r.id) else Routes.ride(r.id) }
            }
            route?.let(go)
        }
    }
}

@Composable
fun NotificationsScreen(nav: NavHostController) {
    val vm: NotificationsViewModel = appViewModel { NotificationsViewModel(it) }
    val notifs by vm.items.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            BackTopBar("Notifications", { nav.popBackStack() }) {
                TextButton(onClick = { vm.markAll() }) { Text("Mark all read", color = BrandBlue) }
            }
        },
    ) { padding ->
        val list = notifs
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.padding(padding)) { EmptyState(Icons.Default.NotificationsNone, "You're all caught up", "Booking updates, messages and ride alerts will show up here.") }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.id }) { n ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (n.read) Color.Transparent else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                            .clickable { vm.open(n) { nav.navigate(it) } }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        val (icon, tint) = notificationIcon(n.type)
                        Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n.title, style = MaterialTheme.typography.titleSmall, color = CsTheme.colors.heading)
                            Text(n.body, style = MaterialTheme.typography.bodyMedium, color = CsTheme.colors.subtle)
                            Text(Fmt.ago(n.createdAt), style = MaterialTheme.typography.labelSmall, color = CsTheme.colors.subtle, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    ThinDivider()
                }
            }
        }
    }
}

@Composable
private fun notificationIcon(type: String): Pair<ImageVector, Color> = when (type) {
    "BOOKING_REQUEST" -> Icons.Default.PersonAdd to CsTheme.colors.warning
    "BOOKING_CONFIRMED" -> Icons.Default.CheckCircle to CsTheme.colors.success
    "BOOKING_DECLINED", "BOOKING_CANCELLED", "RIDE_CANCELLED" -> Icons.Default.Cancel to CsTheme.colors.danger
    "RIDE_UPDATED" -> Icons.Default.Update to BrandBlue
    "MESSAGE" -> Icons.Default.ChatBubble to BrandBlue
    "RATING" -> Icons.Default.Star to CsTheme.colors.warning
    "ALERT" -> Icons.Default.NotificationsActive to BrandBlue
    "VERIFICATION" -> Icons.Default.Verified to BrandBlue
    "PAYMENT" -> Icons.Default.Payments to CsTheme.colors.success
    "REPORT" -> Icons.Default.Flag to CsTheme.colors.subtle
    else -> Icons.Default.Celebration to BrandBlue
}
