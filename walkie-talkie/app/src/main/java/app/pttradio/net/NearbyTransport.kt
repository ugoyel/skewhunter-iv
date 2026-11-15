package app.pttradio.net

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.pttradio.core.Transport
import app.pttradio.core.TransportListener
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * What the no-internet radio is doing, for the UI.
 * [problem] is "perm", "bt", "loc" or "err" when something stops phones from finding each other.
 */
data class NearbyInfo(val running: Boolean = false, val linked: Int = 0, val found: Int = 0, val problem: String? = null, val detail: String? = null)

/**
 * No-internet link. Google Nearby Connections finds other phones running the app and links them over
 * Bluetooth, BLE and Wi-Fi (LAN / hotspot / Wi-Fi Direct), whichever is available. Every phone both
 * advertises and discovers (cluster), and forwards what it hears, so phones form a small mesh.
 */
class NearbyTransport(private val ctx: Context) : Transport {
    override val name = "nearby"
    override val relays = true
    override var listener: TransportListener? = null
    override val isUp: Boolean get() = running && peers.isNotEmpty()

    val info = MutableStateFlow(NearbyInfo())

    private val serviceId = "app.pttradio.mesh"
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy { Nearby.getConnectionsClient(ctx) }
    private val peers = ConcurrentHashMap.newKeySet<String>()
    private val found = ConcurrentHashMap<String, String>()   // endpoint -> user id
    private val pending = ConcurrentHashMap.newKeySet<String>()
    @Volatile var running = false; private set
    @Volatile private var myId = ""
    @Volatile private var error: String? = null
    private var advertising = false
    private var discovering = false

    companion object {
        /**
         * Android only returns Bluetooth scan results to apps that hold the Location permission, on every
         * version - without it phones silently never see each other. The location itself is never read.
         */
        fun permissions(): Array<String> {
            val p = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) p += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
            return p.toTypedArray()
        }
        fun hasPermissions(c: Context) = permissions().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun bluetoothOn() = try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (e: Exception) { true }
    private fun locationOn() = try {
        Build.VERSION.SDK_INT < 28 || ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled != false
    } catch (e: Exception) { true }

    /** Recomputes [info]; safe to call any time (e.g. when the app comes to the front). */
    fun publish() {
        val (problem, detail) = when {
            !hasPermissions(ctx) -> "perm" to "Allow Nearby devices and Location so phones can find each other."
            !bluetoothOn() -> "bt" to "Bluetooth is off. Turn it on to reach phones around you."
            !locationOn() -> "loc" to "Location is switched off. Android needs it on to scan for nearby phones."
            error != null -> "err" to error
            else -> null to null
        }
        info.value = NearbyInfo(running, peers.size, found.keys.count { it !in peers }, problem, detail)
    }

    private fun describe(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            null -> "Nearby link failed: ${e.message ?: e.javaClass.simpleName}"
            ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING, ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING -> ""
            else -> "Nearby link failed: ${ConnectionsStatusCodes.getStatusCodeString(code)} ($code)"
        }
    }

    fun start() = main.post {
        if (myId.isEmpty()) return@post
        if (!hasPermissions(ctx)) { publish(); return@post }
        running = true
        error = null
        try {
            if (!advertising) {
                advertising = true
                client.startAdvertising(myId, serviceId, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                    .addOnFailureListener { e -> describe(e).let { if (it.isNotEmpty()) { advertising = false; error = it } }; publish() }
            }
            if (!discovering) {
                discovering = true
                client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                    .addOnFailureListener { e -> describe(e).let { if (it.isNotEmpty()) { discovering = false; error = it } }; publish() }
            }
        } catch (e: Exception) { advertising = false; discovering = false; error = describe(e) }
        publish()
    }

    /** Scanning that found nobody is restarted, e.g. after Bluetooth or Location was switched on. */
    fun restartIfIdle() = main.post {
        if (!running || peers.isNotEmpty() || pending.isNotEmpty()) return@post
        try { client.stopAdvertising(); client.stopDiscovery() } catch (e: Exception) { }
        advertising = false; discovering = false
        found.clear()
    }

    fun stop() = main.post {
        if (!running) { publish(); return@post }
        running = false
        advertising = false; discovering = false
        try { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints() } catch (e: Exception) { }
        peers.clear(); found.clear(); pending.clear()
        publish()
        listener?.onLink(this)
    }

    private fun request(endpoint: String) {
        if (!running || endpoint in peers || !found.containsKey(endpoint) || !pending.add(endpoint)) return
        client.requestConnection(myId, endpoint, lifecycle).addOnFailureListener { e ->
            pending.remove(endpoint)
            if ((e as? ApiException)?.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                peers.add(endpoint); publish(); listener?.onLink(this); return@addOnFailureListener
            }
            // Usually both phones dialled at once or the radio was busy: try again while the phone is in range.
            main.postDelayed({ request(endpoint) }, 3000 + (Math.random() * 3000).toLong())
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpoint: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != serviceId) return
            found[endpoint] = info.endpointName
            publish()
            // Both phones may see each other. The smaller id dials at once; the other waits a little and
            // dials only if no link came up, so a one-sided discovery still connects.
            if (myId < info.endpointName) request(endpoint)
            else main.postDelayed({ request(endpoint) }, 4000 + (Math.random() * 2000).toLong())
        }
        override fun onEndpointLost(endpoint: String) { found.remove(endpoint); publish() }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        // Links are accepted automatically: everything sent over them is end-to-end encrypted with group keys.
        override fun onConnectionInitiated(endpoint: String, info: ConnectionInfo) {
            pending.add(endpoint)
            client.acceptConnection(endpoint, payloads).addOnFailureListener { pending.remove(endpoint) }
        }
        override fun onConnectionResult(endpoint: String, r: ConnectionResolution) {
            pending.remove(endpoint)
            if (r.status.isSuccess) { peers.add(endpoint); publish(); listener?.onLink(this@NearbyTransport) }
            else main.postDelayed({ request(endpoint) }, 3000 + (Math.random() * 3000).toLong())
        }
        override fun onDisconnected(endpoint: String) {
            peers.remove(endpoint)
            publish()
            listener?.onLink(this@NearbyTransport)
            main.postDelayed({ request(endpoint) }, 2000 + (Math.random() * 2000).toLong())
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpoint: String, p: Payload) {
            val b = p.asBytes() ?: return
            listener?.onPacket(b, this@NearbyTransport, endpoint)
        }
        override fun onPayloadTransferUpdate(endpoint: String, u: PayloadTransferUpdate) {}
    }

    override fun setProfile(id: String, name: String, pub: String) { myId = id }
    override fun setSubscriptions(topics: Set<String>) {}

    override fun send(topic: String, raw: ByteArray, retained: Boolean) {
        if (raw.isEmpty() || peers.isEmpty()) return
        client.sendPayload(peers.toList(), Payload.fromBytes(raw))
    }

    override fun relay(raw: ByteArray, src: String?) {
        val to = peers.filter { it != src }
        if (to.isNotEmpty()) client.sendPayload(to, Payload.fromBytes(raw))
    }
}
