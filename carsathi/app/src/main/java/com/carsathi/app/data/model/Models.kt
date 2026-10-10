package com.carsathi.app.data.model

import com.carsathi.app.data.catalog.City
import com.carsathi.app.data.catalog.Geo
import com.carsathi.app.data.db.BookingEntity
import com.carsathi.app.data.db.CarEntity
import com.carsathi.app.data.db.ConversationEntity
import com.carsathi.app.data.db.RatingEntity
import com.carsathi.app.data.db.RideEntity
import com.carsathi.app.data.db.UserEntity
import java.time.LocalDate
import java.time.Period
import kotlin.math.roundToInt

data class Place(val city: String, val lat: Double, val lng: Double)

data class RidePoint(
    val city: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val km: Int,
    val minutes: Int,
) {
    fun timeAt(departureAt: Long): Long = departureAt + minutes * 60_000L
}

fun encodeStopovers(list: List<Place>): String =
    list.joinToString(";") { "${it.city}|${it.lat}|${it.lng}" }

fun RideEntity.stopoverPlaces(): List<Place> =
    if (stopovers.isBlank()) emptyList() else stopovers.split(";").mapNotNull { part ->
        val p = part.split("|")
        if (p.size == 3) Place(p[0], p[1].toDoubleOrNull() ?: 0.0, p[2].toDoubleOrNull() ?: 0.0) else null
    }

/** Every point of the itinerary (departure, stopovers, arrival) with cumulative km & minutes. */
fun RideEntity.points(): List<RidePoint> {
    val raw = buildList {
        add(Triple(fromCity, fromAddress, fromLat to fromLng))
        stopoverPlaces().forEach { add(Triple(it.city, it.city, it.lat to it.lng)) }
        add(Triple(toCity, toAddress, toLat to toLng))
    }
    val legs = raw.zipWithNext { a, b -> Geo.roadKm(a.third.first, a.third.second, b.third.first, b.third.second) }
    val legSum = legs.sum().coerceAtLeast(1)
    var acc = 0
    return raw.mapIndexed { i, t ->
        if (i > 0) acc += legs[i - 1]
        val km = if (i == raw.lastIndex) distanceKm else (acc * distanceKm.toDouble() / legSum).roundToInt()
        val minutes = if (i == raw.lastIndex) durationMin else (durationMin * km.toDouble() / distanceKm.coerceAtLeast(1)).roundToInt()
        RidePoint(t.first, t.second, t.third.first, t.third.second, km, minutes)
    }
}

data class SegmentMatch(val fromIndex: Int, val toIndex: Int, val fromDetourKm: Int, val toDetourKm: Int)

/** Finds the best boarding / alighting points of [points] for a search between [from] and [to]. */
fun matchSegment(points: List<RidePoint>, from: City, to: City, radiusKm: Double = 35.0): SegmentMatch? {
    var best: SegmentMatch? = null
    var bestCost = Double.MAX_VALUE
    val dFrom = points.map { Geo.distanceKm(it.lat, it.lng, from.lat, from.lng) }
    val dTo = points.map { Geo.distanceKm(it.lat, it.lng, to.lat, to.lng) }
    for (i in 0 until points.lastIndex) {
        if (dFrom[i] > radiusKm) continue
        for (j in i + 1..points.lastIndex) {
            if (dTo[j] > radiusKm) continue
            val cost = dFrom[i] + dTo[j]
            if (cost < bestCost) {
                bestCost = cost
                best = SegmentMatch(i, j, dFrom[i].roundToInt(), dTo[j].roundToInt())
            }
        }
    }
    return best
}

data class RideCardModel(
    val ride: RideEntity,
    val driver: UserEntity,
    val rating: Double,
    val ratingCount: Int,
    val seatsLeft: Int,
    val from: RidePoint,
    val to: RidePoint,
    val price: Int,
    val fromDetourKm: Int = 0,
    val toDetourKm: Int = 0,
) {
    val departureAt: Long get() = from.timeAt(ride.departureAt)
    val arrivalAt: Long get() = to.timeAt(ride.departureAt)
    val durationMin: Int get() = to.minutes - from.minutes
}

data class PassengerInfo(val booking: BookingEntity, val user: UserEntity, val rating: Double, val ratingCount: Int)

data class RideDetail(
    val ride: RideEntity,
    val driver: UserEntity,
    val driverRating: Double,
    val driverRatingCount: Int,
    val car: CarEntity?,
    val points: List<RidePoint>,
    val seatsLeft: Int,
    val passengers: List<PassengerInfo>,
    val pending: List<PassengerInfo>,
    val myBooking: BookingEntity?,
    val isMine: Boolean,
)

data class BookingWithRide(val booking: BookingEntity, val ride: RideEntity, val driver: UserEntity)

data class ConversationItem(
    val conversation: ConversationEntity,
    val other: UserEntity,
    val ride: RideEntity?,
    val unread: Int,
)

data class RatingItem(val rating: RatingEntity, val author: UserEntity?, val subject: UserEntity?)

data class TripItem(
    val ride: RideEntity,
    val asDriver: Boolean,
    val booking: BookingEntity?,
    val counterpart: UserEntity?,
    val pendingRequests: Int,
    val confirmedPassengers: Int,
    val needsRating: Boolean,
) {
    val isPast: Boolean get() = ride.arrivalAt < System.currentTimeMillis()
}

object PrefLabels {
    val chattiness = listOf("I'm the quiet type", "I talk depending on my mood", "I love to chat!")
    val music = listOf("Silence is golden", "It depends on the soundtrack", "It's all about the playlist!")
    val smoking = listOf("No smoking in the car, please", "Cigarette breaks outside the car are ok", "I'm fine with smoking")
    val pets = listOf("No pets, please", "I'll travel with some pets", "Pets welcome. Woof!")
}

object RatingLabels {
    fun label(stars: Int): String = when (stars) {
        5 -> "Outstanding"
        4 -> "Excellent"
        3 -> "Good"
        2 -> "Poor"
        else -> "Very disappointing"
    }
}

fun experienceLevel(rides: Int, rating: Double): String = when {
    rides >= 40 && rating >= 4.6 -> "Ambassador"
    rides >= 20 && rating >= 4.4 -> "Expert"
    rides >= 10 && rating >= 4.0 -> "Experienced"
    rides >= 3 -> "Intermediate"
    else -> "Newcomer"
}

fun UserEntity.age(): Int? =
    if (birthDate == 0L) null else Period.between(LocalDate.ofEpochDay(birthDate), LocalDate.now()).years

fun UserEntity.initials(): String =
    (firstName.take(1) + lastName.take(1)).uppercase().ifBlank { "?" }

/** A city plus the exact pickup / drop-off point chosen inside it. */
data class PickedPlace(val city: City, val address: String, val lat: Double, val lng: Double) {
    val isCityOnly: Boolean get() = address.isBlank() || address == city.name

    /** The city carrying the exact coordinates of the chosen point. */
    fun asCity(): City = city.copy(lat = lat, lng = lng)

    companion object {
        fun cityOnly(city: City) = PickedPlace(city, city.name, city.lat, city.lng)
    }
}
