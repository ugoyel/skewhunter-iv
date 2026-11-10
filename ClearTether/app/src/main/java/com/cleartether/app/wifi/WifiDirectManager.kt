package com.cleartether.app.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.cleartether.app.core.net.AddressPolicy
import com.cleartether.app.data.WifiBand
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A Wi-Fi Direct framework call failed with one of the `WifiP2pManager` reason codes. */
class P2pException(val reason: Int, message: String = describe(reason)) : Exception(message) {
    val isBusy: Boolean get() = reason == WifiP2pManager.BUSY
    val isUnsupported: Boolean get() = reason == WifiP2pManager.P2P_UNSUPPORTED

    companion object {
        fun describe(reason: Int) = when (reason) {
            WifiP2pManager.ERROR -> "Wi-Fi Direct internal error"
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
            WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "No service requests"
            else -> "Wi-Fi Direct error $reason"
        }
    }
}

/** What we ask the framework for. */
data class GroupRequest(val networkName: String, val passphrase: String, val band: WifiBand)

/** The group the framework actually created. */
data class GroupDetails(
    val networkName: String,
    val passphrase: String,
    val interfaceName: String?,
    val ownerAddress: InetAddress,
    val frequencyMhz: Int,
    val clientCount: Int,
)

sealed interface P2pEvent {
    data class StateChanged(val enabled: Boolean) : P2pEvent
    data class ConnectionChanged(val groupFormed: Boolean, val group: WifiP2pGroup?) : P2pEvent
    data object ChannelLost : P2pEvent
}

/**
 * Wraps [WifiP2pManager] for ClearTether's host mode.
 *
 * - Creates an *autonomous* group-owner network with a fixed name and passphrase
 *   (`WifiP2pConfig.Builder`, Android 10+). Laptops, Macs and iPhones join it as a normal
 *   WPA2 network ("legacy clients"), so they don't need Wi-Fi Direct support.
 * - Uses persistent mode, so the framework keeps the group record and P2P-capable peers can
 *   rejoin it instantly. Stable credentials mean clients configure once and auto-join afterwards.
 * - Reads the SSID and WPA2 passphrase back from `requestGroupInfo`, because they are what
 *   the radio really uses.
 * - Turns the framework's callback APIs into suspending functions and a [Flow] of events.
 */
class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private val wifiManager: WifiManager? = context.applicationContext.getSystemService(WifiManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    @Volatile private var channelLostListener: (() -> Unit)? = null

    val isSupported: Boolean
        get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    val isWifiEnabled: Boolean get() = wifiManager?.isWifiEnabled == true

    val is5GhzSupported: Boolean get() = wifiManager?.is5GHzBandSupported == true

    fun hasPermission(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun channel(): WifiP2pManager.Channel {
        channel?.let { return it }
        val m = manager ?: throw P2pException(WifiP2pManager.P2P_UNSUPPORTED)
        return m.initialize(context.applicationContext, Looper.getMainLooper()) {
            channel = null
            channelLostListener?.invoke()
        }.also { channel = it }
    }

    /**
     * Creates (or re-creates) the group and waits until it is up with an IP address.
     * Retries BUSY results, which are common right after `removeGroup`, and falls back from
     * a specific band to AUTO if the radio refuses it.
     */
    @SuppressLint("MissingPermission")
    suspend fun createGroup(request: GroupRequest): GroupDetails {
        check(hasPermission()) { "Missing Wi-Fi Direct permission" }
        val m = manager ?: throw P2pException(WifiP2pManager.P2P_UNSUPPORTED)

        // A stale group (from a crash, or another app) blocks group creation.
        if (requestGroupInfo() != null) {
            runCatching { removeGroup() }
            delay(300)
        }

        val bands = buildList {
            add(request.band)
            if (request.band != WifiBand.AUTO) add(WifiBand.AUTO)
        }
        var lastError: P2pException? = null
        for (band in bands) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(request.networkName)
                .setPassphrase(request.passphrase)
                .setGroupOperatingBand(band.toFrameworkBand())
                .enablePersistentMode(true)
                .build()
            for (attempt in 1..MAX_BUSY_RETRIES) {
                try {
                    awaitAction { m.createGroup(channel(), config, it) }
                    return awaitGroupReady(request)
                } catch (e: P2pException) {
                    lastError = e
                    if (e.isUnsupported) throw e
                    if (!e.isBusy) break // try the next band
                    delay(BUSY_BACKOFF_MS * attempt)
                }
            }
        }
        throw lastError ?: P2pException(WifiP2pManager.ERROR)
    }

    private suspend fun awaitGroupReady(request: GroupRequest): GroupDetails {
        val group = withTimeoutOrNull(GROUP_READY_TIMEOUT_MS) {
            var g: WifiP2pGroup? = null
            while (g == null || !g.isGroupOwner) {
                delay(250)
                g = requestGroupInfo()
            }
            g
        } ?: throw P2pException(WifiP2pManager.ERROR, "Timed out waiting for the Wi-Fi Direct group")

        val ownerAddress = awaitInterfaceAddress(group.`interface`)
            ?: requestConnectionInfo()?.groupOwnerAddress
            ?: InetAddress.getByName(AddressPolicy.DEFAULT_GROUP_OWNER_IP)
        return GroupDetails(
            networkName = group.networkName ?: request.networkName,
            passphrase = group.passphrase ?: request.passphrase,
            interfaceName = group.`interface`,
            ownerAddress = ownerAddress,
            frequencyMhz = group.frequency,
            clientCount = group.clientList?.size ?: 0,
        )
    }

    /** The group-owner IP appears on the P2P interface shortly after the group forms. */
    private suspend fun awaitInterfaceAddress(name: String?): InetAddress? {
        if (name.isNullOrEmpty()) return null
        return withTimeoutOrNull(ADDRESS_TIMEOUT_MS) {
            var address: InetAddress? = null
            while (address == null) {
                address = runCatching {
                    NetworkInterface.getByName(name)?.inetAddresses?.toList()?.firstOrNull { it is Inet4Address }
                }.getOrNull()
                if (address == null) delay(200)
            }
            address
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun requestGroupInfo(): WifiP2pGroup? {
        val m = manager ?: return null
        if (!hasPermission()) return null
        return suspendCancellableCoroutine { cont ->
            try {
                m.requestGroupInfo(channel()) { group -> cont.resume(group) }
            } catch (e: Exception) {
                cont.resume(null)
            }
        }
    }

    suspend fun requestConnectionInfo(): WifiP2pInfo? {
        val m = manager ?: return null
        return suspendCancellableCoroutine { cont ->
            try {
                m.requestConnectionInfo(channel()) { info -> cont.resume(info) }
            } catch (e: Exception) {
                cont.resume(null)
            }
        }
    }

    suspend fun removeGroup() {
        val m = manager ?: return
        try {
            awaitAction { m.removeGroup(channel(), it) }
        } catch (e: P2pException) {
            // Removing a group that doesn't exist reports ERROR: nothing to do.
        }
    }

    /** Framework broadcasts relevant to a running group. */
    fun events(): Flow<P2pEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED)
                        trySend(P2pEvent.StateChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED))
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION") intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        }
                        val group = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                        } else {
                            @Suppress("DEPRECATION") intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                        }
                        trySend(P2pEvent.ConnectionChanged(info?.groupFormed == true, group))
                    }
                }
            }
        }
        channelLostListener = { trySend(P2pEvent.ChannelLost) }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose {
            channelLostListener = null
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Releases the framework channel. Call after [removeGroup] when tethering stops. */
    fun close() {
        runCatching { channel?.close() }
        channel = null
    }

    private suspend fun awaitAction(block: (WifiP2pManager.ActionListener) -> Unit) =
        suspendCancellableCoroutine { cont ->
            try {
                block(object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cont.resume(Unit)
                    override fun onFailure(reason: Int) = cont.resumeWithException(P2pException(reason))
                })
            } catch (e: SecurityException) {
                cont.resumeWithException(e)
            }
        }

    private fun WifiBand.toFrameworkBand() = when (this) {
        WifiBand.AUTO -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
        WifiBand.BAND_2_4_GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
        WifiBand.BAND_5_GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
    }

    companion object {
        private const val MAX_BUSY_RETRIES = 4
        private const val BUSY_BACKOFF_MS = 600L
        private const val GROUP_READY_TIMEOUT_MS = 15_000L
        private const val ADDRESS_TIMEOUT_MS = 6_000L

        /** Runtime permissions Wi-Fi Direct needs on this OS version. */
        fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun bandLabel(frequencyMhz: Int): String = when {
            frequencyMhz <= 0 -> "Wi-Fi"
            frequencyMhz < 3000 -> "2.4 GHz"
            frequencyMhz < 5925 -> "5 GHz"
            else -> "6 GHz"
        }
    }
}
