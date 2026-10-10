package com.carsathi.app.ui.navigation

import android.net.Uri
import com.carsathi.app.data.catalog.CityCatalog
import com.carsathi.app.data.model.PickedPlace

object Routes {
    const val WELCOME = "welcome"
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val FORGOT = "forgot"

    const val SEARCH = "search"
    const val PUBLISH = "publish"
    const val RIDES = "rides"
    const val INBOX = "inbox"
    const val PROFILE = "profile"

    const val RESULTS = "results/{from}/{to}/{date}/{seats}?fa={fa}&flat={flat}&flng={flng}&ta={ta}&tlat={tlat}&tlng={tlng}"
    const val RIDE = "ride/{rideId}?from={from}&to={to}&seats={seats}"
    const val BOOK = "book/{rideId}?from={from}&to={to}&seats={seats}"
    const val BOOKED = "booked/{rideId}/{status}"
    const val MANAGE = "manage/{rideId}"
    const val EDIT_RIDE = "editride/{rideId}"
    const val MAP = "map/{rideId}"
    const val CHAT = "chat/{conversationId}"
    const val USER = "user/{userId}"
    const val RATINGS = "ratings/{userId}"
    const val RATE = "rate/{rideId}/{userId}"
    const val PUBLISHED = "published/{rideId}"

    const val VEHICLES = "vehicles"
    const val ADD_VEHICLE = "vehicle?carId={carId}"
    const val EDIT_PROFILE = "editprofile"
    const val PREFERENCES = "preferences"
    const val VERIFY = "verify"
    const val NOTIFICATIONS = "notifications"
    const val ALERTS = "alerts"
    const val SETTINGS = "settings"
    const val PASSWORD = "password"
    const val PAYMENTS = "payments"
    const val PAYOUTS = "payouts"
    const val TRANSACTIONS = "transactions"
    const val NOTIF_SETTINGS = "notifsettings"
    const val HELP = "help"
    const val LEGAL = "legal/{doc}"
    const val ABOUT = "about"
    const val DELETE = "delete"

    private fun enc(s: String) = Uri.encode(s)

    fun results(from: String, to: String, epochDay: Long, seats: Int) = "results/${enc(from)}/${enc(to)}/$epochDay/$seats"

    /** Search results for exact pickup / drop-off points. */
    fun results(from: PickedPlace, to: PickedPlace, epochDay: Long, seats: Int): String {
        fun q(p: PickedPlace, prefix: String) =
            if (p.isCityOnly) "" else "${prefix}a=${enc(p.address)}&${prefix}lat=${p.lat}&${prefix}lng=${p.lng}"
        val params = listOf(q(from, "f"), q(to, "t")).filter { it.isNotEmpty() }.joinToString("&")
        val base = results(from.city.name, to.city.name, epochDay, seats)
        return if (params.isEmpty()) base else "$base?$params"
    }

    /** Rebuilds an exact place from navigation arguments (null when only the city was chosen). */
    fun place(cityName: String, address: String?, lat: String?, lng: String?): PickedPlace? {
        val city = CityCatalog.find(cityName) ?: return null
        val la = lat?.toDoubleOrNull() ?: return null
        val ln = lng?.toDoubleOrNull() ?: return null
        return PickedPlace(city, address.orEmpty().ifBlank { city.name }, la, ln)
    }
    fun ride(id: Long, from: String = "", to: String = "", seats: Int = 1) = "ride/$id?from=${enc(from)}&to=${enc(to)}&seats=$seats"
    fun book(id: Long, from: String, to: String, seats: Int) = "book/$id?from=${enc(from)}&to=${enc(to)}&seats=$seats"
    fun booked(rideId: Long, status: String) = "booked/$rideId/$status"
    fun manage(rideId: Long) = "manage/$rideId"
    fun editRide(rideId: Long) = "editride/$rideId"
    fun map(rideId: Long) = "map/$rideId"
    fun chat(conversationId: Long) = "chat/$conversationId"
    fun user(userId: Long) = "user/$userId"
    fun ratings(userId: Long) = "ratings/$userId"
    fun rate(rideId: Long, userId: Long) = "rate/$rideId/$userId"
    fun published(rideId: Long) = "published/$rideId"
    fun addVehicle(carId: Long = 0) = "vehicle?carId=$carId"
    fun legal(doc: String) = "legal/$doc"

    val tabs = setOf(SEARCH, RIDES, INBOX, PROFILE)
}
