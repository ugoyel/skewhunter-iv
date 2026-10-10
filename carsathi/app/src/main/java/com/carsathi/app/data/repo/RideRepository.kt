package com.carsathi.app.data.repo

import androidx.room.withTransaction
import com.carsathi.app.data.catalog.City
import com.carsathi.app.data.catalog.Geo
import com.carsathi.app.data.catalog.Pricing
import com.carsathi.app.data.db.AppDatabase
import com.carsathi.app.data.db.BookingEntity
import com.carsathi.app.data.db.RideEntity
import com.carsathi.app.data.db.SearchHistoryEntity
import com.carsathi.app.data.db.UserEntity
import com.carsathi.app.data.model.PassengerInfo
import com.carsathi.app.data.model.Place
import com.carsathi.app.data.model.RideCardModel
import com.carsathi.app.data.model.RideDetail
import com.carsathi.app.data.model.TripItem
import com.carsathi.app.data.model.encodeStopovers
import com.carsathi.app.data.model.matchSegment
import com.carsathi.app.data.model.points
import com.carsathi.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class RideDraft(
    val from: City,
    val fromAddress: String,
    val to: City,
    val toAddress: String,
    val stopovers: List<City>,
    val departureAt: Long,
    val seats: Int,
    val maxTwoInBack: Boolean,
    val instantBooking: Boolean,
    val womenOnly: Boolean,
    val pricePerSeat: Int,
    val carId: Long?,
    val comment: String,
)

data class SearchQuery(val from: City, val to: City, val date: LocalDate, val seats: Int)

class RideRepository(
    private val db: AppDatabase,
    private val notifications: NotificationRepository,
    private val chat: ChatRepository,
    private val wallet: WalletRepository,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    // ---------------------------------------------------------------- publishing

    fun routeKm(from: City, stopovers: List<City>, to: City): Int {
        val pts = listOf(from) + stopovers + to
        return pts.zipWithNext { a, b -> Geo.roadKm(a.lat, a.lng, b.lat, b.lng) }.sum()
    }

    fun buildRide(driverId: Long, d: RideDraft): RideEntity {
        val km = routeKm(d.from, d.stopovers, d.to)
        return RideEntity(
            driverId = driverId,
            carId = d.carId,
            fromCity = d.from.name, fromAddress = d.fromAddress.ifBlank { d.from.name },
            fromLat = d.from.lat, fromLng = d.from.lng,
            toCity = d.to.name, toAddress = d.toAddress.ifBlank { d.to.name },
            toLat = d.to.lat, toLng = d.to.lng,
            stopovers = encodeStopovers(d.stopovers.map { Place(it.name, it.lat, it.lng) }),
            departureAt = d.departureAt,
            durationMin = Geo.durationMin(km) + d.stopovers.size * 10,
            distanceKm = km,
            pricePerSeat = d.pricePerSeat,
            seatsTotal = d.seats,
            maxTwoInBack = d.maxTwoInBack,
            instantBooking = d.instantBooking,
            womenOnly = d.womenOnly,
            comment = d.comment.trim(),
        )
    }

    suspend fun publish(driverId: Long, draft: RideDraft): RideEntity {
        val ride = buildRide(driverId, draft)
        val id = db.rides().insert(ride)
        return ride.copy(id = id)
    }

    suspend fun get(id: Long): RideEntity? = db.rides().get(id)

    suspend fun update(ride: RideEntity) = db.rides().update(ride)

    // ---------------------------------------------------------------- search

    suspend fun search(q: SearchQuery, viewer: UserEntity?): List<RideCardModel> = withContext(Dispatchers.Default) {
        val start = q.date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = q.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val now = System.currentTimeMillis()
        val candidates = db.rides().activeBetween(start - 12 * 3_600_000L, end)
        data class Hit(val ride: RideEntity, val pts: List<com.carsathi.app.data.model.RidePoint>, val m: com.carsathi.app.data.model.SegmentMatch)
        val hits = candidates.mapNotNull { ride ->
            if (viewer != null && ride.driverId == viewer.id) return@mapNotNull null
            if (ride.womenOnly && viewer?.gender != "F") return@mapNotNull null
            val pts = ride.points()
            val m = matchSegment(pts, q.from, q.to) ?: return@mapNotNull null
            val boardAt = pts[m.fromIndex].timeAt(ride.departureAt)
            if (boardAt < start || boardAt > end || boardAt < now) return@mapNotNull null
            Hit(ride, pts, m)
        }
        if (hits.isEmpty()) return@withContext emptyList()
        val rideIds = hits.map { it.ride.id }
        val taken = db.bookings().seatsTaken(rideIds).associate { it.rideId to it.seats }
        val drivers = db.users().getMany(hits.map { it.ride.driverId }.distinct()).associateBy { it.id }
        val stats = db.ratings().stats(drivers.keys.toList()).associateBy { it.userId }
        hits.mapNotNull { h ->
            val driver = drivers[h.ride.driverId] ?: return@mapNotNull null
            val left = h.ride.seatsTotal - (taken[h.ride.id] ?: 0)
            if (left < q.seats) return@mapNotNull null
            val from = h.pts[h.m.fromIndex]
            val to = h.pts[h.m.toIndex]
            RideCardModel(
                ride = h.ride,
                driver = driver,
                rating = stats[driver.id]?.avg ?: 0.0,
                ratingCount = stats[driver.id]?.cnt ?: 0,
                seatsLeft = left,
                from = from,
                to = to,
                price = Pricing.segmentPrice(h.ride.pricePerSeat, to.km - from.km, h.ride.distanceKm),
                fromDetourKm = h.m.fromDetourKm,
                toDetourKm = h.m.toDetourKm,
            )
        }
    }

    /** Number of matching rides on each of the next days (used for "other dates" suggestions). */
    suspend fun countsAround(q: SearchQuery, viewer: UserEntity?, days: Int = 5): List<Pair<LocalDate, Int>> {
        val today = LocalDate.now()
        return (1..days).map { q.date.plusDays(it.toLong()) }
            .plus((1..2).map { q.date.minusDays(it.toLong()) }.filter { !it.isBefore(today) })
            .sorted()
            .map { d -> d to search(q.copy(date = d), viewer).size }
            .filter { it.second > 0 }
    }

    suspend fun recordSearch(userId: Long, q: SearchQuery) {
        db.searches().insert(
            SearchHistoryEntity(userId = userId, fromCity = q.from.name, toCity = q.to.name, dateEpochDay = q.date.toEpochDay(), seats = q.seats),
        )
    }

    fun observeHistory(userId: Long): Flow<List<SearchHistoryEntity>> = db.searches().observe(userId)

    suspend fun clearHistory(userId: Long) = db.searches().clear(userId)

    // ---------------------------------------------------------------- details & trips

    fun observeDetail(rideId: Long, viewerId: Long): Flow<RideDetail?> =
        combine(db.rides().observe(rideId), db.bookings().observeByRide(rideId)) { ride, bookings -> ride to bookings }
            .mapLatest { (ride, bookings) ->
                if (ride == null) return@mapLatest null
                val driver = db.users().get(ride.driverId) ?: return@mapLatest null
                val active = bookings.filter { it.status == "CONFIRMED" || it.status == "PENDING" }
                val userIds = (bookings.map { it.passengerId } + driver.id).distinct()
                val users = db.users().getMany(userIds).associateBy { it.id }
                val stats = db.ratings().stats(userIds).associateBy { it.userId }
                fun info(b: BookingEntity) = users[b.passengerId]?.let {
                    PassengerInfo(b, it, stats[it.id]?.avg ?: 0.0, stats[it.id]?.cnt ?: 0)
                }
                RideDetail(
                    ride = ride,
                    driver = driver,
                    driverRating = stats[driver.id]?.avg ?: 0.0,
                    driverRatingCount = stats[driver.id]?.cnt ?: 0,
                    car = ride.carId?.let { db.cars().get(it) },
                    points = ride.points(),
                    seatsLeft = ride.seatsTotal - active.sumOf { it.seats },
                    passengers = bookings.filter { it.status == "CONFIRMED" }.mapNotNull { info(it) },
                    pending = bookings.filter { it.status == "PENDING" }.mapNotNull { info(it) },
                    myBooking = bookings.lastOrNull { it.passengerId == viewerId },
                    isMine = ride.driverId == viewerId,
                )
            }

    fun observeTrips(userId: Long, ratings: RatingRepository): Flow<List<TripItem>> =
        combine(
            db.rides().observeByDriver(userId),
            db.bookings().observeByPassenger(userId),
            db.bookings().observeForDriver(userId),
            ratings.observeGivenKeys(userId),
        ) { driven, booked, driverBookings, rated -> Quad(driven, booked, driverBookings, rated) }
            .mapLatest { (driven, booked, driverBookings, rated) ->
                val byRide = driverBookings.groupBy { it.rideId }
                val asDriver = driven.map { ride ->
                    val bs = byRide[ride.id].orEmpty()
                    val confirmed = bs.filter { it.status == "CONFIRMED" }
                    TripItem(
                        ride = ride,
                        asDriver = true,
                        booking = null,
                        counterpart = null,
                        pendingRequests = bs.count { it.status == "PENDING" },
                        confirmedPassengers = confirmed.sumOf { it.seats },
                        needsRating = ride.status == "ACTIVE" && ride.arrivalAt < System.currentTimeMillis() &&
                            confirmed.any { (ride.id to it.passengerId) !in rated },
                    )
                }
                val rides = db.rides().getMany(booked.map { it.rideId }.distinct()).associateBy { it.id }
                val drivers = db.users().getMany(rides.values.map { it.driverId }.distinct()).associateBy { it.id }
                val asPassenger = booked.mapNotNull { b ->
                    val ride = rides[b.rideId] ?: return@mapNotNull null
                    TripItem(
                        ride = ride,
                        asDriver = false,
                        booking = b,
                        counterpart = drivers[ride.driverId],
                        pendingRequests = 0,
                        confirmedPassengers = 0,
                        needsRating = b.status == "CONFIRMED" && ride.arrivalAt < System.currentTimeMillis() &&
                            (ride.id to ride.driverId) !in rated,
                    )
                }
                (asDriver + asPassenger).sortedBy { it.ride.departureAt }
            }

    // ---------------------------------------------------------------- bookings

    suspend fun book(
        ride: RideEntity,
        passenger: UserEntity,
        seats: Int,
        fromCity: String,
        toCity: String,
        pricePerSeat: Int,
        paymentMethod: String,
        message: String,
    ): BookingEntity {
        val subtotal = pricePerSeat * seats
        val fee = Pricing.serviceFee(subtotal)
        val status = if (ride.instantBooking) "CONFIRMED" else "PENDING"
        val booking = BookingEntity(
            rideId = ride.id, passengerId = passenger.id, seats = seats, fromCity = fromCity, toCity = toCity,
            pricePerSeat = pricePerSeat, serviceFee = fee, total = subtotal + fee, status = status,
            paymentMethod = paymentMethod, message = message.trim(),
        )
        val id = db.bookings().insert(booking)
        val saved = booking.copy(id = id)
        val route = "$fromCity → $toCity"
        val date = Fmt.dayTime(ride.departureAt)
        if (paymentMethod != "CASH") {
            wallet.record(passenger.id, "PAYMENT", subtotal + fee, "Booking $route", paymentMethod, id)
        } else {
            wallet.record(passenger.id, "PAYMENT", fee, "Service fee · $route (seat paid in cash)", "UPI", id)
        }
        val convId = chat.openConversation(ride.id, passenger.id, ride.driverId)
        if (message.isNotBlank()) chat.send(convId, passenger.id, message.trim())
        val seatsLabel = if (seats == 1) "1 seat" else "$seats seats"
        if (ride.instantBooking) {
            notifications.notify(ride.driverId, "BOOKING_CONFIRMED", "New passenger for $route", "${passenger.displayName} booked $seatsLabel for $date.", ride.id)
            notifications.notify(passenger.id, "BOOKING_CONFIRMED", "Your booking is confirmed!", "$route · $date. Have a good trip!", ride.id, push = false)
        } else {
            notifications.notify(ride.driverId, "BOOKING_REQUEST", "New booking request", "${passenger.displayName} wants to book $seatsLabel on $route ($date).", ride.id)
            notifications.notify(passenger.id, "BOOKING_REQUEST", "Request sent", "The driver will reply to your request for $route soon.", ride.id, push = false)
        }
        return saved
    }

    suspend fun accept(bookingId: Long) {
        val b = db.bookings().get(bookingId) ?: return
        if (b.status != "PENDING") return
        val ride = db.rides().get(b.rideId) ?: return
        db.bookings().update(b.copy(status = "CONFIRMED"))
        notifications.notify(
            b.passengerId, "BOOKING_CONFIRMED", "Booking accepted",
            "Your request for ${b.fromCity} → ${b.toCity} on ${Fmt.dayTime(ride.departureAt)} was accepted.", ride.id,
        )
    }

    suspend fun decline(bookingId: Long) {
        val b = db.bookings().get(bookingId) ?: return
        if (b.status != "PENDING") return
        val ride = db.rides().get(b.rideId) ?: return
        db.bookings().update(b.copy(status = "DECLINED"))
        refund(b, "Declined request")
        notifications.notify(
            b.passengerId, "BOOKING_DECLINED", "Booking request declined",
            "Your request for ${b.fromCity} → ${b.toCity} was declined. You've been fully refunded.", ride.id,
        )
    }

    suspend fun cancelBooking(bookingId: Long) {
        val b = db.bookings().get(bookingId) ?: return
        if (b.status != "PENDING" && b.status != "CONFIRMED") return
        val ride = db.rides().get(b.rideId) ?: return
        db.bookings().update(b.copy(status = "CANCELLED_PASSENGER"))
        refund(b, "Cancelled booking")
        val passenger = db.users().get(b.passengerId)
        notifications.notify(
            ride.driverId, "BOOKING_CANCELLED", "A passenger cancelled",
            "${passenger?.displayName ?: "A passenger"} cancelled their booking for ${b.fromCity} → ${b.toCity}.", ride.id,
        )
    }

    suspend fun cancelRide(rideId: Long) {
        val ride = db.rides().get(rideId) ?: return
        val bookings = db.bookings().byRide(rideId).filter { it.status == "PENDING" || it.status == "CONFIRMED" }
        db.withTransaction {
            db.rides().update(ride.copy(status = "CANCELLED"))
            bookings.forEach { db.bookings().update(it.copy(status = "CANCELLED_DRIVER")) }
        }
        bookings.forEach { b ->
            refund(b, "Ride cancelled by driver")
            notifications.notify(
                b.passengerId, "RIDE_CANCELLED", "Ride cancelled",
                "The driver cancelled ${b.fromCity} → ${b.toCity} on ${Fmt.dayTime(ride.departureAt)}. You've been refunded.", ride.id,
            )
        }
    }

    private suspend fun refund(b: BookingEntity, reason: String) {
        if (b.paymentMethod != "CASH" && db.wallet().countFor(b.id, "REFUND") == 0) {
            wallet.record(b.passengerId, "REFUND", b.total, "$reason · ${b.fromCity} → ${b.toCity}", b.paymentMethod, b.id)
        }
    }

    /** Records payouts for completed rides the driver was paid for online. */
    suspend fun settlePayouts(driverId: Long) {
        val now = System.currentTimeMillis()
        val bookings = db.bookings().forDriver(driverId).filter { it.status == "CONFIRMED" && it.paymentMethod != "CASH" }
        if (bookings.isEmpty()) return
        val rides = db.rides().getMany(bookings.map { it.rideId }.distinct()).associateBy { it.id }
        bookings.forEach { b ->
            val ride = rides[b.rideId] ?: return@forEach
            if (ride.arrivalAt < now && db.wallet().countFor(b.id, "PAYOUT") == 0) {
                wallet.record(driverId, "PAYOUT", b.pricePerSeat * b.seats, "Payout · ${b.fromCity} → ${b.toCity}", "Bank transfer", b.id)
            }
        }
    }
}

data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
