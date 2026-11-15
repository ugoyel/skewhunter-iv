package app.pttradio.core

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The radio itself: identity, groups, channels, locks, push-to-talk and expiring text.
 * Platform independent - links and audio hardware are plugged in through [Transport] and [AudioIO].
 * Voice and text are never written to storage; only identity, group membership and settings are kept.
 */
class RadioCore(
    private val kv: KV,
    private val audio: AudioIO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "radio").apply { isDaemon = true } },
    private val opus: VoiceCodec? = null,
) : TransportListener {

    companion object {
        const val P = "pttr1"
        const val T_HELLO = 1; const val T_AUDIO = 2; const val T_TEXT = 3; const val T_PRES = 4; const val T_CTL = 5; const val T_DIRECT = 6; const val T_IMG = 7
        const val IMG_CHUNK = 12_000
        const val IMG_MAX_CHUNKS = 20
        const val MAX_CH = 16
        const val MAX_TTL_SEC = 120
        const val HOPS = 3
        const val PRESENCE_EVERY = 45_000L
        const val PRESENCE_EXPIRE = 120_000L
        const val NEARBY_EXPIRE = 150_000L
        const val STALE_PROFILE = 30L * 24 * 3600 * 1000
        const val MAX_TX = 60_000L
        fun inbox(uid: String) = "$P/i/$uid"
        fun inviteTopic(uid: String, gid: String) = "$P/inv/$uid/$gid"
        fun groupTopic(gid: String) = "$P/g/$gid/s"
        fun chanTopic(gid: String, ch: Int) = "$P/g/$gid/c/$ch"
    }

    val state = MutableStateFlow(RadioState())
    @Volatile var onEvent: ((Event) -> Unit)? = null

    private val transports = CopyOnWriteArrayList<Transport>()
    private var priv: String? = null
    private var meId = 0L
    private val seen = object : LinkedHashMap<Long, Boolean>(512, 0.75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Boolean>?) = size > 4096
    }
    private val chKeys = HashMap<String, ByteArray>()            // "gid/ch" -> key of a locked channel
    private val pairKeys = HashMap<String, ByteArray>()          // peer public key -> shared key
    private val lockMap = HashMap<String, HashMap<String, List<Lock>>>() // gid -> locker -> locks
    private val inviteSentAt = HashMap<String, Long>()
    private val enc = Adpcm.State()
    private var txSeq = 0
    private var txOpus = false
    private val opCaps = HashMap<String, Boolean>()            // "gid/uid" -> that phone can decode low-data voice
    private var txStart = 0L
    private var rxSeq = -1
    private var lastRxAt = 0L
    private var lastPresenceAt = 0L
    private var lastHelloAt = 0L
    private var usersDirty = false
    private var usersSavedAt = 0L
    private var started = false

    private val s get() = state.value
    private inline fun update(f: RadioState.() -> RadioState) { state.value = state.value.f() }
    private fun run(f: () -> Unit) { exec.execute { try { f() } catch (e: Exception) { e.printStackTrace() } } }
    private fun notice(msg: String) = update { copy(notice = msg, noticeAt = clock()) }

    // ---------------------------------------------------------------- lifecycle

    fun start() = run {
        if (started) return@run
        started = true
        load()
        exec.scheduleWithFixedDelay({ try { tick() } catch (e: Exception) { e.printStackTrace() } }, 1, 1, TimeUnit.SECONDS)
    }

    fun attach(t: Transport) {
        t.listener = this
        transports.add(t)
        run { s.me?.let { t.setProfile(it.id, it.name, it.pub) }; refreshSubs(); onLinkNow(t) }
    }

    private fun load() {
        var st = RadioState(
            ready = true,
            ttlSec = (kv.get("ttl")?.toIntOrNull() ?: MAX_TTL_SEC).coerceIn(5, MAX_TTL_SEC),
            beep = kv.get("beep") != "0",
            hd = kv.get("hd") != "0",
            hdOk = try { opus?.ok == true } catch (e: Throwable) { false },
        )
        kv.get("me")?.let {
            val o = JSONObject(it)
            priv = o.getString("priv")
            meId = o.getString("id").idLong()
            st = st.copy(me = User(o.getString("id"), o.getString("name"), o.getString("pub")))
        }
        kv.get("groups")?.let { val a = JSONArray(it); st = st.copy(groups = (0 until a.length()).map { i -> groupOf(a.getJSONObject(i)) }) }
        kv.get("users")?.let {
            val a = JSONArray(it)
            st = st.copy(users = (0 until a.length()).map { i -> a.getJSONObject(i) }
                .associate { o -> o.getString("id") to User(o.getString("id"), o.getString("name"), o.getString("pub"), ts = o.optLong("ts")) })
        }
        val tg = kv.get("tunedGroup")
        if (tg != null && st.groups.any { it.id == tg }) st = st.copy(tunedGroup = tg, tunedCh = (kv.get("tunedCh")?.toIntOrNull() ?: 0).coerceIn(0, MAX_CH))
        state.value = st
        st.me?.let { me -> transports.forEach { it.setProfile(me.id, me.name, me.pub) } }
        setMyPresence()
        refreshSubs()
    }

    private fun saveGroups() = kv.put("groups", JSONArray().also { a -> s.groups.forEach { a.put(it.json()) } }.toString())

    private fun refreshSubs() {
        val me = s.me ?: return
        val subs = HashSet<String>()
        subs += "$P/u/+"; subs += inbox(me.id); subs += "$P/inv/${me.id}/+"
        s.groups.forEach { subs += groupTopic(it.id) }
        s.tunedGroup?.let { subs += chanTopic(it, s.tunedCh) }
        transports.forEach { it.setSubscriptions(subs) }
    }

    // ---------------------------------------------------------------- identity & settings

    fun setName(name: String) = run {
        val n = name.trim().take(24)
        if (n.isEmpty()) return@run
        var me = s.me
        if (me == null) {
            val (pub, pr) = Crypto.newKeyPair()
            priv = pr
            meId = Crypto.randomId()
            me = User(meId.hex(), n, pub)
        } else me = me.copy(name = n)
        kv.put("me", JSONObject().put("id", me.id).put("name", me.name).put("pub", me.pub).put("priv", priv).toString())
        update { copy(me = me, groups = groups.map { g -> g.copy(members = g.members.map { if (it.id == me.id) it.copy(name = n) else it }) }) }
        saveGroups()
        transports.forEach { it.setProfile(me.id, me.name, me.pub) }
        refreshSubs()
    }

    fun setTtl(sec: Int) = run { val v = sec.coerceIn(5, MAX_TTL_SEC); kv.put("ttl", v.toString()); update { copy(ttlSec = v) } }
    fun setHd(on: Boolean) = run {
        kv.put("hd", if (on) "1" else "0"); update { copy(hd = on) }
        s.group?.let { sendPresence(it, s.tunedCh) }
    }
    fun setBeep(on: Boolean) = run { kv.put("beep", if (on) "1" else "0"); update { copy(beep = on) } }

    // ---------------------------------------------------------------- sending

    private fun header(type: Int, scope: Long, ch: Int) = Packet(type, HOPS, Crypto.randomId(), scope, ch, meId, ByteArray(0))

    private fun transmit(p: Packet, topic: String, retained: Boolean = false): Boolean {
        seen[p.msgId] = true
        val raw = p.encode()
        var sent = false
        for (t in transports) if (t.isUp) try { t.send(topic, raw, retained); sent = true } catch (e: Exception) { }
        return sent
    }

    private fun sendSealed(type: Int, gid: String, ch: Int, key: ByteArray, plain: ByteArray, topic: String): Boolean {
        val p = header(type, gid.idLong(), ch)
        p.body = Crypto.seal(key, plain, p.aad())
        return transmit(p, topic)
    }

    private fun pairKey(pub: String): ByteArray = pairKeys.getOrPut(pub) { Crypto.shared(priv!!, pub) }

    private fun sendDirect(uid: String, pub: String, json: JSONObject, topic: String, retained: Boolean = false) {
        val me = s.me ?: return
        val p = header(T_DIRECT, uid.idLong(), 0)
        val myPub = unb64(me.pub)
        p.body = byteArrayOf(myPub.size.toByte()) + myPub + Crypto.seal(pairKey(pub), json.toString().toByteArray(), p.aad())
        transmit(p, topic, retained)
    }

    private fun sendCtl(g: Group, json: JSONObject) = sendSealed(T_CTL, g.id, 0, unb64(g.key), json.toString().toByteArray(), groupTopic(g.id))

    private fun sendHello() {
        val me = s.me ?: return
        lastHelloAt = clock()
        val p = header(T_HELLO, 0, 0)
        p.body = JSONObject().put("id", me.id).put("name", me.name).put("pub", me.pub).toString().toByteArray()
        seen[p.msgId] = true
        val raw = p.encode()
        for (t in transports) if (t.relays && t.isUp) try { t.send("", raw, false) } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- groups

    fun createGroup(name: String, memberIds: List<String>) = run {
        val me = s.me ?: return@run
        val others = memberIds.distinct().filter { it != me.id }.mapNotNull { s.users[it] }
        val g = Group(Crypto.randomId().hex(), name.trim().take(24).ifEmpty { "GROUP" }, b64(Crypto.randomKey()), me.id,
            listOf(Member(me.id, me.name, me.pub, true)) + others.map { Member(it.id, it.name, it.pub, false) })
        update { copy(groups = groups + g) }
        saveGroups()
        refreshSubs()
        g.members.forEach { sendInvite(g, it, force = true) }
        tuneNow(g.id, 0)
    }

    fun addMembers(gid: String, ids: List<String>) = run {
        var g = s.groups.firstOrNull { it.id == gid } ?: return@run
        val fresh = ids.distinct().filter { id -> g.members.none { it.id == id } }.mapNotNull { s.users[it] }.map { Member(it.id, it.name, it.pub, false) }
        if (fresh.isEmpty()) return@run
        g = g.copy(members = g.members + fresh)
        replaceGroup(g)
        fresh.forEach { sendCtl(g, JSONObject().put("t", "add").put("u", it.json())); sendInvite(g, it, force = true) }
    }

    private fun replaceGroup(g: Group) { update { copy(groups = groups.map { if (it.id == g.id) g else it }) }; saveGroups() }

    private fun sendInvite(g: Group, m: Member, force: Boolean = false) {
        val me = s.me ?: return
        if (m.id == me.id || m.joined) return
        val k = g.id + m.id
        if (!force && clock() - (inviteSentAt[k] ?: 0) < 30_000) return
        inviteSentAt[k] = clock()
        sendDirect(m.id, m.pub, JSONObject().put("t", "invite").put("from", me.name).put("group", g.json()), inviteTopic(m.id, g.id), retained = true)
    }

    private fun resendInvites(onlyTo: String? = null) {
        val me = s.me ?: return
        for (g in s.groups) if (g.owner == me.id || onlyTo != null)
            for (m in g.members) if (!m.joined && (onlyTo == null || m.id == onlyTo)) sendInvite(g, m)
    }

    fun acceptInvite(gid: String) = run {
        val me = s.me ?: return@run
        val inv = s.invites.firstOrNull { it.group.id == gid } ?: return@run
        val g = inv.group.copy(members = inv.group.members.map { if (it.id == me.id) Member(me.id, me.name, me.pub, true) else it })
        update { copy(invites = invites - inv, groups = groups.filter { it.id != gid } + g) }
        saveGroups()
        refreshSubs()
        sendCtl(g, JSONObject().put("t", "join").put("u", Member(me.id, me.name, me.pub, true).json()))
        clearInvite(me.id, gid)
        tuneNow(gid, 0)
    }

    fun declineInvite(gid: String) = run {
        val me = s.me ?: return@run
        update { copy(invites = invites.filter { it.group.id != gid }) }
        clearInvite(me.id, gid)
    }

    private fun clearInvite(uid: String, gid: String) {
        for (t in transports) if (t.isUp) try { t.send(inviteTopic(uid, gid), ByteArray(0), true) } catch (e: Exception) { }
    }

    fun leaveGroup(gid: String) = run {
        val me = s.me ?: return@run
        val g = s.groups.firstOrNull { it.id == gid } ?: return@run
        if (s.tunedGroup == gid) tuneNow(null, 0)
        sendCtl(g, JSONObject().put("t", "leave").put("id", me.id))
        lockMap.remove(gid)
        chKeys.keys.removeAll { it.startsWith("$gid/") }
        update { copy(groups = groups - g, presence = presence - gid, locks = locks - gid) }
        saveGroups()
        refreshSubs()
    }

    // ---------------------------------------------------------------- channels, presence, locks

    fun tune(gid: String?, ch: Int) = run { tuneNow(gid, ch) }

    private fun allowed(gid: String, ch: Int): Boolean {
        val l = s.lockOf(gid, ch) ?: return true
        return s.me?.id in l.allow || chKeys.containsKey("$gid/$ch")
    }

    private fun tuneNow(gid: String?, chIn: Int) {
        val me = s.me ?: return
        val ch = chIn.coerceIn(0, MAX_CH)
        val old = s.tunedGroup
        if (gid == old && ch == s.tunedCh) return
        if (gid != null) {
            if (s.groups.none { it.id == gid }) return
            if (!allowed(gid, ch)) { notice("${chName(ch)} LOCKED"); return }
        }
        if (s.transmitting) pttUpNow()
        // A lock is released as soon as its owner leaves the channel.
        if (old != null) {
            val mine = lockMap[old]?.get(me.id)
            if (mine != null) { lockMap[old]!!.remove(me.id); mine.forEach { chKeys.remove("$old/${it.ch}") } }
            if (old != gid) {
                update { copy(presence = presence.edit(old) { it - me.id }) }
                s.groups.firstOrNull { it.id == old }?.let { sendPresence(it, -1) }
            }
        }
        rxSeq = -1
        incoming.clear()
        update { copy(tunedGroup = gid, tunedCh = ch, messages = emptyList(), talker = null, talkerId = null) }
        kv.put("tunedGroup", gid); kv.put("tunedCh", ch.toString())
        setMyPresence()
        publishLocks()
        refreshSubs()
        s.group?.let { sendPresence(it, ch) }
    }

    private fun Map<String, Map<String, Pres>>.edit(gid: String, f: (Map<String, Pres>) -> Map<String, Pres>) = this + (gid to f(this[gid] ?: emptyMap()))

    private fun setMyPresence() {
        val me = s.me ?: return
        val g = s.tunedGroup ?: return
        update { copy(presence = presence.edit(g) { it + (me.id to Pres(tunedCh, clock())) }) }
    }

    private fun publishLocks() = update { copy(locks = lockMap.mapValues { e -> e.value.values.flatten() }.filterValues { it.isNotEmpty() }) }

    private fun sendPresence(g: Group, ch: Int) {
        val me = s.me ?: return
        lastPresenceAt = clock()
        val locks = JSONArray()
        if (ch >= 0) lockMap[g.id]?.get(me.id)?.forEach { l -> locks.put(JSONObject().put("ch", l.ch).put("allow", JSONArray(l.allow.toList()))) }
        sendSealed(T_PRES, g.id, 0, unb64(g.key), JSONObject().put("ch", ch).put("locks", locks).put("op", if (s.hd && s.hdOk) 1 else 0).toString().toByteArray(), groupTopic(g.id))
    }

    fun lockChannel() = run {
        val me = s.me ?: return@run
        val g = s.group ?: return@run
        val ch = s.tunedCh
        if (ch == 0) { notice("MAIN CANNOT BE LOCKED"); return@run }
        if (s.lockOf(g.id, ch) != null) { notice("ALREADY LOCKED"); return@run }
        val allow = (s.occupants(g.id, ch) + me.id).toSet()
        val key = Crypto.randomKey()
        chKeys["${g.id}/$ch"] = key
        lockMap.getOrPut(g.id) { HashMap() }[me.id] = (lockMap[g.id]?.get(me.id) ?: emptyList()) + Lock(ch, me.id, allow)
        publishLocks()
        for (m in g.members) if (m.id in allow && m.id != me.id) sendChKey(g, m, ch, key)
        sendPresence(g, ch)
        notice("${chName(ch)} LOCKED")
    }

    private fun sendChKey(g: Group, m: Member, ch: Int, key: ByteArray, summon: Boolean = false) =
        sendDirect(m.id, m.pub, JSONObject().put("t", if (summon) "summon" else "chkey").put("g", g.id).put("ch", ch).put("k", b64(key)), inbox(m.id))

    fun unlockChannel() = run {
        val me = s.me ?: return@run
        val g = s.group ?: return@run
        val l = s.lockOf(g.id, s.tunedCh) ?: return@run
        if (l.by != me.id) { notice("ONLY ${s.nameOf(l.by).uppercase()} CAN UNLOCK"); return@run }
        lockMap[g.id]?.let { m -> m[me.id] = m[me.id].orEmpty().filter { it.ch != l.ch } }
        chKeys.remove("${g.id}/${l.ch}")
        publishLocks()
        sendPresence(g, s.tunedCh)
        notice("${chName(l.ch)} UNLOCKED")
    }

    /** Pull a group member onto the channel this radio is tuned to. */
    fun summon(uid: String) = run {
        val me = s.me ?: return@run
        val g = s.group ?: return@run
        val m = g.members.firstOrNull { it.id == uid } ?: return@run
        val ch = s.tunedCh
        val l = s.lockOf(g.id, ch)
        if (l != null) {
            if (l.by != me.id) { notice("ONLY ${s.nameOf(l.by).uppercase()} CAN ADD TO LOCKED CH"); return@run }
            val key = chKeys["${g.id}/$ch"] ?: return@run
            lockMap[g.id]!![me.id] = lockMap[g.id]!![me.id].orEmpty().map { if (it.ch == ch) it.copy(allow = it.allow + uid) else it }
            publishLocks()
            sendPresence(g, ch)
            sendChKey(g, m, ch, key, summon = true)
        } else sendDirect(m.id, m.pub, JSONObject().put("t", "summon").put("g", g.id).put("ch", ch), inbox(m.id))
        notice("CALLING ${m.name.uppercase()} TO ${chName(ch)}")
    }

    // ---------------------------------------------------------------- push to talk & text

    private fun channelKey(g: Group, ch: Int): ByteArray? =
        if (s.lockOf(g.id, ch) != null) chKeys["${g.id}/$ch"] else unb64(g.key)

    /** @param gid when set (hardware talk button with a default group), tune to that group's main room first. */
    fun pttDown(gid: String? = null) = run {
        if (gid != null && gid != s.tunedGroup && s.groups.any { it.id == gid }) tuneNow(gid, 0)
        val g = s.group
        if (g == null || s.transmitting) return@run
        if (transports.none { it.isUp }) { notice("NO LINK"); return@run }
        if (channelKey(g, s.tunedCh) == null) { notice("${chName(s.tunedCh)} LOCKED"); return@run }
        if (s.talkerId != null && clock() - lastRxAt < 800) { notice("CHANNEL BUSY"); return@run }
        enc.pred = 0; enc.index = 0
        // Low-data voice only when every phone listening on this channel has said it can decode it.
        val others = s.occupants(g.id, s.tunedCh).filter { it != s.me?.id }
        txOpus = s.hd && s.hdOk && opus != null && others.isNotEmpty() && others.all { opCaps["${g.id}/$it"] == true }
        if (txOpus) opus!!.resetEnc()
        txStart = clock()
        if (!audio.startCapture { pcm -> run { txFrame(pcm) } }) { notice("MIC UNAVAILABLE"); return@run }
        update { copy(transmitting = true, talker = null, talkerId = null, hdNow = txOpus) }
    }

    private fun txFrame(pcm: ShortArray) {
        val g = s.group
        if (!s.transmitting || g == null) return
        val key = channelKey(g, s.tunedCh) ?: return
        var flags = 0
        var a: ByteArray? = null
        if (txOpus) {
            a = opus!!.encode(pcm)
            if (a == null) { txOpus = false; update { copy(hdNow = false) } } // encoder died: carry on with the standard codec
            else if (a.isEmpty() || a[0].toInt() == 0) return                 // encoder still buffering
            else flags = 2
        }
        if (a == null) a = Adpcm.encode(pcm, enc)
        val seq = txSeq++ and 0xffff
        sendSealed(T_AUDIO, g.id, s.tunedCh, key, byteArrayOf((seq shr 8).toByte(), seq.toByte(), flags.toByte()) + a, chanTopic(g.id, s.tunedCh))
    }

    fun pttUp() = run { pttUpNow() }

    private fun pttUpNow() {
        if (!s.transmitting) return
        audio.stopCapture()
        update { copy(transmitting = false, hdNow = false) }
        val g = s.group ?: return
        val key = channelKey(g, s.tunedCh) ?: return
        val seq = txSeq++ and 0xffff
        sendSealed(T_AUDIO, g.id, s.tunedCh, key, byteArrayOf((seq shr 8).toByte(), seq.toByte(), 1), chanTopic(g.id, s.tunedCh))
    }

    fun sendText(text: String) = run {
        val me = s.me ?: return@run
        val g = s.group ?: return@run
        val t = text.trim().take(500)
        if (t.isEmpty()) return@run
        val key = channelKey(g, s.tunedCh)
        if (key == null) { notice("${chName(s.tunedCh)} LOCKED"); return@run }
        if (!sendSealed(T_TEXT, g.id, s.tunedCh, key, t.toByteArray(), chanTopic(g.id, s.tunedCh))) { notice("NO LINK"); return@run }
        addMessage(me.id, me.name, t, true)
    }

    /** Shows a short status line on the radio display. */
    fun note(msg: String) = run { notice(msg) }

    /** Shares a position on the channel. It expires like any other message. */
    fun sendLocation(lat: Double, lon: Double, accuracyM: Int) =
        sendText("geo:" + String.format(java.util.Locale.US, "%.6f,%.6f,%d", lat, lon, accuracyM))

    /** Sends a (small, already compressed) JPEG to the channel in encrypted pieces. Held in memory only. */
    fun sendImage(jpeg: ByteArray) = run {
        val me = s.me ?: return@run
        val g = s.group ?: return@run
        val key = channelKey(g, s.tunedCh)
        if (key == null) { notice("${chName(s.tunedCh)} LOCKED"); return@run }
        val total = (jpeg.size + IMG_CHUNK - 1) / IMG_CHUNK
        if (jpeg.isEmpty() || total > IMG_MAX_CHUNKS) { notice("IMAGE TOO LARGE"); return@run }
        val id = java.nio.ByteBuffer.allocate(8).putLong(Crypto.randomId()).array()
        for (i in 0 until total) {
            val part = jpeg.copyOfRange(i * IMG_CHUNK, minOf(jpeg.size, (i + 1) * IMG_CHUNK))
            if (!sendSealed(T_IMG, g.id, s.tunedCh, key, id + byteArrayOf(i.toByte(), total.toByte()) + part, chanTopic(g.id, s.tunedCh))) { notice("NO LINK"); return@run }
        }
        addMessage(me.id, me.name, "", true, jpeg)
    }

    private class Asm(val parts: Array<ByteArray?>, val at: Long)
    private val incoming = HashMap<String, Asm>()

    private fun onImagePart(sender: String, plain: ByteArray) {
        if (plain.size < 11) return
        val idx = plain[8].toInt() and 0xff; val total = plain[9].toInt() and 0xff
        if (total !in 1..IMG_MAX_CHUNKS || idx >= total || plain.size - 10 > IMG_CHUNK) return
        val k = sender + java.nio.ByteBuffer.wrap(plain, 0, 8).long.hex()
        if (incoming.size > 8 && k !in incoming) incoming.entries.minByOrNull { it.value.at }?.let { incoming.remove(it.key) }
        val a = incoming.getOrPut(k) { Asm(arrayOfNulls(total), clock()) }
        if (a.parts.size != total) return
        a.parts[idx] = plain.copyOfRange(10, plain.size)
        if (a.parts.all { it != null }) {
            incoming.remove(k)
            addMessage(sender, s.nameOf(sender), "", false, a.parts.fold(ByteArray(0)) { acc, b -> acc + b!! })
        }
    }

    private fun addMessage(fromId: String, from: String, text: String, mine: Boolean, image: ByteArray? = null) {
        val now = clock()
        update { copy(messages = (messages + TextMsg(Crypto.randomId(), fromId, from, text, now, now + ttlSec * 1000L, mine, image)).takeLast(100)) }
    }

    // ---------------------------------------------------------------- receiving

    override fun onPacket(raw: ByteArray, t: Transport, src: String?) = run {
        val p = Packet.parse(raw) ?: return@run
        if (meId == 0L || p.sender == meId) return@run
        if (seen.put(p.msgId, true) != null) return@run
        if (t.relays && p.ttl > 0) try { t.relay(p.encode(p.ttl - 1), src) } catch (e: Exception) { }
        when (p.type) {
            T_HELLO -> onHello(p)
            T_AUDIO, T_TEXT, T_IMG -> onChannel(p)
            T_PRES -> onPresence(p)
            T_CTL -> onCtl(p)
            T_DIRECT -> if (p.scope == meId) onDirect(p)
        }
    }

    private fun onHello(p: Packet) {
        val o = JSONObject(String(p.body))
        val id = o.getString("id")
        if (!isId(id) || id.idLong() != p.sender) return
        val u = (s.users[id] ?: User(id, "", "")).copy(name = o.getString("name").take(24), pub = o.getString("pub"), nearbyAt = clock(), ts = clock())
        update { copy(users = users + (id to u)) }
        usersDirty = true
        resendInvites(onlyTo = id)
    }

    private fun onChannel(p: Packet) {
        val g = s.group ?: return
        if (p.scope != g.id.idLong() || p.ch != s.tunedCh) return
        val aad = p.aad()
        val plain = chKeys["${g.id}/${p.ch}"]?.let { Crypto.open(it, p.body, 0, aad) }
            ?: (if (s.lockOf(g.id, p.ch) == null) Crypto.open(unb64(g.key), p.body, 0, aad) else null) ?: return
        val sender = p.sender.hex()
        if (p.type == T_TEXT) { addMessage(sender, s.nameOf(sender), String(plain).take(500), false); return }
        if (p.type == T_IMG) { onImagePart(sender, plain); return }
        if (plain.size < 3 || s.transmitting) return
        val now = clock()
        if (s.talkerId != null && s.talkerId != sender && now - lastRxAt < 800) return // someone else holds the channel
        val seq = ((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff)
        val isOpus = plain[2].toInt() and 2 != 0
        if (isOpus && !s.hdOk) return
        if (s.talkerId != sender) { rxSeq = -1; if (isOpus) opus?.resetDec(); update { copy(talkerId = sender, talker = nameOf(sender), hdNow = isOpus) } }
        else if (rxSeq >= 0 && ((seq - rxSeq) and 0xffff) > 0x8000) return // late duplicate
        rxSeq = seq
        lastRxAt = now
        if (plain[2].toInt() and 1 != 0) {
            audio.endOfTalk(s.beep)
            update { copy(talkerId = null, talker = null, hdNow = false) }
        } else audio.play(if (isOpus) opus!!.decode(plain, 3, plain.size - 3) else Adpcm.decode(plain, 3, plain.size - 3))
    }

    private fun onPresence(p: Packet) {
        val gid = p.scope.hex()
        val g = s.groups.firstOrNull { it.id == gid } ?: return
        val o = JSONObject(String(Crypto.open(unb64(g.key), p.body, 0, p.aad()) ?: return))
        val uid = p.sender.hex()
        val ch = o.getInt("ch")
        opCaps["$gid/$uid"] = o.optInt("op") == 1
        val la = o.optJSONArray("locks")
        val locks = if (ch < 0 || la == null) emptyList() else (0 until la.length()).map { la.getJSONObject(it) }.map { l ->
            val a = l.getJSONArray("allow")
            Lock(l.getInt("ch"), uid, (0 until a.length()).map { a.getString(it) }.toSet() + uid)
        }.filter { it.ch in 1..MAX_CH }
        val m = lockMap.getOrPut(gid) { HashMap() }
        if (locks.isEmpty()) m.remove(uid) else m[uid] = locks
        publishLocks()
        update { copy(presence = presence.edit(gid) { if (ch < 0) it - uid else it + (uid to Pres(ch, clock())) }) }
        if (g.members.any { it.id == uid && !it.joined }) replaceGroup(g.copy(members = g.members.map { if (it.id == uid) it.copy(joined = true) else it }))
    }

    private fun onCtl(p: Packet) {
        val gid = p.scope.hex()
        val g = s.groups.firstOrNull { it.id == gid } ?: return
        val o = JSONObject(String(Crypto.open(unb64(g.key), p.body, 0, p.aad()) ?: return))
        when (o.getString("t")) {
            "join", "add" -> {
                val u = memberOf(o.getJSONObject("u")).let { it.copy(joined = o.getString("t") == "join") }
                if (!isId(u.id) || u.id == s.me?.id) return
                val exists = g.members.any { it.id == u.id }
                if (exists && !u.joined) return
                replaceGroup(g.copy(members = if (exists) g.members.map { if (it.id == u.id) u else it } else g.members + u))
            }
            "leave" -> {
                val id = o.getString("id")
                if (id != p.sender.hex()) return
                lockMap[gid]?.remove(id)
                publishLocks()
                update { copy(presence = presence.edit(gid) { it - id }) }
                replaceGroup(g.copy(members = g.members.filter { it.id != id }))
            }
        }
    }

    private fun onDirect(p: Packet) {
        val me = s.me ?: return
        if (p.body.isEmpty()) return
        val n = p.body[0].toInt() and 0xff
        if (n < 32 || p.body.size < 1 + n + 28) return
        val pub = b64(p.body.copyOfRange(1, 1 + n))
        val key = try { pairKey(pub) } catch (e: Exception) { return }
        val o = JSONObject(String(Crypto.open(key, p.body, 1 + n, p.aad()) ?: return))
        val from = p.sender.hex()
        when (o.getString("t")) {
            "invite" -> {
                val g = groupOf(o.getJSONObject("group"))
                if (g.members.none { it.id == me.id } || g.members.none { it.id == from }) return
                if (s.groups.any { it.id == g.id }) { clearInvite(me.id, g.id); return }
                if (s.invites.any { it.group.id == g.id }) return
                val inv = Invite(g, from, o.optString("from", "?").take(24))
                update { copy(invites = invites + inv) }
                onEvent?.invoke(Event.InviteReceived(inv))
            }
            "chkey", "summon" -> {
                val gid = o.getString("g"); val ch = o.getInt("ch")
                val g = s.groups.firstOrNull { it.id == gid } ?: return
                if (ch !in 0..MAX_CH || g.members.none { it.id == from }) return
                if (o.has("k")) { val k = unb64(o.getString("k")); if (k.size == 32) chKeys["$gid/$ch"] = k }
                if (o.getString("t") == "summon") {
                    val auto = s.tunedGroup == gid
                    if (auto) { tuneNow(gid, ch); notice("${s.nameOf(from).uppercase()} MOVED YOU TO ${chName(ch)}") }
                    onEvent?.invoke(Event.Summoned(gid, g.name, ch, s.nameOf(from), auto))
                }
            }
        }
    }

    override fun onUser(id: String, name: String, pub: String, online: Boolean, ts: Long) = run {
        if (!isId(id) || id == s.me?.id || name.isBlank() || pub.isEmpty()) return@run
        if (s.users.size > 5000 && id !in s.users) return@run
        val old = s.users[id]
        val u = (old ?: User(id, "", "")).copy(name = name.take(24), pub = pub, online = online, ts = ts)
        if (u == old) return@run
        update { copy(users = users + (id to u)) }
        usersDirty = true
    }

    override fun onLink(t: Transport) = run { onLinkNow(t) }

    private fun onLinkNow(t: Transport) {
        val net = transports.any { !it.relays && it.isUp }
        val near = transports.any { it.relays && it.isUp }
        val netCameUp = net && !s.netUp
        update { copy(netUp = net, nearbyUp = near, users = if (net) users else users.mapValues { it.value.copy(online = false) }) }
        if (t.relays && t.isUp) sendHello()
        if (netCameUp || (t.relays && t.isUp)) {
            resendInvites()
            s.group?.let { sendPresence(it, s.tunedCh) }
        }
    }

    // ---------------------------------------------------------------- housekeeping

    private fun tick() {
        val now = clock()
        var st = s
        if (st.messages.any { it.expires <= now }) st = st.copy(messages = st.messages.filter { it.expires > now })
        if (st.talkerId != null && now - lastRxAt > 1000) { st = st.copy(talkerId = null, talker = null, hdNow = false); audio.endOfTalk(false) }
        if (st.notice != null && now - st.noticeAt > 3500) st = st.copy(notice = null)
        val me = st.me?.id
        var lockChange = false
        val pres = st.presence.mapValues { (gid, m) ->
            m.filter { (uid, p) ->
                val keep = uid == me || now - p.at < PRESENCE_EXPIRE
                if (!keep && lockMap[gid]?.remove(uid) != null) lockChange = true
                keep
            }
        }
        if (pres != st.presence) st = st.copy(presence = pres)
        state.value = st
        if (lockChange) publishLocks()
        if (s.transmitting && now - txStart > MAX_TX) { pttUpNow(); notice("TX TIME LIMIT") }
        val g = s.group
        if (g != null && now - lastPresenceAt > PRESENCE_EVERY) { setMyPresence(); sendPresence(g, s.tunedCh) }
        if (s.nearbyUp && now - lastHelloAt > 20_000) sendHello()
        incoming.entries.removeAll { now - it.value.at > 60_000 }
        if (usersDirty && now - usersSavedAt > 10_000) {
            usersDirty = false; usersSavedAt = now
            val a = JSONArray()
            s.users.values.filter { now - it.ts < STALE_PROFILE }.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("pub", it.pub).put("ts", it.ts)) }
            kv.put("users", a.toString())
        }
    }
}
