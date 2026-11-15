package app.pttradio.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import app.pttradio.core.hex
import app.pttradio.core.idLong
import com.google.android.gms.nearby.connection.BandwidthInfo
import java.nio.ByteBuffer
import java.util.UUID
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
data class NearbyInfo(val running: Boolean = false, val linked: Int = 0, val found: Int = 0, val problem: String? = null, val detail: String? = null,
                      val peers: Map<String, PeerLink> = emptyMap())

/**
 * A directly linked phone. [tech] is the radio carrying the link ("Wi-Fi", "Bluetooth", "Bluetooth LE").
 * [tech] is "Nearby" until Android reports which radio is in use.
 * [pct] is the received Bluetooth signal of that phone on a 0-100 scale; 0 = not heard for several seconds, -1 = not measured.
 */
data class PeerLink(val tech: String, val pct: Int)

/**
 * Tiny Bluetooth LE beacon carrying this phone's id, used only to measure how strongly other phones are
 * received. Nearby Connections does not report signal strength, so it is measured here.
 */
@SuppressLint("MissingPermission")
class Beacon(private val ctx: Context) {
    private val uuid = ParcelUuid(UUID.fromString("7c1f0a52-6b1e-4d7e-9a53-70747472616f"))
    private val MFR = 0xFFFF // Bluetooth SIG id reserved for non-commercial use
    private class Seen(var rssi: Double, var at: Long)
    private val seen = ConcurrentHashMap<String, Seen>()
    private var on = false
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val advCb = object : AdvertiseCallback() {}
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            // current builds put the id in manufacturer data; the previous build used service data
            val d = r.scanRecord?.getManufacturerSpecificData(MFR)?.takeIf { it.size >= 8 } ?: r.scanRecord?.getServiceData(uuid) ?: return
            if (d.size < 8) return
            val id = ByteBuffer.wrap(d).long.hex()
            val s = seen.getOrPut(id) { Seen(r.rssi.toDouble(), 0) }
            // light smoothing: single readings jump by several dB even when nothing moves
            s.rssi = if (System.currentTimeMillis() - s.at > 8000) r.rssi.toDouble() else s.rssi * 0.6 + r.rssi * 0.4
            s.at = System.currentTimeMillis()
        }
    }

    fun start(myId: String) {
        if (on) return
        try {
            val a = adapter ?: return
            if (!a.isEnabled) return
            a.bluetoothLeAdvertiser?.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(false).build(),
                AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
                    .addServiceUuid(uuid).addManufacturerData(MFR, ByteBuffer.allocate(8).putLong(myId.idLong()).array()).build(), advCb)
            a.bluetoothLeScanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(uuid).build(), ScanFilter.Builder().setServiceData(uuid, ByteArray(0)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scanCb)
            on = true
        } catch (e: Exception) { }
    }

    fun stop() {
        if (!on) return
        on = false
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCb) } catch (e: Exception) { }
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { }
        seen.clear()
    }

    /**
     * Signal of [uid] as a percentage: -100 dBm (the weakest a phone can still decode) or weaker = 1 %,
     * -40 dBm (phones side by side) = 100 %, linear in between; 0 % = nothing received for 12 seconds;
     * -1 = this phone has never been measured.
     */
    fun percent(uid: String): Int {
        val s = seen[uid] ?: return -1 // never measured, e.g. that phone runs an older version
        if (System.currentTimeMillis() - s.at > 12_000) return 0
        return (((s.rssi + 100) / 60.0) * 100).toInt().coerceIn(1, 100)
    }
}

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
    private val names = ConcurrentHashMap<String, String>()     // endpoint -> user id, for linked phones
    private val quality = ConcurrentHashMap<String, Int>()      // endpoint -> Nearby bandwidth quality
    private val beacon = Beacon(ctx)
    private val refresh = object : Runnable { override fun run() { if (running) { publish(); main.postDelayed(this, 2000) } } }
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
        val links = HashMap<String, PeerLink>()
        for (e in peers) {
            val uid = names[e] ?: found[e] ?: continue
            // Nearby reports link speed class: 3 = a Wi-Fi link, 2 = classic Bluetooth, 1 = Bluetooth LE
            // With Bluetooth switched off a live link can only be Wi-Fi. Otherwise claim a radio only once Android says.
            val tech = when {
                quality[e] == BandwidthInfo.Quality.HIGH || !bluetoothOn() -> "Wi-Fi"
                quality[e] == BandwidthInfo.Quality.LOW -> "Bluetooth LE"
                quality[e] == BandwidthInfo.Quality.MEDIUM -> "Bluetooth"
                else -> "Nearby"
            }
            links[uid] = PeerLink(tech, beacon.percent(uid))
        }
        info.value = NearbyInfo(running, peers.size, found.keys.count { it !in peers }, problem, detail, links)
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
        beacon.start(myId)
        main.removeCallbacks(refresh); main.postDelayed(refresh, 2000)
        publish()
    }

    /** Scanning that found nobody is restarted, e.g. after Bluetooth or Location was switched on. */
    fun restartIfIdle() = main.post {
        if (!running || peers.isNotEmpty() || pending.isNotEmpty()) return@post
        try { client.stopAdvertising(); client.stopDiscovery() } catch (e: Exception) { }
        beacon.stop()
        advertising = false; discovering = false
        found.clear()
    }

    private fun halt() {
        running = false
        advertising = false; discovering = false
        try { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints() } catch (e: Exception) { }
        peers.clear(); found.clear(); pending.clear(); names.clear(); quality.clear()
        beacon.stop()
        main.removeCallbacks(refresh)
        publish()
        listener?.onLink(this)
    }

    fun stop() = main.post { if (!running) publish() else halt() }

    /** Drops every link and starts looking again from scratch: manual refresh, or Bluetooth / Wi-Fi was toggled. */
    fun restart() = main.post { if (running) { halt(); start() } }

    private val restartSoon = Runnable { restart() }

    init {
        // Reconnect as soon as a radio is switched back on instead of waiting for the next scan cycle.
        try {
            androidx.core.content.ContextCompat.registerReceiver(ctx, object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: android.content.Intent) {
                    val st = i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, i.getIntExtra(android.net.wifi.WifiManager.EXTRA_WIFI_STATE, -1))
                    val settled = st == android.bluetooth.BluetoothAdapter.STATE_ON || st == android.bluetooth.BluetoothAdapter.STATE_OFF ||
                        st == android.net.wifi.WifiManager.WIFI_STATE_ENABLED || st == android.net.wifi.WifiManager.WIFI_STATE_DISABLED
                    publish()
                    if (settled) { main.removeCallbacks(restartSoon); main.postDelayed(restartSoon, 400) }
                }
            }, android.content.IntentFilter().apply {
                addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) { }
    }

    private fun request(endpoint: String) {
        if (!running || endpoint in peers || !found.containsKey(endpoint) || !pending.add(endpoint)) return
        client.requestConnection(myId, endpoint, lifecycle).addOnFailureListener { e ->
            pending.remove(endpoint)
            if ((e as? ApiException)?.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                peers.add(endpoint); publish(); listener?.onLink(this); return@addOnFailureListener
            }
            // Usually both phones dialled at once or the radio was busy: try again while the phone is in range.
            main.postDelayed({ request(endpoint) }, 1200 + (Math.random() * 1500).toLong())
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
            else main.postDelayed({ request(endpoint) }, 1500 + (Math.random() * 1000).toLong())
        }
        override fun onEndpointLost(endpoint: String) { found.remove(endpoint); publish() }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        // Links are accepted automatically: everything sent over them is end-to-end encrypted with group keys.
        override fun onConnectionInitiated(endpoint: String, info: ConnectionInfo) {
            pending.add(endpoint)
            names[endpoint] = info.endpointName
            client.acceptConnection(endpoint, payloads).addOnFailureListener { pending.remove(endpoint) }
        }
        override fun onConnectionResult(endpoint: String, r: ConnectionResolution) {
            pending.remove(endpoint)
            if (r.status.isSuccess) { peers.add(endpoint); publish(); listener?.onLink(this@NearbyTransport) }
            else main.postDelayed({ request(endpoint) }, 1200 + (Math.random() * 1500).toLong())
        }
        override fun onBandwidthChanged(endpoint: String, b: BandwidthInfo) { quality[endpoint] = b.quality; publish() }
        override fun onDisconnected(endpoint: String) {
            peers.remove(endpoint); quality.remove(endpoint)
            publish()
            listener?.onLink(this@NearbyTransport)
            main.postDelayed({ request(endpoint) }, 800 + (Math.random() * 800).toLong())
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
