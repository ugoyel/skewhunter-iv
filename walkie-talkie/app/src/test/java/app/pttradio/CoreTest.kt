package app.pttradio

import app.pttradio.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

class MemKV : KV {
    val m = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(k: String) = m[k]
    override fun put(k: String, v: String?) { if (v == null) m.remove(k) else m[k] = v }
}

class FakeAudio : AudioIO {
    @Volatile var onFrame: ((ShortArray) -> Unit)? = null
    val played = CopyOnWriteArrayList<ShortArray>()
    @Volatile var ends = 0
    override fun startCapture(onFrame: (ShortArray) -> Unit): Boolean { this.onFrame = onFrame; return true }
    override fun stopCapture() { onFrame = null }
    override fun play(pcm: ShortArray) { played.add(pcm) }
    override fun endOfTalk(beep: Boolean) { ends++ }
}

/** In-memory broker with MQTT-like topics, wildcards and retained messages. */
class Bus {
    val nodes = CopyOnWriteArrayList<BusTransport>()
    val retained = HashMap<String, ByteArray>()
    val log = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    val profiles = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    fun match(sub: String, topic: String): Boolean {
        val a = sub.split("/"); val b = topic.split("/")
        return a.size == b.size && a.indices.all { a[it] == "+" || a[it] == b[it] }
    }
}

class BusTransport(val bus: Bus, override val relays: Boolean = false) : Transport {
    override val name = if (relays) "nearby" else "net"
    override var isUp = true
    override var listener: TransportListener? = null
    var subs: Set<String> = emptySet()
    var id = ""
    val peers = CopyOnWriteArrayList<BusTransport>() // mesh neighbours when relays
    init { bus.nodes.add(this) }
    override fun setProfile(id: String, name: String, pub: String) {
        this.id = id
        if (relays) return
        bus.profiles[id] = name to pub
        bus.nodes.forEach { if (it !== this && !it.relays) it.listener?.onUser(id, name, pub, true, System.currentTimeMillis()) }
        bus.profiles.forEach { (k, v) -> if (k != id) listener?.onUser(k, v.first, v.second, true, System.currentTimeMillis()) }
    }
    override fun setSubscriptions(topics: Set<String>) {
        val fresh = topics - subs
        subs = topics
        if (!relays) synchronized(bus.retained) { bus.retained.toMap() }.forEach { (t, d) -> if (fresh.any { bus.match(it, t) }) listener?.onPacket(d, this, null) }
    }
    override fun send(topic: String, raw: ByteArray, retained: Boolean) {
        if (relays) { if (raw.isNotEmpty()) peers.forEach { if (it.isUp) it.listener?.onPacket(raw, it, id) }; return }
        bus.log.add(topic to raw)
        if (retained) synchronized(bus.retained) { if (raw.isEmpty()) bus.retained.remove(topic) else bus.retained[topic] = raw }
        if (raw.isNotEmpty()) bus.nodes.forEach { n -> if (n !== this && !n.relays && n.isUp && n.subs.any { bus.match(it, topic) }) n.listener?.onPacket(raw, n, null) }
    }
    override fun relay(raw: ByteArray, src: String?) { peers.forEach { if (it.id != src && it.isUp) it.listener?.onPacket(raw, it, id) } }
}

class Node(bus: Bus, name: String, relays: Boolean = false) {
    val kv = MemKV(); val audio = FakeAudio(); val t = BusTransport(bus, relays)
    val events = CopyOnWriteArrayList<Event>()
    val core = RadioCore(kv, audio).also { it.onEvent = { e -> events.add(e) }; it.start(); it.attach(t); it.setName(name) }
    val s get() = core.state.value
    val id get() = s.me!!.id
}

fun await(what: String, ms: Long = 4000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
    fail("timeout: $what")
}

fun tone(n: Int = 960) = ShortArray(n) { (8000 * sin(2 * PI * 440 * it / 16000)).toInt().toShort() }

class CoreTest {
    @Test fun adpcmRoundTrip() {
        val st = Adpcm.State()
        var err = 0.0; var n = 0
        repeat(5) { f ->
            val pcm = ShortArray(960) { (9000 * sin(2 * PI * 300 * (it + f * 960) / 16000)).toInt().toShort() }
            val e = Adpcm.encode(pcm, st)
            assertEquals(483, e.size)
            val d = Adpcm.decode(e, 0, e.size)
            assertEquals(960, d.size)
            if (f > 0) for (i in pcm.indices) { err += Math.abs(pcm[i] - d[i]); n++ }
        }
        assertTrue("mean error ${err / n}", err / n < 400)
    }

    @Test fun cryptoAndPacket() {
        val (pa, sa) = Crypto.newKeyPair(); val (pb, sb) = Crypto.newKeyPair()
        assertArrayEquals(Crypto.shared(sa, pb), Crypto.shared(sb, pa))
        val k = Crypto.randomKey()
        val p = Packet(2, 3, 77, 5, 9, 11, ByteArray(0))
        p.body = Crypto.seal(k, "hi".toByteArray(), p.aad())
        val q = Packet.parse(p.encode(1))!!
        assertEquals(1, q.ttl); assertEquals(9, q.ch)
        assertEquals("hi", String(Crypto.open(k, q.body, 0, q.aad())!!))
        assertNull(Crypto.open(Crypto.randomKey(), q.body, 0, q.aad()))
        assertNull(Crypto.open(k, q.body, 0, Packet(2, 3, 77, 5, 8, 11, ByteArray(0)).aad()))
    }

    private fun trio(bus: Bus = Bus()): Triple<Node, Node, Node> {
        val a = Node(bus, "Alpha"); val b = Node(bus, "Bravo"); val c = Node(bus, "Charlie")
        await("directory") { a.s.users.size == 2 && b.s.users.size == 2 && c.s.users.size == 2 }
        a.core.createGroup("Ops", listOf(b.id, c.id))
        await("invites") { b.s.invites.size == 1 && c.s.invites.size == 1 }
        await("invite event") { b.events.any { it is Event.InviteReceived } }
        b.core.acceptInvite(b.s.invites[0].group.id); c.core.acceptInvite(c.s.invites[0].group.id)
        await("joined") { a.s.groups[0].members.all { it.joined } && b.s.tunedGroup != null && c.s.tunedGroup != null }
        return Triple(a, b, c)
    }

    private fun talk(n: Node, frames: Int = 3) {
        Thread.sleep(150) // let channel subscriptions settle
        n.core.pttDown()
        await("tx") { n.s.transmitting }
        repeat(frames) { n.audio.onFrame!!(tone()) }
        n.core.pttUp()
        await("tx end") { !n.s.transmitting }
    }

    @Test fun groupPttTextAndExpiry() {
        val bus = Bus()
        val (a, b, c) = trio(bus)
        val gid = a.s.groups[0].id
        // accepted invitations are wiped from the server
        await("invite cleared") { synchronized(bus.retained) { bus.retained.isEmpty() } }
        await("presence") { a.s.occupants(gid, 0).size == 3 }

        talk(a)
        await("b hears") { b.audio.played.size == 3 && b.audio.ends == 1 }
        await("c hears") { c.audio.played.size == 3 }
        assertEquals(960, b.audio.played[0].size)
        assertEquals(0, a.audio.played.size)

        // nothing readable and nothing retained for voice/text on the wire
        b.core.setTtl(5)
        await("ttl") { b.s.ttlSec == 5 }
        a.core.sendText("meet at gate 4")
        await("text") { b.s.messages.size == 1 && c.s.messages.size == 1 && a.s.messages.size == 1 }
        assertEquals("meet at gate 4", b.s.messages[0].text); assertEquals("Alpha", b.s.messages[0].from)
        assertTrue(bus.log.none { String(it.second, Charsets.ISO_8859_1).contains("gate 4") })
        assertTrue(synchronized(bus.retained) { bus.retained.isEmpty() })
        assertTrue((a.kv.m.values + b.kv.m.values).none { it.contains("gate 4") })
        await("expired on b", 8000) { b.s.messages.isEmpty() }
        assertEquals(1, c.s.messages.size) // c keeps the default 2 minutes
    }

    @Test fun channelsSummonAndLock() {
        val (a, b, c) = trio()
        val gid = a.s.groups[0].id
        a.core.tune(gid, 9)
        await("a on 9") { a.s.tunedCh == 9 && b.s.occupants(gid, 9) == listOf(a.id) }
        talk(a)
        Thread.sleep(200)
        assertEquals("main room must not hear channel 9", 0, b.audio.played.size)

        a.core.summon(b.id)
        await("b moved") { b.s.tunedCh == 9 }
        assertTrue(b.events.any { it is Event.Summoned && it.ch == 9 && it.autoTuned })
        await("a sees b") { a.s.occupants(gid, 9).size == 2 }

        a.core.lockChannel()
        await("locked everywhere") { listOf(a, b, c).all { it.s.lockOf(gid, 9)?.by == a.id } }
        c.core.tune(gid, 9)
        await("c refused") { c.s.notice?.contains("LOCKED") == true }
        assertEquals(0, c.s.tunedCh)
        talk(a)
        await("b hears locked ch") { b.audio.played.size == 3 }
        talk(b, 2)
        await("a hears b") { a.audio.played.size == 2 }

        // b cannot unlock, a can; after that c may enter
        b.core.unlockChannel()
        Thread.sleep(150)
        assertNotNull(c.s.lockOf(gid, 9))
        a.core.summon(c.id) // owner of the lock brings c in
        await("c in locked ch") { c.s.tunedCh == 9 }
        talk(a, 1)
        await("c hears") { c.audio.played.size == 1 }
        a.core.unlockChannel()
        await("unlocked") { listOf(a, b, c).all { it.s.lockOf(gid, 9) == null } }
        // leaving the channel releases a lock
        a.core.lockChannel()
        await("relock") { c.s.lockOf(gid, 9) != null }
        a.core.tune(gid, 0)
        await("released") { c.s.lockOf(gid, 9) == null && b.s.lockOf(gid, 9) == null }
    }

    @Test fun busyChannelAndLeave() {
        val (a, b, c) = trio()
        val gid = a.s.groups[0].id
        a.core.pttDown(); await("tx") { a.s.transmitting }
        a.audio.onFrame!!(tone())
        await("b rx") { b.s.talker == "Alpha" }
        b.core.pttDown()
        await("busy") { b.s.notice == "CHANNEL BUSY" }
        assertFalse(b.s.transmitting)
        a.core.pttUp()
        await("idle") { b.s.talker == null }
        c.core.leaveGroup(gid)
        await("left") { c.s.groups.isEmpty() && a.s.groups[0].members.size == 2 }
    }

    @Test fun offlineMeshRelaysAcrossHops() {
        val bus = Bus()
        val a = Node(bus, "Alpha", true); val b = Node(bus, "Bravo", true); val c = Node(bus, "Charlie", true)
        // a <-> b <-> c : a and c are out of each other's radio range
        a.t.peers.add(b.t); b.t.peers.add(a.t); b.t.peers.add(c.t); c.t.peers.add(b.t)
        listOf(a, b, c).forEach { it.core.onLink(it.t) }
        await("hello over mesh") { a.s.users.containsKey(c.id) && c.s.users.containsKey(a.id) && a.s.nearbyUp }
        a.core.createGroup("Field", listOf(c.id))
        await("invite via relay") { c.s.invites.size == 1 }
        c.core.acceptInvite(c.s.invites[0].group.id)
        await("joined") { a.s.groups[0].members.all { it.joined } }
        talk(a)
        await("c hears through b") { c.audio.played.size == 3 }
        assertEquals(0, b.audio.played.size)
        c.core.sendText("copy")
        await("text back") { a.s.messages.any { it.text == "copy" } }
    }

    @Test fun stateSurvivesRestartButMessagesDoNot() {
        val (a, b, _) = trio()
        a.core.sendText("secret")
        await("text") { b.s.messages.size == 1 }
        val again = RadioCore(b.kv, FakeAudio()).also { it.start() }
        await("reload") { again.state.value.groups.size == 1 && again.state.value.me?.name == "Bravo" }
        assertEquals(b.s.tunedGroup, again.state.value.tunedGroup)
        assertTrue(again.state.value.messages.isEmpty())
    }
}
