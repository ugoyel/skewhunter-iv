package com.carsathi.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "users", indices = [Index(value = ["email"], unique = true)])
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String = "",
    val passwordHash: String = "",
    val salt: String = "",
    /** Date of birth as epoch day. */
    val birthDate: Long = 0,
    /** M = Mr, F = Ms, U = rather not say */
    val gender: String = "U",
    val bio: String = "",
    val photoPath: String? = null,
    val memberSince: Long = System.currentTimeMillis(),
    val emailVerified: Boolean = false,
    val phoneVerified: Boolean = false,
    /** NONE, PENDING, VERIFIED */
    val idStatus: String = "NONE",
    val idDocType: String = "",
    val chattiness: Int = 1,
    val music: Int = 1,
    val smoking: Int = 0,
    val pets: Int = 1,
    val isDemo: Boolean = false,
    val payoutUpi: String = "",
    val payoutBank: String = "",
    val payoutIfsc: String = "",
    val notifPush: Boolean = true,
    val notifEmail: Boolean = true,
    val notifSms: Boolean = false,
    val newsletter: Boolean = false,
    /** Historical number of completed rides (used for seeded demo members). */
    val pastRides: Int = 0,
) {
    val fullName: String get() = "$firstName $lastName".trim()
    val displayName: String get() = if (lastName.isNotBlank()) "$firstName ${lastName.first()}." else firstName
    val idVerified: Boolean get() = idStatus == "VERIFIED"
}

@Entity(tableName = "cars", indices = [Index("ownerId")])
data class CarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerId: Long,
    val brand: String,
    val model: String,
    val bodyType: String,
    val color: String,
    val year: Int,
    val plate: String,
    val fuel: String = "Petrol",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val title: String get() = "$brand $model"
}

@Entity(tableName = "rides", indices = [Index("driverId"), Index("departureAt")])
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driverId: Long,
    val carId: Long?,
    val fromCity: String,
    val fromAddress: String,
    val fromLat: Double,
    val fromLng: Double,
    val toCity: String,
    val toAddress: String,
    val toLat: Double,
    val toLng: Double,
    /** Stopovers encoded as "City|lat|lng" separated by ";" */
    val stopovers: String = "",
    val departureAt: Long,
    val durationMin: Int,
    val distanceKm: Int,
    val pricePerSeat: Int,
    val seatsTotal: Int,
    val maxTwoInBack: Boolean = false,
    val instantBooking: Boolean = true,
    val womenOnly: Boolean = false,
    val comment: String = "",
    /** ACTIVE, CANCELLED */
    val status: String = "ACTIVE",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val arrivalAt: Long get() = departureAt + durationMin * 60_000L
}

@Entity(tableName = "bookings", indices = [Index("rideId"), Index("passengerId")])
data class BookingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val passengerId: Long,
    val seats: Int,
    val fromCity: String,
    val toCity: String,
    val pricePerSeat: Int,
    val serviceFee: Int,
    val total: Int,
    /** PENDING, CONFIRMED, DECLINED, CANCELLED_PASSENGER, CANCELLED_DRIVER, EXPIRED */
    val status: String,
    /** CASH, UPI, CARD */
    val paymentMethod: String,
    val message: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["rideId", "passengerId", "driverId"], unique = true)],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val passengerId: Long,
    val driverId: Long,
    val lastMessage: String = "",
    val lastAt: Long = System.currentTimeMillis(),
    val unreadPassenger: Int = 0,
    val unreadDriver: Int = 0,
)

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val senderId: Long,
    val text: String,
    val sentAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "ratings", indices = [Index("toUserId"), Index("fromUserId"), Index("rideId")])
data class RatingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val fromUserId: Long,
    val toUserId: Long,
    val stars: Int,
    val comment: String,
    /** Role of the rated member on that ride: DRIVER or PASSENGER */
    val toRole: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "notifications", indices = [Index("userId")])
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    /** BOOKING_REQUEST, BOOKING_CONFIRMED, BOOKING_DECLINED, BOOKING_CANCELLED, RIDE_CANCELLED,
     *  MESSAGE, RATING, ALERT, VERIFICATION, PAYMENT, WELCOME */
    val type: String,
    val title: String,
    val body: String,
    val refId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val read: Boolean = false,
)

@Entity(tableName = "alerts", indices = [Index("userId")])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val fromCity: String,
    val toCity: String,
    /** Epoch day, or -1 for any date */
    val dateEpochDay: Long,
    val seats: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "transactions", indices = [Index("userId")])
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    /** PAYMENT, REFUND, PAYOUT */
    val type: String,
    val amount: Int,
    val description: String,
    val method: String = "",
    val bookingId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "payment_methods", indices = [Index("userId")])
data class PaymentMethodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    /** UPI or CARD */
    val type: String,
    val label: String,
    val details: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "search_history", indices = [Index("userId")])
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val fromCity: String,
    val toCity: String,
    val dateEpochDay: Long,
    val seats: Int,
    val searchedAt: Long = System.currentTimeMillis(),
)

/** Aggregate rows returned by DAO queries. */
data class RatingStat(val userId: Long, val avg: Double, val cnt: Int)

data class RideSeats(val rideId: Long, val seats: Int)
