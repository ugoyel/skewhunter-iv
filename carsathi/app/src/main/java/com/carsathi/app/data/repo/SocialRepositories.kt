package com.carsathi.app.data.repo

import com.carsathi.app.data.SessionStore
import com.carsathi.app.data.db.AlertEntity
import com.carsathi.app.data.db.AppDatabase
import com.carsathi.app.data.db.ConversationEntity
import com.carsathi.app.data.db.MessageEntity
import com.carsathi.app.data.db.NotificationEntity
import com.carsathi.app.data.db.PaymentMethodEntity
import com.carsathi.app.data.db.RatingEntity
import com.carsathi.app.data.db.TransactionEntity
import com.carsathi.app.data.model.ConversationItem
import com.carsathi.app.data.model.RatingItem
import com.carsathi.app.notifications.SystemNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

class NotificationRepository(
    private val db: AppDatabase,
    private val session: SessionStore,
    private val notifier: SystemNotifier,
) {
    fun observe(userId: Long): Flow<List<NotificationEntity>> = db.notifications().observe(userId)
    fun observeUnread(userId: Long): Flow<Int> = db.notifications().observeUnread(userId)
    suspend fun markAllRead(userId: Long) = db.notifications().markAllRead(userId)
    suspend fun markRead(id: Long) = db.notifications().markRead(id)

    /** Stores an in-app notification and mirrors it as a system notification for the signed-in member. */
    suspend fun notify(userId: Long, type: String, title: String, body: String, refId: Long = 0, push: Boolean = true) {
        val id = db.notifications().insert(
            NotificationEntity(userId = userId, type = type, title = title, body = body, refId = refId),
        )
        if (push && userId == session.userId) {
            val user = db.users().get(userId)
            if (user?.notifPush != false) notifier.show(id.toInt(), title, body)
        }
    }
}

class ChatRepository(private val db: AppDatabase) {

    suspend fun openConversation(rideId: Long, passengerId: Long, driverId: Long): Long {
        db.chat().find(rideId, passengerId, driverId)?.let { return it.id }
        return db.chat().insertConversation(
            ConversationEntity(rideId = rideId, passengerId = passengerId, driverId = driverId),
        )
    }

    suspend fun conversation(id: Long): ConversationEntity? = db.chat().conversation(id)

    fun observeConversation(id: Long): Flow<ConversationEntity?> = db.chat().observeConversation(id)

    suspend fun send(conversationId: Long, senderId: Long, text: String, at: Long = System.currentTimeMillis()) {
        val c = db.chat().conversation(conversationId) ?: return
        db.chat().insertMessage(MessageEntity(conversationId = conversationId, senderId = senderId, text = text, sentAt = at))
        val fromPassenger = senderId == c.passengerId
        db.chat().updateConversation(
            c.copy(
                lastMessage = text,
                lastAt = at,
                unreadDriver = if (fromPassenger) c.unreadDriver + 1 else c.unreadDriver,
                unreadPassenger = if (!fromPassenger) c.unreadPassenger + 1 else c.unreadPassenger,
            ),
        )
    }

    suspend fun markRead(conversationId: Long, userId: Long) {
        val c = db.chat().conversation(conversationId) ?: return
        val updated = if (userId == c.passengerId) c.copy(unreadPassenger = 0) else c.copy(unreadDriver = 0)
        if (updated != c) db.chat().updateConversation(updated)
    }

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = db.chat().observeMessages(conversationId)

    fun observeUnread(userId: Long): Flow<Int> = db.chat().observeUnread(userId)

    fun observeConversations(userId: Long): Flow<List<ConversationItem>> =
        db.chat().observeForUser(userId).mapLatest { list ->
            val otherIds = list.map { if (it.passengerId == userId) it.driverId else it.passengerId }.distinct()
            val users = db.users().getMany(otherIds).associateBy { it.id }
            val rides = db.rides().getMany(list.map { it.rideId }.distinct()).associateBy { it.id }
            list.mapNotNull { c ->
                val otherId = if (c.passengerId == userId) c.driverId else c.passengerId
                val other = users[otherId] ?: return@mapNotNull null
                ConversationItem(
                    conversation = c,
                    other = other,
                    ride = rides[c.rideId],
                    unread = if (c.passengerId == userId) c.unreadPassenger else c.unreadDriver,
                )
            }
        }
}

class RatingRepository(private val db: AppDatabase) {
    suspend fun hasRated(rideId: Long, fromUserId: Long, toUserId: Long): Boolean =
        db.ratings().exists(rideId, fromUserId, toUserId) > 0

    suspend fun rate(rideId: Long, fromUserId: Long, toUserId: Long, stars: Int, comment: String, toRole: String) {
        if (hasRated(rideId, fromUserId, toUserId)) return
        db.ratings().insert(
            RatingEntity(
                rideId = rideId, fromUserId = fromUserId, toUserId = toUserId,
                stars = stars.coerceIn(1, 5), comment = comment.trim(), toRole = toRole,
            ),
        )
    }

    fun observeGivenKeys(userId: Long): Flow<Set<Pair<Long, Long>>> =
        db.ratings().observeGiven(userId).mapLatest { list -> list.map { it.rideId to it.toUserId }.toSet() }

    fun observeReceived(userId: Long): Flow<List<RatingItem>> = withUsers(db.ratings().observeReceived(userId))

    fun observeGiven(userId: Long): Flow<List<RatingItem>> = withUsers(db.ratings().observeGiven(userId))

    private fun withUsers(source: Flow<List<RatingEntity>>): Flow<List<RatingItem>> = source.mapLatest { list ->
        val ids = (list.map { it.fromUserId } + list.map { it.toUserId }).distinct()
        val users = db.users().getMany(ids).associateBy { it.id }
        list.map { RatingItem(it, users[it.fromUserId], users[it.toUserId]) }
    }
}

class AlertRepository(private val db: AppDatabase) {
    fun observe(userId: Long): Flow<List<AlertEntity>> = db.alerts().observe(userId)
    suspend fun create(userId: Long, fromCity: String, toCity: String, dateEpochDay: Long, seats: Int): Long =
        db.alerts().insert(AlertEntity(userId = userId, fromCity = fromCity, toCity = toCity, dateEpochDay = dateEpochDay, seats = seats))
    suspend fun delete(id: Long) = db.alerts().delete(id)
}

class WalletRepository(private val db: AppDatabase) {
    fun observeTransactions(userId: Long): Flow<List<TransactionEntity>> = db.wallet().observeTransactions(userId)
    fun observeMethods(userId: Long): Flow<List<PaymentMethodEntity>> = db.wallet().observeMethods(userId)

    suspend fun record(userId: Long, type: String, amount: Int, description: String, method: String = "", bookingId: Long = 0) {
        db.wallet().insertTransaction(
            TransactionEntity(userId = userId, type = type, amount = amount, description = description, method = method, bookingId = bookingId),
        )
    }

    suspend fun addMethod(userId: Long, type: String, label: String, details: String) {
        db.wallet().insertMethod(PaymentMethodEntity(userId = userId, type = type, label = label, details = details))
    }

    suspend fun deleteMethod(id: Long) = db.wallet().deleteMethod(id)
}
