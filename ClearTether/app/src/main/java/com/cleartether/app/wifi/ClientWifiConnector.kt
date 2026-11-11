package com.cleartether.app.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import com.cleartether.app.core.net.Cidr
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.coroutines.resume

/** Network availability for a client's connection to the host. */
sealed interface TetherNetworkEvent {
    data class Available(val network: Network) : TetherNetworkEvent
    data object Lost : TetherNetworkEvent
    data object Unavailable : TetherNetworkEvent
}

/**
 * Finds or joins the host's Wi-Fi Direct network on a ClearTether **client** device.
 *
 * Preferred path: an app-scoped "local-only" connection through [WifiNetworkSpecifier]. Android
 * shows a one-tap "Connect to device" sheet, and the network never becomes the system default,
 * so it isn't marked "No internet" and the client's own cellular data isn't used. ClearTether
 * binds its relay sockets to this network and gives every other app internet through the VPN.
 */
class ClientWifiConnector(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Requests the host network; emits while the request is held. Cancel the flow to disconnect. */
    fun join(ssid: String, passphrase: String?): Flow<TetherNetworkEvent> = callbackFlow {
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (!passphrase.isNullOrEmpty()) setWpa2Passphrase(passphrase)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(TetherNetworkEvent.Available(network)) }
            override fun onLost(network: Network) { trySend(TetherNetworkEvent.Lost) }
            override fun onUnavailable() { trySend(TetherNetworkEvent.Unavailable) }
        }
        connectivity.requestNetwork(request, callback, JOIN_TIMEOUT_MS)
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    /** Finds an already-connected Wi-Fi network (joined in system settings) whose subnet contains [host]. */
    suspend fun findExisting(host: InetAddress, timeoutMs: Long = 3_000): Network? {
        val subnet = Cidr(host, 24)
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                            if (linkProperties.linkAddresses.any { subnet.contains(it.address) } && cont.isActive) {
                                cont.resume(network)
                            }
                        }
                    }
                    callback = cb
                    connectivity.registerNetworkCallback(request, cb)
                }
            }
        } finally {
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        }
    }

    fun bindProcess(network: Network?) {
        connectivity.bindProcessToNetwork(network)
    }

    companion object {
        private const val JOIN_TIMEOUT_MS = 120_000
    }
}
