package app.pttradio.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.pttradio.core.Transport
import app.pttradio.core.TransportListener
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.concurrent.ConcurrentHashMap

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

    private val serviceId = "app.pttradio.mesh"
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy { Nearby.getConnectionsClient(ctx) }
    private val peers = ConcurrentHashMap.newKeySet<String>()
    private val found = ConcurrentHashMap<String, String>()   // endpoint -> user id
    private val pending = ConcurrentHashMap.newKeySet<String>()
    @Volatile var running = false; private set
    @Volatile private var myId = ""

    val peerCount: Int get() = peers.size

    companion object {
        fun permissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        fun hasPermissions(c: Context) = permissions().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    fun start() = main.post {
        if (running || myId.isEmpty() || !hasPermissions(ctx)) return@post
        running = true
        try {
            client.startAdvertising(myId, serviceId, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener { }
            client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener { }
        } catch (e: Exception) { running = false }
    }

    fun stop() = main.post {
        if (!running) return@post
        running = false
        try { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints() } catch (e: Exception) { }
        peers.clear(); found.clear(); pending.clear()
        listener?.onLink(this)
    }

    private fun request(endpoint: String) {
        if (!running || endpoint in peers || !pending.add(endpoint)) return
        client.requestConnection(myId, endpoint, lifecycle).addOnFailureListener {
            pending.remove(endpoint)
            // Typically a collision or a radio that is busy: try again shortly while the phone is still in range.
            main.postDelayed({ if (running && found.containsKey(endpoint)) request(endpoint) }, 4000)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpoint: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != serviceId) return
            found[endpoint] = info.endpointName
            // Both phones see each other; only the one with the smaller id dials, to avoid crossed requests.
            if (myId < info.endpointName) request(endpoint)
        }
        override fun onEndpointLost(endpoint: String) { found.remove(endpoint) }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        // Links are accepted automatically: everything sent over them is end-to-end encrypted with group keys.
        override fun onConnectionInitiated(endpoint: String, info: ConnectionInfo) { client.acceptConnection(endpoint, payloads) }
        override fun onConnectionResult(endpoint: String, r: ConnectionResolution) {
            pending.remove(endpoint)
            if (r.status.isSuccess) { peers.add(endpoint); listener?.onLink(this@NearbyTransport) }
        }
        override fun onDisconnected(endpoint: String) {
            peers.remove(endpoint)
            listener?.onLink(this@NearbyTransport)
            if (running && found.containsKey(endpoint) && myId < (found[endpoint] ?: "")) main.postDelayed({ request(endpoint) }, 2000)
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
