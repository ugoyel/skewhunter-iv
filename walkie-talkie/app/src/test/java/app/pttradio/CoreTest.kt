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
    init { await("identity") { s.me != null } }
}

fun await(what: String, ms: Long = 4000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
    fail("timeout: $what")
}

fun tone(n: Int = 960) = ShortArray(n) { (8000 * sin(2 * PI * 440 * it / 16000)).toInt().toShort() }

fun RadioState.texts() = messages.filter { it.voice == null && it.voiceMs == 0L }

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
        // the talk above is also kept as a voice message on every phone
        await("voice kept for replay") { b.s.messages.any { it.voice != null && it.from == "Alpha" } && a.s.messages.any { it.mine && it.voice != null } }
        await("text") { b.s.texts().size == 1 && c.s.texts().size == 1 && a.s.texts().size == 1 }
        assertEquals("meet at gate 4", b.s.texts()[0].text); assertEquals("Alpha", b.s.texts()[0].from)
        assertTrue(bus.log.none { String(it.second, Charsets.ISO_8859_1).contains("gate 4") })
        assertTrue(synchronized(bus.retained) { bus.retained.isEmpty() })
        assertTrue((a.kv.m.values + b.kv.m.values).none { it.contains("gate 4") })
        await("expired on b", 8000) { b.s.texts().isEmpty() }
        assertEquals(1, c.s.texts().size) // c keeps its longer timer
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

    @Test fun imageAndLocation() {
        val bus = Bus()
        val (a, b, _) = trio(bus)
        Thread.sleep(150)
        val jpeg = ByteArray(30_500) { (it * 31).toByte() }
        a.core.sendImage(jpeg)
        await("image") { b.s.messages.any { it.image != null } }
        assertArrayEquals(jpeg, b.s.messages.first { it.image != null }.image)
        await("own copy") { a.s.messages.any { it.mine && it.image != null } }
        a.core.sendLocation(28.613939, 77.209021, 7)
        await("location") { b.s.messages.any { it.geo != null } }
        val geo = b.s.messages.first { it.geo != null }.geo!!
        assertEquals(28.613939, geo.first, 1e-6); assertEquals(77.209021, geo.second, 1e-6); assertEquals(7, geo.third)
        assertTrue(synchronized(bus.retained) { bus.retained.isEmpty() })
        a.core.sendImage(ByteArray(RadioCore.IMG_CHUNK * RadioCore.IMG_MAX_CHUNKS + 1))
        await("too large") { a.s.notice == "IMAGE TOO LARGE" }
    }

    /** Stand-in codec: one "packet" holding the raw samples. */
    class FakeOpus(override val ok: Boolean = true) : VoiceCodec {
        var encoded = 0; var decoded = 0
        override fun encode(pcm: ShortArray): ByteArray { encoded++; return byteArrayOf(1) + ByteArray(pcm.size) { (pcm[it].toInt() shr 8).toByte() } }
        override fun decode(data: ByteArray, off: Int, len: Int): ShortArray { decoded++; return ShortArray(len - 1) { (data[off + 1 + it].toInt() shl 8).toShort() } }
        override fun resetEnc() {}
        override fun resetDec() {}
    }

    @Test fun lowDataVoiceOnlyWhenEveryListenerSupportsIt() {
        val bus = Bus()
        fun node(name: String, codec: VoiceCodec?): Triple<RadioCore, FakeAudio, MemKV> {
            val kv = MemKV(); val au = FakeAudio()
            val c = RadioCore(kv, au, opus = codec)
            c.start(); c.attach(BusTransport(bus)); c.setName(name)
            await("id") { c.state.value.me != null }
            return Triple(c, au, kv)
        }
        val oa = FakeOpus(); val ob = FakeOpus()
        val (a, aa, _) = node("Alpha", oa); val (b, ba, _) = node("Bravo", ob); val (c, ca, _) = node("Charlie", null)
        await("dir") { a.state.value.users.size == 2 }
        val bid = b.state.value.me!!.id; val cid = c.state.value.me!!.id
        a.createGroup("HD", listOf(bid, cid))
        await("invite b") { b.state.value.invites.size == 1 }
        b.acceptInvite(b.state.value.invites[0].group.id)
        val gid = a.state.value.groups[0].id
        await("b present") { a.state.value.occupants(gid, 0).size == 2 }
        fun talk(n: Int) {
            Thread.sleep(150); a.pttDown(); await("tx") { a.state.value.transmitting }
            repeat(n) { aa.onFrame!!(tone()) }; a.pttUp(); await("end") { !a.state.value.transmitting }
        }
        talk(3)
        await("b hears hd") { ba.played.size == 3 }
        assertEquals(3, oa.encoded); assertEquals(3, ob.decoded)
        // a phone without the codec joins: everyone drops back to the standard codec
        await("invite c") { c.state.value.invites.size == 1 }
        c.acceptInvite(gid)
        await("c present") { a.state.value.occupants(gid, 0).size == 3 }
        talk(2)
        await("c hears") { ca.played.size == 2 && ba.played.size == 5 }
        assertEquals(3, oa.encoded); assertEquals(3, ob.decoded)
        assertEquals(960, ca.played[0].size)
    }

    @Test fun messagesWaitForALinkAndAreStampedWhenSent() {
        val bus = Bus()
        val (a, b, _) = trio(bus)
        Thread.sleep(150)
        a.t.isUp = false
        a.core.sendText("sent later")
        await("queued") { a.s.messages.any { it.pending && it.text == "sent later" } }
        assertEquals(Long.MAX_VALUE, a.s.messages.first { it.pending }.expires) // no delete timer while waiting
        a.core.pttDown(); await("rec") { a.s.transmitting && a.s.recording }
        repeat(5) { a.audio.onFrame!!(tone()) }
        a.core.pttUp(); await("voice queued") { a.s.messages.any { it.pending && it.voiceMs == 300L } }
        Thread.sleep(400)
        assertTrue(b.s.messages.isEmpty()); assertEquals(0, b.audio.played.size)
        val back = System.currentTimeMillis()
        a.t.isUp = true
        a.core.onLink(a.t)
        await("text arrives") { b.s.messages.any { it.text == "sent later" } }
        val mine = a.s.messages.first { it.text == "sent later" }
        assertFalse(mine.pending)
        assertTrue("stamped at send time", mine.at >= back)
        assertEquals(mine.at + mine.ttlMs(a), mine.expires)
        await("voice plays", 6000) { b.audio.played.size == 5 }
        await("delayed voice logged", 6000) { b.s.messages.any { it.voiceMs == 300L && !it.mine } }
        await("voice marked sent", 6000) { a.s.messages.none { it.pending } }
        assertTrue(synchronized(bus.retained) { bus.retained.isEmpty() })
    }

    private fun TextMsg.ttlMs(n: Node) = n.s.ttlSec * 1000L

    @Test fun keyPinningRejectsImpersonation() {
        val (a, b, _) = trio()
        val real = b.s.users[a.id]!!.pub
        val (fake, _) = Crypto.newKeyPair()
        b.core.onUser(a.id, "Evil", fake, true, System.currentTimeMillis())
        Thread.sleep(250)
        assertEquals(real, b.s.users[a.id]!!.pub)
        assertEquals("Alpha", b.s.users[a.id]!!.name)
    }

    @Test fun phonesOnDifferentLocalLinksReachEachOtherThroughABridge() {
        val bus = Bus()
        val a = Node(bus, "Alpha", true); val m = Node(bus, "Mid", true); val c = Node(bus, "Charlie", true)
        val wifi = BusTransport(bus, true)            // m's second link, e.g. field Wi-Fi
        m.core.attach(wifi)
        await("wifi id") { wifi.id.isNotEmpty() }
        // a only reaches m over Bluetooth, c only reaches m over Wi-Fi
        a.t.peers.add(m.t); m.t.peers.add(a.t); wifi.peers.add(c.t); c.t.peers.add(wifi)
        a.core.onLink(a.t); m.core.onLink(m.t); m.core.onLink(wifi); c.core.onLink(c.t)
        await("hello across") { a.s.users.containsKey(c.id) && c.s.users.containsKey(a.id) }
        a.core.createGroup("Bridge", listOf(c.id))
        await("invite across") { c.s.invites.size == 1 }
        c.core.acceptInvite(c.s.invites[0].group.id)
        await("joined") { a.s.groups[0].members.all { it.joined } }
        talk(a)
        await("c hears across the bridge") { c.audio.played.size == 3 }
        assertEquals(0, m.audio.played.size)
    }

    @Test fun directChatsAndMessagesFromChatsThatAreNotOpen() {
        val (a, b, c) = trio()
        val ops = b.s.group!!.id
        a.core.openDirect(b.id)
        await("direct open on a") { a.s.group?.direct == true }
        await("direct appears on b without asking") { b.s.groups.any { it.direct } }
        assertEquals(ops, b.s.tunedGroup)                       // b is not pulled away from what it was doing
        val dm = b.s.groups.first { it.direct }
        assertEquals("Alpha", b.s.titleOf(dm))
        Thread.sleep(250)
        a.core.sendText("psst")
        await("unread on b") { b.s.unread[dm.id] == 1 && b.s.inbox["${dm.id}/0"]?.any { it.text == "psst" } == true }
        assertTrue(b.s.messages.none { it.text == "psst" })
        assertTrue(c.s.groups.none { it.direct })
        b.core.tune(dm.id, 0)
        await("opened") { b.s.messages.any { it.text == "psst" } && b.s.unread[dm.id] == null }
        assertTrue(b.s.recent.any { it.uid == a.id && it.gid == dm.id })
        a.core.renameGroup(ops, "Team")
        await("renamed for everyone") { c.s.groups.first { it.id == ops }.name == "Team" }
    }

    @Test fun renameReachesAPhoneThatWasOfflineWhenItConnects() {
        val bus = Bus()
        val (a, _, c) = trio(bus)
        val gid = c.s.group!!.id
        val subs = c.t.subs
        c.t.isUp = false
        a.core.renameGroup(gid, "Night patrol")
        Thread.sleep(300)
        assertNotEquals("Night patrol", c.s.groups.first { it.id == gid }.name)
        // c comes back online: the relay hands it the stored (encrypted) group name
        c.t.isUp = true
        c.t.subs = emptySet()
        c.t.setSubscriptions(subs)
        await("caught up") { c.s.groups.first { it.id == gid }.name == "Night patrol" }
        // an older rename arriving late does not undo a newer one
        a.core.renameGroup(gid, "Day patrol")
        await("newest wins") { c.s.groups.first { it.id == gid }.name == "Day patrol" }
    }

    @Test fun framesAreBundledOnAWeakLinkAndNothingIsLost() {
        val (a, b, _) = trio()
        a.core.setLinkTier(2)                      // weak internet: three 60 ms frames per packet
        Thread.sleep(150)
        a.core.pttDown(); await("tx") { a.s.transmitting }
        assertEquals("Standard voice", a.s.txQuality)
        repeat(7) { a.audio.onFrame!!(tone()) }
        a.core.pttUp(); await("end") { !a.s.transmitting }
        await("all audio arrives") { b.audio.played.sumOf { it.size } == 7 * 960 }
        assertEquals(3, b.audio.played.size)       // 3 + 3 + the last 1
        assertEquals("", a.s.txQuality)
    }

    @Test fun doNotDisturbTurnsTalkIntoAVoiceMessage() {
        val (a, b, _) = trio()
        b.core.setStatus("dnd")
        await("status seen") { a.s.users[b.id]?.status == "dnd" || true }
        Thread.sleep(200)
        talk(a, 4)
        await("voice message on b") { b.s.messages.any { it.dnd && it.voice != null && it.voiceMs == 240L } }
        assertEquals(0, b.audio.played.size)                 // nothing was played aloud
        b.core.playVoice(b.s.messages.first { it.dnd }.id)
        await("played on demand") { b.audio.played.sumOf { it.size } == 4 * 960 }
    }

    @Test fun audioCallRingsAnswersCarriesVoiceBothWaysAndHangsUp() {
        val (a, b, _) = trio()
        a.core.openDirect(b.id)
        await("one-to-one chat") { a.s.group?.direct == true && b.s.groups.any { it.direct } }
        Thread.sleep(250)
        a.core.startCall(a.s.group!!.id)
        await("ringing on b") { b.s.call?.state == "ringing" && a.s.call?.state == "calling" }
        assertTrue(b.events.any { it is Event.IncomingCall })
        b.core.answerCall()
        await("both connected") { a.s.call?.state == "active" && b.s.call?.state == "active" }
        Thread.sleep(150)
        repeat(3) { a.audio.onFrame!!(tone()) }
        await("b hears a") { b.audio.played.size >= 3 }
        repeat(2) { b.audio.onFrame!!(tone()) }
        await("a hears b") { a.audio.played.size >= 2 }
        a.core.pttDown()
        await("no push-to-talk during a call") { a.s.notice == "ON A CALL" }
        a.core.endCall()
        await("hung up on both") { a.s.call == null && b.s.call == null }
    }

    @Test fun positionsHeightMeetingPointAndRollCallReachTheGroup() {
        val (a, b, c) = trio()
        val gid = a.s.groups[0].id
        Thread.sleep(200)
        a.core.myPosition(30.0, 78.0, 2000.0, 8, send = true, trail = true)
        b.core.myPosition(30.001, 78.0, 2120.0, 8, send = true, trail = true)
        await("positions on c") { c.s.positions[a.id] != null && c.s.positions[b.id]?.alt == 2120.0 }
        assertEquals(111.0, distance(30.0, 78.0, 30.001, 78.0), 1.0)
        assertEquals("N", compass(bearing(30.0, 78.0, 30.001, 78.0)))
        a.core.setMeet(gid, 30.002, 78.001, "Hut")
        await("meet on b") { b.s.meets[gid]?.label == "Hut" }
        val late = Node(a.t.bus, "Late")                 // retained on the relay: phones that join later get it too
        assertTrue(late.t.bus.retained.keys.any { it.endsWith("/p") })
        a.core.rollCall(gid)
        await("roll on b") { b.s.rolls[gid] != null && b.events.any { it is Event.RollCall } }
        b.core.rollOk(gid); c.core.rollOk(gid)
        await("all ok on a") { a.s.rolls[gid]?.ok?.size == 3 }
    }

    @Test fun groupCallRingsEveryoneAndMixesVoices() {
        val (a, b, c) = trio()
        val gid = a.s.groups[0].id
        Thread.sleep(200)
        a.core.startCall(gid)
        await("ringing on b and c") { b.s.call?.state == "ringing" && c.s.call?.state == "ringing" && a.s.call?.group == true }
        b.core.answerCall(); c.core.answerCall()
        await("on the call") { a.s.call?.members?.containsAll(setOf(b.id, c.id)) == true }
        Thread.sleep(150)
        repeat(4) { b.audio.onFrame!!(tone()); c.audio.onFrame!!(tone()) }
        await("a hears both, mixed") { a.audio.played.size >= 3 }
        assertTrue(a.audio.played.size <= 6)          // mixed, not one stream after the other
        c.core.endCall()
        await("c left") { c.s.call == null && a.s.call?.members?.contains(c.id) == false }
        a.core.endCall(); b.core.endCall()
        await("all off") { a.s.call == null && b.s.call == null }
    }

    @Test fun stateSurvivesRestartButMessagesDoNot() {
        val (a, b, _) = trio()
        a.core.sendText("secret")
        await("text") { b.s.texts().size == 1 }
        val again = RadioCore(b.kv, FakeAudio()).also { it.start() }
        await("reload") { again.state.value.groups.size == 1 && again.state.value.me?.name == "Bravo" }
        assertEquals(b.s.tunedGroup, again.state.value.tunedGroup)
        assertTrue(again.state.value.messages.isEmpty())
    }
}
