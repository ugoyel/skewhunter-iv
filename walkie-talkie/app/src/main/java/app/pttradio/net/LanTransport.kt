package app.pttradio.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import app.pttradio.core.Transport
import app.pttradio.core.TransportListener
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Field Wi-Fi link: phones on the same Wi-Fi network - typically one phone's hotspot that the others join, with
 * no internet anywhere - reach each other directly with UDP broadcasts. The hotspot phone is the hub, so everyone
 * within Wi-Fi range of it is connected, which is usually further than Bluetooth reaches between two phones.
 * Packets are the same end-to-end encrypted packets used on every other link.
 */
class LanTransport(private val ctx: Context) : Transport {
    override val name = "lan"
    override val relays = true
    override var listener: TransportListener? = null
    override val isUp: Boolean get() = running && peers.values.any { System.currentTimeMillis() - it < PEER_MS }

    /** Phones heard on the local Wi-Fi in the last 20 s. */
    val peerCount: Int get() = peers.values.count { System.currentTimeMillis() - it < PEER_MS }

    companion object {
        const val PORT = 47290
        const val PEER_MS = 20_000L
        private val PING = "PTTL1".toByteArray()
    }

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lan").apply { isDaemon = true } }
    private val peers = ConcurrentHashMap<String, Long>()                 // ip -> last heard
    private val senders = HashMap<String, DatagramSocket>()                // local ip -> socket used to broadcast from it
    @Volatile private var running = false
    @Volatile private var mine: Set<String> = emptySet()
    @Volatile private var targets: List<Pair<String, InetAddress>> = emptyList()  // local ip -> broadcast address
    private var rx: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var wasUp = false
    private var beatTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var myId = ""

    fun start() = exec.execute {
        if (running) return@execute
        running = true
        try {
            lock = ctx.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("pttradio:lan")?.apply { setReferenceCounted(false) }
        } catch (e: Exception) { }
        try {
            val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(PORT)) }
            rx = s
            thread(isDaemon = true, name = "lan-rx") { receive(s) }
        } catch (e: Exception) { }
        beatTask?.cancel(false)
        beatTask = exec.scheduleWithFixedDelay({ try { beat() } catch (e: Exception) { } }, 0, 4, TimeUnit.SECONDS)
    }

    fun stop() = exec.execute {
        if (!running) return@execute
        running = false
        beatTask?.cancel(false); beatTask = null
        try { rx?.close() } catch (e: Exception) { }
        rx = null
        senders.values.forEach { try { it.close() } catch (e: Exception) { } }
        senders.clear()
        try { if (lock?.isHeld == true) lock?.release() } catch (e: Exception) { }
        peers.clear()
        changed()
    }

    private fun receive(s: DatagramSocket) {
        val buf = ByteArray(65_535)
        while (running && !s.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val ip = p.address?.hostAddress ?: continue
                if (ip in mine) continue
                peers[ip] = System.currentTimeMillis()
                val data = p.data.copyOfRange(p.offset, p.offset + p.length)
                if (data.size >= PING.size && data.copyOf(PING.size).contentEquals(PING)) { exec.execute { changed() }; continue }
                listener?.onPacket(data, this, ip)
            } catch (e: Exception) { if (!running) return }
        }
    }

    /** Every 4 s: find the Wi-Fi / hotspot networks this phone is on and announce it on each. */
    private fun beat() {
        if (!running) return
        val t = ArrayList<Pair<String, InetAddress>>()
        val own = HashSet<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback || ni.isPointToPoint) continue
                // only Wi-Fi client, hotspot and Wi-Fi Direct interfaces: never the mobile network
                val n = ni.name.lowercase()
                if (!(n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("p2p") || n.startsWith("softap"))) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    own += a.hostAddress ?: continue
                    ia.broadcast?.let { t += (a.hostAddress!! to it) }
                }
            }
        } catch (e: Exception) { }
        mine = own
        targets = t
        // only keep the Wi-Fi radio listening for broadcasts while there is a Wi-Fi network to listen on
        try { if (t.isEmpty()) { if (lock?.isHeld == true) lock?.release() } else if (lock?.isHeld == false) lock?.acquire() } catch (e: Exception) { }
        senders.keys.filter { it !in own }.forEach { senders.remove(it)?.close() }
        if (t.isNotEmpty()) broadcast(PING + myId.toByteArray())
        val now = System.currentTimeMillis()
        peers.entries.removeAll { now - it.value > PEER_MS * 3 }
        changed()
    }

    private fun changed() {
        val up = isUp
        if (up != wasUp) { wasUp = up; listener?.onLink(this) }
    }

    private fun socketFor(local: String): DatagramSocket? = senders[local] ?: try {
        DatagramSocket(null).apply {
            broadcast = true
            bind(InetSocketAddress(InetAddress.getByName(local), 0))
            // a Wi-Fi network without internet is not the phone's default route: tie the socket to it explicitly
            try {
                val cm = ctx.getSystemService(ConnectivityManager::class.java)
                cm?.allNetworks?.firstOrNull { net ->
                    cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                        cm.getLinkProperties(net)?.linkAddresses?.any { it.address.hostAddress == local } == true
                }?.bindSocket(this)
            } catch (e: Exception) { }
        }.also { senders[local] = it }
    } catch (e: Exception) { null }

    private fun broadcast(data: ByteArray) {
        for ((local, bcast) in targets) {
            val s = socketFor(local) ?: continue
            try { s.send(DatagramPacket(data, data.size, bcast, PORT)) } catch (e: Exception) { senders.remove(local)?.close() }
        }
    }

    override fun setProfile(id: String, name: String, pub: String) { myId = id }
    override fun setSubscriptions(topics: Set<String>) {}

    override fun send(topic: String, raw: ByteArray, retained: Boolean) {
        if (raw.isEmpty() || !running) return
        exec.execute { broadcast(raw) }
    }

    /** Everyone on this Wi-Fi already heard a packet that came in over it, so only packets from other links are repeated here. */
    override fun relay(raw: ByteArray, src: String?) {
        if (src != null || !running) return
        exec.execute { broadcast(raw) }
    }
}
