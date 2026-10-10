package com.carsathi.app.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.carsathi.app.data.catalog.City
import com.carsathi.app.data.catalog.CityCatalog
import com.carsathi.app.data.catalog.Geo
import com.carsathi.app.data.model.PickedPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.function.Consumer
import kotlin.coroutines.resume

sealed interface LocResult {
    data class Found(val lat: Double, val lng: Double) : LocResult
    data object Denied : LocResult
    data object Disabled : LocResult
    data object Unavailable : LocResult
}

sealed interface Resolved {
    data class Ok(val place: PickedPlace) : Resolved
    data class TooFar(val target: City, val km: Int, val nearest: City, val nearestKm: Int) : Resolved
}

/** Device location through the platform LocationManager (works without Google Play services). */
object LocationHelper {
    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPermission(context: Context): Boolean = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun locate(context: Context): LocResult {
        if (!hasPermission(context)) return LocResult.Denied
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return LocResult.Unavailable
        val enabled = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (enabled.isEmpty()) return LocResult.Disabled
        val last = (enabled + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 5 * 60_000L) return LocResult.Found(last.latitude, last.longitude)
        val fresh = withTimeoutOrNull(15_000L) { firstFix(context, lm, enabled) }
        if (fresh != null) return LocResult.Found(fresh.latitude, fresh.longitude)
        return last?.let { LocResult.Found(it.latitude, it.longitude) } ?: LocResult.Unavailable
    }

    /** Asks every enabled provider at once and returns the first real fix (GPS or network, whichever is faster). */
    private suspend fun firstFix(context: Context, lm: LocationManager, providers: List<String>): Location? = coroutineScope {
        val results = Channel<Location?>(Channel.UNLIMITED)
        val jobs = providers.map { p -> launch { results.send(requestOnce(context, lm, p)) } }
        var remaining = providers.size
        var found: Location? = null
        while (remaining > 0 && found == null) {
            found = results.receive()
            remaining--
        }
        jobs.forEach { it.cancel() }
        found
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(context: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context), Consumer<Location?> { loc ->
                    if (cont.isActive) cont.resume(loc)
                })
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }

    /**
     * Turns a coordinate into a [PickedPlace]. With [within] the point must be close to that city;
     * otherwise the nearest covered city is used.
     */
    suspend fun resolve(context: Context, lat: Double, lng: Double, within: City? = null): Resolved {
        val (nearest, nearestKm) = CityCatalog.nearest(lat, lng)
        val target = within ?: nearest
        val km = Geo.distanceKm(lat, lng, target.lat, target.lng)
        if (km > MAX_KM) return Resolved.TooFar(target, km.toInt(), nearest, nearestKm.toInt())
        val label = Geocoding.reverse(context, lat, lng)
            ?: CityCatalog.nearestMeetingPoint(target, lat, lng, 1.5)?.let { "Near ${it.label}" }
            ?: "Current location"
        return Resolved.Ok(PickedPlace(target, label, lat, lng))
    }

    private const val MAX_KM = 60.0
}

/** Address lookups through the platform Geocoder (returns null when no geocoder is available). */
object Geocoding {
    suspend fun reverse(context: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.ENGLISH)
        val list = withTimeoutOrNull(6_000L) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    suspendCancellableCoroutine<List<Address>?> { cont ->
                        geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                            override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lng, 1)
                }
            } catch (e: Exception) {
                null
            }
        }
        list?.firstOrNull()?.let { label(it) }
    }

    /** Finds coordinates for a typed address, restricted to the area around [near]. */
    suspend fun forward(context: Context, query: String, near: City): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent() || query.isBlank()) return@withContext null
        val geocoder = Geocoder(context, Locale.ENGLISH)
        val text = "$query, ${near.name}, ${near.state}"
        val south = near.lat - 0.6
        val west = near.lng - 0.6
        val north = near.lat + 0.6
        val east = near.lng + 0.6
        val list = withTimeoutOrNull(6_000L) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    suspendCancellableCoroutine<List<Address>?> { cont ->
                        geocoder.getFromLocationName(text, 1, south, west, north, east, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                            override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(text, 1, south, west, north, east)
                }
            } catch (e: Exception) {
                null
            }
        }
        list?.firstOrNull()?.let { it.latitude to it.longitude }
    }

    private fun label(a: Address): String? {
        val line = a.getAddressLine(0)
            ?: listOfNotNull(a.featureName, a.subLocality, a.locality).distinct().joinToString(", ")
        return line.replace(Regex(",?\\s*\\d{6}"), "").removeSuffix(", India").trim().take(90).ifBlank { null }
    }
}
