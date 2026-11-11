package com.cleartether.app.core.proxy

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Receives traffic accounting from the proxy engine. Must be thread-safe and cheap: it's on the hot path. */
interface TrafficMeter {
    fun onConnectionOpened(client: InetAddress) {}
    fun onConnectionClosed(client: InetAddress) {}

    /** [uploaded] = client -> internet bytes, [downloaded] = internet -> client bytes. */
    fun onTraffic(client: InetAddress, uploaded: Long, downloaded: Long)

    companion object {
        val NONE = object : TrafficMeter {
            override fun onTraffic(client: InetAddress, uploaded: Long, downloaded: Long) {}
        }
    }
}

/** Snapshot of one tethered device as seen by the proxy. */
data class ClientStats(
    val address: String,
    val bytesUploaded: Long,
    val bytesDownloaded: Long,
    val activeConnections: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

/** Per-client counters, keyed by IP address. */
class ClientRegistry(private val clock: () -> Long = System::currentTimeMillis) : TrafficMeter {

    private class Entry(val firstSeen: Long) {
        val up = AtomicLong()
        val down = AtomicLong()
        val active = AtomicInteger()
        @Volatile var lastSeen: Long = firstSeen
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun entry(client: InetAddress): Entry {
        val key = client.hostAddress ?: client.toString()
        return entries.getOrPut(key) { Entry(clock()) }
    }

    override fun onConnectionOpened(client: InetAddress) {
        entry(client).apply { active.incrementAndGet(); lastSeen = clock() }
    }

    override fun onConnectionClosed(client: InetAddress) {
        entry(client).apply { if (active.decrementAndGet() < 0) active.set(0); lastSeen = clock() }
    }

    override fun onTraffic(client: InetAddress, uploaded: Long, downloaded: Long) {
        entry(client).apply {
            if (uploaded > 0) up.addAndGet(uploaded)
            if (downloaded > 0) down.addAndGet(downloaded)
            lastSeen = clock()
        }
    }

    fun snapshot(): List<ClientStats> = entries.map { (ip, e) ->
        ClientStats(ip, e.up.get(), e.down.get(), e.active.get(), e.firstSeen, e.lastSeen)
    }.sortedByDescending { it.bytesDownloaded + it.bytesUploaded }

    fun clear() = entries.clear()
}

/** Fans traffic events out to several meters (session quota and per-client stats). */
class CompositeTrafficMeter(private vararg val meters: TrafficMeter) : TrafficMeter {
    override fun onConnectionOpened(client: InetAddress) = meters.forEach { it.onConnectionOpened(client) }
    override fun onConnectionClosed(client: InetAddress) = meters.forEach { it.onConnectionClosed(client) }
    override fun onTraffic(client: InetAddress, uploaded: Long, downloaded: Long) =
        meters.forEach { it.onTraffic(client, uploaded, downloaded) }
}

/** Resolves raw DNS wire-format queries on the host's network (DNS-over-HTTP endpoint). */
fun interface DnsBackend {
    suspend fun query(message: ByteArray): ByteArray
}
