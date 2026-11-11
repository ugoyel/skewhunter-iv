package io.github.ugoyel.hearby.client

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import io.github.ugoyel.hearby.LocalNet
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.core.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredHost(val name: String, val address: String, val port: Int, val listeners: Int)

/**
 * Finds hosts on the local network.
 *
 * Two mechanisms feed one list:
 *  - mDNS / DNS-SD (`_hearby._udp`) through NsdManager, the same service iOS browses with Bonjour;
 *  - a tiny UDP probe (DISCOVER) sent to the broadcast addresses and to the Wi-Fi gateway / DHCP
 *    server. On a phone hotspot the gateway *is* the host, so this finds it even on networks and
 *    Android versions where mDNS is unreliable.
 *
 * A host is only listed once it has actually answered a probe, so the list never shows something
 * that cannot be reached, and entries disappear a few seconds after a host stops answering.
 */
class HostDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null

    /** Addresses learned from mDNS; probed directly from then on. */
    private val mdnsTargets = ConcurrentHashMap<InetSocketAddress, Boolean>()

    private class Seen(val host: DiscoveredHost, val atNs: Long)

    fun start() {
        if (running) return
        running = true
        thread = Thread(::probeLoop, "hearby-discovery").also { it.start() }
        startNsd()
    }

    fun stop() {
        if (!running) return
        running = false
        stopNsd()
        socket?.close()
        thread?.join(500)
        thread = null
        mdnsTargets.clear()
        _hosts.value = emptyList()
    }

    // ---- UDP probe --------------------------------------------------------------------------------

    private fun probeLoop() {
        val sock = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 250
            }
        } catch (e: Exception) {
            Log.w(TAG, "discovery socket failed", e)
            return
        }
        socket = sock
        LocalNet.bindToWifi(appContext, sock)

        val seen = HashMap<String, Seen>()
        val buf = ByteArray(Proto.MAX_DATAGRAM)
        val packet = DatagramPacket(buf, buf.size)
        var nonce = System.nanoTime() and 0xFFFFFFFFL
        var lastProbeNs = 0L

        while (running) {
            val now = System.nanoTime()
            if (now - lastProbeNs >= PROBE_INTERVAL_NS) {
                lastProbeNs = now
                nonce = (nonce + 1) and 0xFFFFFFFFL
                val probe = Wire.encodeDiscover(nonce)
                val targets = HashSet<InetSocketAddress>()
                for (address in LocalNet.probeTargets(appContext)) targets.add(InetSocketAddress(address, Proto.PORT))
                targets.addAll(mdnsTargets.keys)
                for (target in targets) {
                    try {
                        sock.send(DatagramPacket(probe, probe.size, target))
                    } catch (e: Exception) {
                        // no route on this interface; others may work
                    }
                }
            }
            try {
                packet.setData(buf, 0, buf.size)
                sock.receive(packet)
                if (Wire.typeOf(buf, packet.length) == Proto.TYPE_DISCOVER_RESP) {
                    val m = Wire.decodeDiscoverResp(buf, packet.length)
                    val from = packet.address?.hostAddress
                    if (m != null && from != null) {
                        val key = "$from:${packet.port}"
                        seen[key] = Seen(DiscoveredHost(m.hostName.ifBlank { from }, from, packet.port, m.listeners), System.nanoTime())
                    }
                }
            } catch (e: SocketTimeoutException) {
                // nothing this round
            } catch (e: Exception) {
                if (!running) break
            }
            val cutoff = System.nanoTime() - HOST_EXPIRY_NS
            seen.values.removeAll { it.atNs < cutoff }
            val list = seen.values.map { it.host }.sortedBy { it.name.lowercase() }
            if (list != _hosts.value) _hosts.value = list
        }
        sock.close()
    }

    // ---- mDNS -------------------------------------------------------------------------------------

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun startNsd() {
        val manager = nsd ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed to start: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(resolveQueue) { resolveQueue.add(service) }
                resolveNext()
            }
        }
        try {
            manager.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discoveryListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery threw", e)
        }
    }

    private fun stopNsd() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsd?.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            // never started
        }
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
    }

    /** NsdManager resolves one service at a time (older releases fail with ALREADY_ACTIVE otherwise). */
    private fun resolveNext() {
        val manager = nsd ?: return
        val service = synchronized(resolveQueue) {
            if (resolving || !running) return
            val next = resolveQueue.poll() ?: return
            resolving = true
            next
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()

            override fun onServiceResolved(info: NsdServiceInfo) {
                val address = hostAddress(info)
                if (address is Inet4Address && info.port > 0) mdnsTargets[InetSocketAddress(address, info.port)] = true
                done()
            }

            private fun done() {
                synchronized(resolveQueue) { resolving = false }
                resolveNext()
            }
        }
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(service, listener)
        } catch (e: Exception) {
            synchronized(resolveQueue) { resolving = false }
        }
    }

    private fun hostAddress(info: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
        }
        @Suppress("DEPRECATION")
        return info.host
    }

    companion object {
        private const val TAG = "HearbyDiscovery"
        private const val PROBE_INTERVAL_NS = 1_500_000_000L
        private const val HOST_EXPIRY_NS = 5_000_000_000L
    }
}
