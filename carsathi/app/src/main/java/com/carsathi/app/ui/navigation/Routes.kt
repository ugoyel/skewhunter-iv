package com.carsathi.app.ui.navigation

import android.net.Uri

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

    const val RESULTS = "results/{from}/{to}/{date}/{seats}"
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
