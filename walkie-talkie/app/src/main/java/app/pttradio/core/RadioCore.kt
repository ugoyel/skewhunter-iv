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
        const val IMG_MAX_CHUNKS = 64               // 768 KB: room for HD pictures over internet
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
        /** Kept on the relay (encrypted): the group's current name, so phones that were offline catch up when they connect. */
        fun metaTopic(gid: String) = "$P/g/$gid/m"
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

    /** Live microphone or playback loudness 0..1, for the talk key animation. */
    val level = MutableStateFlow(0f)

    /** Messages waiting for a link. Kept in memory only; [created] is when the user sent them. */
    private class Out(val msgId: Long, val gid: String, val ch: Int, val text: String, val image: ByteArray?, val pcm: List<ShortArray>?, val created: Long)
    private val outbox = ArrayDeque<Out>()
    private var txClip: ArrayList<ShortArray>? = null          // voice recorded while there is no link
    private var clipTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var rxFrames = 0
    private var rxDelayed = false
    private var txOpus = false
    /**
     * Link quality, set by the app from the phone's network: 3 = good internet, 2 = weak internet, 1 = no internet
     * (nearby or field Wi-Fi only), 0 = not known. It picks the voice bit rate and how many 60 ms frames go in one
     * packet: fewer, larger packets ride out poor networks and rate-limited relays much better.
     */
    @Volatile private var linkTier = 0
    private var bundle = 1
    private val txBuf = ArrayList<ShortArray>()
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
        run { s.me?.let { t.setPicture(it.pic); t.setProfile(it.id, it.name, it.pub) }; refreshSubs(); onLinkNow(t) }
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
            st = st.copy(me = User(o.getString("id"), o.getString("name"), o.getString("pub"), pic = kv.get("pic") ?: ""))
        }
        kv.get("groups")?.let { val a = JSONArray(it); st = st.copy(groups = (0 until a.length()).map { i -> groupOf(a.getJSONObject(i)) }) }
        kv.get("users")?.let {
            val a = JSONArray(it)
            st = st.copy(users = (0 until a.length()).map { i -> a.getJSONObject(i) }
                .associate { o -> o.getString("id") to User(o.getString("id"), o.getString("name"), o.getString("pub"), ts = o.optLong("ts"),
                    pic = o.optString("pic"), seenAt = o.optLong("seen")) })
        }
        kv.get("recent")?.let {
            val a = JSONArray(it)
            st = st.copy(recent = (0 until a.length()).map { i -> a.getJSONObject(i) }
                .map { o -> Recent(o.getString("u"), o.getString("g"), o.getString("k"), o.getLong("t"), o.optBoolean("m")) })
        }
        val tg = kv.get("tunedGroup")
        if (tg != null && st.groups.any { it.id == tg }) st = st.copy(tunedGroup = tg, tunedCh = (kv.get("tunedCh")?.toIntOrNull() ?: 0).coerceIn(0, MAX_CH))
        state.value = st
        st.me?.let { me -> transports.forEach { it.setPicture(me.pic); it.setProfile(me.id, me.name, me.pub) } }
        setMyPresence()
        refreshSubs()
    }

    private fun saveGroups() = kv.put("groups", JSONArray().also { a -> s.groups.forEach { a.put(it.json()) } }.toString())

    private fun refreshSubs() {
        val me = s.me ?: return
        val subs = HashSet<String>()
        subs += "$P/u/+"; subs += inbox(me.id); subs += "$P/inv/${me.id}/+"
        s.groups.forEach { subs += groupTopic(it.id); subs += chanTopic(it.id, 0); subs += metaTopic(it.id) }
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
    fun setLinkTier(t: Int) { linkTier = t }

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

    private fun sendSealed(type: Int, gid: String, ch: Int, key: ByteArray, plain: ByteArray, topic: String, retained: Boolean = false): Boolean {
        val p = header(type, gid.idLong(), ch)
        p.body = Crypto.seal(key, plain, p.aad())
        return transmit(p, topic, retained)
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

    fun createGroup(name: String, memberIds: List<String>) = run { makeGroup(name, memberIds, false)?.let { tuneNow(it.id, 0) } }

    /** A person known from the directory or from any shared group. */
    private fun memberFor(uid: String): Member? =
        s.users[uid]?.takeIf { it.pub.isNotEmpty() }?.let { Member(it.id, it.name, it.pub, false) }
            ?: s.groups.firstNotNullOfOrNull { g -> g.members.firstOrNull { it.id == uid } }?.copy(joined = false)

    private fun makeGroup(name: String, memberIds: List<String>, direct: Boolean): Group? {
        val me = s.me ?: return null
        val others = memberIds.distinct().filter { it != me.id }.mapNotNull { memberFor(it) }
        if (direct && others.size != 1) return null
        val g = Group(Crypto.randomId().hex(), name.trim().take(24).ifEmpty { "Group" }, b64(Crypto.randomKey()), me.id,
            listOf(Member(me.id, me.name, me.pub, true)) + others, direct)
        update { copy(groups = groups + g) }
        saveGroups()
        refreshSubs()
        g.members.forEach { sendInvite(g, it, force = true) }
        record(me.id, g.id, "open", true)
        return g
    }

    /** Opens (creating when needed) the one-to-one chat with a person. [done] gets the chat id. */
    fun openDirect(uid: String, tune: Boolean = true, done: ((String) -> Unit)? = null) = run {
        val me = s.me ?: return@run
        if (uid == me.id) return@run
        val other = memberFor(uid) ?: return@run
        val g = s.directWith(uid) ?: makeGroup("${me.name} · ${other.name}", listOf(uid), true) ?: return@run
        if (tune) tuneNow(g.id, 0)
        done?.invoke(g.id)
    }

    fun renameGroup(gid: String, name: String) = run {
        val g = s.groups.firstOrNull { it.id == gid } ?: return@run
        val n = name.trim().take(24)
        if (g.direct || n.isEmpty() || n == g.name) return@run
        val at = clock()
        replaceGroup(g.copy(name = n, nameAt = at))
        // retained on the relay: members who are offline now get the new name as soon as they connect
        sendSealed(T_CTL, g.id, 0, unb64(g.key), JSONObject().put("t", "rename").put("name", n).put("nv", at).toString().toByteArray(),
            metaTopic(g.id), retained = true)
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

    fun acceptInvite(gid: String) = run { s.invites.firstOrNull { it.group.id == gid }?.let { acceptNow(it, true) } }

    private fun acceptNow(inv: Invite, tune: Boolean) {
        val me = s.me ?: return
        val gid = inv.group.id
        val g = inv.group.copy(members = inv.group.members.map { if (it.id == me.id) Member(me.id, me.name, me.pub, true) else it })
        update { copy(invites = invites - inv, groups = groups.filter { it.id != gid } + g) }
        saveGroups()
        refreshSubs()
        sendCtl(g, JSONObject().put("t", "join").put("u", Member(me.id, me.name, me.pub, true).json()))
        clearInvite(me.id, gid)
        if (tune) tuneNow(gid, 0)
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
        update { copy(groups = groups - g, presence = presence - gid, locks = locks - gid, recent = recent.filter { it.gid != gid },
            inbox = inbox.filterKeys { !it.startsWith("$gid/") }, unread = unread - gid) }
        recentDirty = true
        saveGroups()
        refreshSubs()
    }

    // ---------------------------------------------------------------- channels, presence, locks

    fun tune(gid: String?, ch: Int) = run { tuneNow(gid, ch) }

    /** Switch to a group's main room unless the radio is already on that group (any channel). */
    fun tuneGroup(gid: String) = run { if (s.tunedGroup != gid) tuneNow(gid, 0) }

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
        val oldKey = old?.let { "$it/${s.tunedCh}" }
        val newKey = gid?.let { "$it/$ch" }
        update {
            val box = if (oldKey != null && messages.isNotEmpty()) inbox + (oldKey to messages) else if (oldKey != null) inbox - oldKey else inbox
            copy(tunedGroup = gid, tunedCh = ch, messages = newKey?.let { box[it] } ?: emptyList(), inbox = if (newKey != null) box - newKey else box,
                unread = if (gid != null) unread - gid else unread, talker = null, talkerId = null, talkGid = null)
        }
        if (gid != null) record(me.id, gid, "open", true)
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
        if (channelKey(g, s.tunedCh) == null) { notice("${chName(s.tunedCh)} LOCKED"); return@run }
        // No link (or an earlier recording still going out): record now, send when possible.
        val offline = transports.none { it.isUp } || clipTask != null
        if (!offline && s.talkerId != null && clock() - lastRxAt < 800 && rxKey == "${g.id}/${s.tunedCh}") { notice("CHANNEL BUSY"); return@run }
        txClip = if (offline) ArrayList() else null
        enc.pred = 0; enc.index = 0
        // Low-data voice only when every phone listening on this channel has said it can decode it.
        val others = s.occupants(g.id, s.tunedCh).filter { it != s.me?.id }
        txOpus = !offline && s.hd && s.hdOk && opus != null && others.isNotEmpty() && others.all { opCaps["${g.id}/$it"] == true }
        val tier = linkTier
        bundle = when (tier) { 3 -> 2; 2 -> 3; 1 -> 2; else -> 1 }
        txBuf.clear()
        if (txOpus) { opus!!.setBitrate(when (tier) { 3 -> 32_000; 1 -> 12_000; else -> 16_000 }); opus.resetEnc() }
        val quality = when { offline -> ""; !txOpus -> "Standard voice"; tier == 3 -> "HD voice"; tier == 1 -> "Data saver"; else -> "Low-data voice" }
        txStart = clock()
        if (!audio.startCapture { pcm -> run { txFrame(pcm) } }) { notice("MIC UNAVAILABLE"); return@run }
        update { copy(transmitting = true, recording = offline, talker = null, talkerId = null, hdNow = txOpus, txQuality = quality) }
        if (offline) notice("NO LINK · RECORDING")
        record(s.otherIn(g) ?: s.me!!.id, g.id, "talk", true)
    }

    private fun txFrame(pcm: ShortArray) {
        if (!s.transmitting || s.group == null) return
        level.value = levelOf(pcm)
        txClip?.let { if (it.size < MAX_TX / 60) it.add(pcm); return }
        txBuf.add(pcm)
        if (txBuf.size >= bundle) sendBundle()
    }

    /** Encodes the frames collected so far as one packet and sends it. */
    private fun sendBundle() {
        val g = s.group ?: return
        if (txBuf.isEmpty()) return
        val frames = ArrayList(txBuf)
        txBuf.clear()
        val pcm = if (frames.size == 1) frames[0] else ShortArray(frames.sumOf { it.size }).also { o ->
            var at = 0; for (f in frames) { f.copyInto(o, at); at += f.size }
        }
        val key = channelKey(g, s.tunedCh) ?: return
        var flags = 0
        var a: ByteArray? = null
        if (txOpus) {
            a = opus!!.encode(pcm)
            if (a == null) { txOpus = false; update { copy(hdNow = false, txQuality = "Standard voice") } } // encoder died: carry on with the standard codec
            else if (a.isEmpty() || a[0].toInt() == 0) return                 // encoder still buffering
            else flags = 2
        }
        if (a == null) a = Adpcm.encode(pcm, enc)
        val seq = txSeq++ and 0xffff
        if (!sendSealed(T_AUDIO, g.id, s.tunedCh, key, byteArrayOf((seq shr 8).toByte(), seq.toByte(), flags.toByte()) + a, chanTopic(g.id, s.tunedCh))) {
            // the link dropped mid-sentence: keep the rest and send it once a link is back
            txClip = frames
            update { copy(recording = true, hdNow = false) }
            notice("LINK LOST · RECORDING")
        }
    }

    private fun levelOf(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (v in pcm) sum += v.toDouble() * v
        val rms = Math.sqrt(sum / pcm.size)
        if (rms < 1) return 0f
        return ((20 * Math.log10(rms / 32768.0) + 55) / 50).toFloat().coerceIn(0f, 1f)
    }

    fun pttUp() = run { pttUpNow() }

    private fun pttUpNow() {
        if (!s.transmitting) return
        audio.stopCapture()
        level.value = 0f
        // the last few frames that did not fill a whole packet
        if (txClip == null) sendBundle() else { txClip!!.addAll(txBuf); txBuf.clear() }
        val clip = txClip
        txClip = null
        update { copy(transmitting = false, hdNow = false, recording = false, txQuality = "") }
        val g = s.group ?: return
        if (clip != null) {
            if (clip.size >= 4) queue(Out(Crypto.randomId(), g.id, s.tunedCh, "", null, clip, clock()))
            return
        }
        val key = channelKey(g, s.tunedCh) ?: return
        val seq = txSeq++ and 0xffff
        sendSealed(T_AUDIO, g.id, s.tunedCh, key, byteArrayOf((seq shr 8).toByte(), seq.toByte(), 1), chanTopic(g.id, s.tunedCh))
    }

    /** [to] = chat id and channel to send to; by default the chat that is open now. */
    fun sendText(text: String, to: Pair<String, Int>? = null) = run {
        val t = text.trim().take(500)
        if (t.isNotEmpty()) post(t, null, to)
    }

    /** Emergency: the text (and position) to the main room of every group, now or as soon as a link is back. */
    fun alertGroups(text: String, geo: String?) = run {
        val me = s.me ?: return@run
        for (g in s.groups) for (t in listOfNotNull(text, geo)) {
            if (channelKey(g, 0) == null) continue
            val o = Out(Crypto.randomId(), g.id, 0, t, null, null, clock())
            if (outbox.isEmpty() && deliver(g, o)) addMessage(me.id, me.name, t, true, id = o.msgId, gid = g.id, ch = 0)
            else queue(o)
        }
    }

    /** Sends now when a link is up, otherwise keeps the message and sends it as soon as one is. */
    private fun post(text: String, image: ByteArray?, to: Pair<String, Int>? = null) {
        val me = s.me ?: return
        // a photo and its location text must both go to the chat they were made in, even if another chat is open by now
        val g = (if (to != null) s.groups.firstOrNull { it.id == to.first } else s.group) ?: return
        val ch = to?.second ?: s.tunedCh
        if (channelKey(g, ch) == null) { notice("${chName(ch)} LOCKED"); return }
        val o = Out(Crypto.randomId(), g.id, ch, text, image, null, clock())
        record(s.otherIn(g) ?: me.id, g.id, kindOf(text, image, 0), true)
        if (outbox.isEmpty() && deliver(g, o)) { addMessage(me.id, me.name, text, true, image, id = o.msgId, gid = g.id, ch = ch); return }
        queue(o)
    }

    private fun kindOf(text: String, image: ByteArray?, voiceMs: Long) = when {
        image != null -> "pic"; voiceMs > 0 -> "voice"; text.startsWith("geo:") -> "loc"; else -> "msg"
    }

    private var recentDirty = false
    private var recentSavedAt = 0L

    private fun record(uid: String, gid: String, kind: String, mine: Boolean) {
        val r = Recent(uid, gid, kind, clock(), mine)
        update { copy(recent = (listOf(r) + recent.filter { !(it.uid == uid && it.gid == gid) }).take(80)) }
        recentDirty = true
    }

    /** Changes the message list of one chat, whether it is the open one or not. */
    private fun editChat(gid: String, ch: Int, f: (List<TextMsg>) -> List<TextMsg>) {
        if (gid == s.tunedGroup && ch == s.tunedCh) update { copy(messages = f(messages)) }
        else { val k = "$gid/$ch"; update { copy(inbox = inbox + (k to f(inbox[k] ?: emptyList()))) } }
    }

    private fun queue(o: Out) {
        outbox.addLast(o)
        editChat(o.gid, o.ch) { (it + pendingMsg(o)).takeLast(100) }
        notice(if (o.pcm != null) "VOICE QUEUED" else "NO LINK · QUEUED")
        flush()
    }

    private fun pendingMsg(o: Out) = TextMsg(o.msgId, s.me?.id ?: "", s.me?.name ?: "", o.text, o.created, Long.MAX_VALUE, true, o.image,
        pending = true, voiceMs = (o.pcm?.size ?: 0) * 60L)

    private fun deliver(g: Group, o: Out): Boolean {
        val key = channelKey(g, o.ch) ?: return false
        if (transports.none { it.isUp }) return false
        val topic = chanTopic(g.id, o.ch)
        val img = o.image ?: return sendSealed(T_TEXT, g.id, o.ch, key, o.text.toByteArray(), topic)
        val total = (img.size + IMG_CHUNK - 1) / IMG_CHUNK
        val id = java.nio.ByteBuffer.allocate(8).putLong(Crypto.randomId()).array()
        for (i in 0 until total) {
            val part = img.copyOfRange(i * IMG_CHUNK, minOf(img.size, (i + 1) * IMG_CHUNK))
            if (!sendSealed(T_IMG, g.id, o.ch, key, id + byteArrayOf(i.toByte(), total.toByte()) + part, topic)) return false
        }
        return true
    }

    /** The message left the phone now: its time and its delete timer start from this moment. */
    private fun markSent(o: Out) {
        val now = clock()
        editChat(o.gid, o.ch) { l -> l.map { if (it.id == o.msgId) it.copy(pending = false, at = now, expires = now + s.ttlSec * 1000L) else it } }
    }

    /** Sends what is waiting, oldest first, while a link is up. */
    private fun flush() {
        while (outbox.isNotEmpty() && clipTask == null && !s.transmitting) {
            if (transports.none { it.isUp }) return
            val o = outbox.first()
            val g = s.groups.firstOrNull { it.id == o.gid }
            if (g == null || channelKey(g, o.ch) == null) {
                outbox.removeFirst()
                editChat(o.gid, o.ch) { l -> l.filter { it.id != o.msgId } }
                notice("QUEUED MESSAGE DROPPED")
                continue
            }
            val pcm = o.pcm
            if (pcm != null) {
                if (s.tunedGroup == o.gid && s.tunedCh == o.ch && s.talkerId != null && clock() - lastRxAt < 800) return // wait for a free channel
                startClip(g, o, pcm)
                return
            }
            if (!deliver(g, o)) return
            outbox.removeFirst()
            markSent(o)
        }
    }

    /** Plays a recorded voice message out in real time, marked as delayed, so it sounds like a normal call. */
    private fun startClip(g: Group, o: Out, pcm: List<ShortArray>) {
        val st = Adpcm.State()
        var i = 0
        notice("SENDING QUEUED VOICE")
        clipTask = exec.scheduleAtFixedRate(Runnable {
            try {
                val key = channelKey(g, o.ch)
                val last = i >= pcm.size
                val seq = txSeq++ and 0xffff
                val body = byteArrayOf((seq shr 8).toByte(), seq.toByte(), (if (last) 5 else 4).toByte()) + (if (last) ByteArray(0) else Adpcm.encode(pcm[i], st))
                if (key == null || !sendSealed(T_AUDIO, g.id, o.ch, key, body, chanTopic(g.id, o.ch))) { endClip(); return@Runnable } // retried later
                i++
                if (last) { endClip(); outbox.remove(o); markSent(o); flush() }
            } catch (e: Exception) { endClip() }
        }, 0, 60, TimeUnit.MILLISECONDS)
    }

    private fun endClip() { clipTask?.cancel(false); clipTask = null }

    /** Shows a short status line on the radio display. */
    fun note(msg: String) = run { notice(msg) }

    /** Shares a position on the channel. It expires like any other message. */
    fun sendLocation(lat: Double, lon: Double, accuracyM: Int) =
        sendText("geo:" + String.format(java.util.Locale.US, "%.6f,%.6f,%d", lat, lon, accuracyM))

    /** Sends a (small, already compressed) JPEG to the channel in encrypted pieces. Held in memory only. */
    fun sendImage(jpeg: ByteArray, to: Pair<String, Int>? = null) = run {
        val total = (jpeg.size + IMG_CHUNK - 1) / IMG_CHUNK
        if (jpeg.isEmpty() || total > IMG_MAX_CHUNKS) { notice("IMAGE TOO LARGE"); return@run }
        post("", jpeg, to)
    }

    private class Asm(val parts: Array<ByteArray?>, val at: Long)
    private val incoming = HashMap<String, Asm>()

    private fun onImagePart(sender: String, plain: ByteArray, gid: String, ch: Int) {
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
            addMessage(sender, s.nameOf(sender), "", false, a.parts.fold(ByteArray(0)) { acc, b -> acc + b!! }, gid = gid, ch = ch)
        }
    }

    private fun addMessage(fromId: String, from: String, text: String, mine: Boolean, image: ByteArray? = null,
                           id: Long = Crypto.randomId(), voiceMs: Long = 0, gid: String? = s.tunedGroup, ch: Int = s.tunedCh) {
        val now = clock()
        val chat = gid ?: return
        editChat(chat, ch) { (it + TextMsg(id, fromId, from, text, now, now + s.ttlSec * 1000L, mine, image, voiceMs = voiceMs)).takeLast(100) }
        if (mine) return
        if (chat != s.tunedGroup) update { copy(unread = unread + (chat to (unread[chat] ?: 0) + 1)) }
        record(fromId, chat, kindOf(text, image, voiceMs), false)
        val g = s.groups.firstOrNull { it.id == chat }
        val preview = when { image != null -> "Picture"; voiceMs > 0 -> "Voice message"; text.startsWith("geo:") -> "Location"; else -> text }
        onEvent?.invoke(Event.Message(from, chat, g?.let { s.titleOf(it) } ?: "", preview))
    }

    // ---------------------------------------------------------------- receiving

    override fun onPacket(raw: ByteArray, t: Transport, src: String?) = run {
        val p = Packet.parse(raw) ?: return@run
        if (meId == 0L || p.sender == meId) return@run
        if (seen.put(p.msgId, true) != null) return@run
        if (p.ttl > 0) {
            val out = p.encode(p.ttl - 1)
            for (o in transports) if (o.relays && o.isUp) try { o.relay(out, if (o === t) src else null) } catch (e: Exception) { }
        }
        when (p.type) {
            T_HELLO -> onHello(p, if (p.ttl >= HOPS) t.name else "")
            T_AUDIO, T_TEXT, T_IMG -> onChannel(p)
            T_PRES -> onPresence(p)
            T_CTL -> onCtl(p)
            T_DIRECT -> if (p.scope == meId) onDirect(p)
        }
    }

    private fun onHello(p: Packet, via: String) {
        val o = JSONObject(String(p.body))
        val id = o.getString("id")
        if (!isId(id) || id.idLong() != p.sender) return
        val pub = o.getString("pub")
        if (!pinned(id, pub)) return
        val old = s.users[id]
        // via: the link it came in on when heard directly, empty when another phone passed it on
        val u = (old ?: User(id, "", "")).copy(name = o.getString("name").take(24), pub = pub, nearbyAt = clock(), ts = clock(), via = via, seenAt = clock())
        update { copy(users = users + (id to u)) }
        usersDirty = true
        resendInvites(onlyTo = id)
    }

    private var rxKey = ""                      // "gid/ch" of the voice being received

    private fun onChannel(p: Packet) {
        val g = s.groups.firstOrNull { it.id.idLong() == p.scope } ?: return
        val open = g.id == s.tunedGroup && p.ch == s.tunedCh
        if (!open && p.ch != 0) return
        val aad = p.aad()
        val plain = chKeys["${g.id}/${p.ch}"]?.let { Crypto.open(it, p.body, 0, aad) }
            ?: (if (s.lockOf(g.id, p.ch) == null) Crypto.open(unb64(g.key), p.body, 0, aad) else null) ?: return
        val sender = p.sender.hex()
        if (p.type == T_TEXT) { addMessage(sender, s.nameOf(sender), String(plain).take(500), false, gid = g.id, ch = p.ch); return }
        if (p.type == T_IMG) { onImagePart(sender, plain, g.id, p.ch); return }
        if (plain.size < 3 || s.transmitting) return
        val now = clock()
        val key = "${g.id}/${p.ch}"
        if (s.talkerId != null && s.talkerId != sender && now - lastRxAt < 800) {
            // one voice at a time; the open chat wins over a chat that is only being listened to in the background
            val openTalk = rxKey == "${s.tunedGroup}/${s.tunedCh}"
            if (!open || openTalk) return
        }
        val seq = ((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff)
        val isOpus = plain[2].toInt() and 2 != 0
        if (isOpus && !s.hdOk) return
        if (s.talkerId != sender || rxKey != key) {
            rxSeq = -1; rxFrames = 0; rxDelayed = plain[2].toInt() and 4 != 0; rxKey = key
            if (isOpus) opus?.resetDec()
            update { copy(talkerId = sender, talker = nameOf(sender), hdNow = isOpus, talkGid = g.id) }
            record(sender, g.id, "talk", false)
            if (plain[2].toInt() and 1 == 0) onEvent?.invoke(Event.Talk(g.id, s.titleOf(g), s.nameOf(sender)))
        }
        else if (rxSeq >= 0 && ((seq - rxSeq) and 0xffff) > 0x8000) return // late duplicate
        rxSeq = seq
        lastRxAt = now
        if (plain[2].toInt() and 1 != 0) {
            audio.endOfTalk(s.beep)
            level.value = 0f
            update { copy(talkerId = null, talker = null, hdNow = false, talkGid = null) }
            // a recording sent after a delay also leaves a line in the log, stamped with when it arrived
            if (rxDelayed && rxFrames > 0) addMessage(sender, s.nameOf(sender), "", false, voiceMs = rxFrames * 60L, gid = g.id, ch = p.ch)
            rxFrames = 0
        } else {
            val pcm = if (isOpus) opus!!.decode(plain, 3, plain.size - 3) else Adpcm.decode(plain, 3, plain.size - 3)
            rxFrames++
            level.value = levelOf(pcm)
            audio.play(pcm)
        }
    }

    /** Any packet from someone is a sign of life for "last seen". */
    private fun sawAlive(uid: String) {
        val u = s.users[uid] ?: return
        if (clock() - u.seenAt > 5000) { update { copy(users = users + (uid to u.copy(seenAt = clock()))) }; usersDirty = true }
    }

    /** Sets (or clears, with "") this phone's profile photo. */
    fun setPhoto(b64: String) = run {
        kv.put("pic", b64.ifEmpty { null })
        update { copy(me = me?.copy(pic = b64)) }
        transports.forEach { it.setPicture(b64) }
    }

    private fun onPresence(p: Packet) {
        val gid = p.scope.hex()
        val g = s.groups.firstOrNull { it.id == gid } ?: return
        val o = JSONObject(String(Crypto.open(unb64(g.key), p.body, 0, p.aad()) ?: return))
        val uid = p.sender.hex()
        sawAlive(uid)
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
            "rename" -> {
                val n = o.optString("name").trim().take(24)
                val at = o.optLong("nv")
                // the newest rename wins, whichever order the messages arrive in
                if (n.isNotEmpty() && !g.direct && g.members.any { it.id == p.sender.hex() } && (at == 0L || at > g.nameAt))
                    replaceGroup(g.copy(name = n, nameAt = if (at > 0) at else clock()))
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
        if (!pinned(from, pub)) return
        when (o.getString("t")) {
            "invite" -> {
                val g = groupOf(o.getJSONObject("group"))
                if (g.members.none { it.id == me.id } || g.members.none { it.id == from }) return
                if (s.groups.any { it.id == g.id }) { clearInvite(me.id, g.id); return }
                if (s.invites.any { it.group.id == g.id }) return
                val inv = Invite(g, from, o.optString("from", "?").take(24))
                update { copy(invites = invites + inv) }
                // a one-to-one chat needs no "join": like a phone number, it simply appears in the list
                if (g.direct && g.members.size == 2) acceptNow(inv, false)
                else onEvent?.invoke(Event.InviteReceived(inv))
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

    /**
     * Key pinning: once a phone's public key is known, a different key for the same id is rejected. Stops anyone
     * from overwriting a person's directory entry (or faking an invite) to impersonate them.
     */
    private fun knownPub(id: String): String? =
        s.users[id]?.pub?.takeIf { it.isNotEmpty() } ?: s.groups.firstNotNullOfOrNull { g -> g.members.firstOrNull { it.id == id }?.pub }

    private fun pinned(id: String, pub: String): Boolean = knownPub(id).let { it == null || it == pub }

    override fun onUser(id: String, name: String, pub: String, online: Boolean, ts: Long, pic: String) = run {
        if (!isId(id) || id == s.me?.id || name.isBlank() || pub.isEmpty()) return@run
        if (s.users.size > 5000 && id !in s.users) return@run
        if (!pinned(id, pub)) return@run
        val old = s.users[id]
        // last seen: now while online, or the moment we notice them go offline
        val seen = if (online || old?.online == true) clock() else maxOf(old?.seenAt ?: 0, ts)
        val u = (old ?: User(id, "", "")).copy(name = name.take(24), pub = pub, online = online, ts = ts, pic = pic.take(20_000), seenAt = seen)
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
        flush()
    }

    // ---------------------------------------------------------------- housekeeping

    private fun tick() {
        val now = clock()
        var st = s
        if (st.messages.any { it.expires <= now }) st = st.copy(messages = st.messages.filter { it.expires > now })
        if (st.inbox.values.any { l -> l.any { it.expires <= now } })
            st = st.copy(inbox = st.inbox.mapValues { e -> e.value.filter { it.expires > now } }.filterValues { it.isNotEmpty() })
        var lateVoice: Pair<String, Long>? = null
        if (st.talkerId != null && now - lastRxAt > 1000) {
            if (rxDelayed && rxFrames > 0) lateVoice = st.talkerId!! to rxFrames * 60L
            rxFrames = 0
            level.value = 0f
            st = st.copy(talkerId = null, talker = null, hdNow = false, talkGid = null); audio.endOfTalk(false)
        }
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
        lateVoice?.let { (id, ms) ->
            val parts = rxKey.split("/")
            addMessage(id, s.nameOf(id), "", false, voiceMs = ms, gid = parts.getOrNull(0), ch = parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        if (recentDirty && now - recentSavedAt > 10_000) {
            recentDirty = false; recentSavedAt = now
            val a = JSONArray()
            s.recent.forEach { a.put(JSONObject().put("u", it.uid).put("g", it.gid).put("k", it.kind).put("t", it.at).put("m", it.mine)) }
            kv.put("recent", a.toString())
        }
        if (outbox.isNotEmpty()) flush()
        if (s.transmitting && now - txStart > MAX_TX) { pttUpNow(); notice("TX TIME LIMIT") }
        val g = s.group
        if (g != null && now - lastPresenceAt > PRESENCE_EVERY) { setMyPresence(); sendPresence(g, s.tunedCh) }
        if (s.nearbyUp && now - lastHelloAt > 20_000) sendHello()
        incoming.entries.removeAll { now - it.value.at > 60_000 }
        if (usersDirty && now - usersSavedAt > 10_000) {
            usersDirty = false; usersSavedAt = now
            val a = JSONArray()
            s.users.values.filter { now - it.ts < STALE_PROFILE }.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("pub", it.pub).put("ts", it.ts).put("pic", it.pic).put("seen", it.seenAt)) }
            kv.put("users", a.toString())
        }
    }
}
