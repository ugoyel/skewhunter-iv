package com.carsathi.app.data.seed

import com.carsathi.app.data.catalog.Pricing
import com.carsathi.app.data.db.AppDatabase
import com.carsathi.app.data.db.BookingEntity
import com.carsathi.app.data.db.RideEntity
import com.carsathi.app.data.repo.ChatRepository
import com.carsathi.app.data.repo.NotificationRepository
import com.carsathi.app.data.repo.RatingRepository
import com.carsathi.app.data.repo.RideRepository
import com.carsathi.app.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Makes the seeded community feel alive while the app runs offline: demo drivers answer messages
 * and booking requests, demo passengers book the rides you publish, and IDs get "reviewed".
 * Replace with real backend events (push / websockets) when a server is connected.
 */
class DemoSimulator(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val chat: ChatRepository,
    private val notifications: NotificationRepository,
    private val rides: RideRepository,
    private val ratings: RatingRepository,
) {
    private val replies = listOf(
        "Hi! Sure, no problem 🙂",
        "Yes, that works for me. See you at the pickup point!",
        "Hello! I'll be there 10 minutes early. My number is in the app.",
        "Great, thanks for the message. Please keep luggage to one medium bag.",
        "Perfect 👍",
        "Sure! Let me know when you're close.",
    )

    fun onMessageSent(conversationId: Long, senderId: Long, text: String) {
        scope.launch {
            val c = chat.conversation(conversationId) ?: return@launch
            val otherId = if (c.passengerId == senderId) c.driverId else c.passengerId
            val other = db.users().get(otherId) ?: return@launch
            if (!other.isDemo) return@launch
            delay(Random.nextLong(2_500, 5_000))
            val lower = text.lowercase()
            val reply = when {
                "luggage" in lower || "bag" in lower -> "Yes, there's space for a bag in the boot 👍"
                "pickup" in lower || "where" in lower || "location" in lower -> "I'll pick you up at the meeting point shown in the ride. Call me if you can't find me!"
                "time" in lower || "late" in lower || "when" in lower -> "I'm planning to leave on time. I'll message you if anything changes."
                "pet" in lower || "dog" in lower -> "Small pets are fine if they stay in a carrier 🐶"
                "thank" in lower -> "You're welcome! Have a great day 😊"
                else -> replies[Random.nextInt(replies.size)]
            }
            chat.send(conversationId, otherId, reply)
            notifications.notify(senderId, "MESSAGE", "New message from ${other.displayName}", reply, conversationId)
        }
    }

    fun onBookingCreated(booking: BookingEntity, ride: RideEntity) {
        scope.launch {
            val driver = db.users().get(ride.driverId) ?: return@launch
            if (!driver.isDemo) return@launch
            val convId = chat.openConversation(ride.id, booking.passengerId, ride.driverId)
            if (booking.status == "PENDING") {
                delay(Random.nextLong(6_000, 10_000))
                rides.accept(booking.id)
                chat.send(convId, driver.id, "Hi! I've accepted your booking. See you on ${Fmt.dayTime(ride.departureAt)} 🚗")
            } else {
                delay(Random.nextLong(4_000, 7_000))
                chat.send(convId, driver.id, "Thanks for booking! See you on ${Fmt.dayTime(ride.departureAt)} at ${ride.fromAddress}.")
                notifications.notify(booking.passengerId, "MESSAGE", "New message from ${driver.displayName}", "Thanks for booking!", convId)
            }
        }
    }

    fun onRidePublished(ride: RideEntity) {
        scope.launch {
            delay(Random.nextLong(12_000, 20_000))
            val current = db.rides().get(ride.id) ?: return@launch
            if (current.status != "ACTIVE") return@launch
            val candidates = db.users().demoUsers().filter { !current.womenOnly || it.gender == "F" }
            if (candidates.isEmpty()) return@launch
            val passenger = candidates[Random.nextInt(candidates.size)]
            val fee = Pricing.serviceFee(current.pricePerSeat)
            val status = if (current.instantBooking) "CONFIRMED" else "PENDING"
            db.bookings().insert(
                BookingEntity(
                    rideId = current.id, passengerId = passenger.id, seats = 1, fromCity = current.fromCity, toCity = current.toCity,
                    pricePerSeat = current.pricePerSeat, serviceFee = fee, total = current.pricePerSeat + fee, status = status,
                    paymentMethod = "UPI", message = "Hi! Is there space for a small suitcase?",
                ),
            )
            val convId = chat.openConversation(current.id, passenger.id, current.driverId)
            chat.send(convId, passenger.id, "Hi! Is there space for a small suitcase?")
            val route = "${current.fromCity} → ${current.toCity}"
            if (status == "CONFIRMED") {
                notifications.notify(current.driverId, "BOOKING_CONFIRMED", "New passenger for $route", "${passenger.displayName} booked 1 seat.", current.id)
            } else {
                notifications.notify(current.driverId, "BOOKING_REQUEST", "New booking request", "${passenger.displayName} wants to book 1 seat on $route. Accept or decline it in Your rides.", current.id)
            }
        }
    }

    fun onIdSubmitted(userId: Long) {
        scope.launch {
            delay(6_000)
            val user = db.users().get(userId) ?: return@launch
            if (user.idStatus != "PENDING") return@launch
            db.users().update(user.copy(idStatus = "VERIFIED"))
            notifications.notify(userId, "VERIFICATION", "Your ID is verified ✅", "Members now see a Verified ID badge on your profile.", userId)
        }
    }

    /** Demo members rate you back after you rate them. */
    fun onRated(rideId: Long, raterId: Long, ratedId: Long, ratedRole: String) {
        scope.launch {
            val rated = db.users().get(ratedId) ?: return@launch
            if (!rated.isDemo || rideId == 0L) return@launch
            delay(Random.nextLong(5_000, 9_000))
            if (ratings.hasRated(rideId, ratedId, raterId)) return@launch
            val stars = if (Random.nextInt(10) < 7) 5 else 4
            val myRole = if (ratedRole == "DRIVER") "PASSENGER" else "DRIVER"
            val comment = if (myRole == "DRIVER") "Great driver, very comfortable ride!" else "Lovely co-traveller, on time and friendly."
            ratings.rate(rideId, ratedId, raterId, stars, comment, myRole)
            notifications.notify(raterId, "RATING", "${rated.displayName} rated you", "$stars★ — \"$comment\"", raterId)
        }
    }
}
