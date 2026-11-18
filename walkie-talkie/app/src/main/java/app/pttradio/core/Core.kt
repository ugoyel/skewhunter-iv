package app.pttradio.core

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun Long.hex(): String = java.lang.Long.toHexString(this).padStart(16, '0')
fun String.idLong(): Long = java.lang.Long.parseUnsignedLong(this, 16)
fun isId(s: String) = s.length == 16 && s.all { it in '0'..'9' || it in 'a'..'f' }
fun b64(b: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(b)
fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

/** IMA ADPCM, 4 bits per sample. Every frame carries its own start state so a lost frame never corrupts the next. */
object Adpcm {
    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107,
        118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876,
        963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
        5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385,
        24623, 27086, 29794, 32767
    )
    private val IDX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    class State { var pred = 0; var index = 0 }

    private fun step(code: Int, st: State) {
        val s = STEP[st.index]
        var diff = s shr 3
        if (code and 4 != 0) diff += s
        if (code and 2 != 0) diff += s shr 1
        if (code and 1 != 0) diff += s shr 2
        st.pred = (if (code and 8 != 0) st.pred - diff else st.pred + diff).coerceIn(-32768, 32767)
        st.index = (st.index + IDX[code]).coerceIn(0, 88)
    }

    fun encode(pcm: ShortArray, st: State): ByteArray {
        val out = ByteArray(3 + (pcm.size + 1) / 2)
        out[0] = (st.pred shr 8).toByte(); out[1] = st.pred.toByte(); out[2] = st.index.toByte()
        for (i in pcm.indices) {
            val s = STEP[st.index]
            var d = pcm[i] - st.pred
            var code = 0
            if (d < 0) { code = 8; d = -d }
            if (d >= s) { code = code or 4; d -= s }
            if (d >= s shr 1) { code = code or 2; d -= s shr 1 }
            if (d >= s shr 2) code = code or 1
            step(code, st)
            val o = 3 + i / 2
            out[o] = if (i and 1 == 0) code.toByte() else (out[o].toInt() or (code shl 4)).toByte()
        }
        return out
    }

    fun decode(data: ByteArray, off: Int, len: Int): ShortArray {
        if (len < 3) return ShortArray(0)
        val st = State()
        st.pred = (data[off].toInt() shl 8) or (data[off + 1].toInt() and 0xff)
        st.index = (data[off + 2].toInt() and 0xff).coerceIn(0, 88)
        val n = (len - 3) * 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            val b = data[off + 3 + i / 2].toInt()
            step(if (i and 1 == 0) b and 15 else (b shr 4) and 15, st)
            out[i] = st.pred.toShort()
        }
        return out
    }
}

object Crypto {
    private val rnd = SecureRandom()

    fun randomId(): Long { var v: Long; do { v = rnd.nextLong() } while (v == 0L); return v }
    fun randomKey(): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }

    /** @return public (X.509) and private (PKCS8) key, base64 */
    fun newKeyPair(): Pair<String, String> {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), rnd)
        val kp = g.generateKeyPair()
        return b64(kp.public.encoded) to b64(kp.private.encoded)
    }

    fun shared(privB64: String, pubB64: String): ByteArray {
        val kf = KeyFactory.getInstance("EC")
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(kf.generatePrivate(PKCS8EncodedKeySpec(unb64(privB64))))
        ka.doPhase(kf.generatePublic(X509EncodedKeySpec(unb64(pubB64))), true)
        return MessageDigest.getInstance("SHA-256").digest(ka.generateSecret())
    }

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    fun open(key: ByteArray, data: ByteArray, off: Int, aad: ByteArray): ByteArray? {
        if (data.size - off < 28) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, off, 12))
            c.updateAAD(aad)
            c.doFinal(data, off + 12, data.size - off - 12)
        } catch (e: Exception) { null }
    }
}

/** Wire packet shared by every link (internet relay, Bluetooth, Wi-Fi). */
class Packet(val type: Int, val ttl: Int, val msgId: Long, val scope: Long, val ch: Int, val sender: Long, var body: ByteArray) {
    fun encode(ttlOverride: Int = ttl): ByteArray =
        ByteBuffer.allocate(HEADER + body.size).put(type.toByte()).put(ttlOverride.toByte()).putLong(msgId).putLong(scope)
            .put(ch.toByte()).putLong(sender).put(body).array()

    /** Authenticated header: everything except the hop counter, which relays change. */
    fun aad(): ByteArray =
        ByteBuffer.allocate(HEADER).put(type.toByte()).put(0).putLong(msgId).putLong(scope).put(ch.toByte()).putLong(sender).array()

    companion object {
        const val HEADER = 27
        fun parse(raw: ByteArray): Packet? {
            if (raw.size < HEADER) return null
            val b = ByteBuffer.wrap(raw)
            val type = b.get().toInt(); val ttl = b.get().toInt()
            val id = b.long; val scope = b.long; val ch = b.get().toInt(); val sender = b.long
            return Packet(type, ttl, id, scope, ch, sender, raw.copyOfRange(HEADER, raw.size))
        }
    }
}

/** [via] is the link this phone was last heard on directly: "nearby" (Bluetooth / Wi-Fi Direct) or "lan" (field Wi-Fi). */
/** [pic] = small profile photo (base64 JPEG), [seenAt] = last time this phone saw any sign of them. */
data class User(val id: String, val name: String, val pub: String, val online: Boolean = false, val nearbyAt: Long = 0, val ts: Long = 0, val via: String = "",
                val pic: String = "", val seenAt: Long = 0, val status: String = "")
data class Member(val id: String, val name: String, val pub: String, val joined: Boolean)
/** [direct] = a one-to-one chat: shown under the other person's name. */
/** [nameAt] = when the name was last changed, so the newest rename wins everywhere. */
data class Group(val id: String, val name: String, val key: String, val owner: String, val members: List<Member>, val direct: Boolean = false,
                 val nameAt: Long = 0)

/** Who did what in which chat, and when. Kept without any content: used for the Recent page and to sort chats by use. */
data class Recent(val uid: String, val gid: String, val kind: String, val at: Long, val mine: Boolean)
data class Invite(val group: Group, val fromId: String, val fromName: String)
data class Lock(val ch: Int, val by: String, val allow: Set<String>)
data class Pres(val ch: Int, val at: Long)
/** [image] holds a JPEG for picture messages. A [text] of the form "geo:lat,lon,accuracy" is a shared location. */
/** [pending] = waiting for a link (no timer yet). [voiceMs] > 0 marks a voice message sent or heard after a delay. */
data class TextMsg(val id: Long, val fromId: String, val from: String, val text: String, val at: Long, val expires: Long, val mine: Boolean,
                   val image: ByteArray? = null, val pending: Boolean = false, val voiceMs: Long = 0,
                   /** The voice itself (ADPCM, memory only) so it can be played again; [dnd] = arrived while on Do not disturb. */
                   val voice: ByteArray? = null, val dnd: Boolean = false) {
    /** latitude, longitude, accuracy in metres - or null when this is not a location message. */
    val geo: Triple<Double, Double, Int>? get() {
        if (!text.startsWith("geo:")) return null
        val p = text.substring(4).split(",")
        val la = p.getOrNull(0)?.toDoubleOrNull(); val lo = p.getOrNull(1)?.toDoubleOrNull()
        if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) return null
        return Triple(la, lo, p.getOrNull(2)?.toIntOrNull() ?: 0)
    }
}

data class RadioState(
    val me: User? = null,
    val netUp: Boolean = false,
    val nearbyUp: Boolean = false,
    val users: Map<String, User> = emptyMap(),
    val groups: List<Group> = emptyList(),
    val invites: List<Invite> = emptyList(),
    val tunedGroup: String? = null,
    val tunedCh: Int = 0,
    val presence: Map<String, Map<String, Pres>> = emptyMap(),
    val locks: Map<String, List<Lock>> = emptyMap(),
    val talkerId: String? = null,
    val talker: String? = null,
    val transmitting: Boolean = false,
    val messages: List<TextMsg> = emptyList(),
    val notice: String? = null,
    val noticeAt: Long = 0,
    val ttlSec: Int = 120,
    val beep: Boolean = true,
    val ready: Boolean = false,
    /** Low-data HD voice: the user's setting, whether this phone supports it, and whether it is in use right now. */
    val hd: Boolean = true,
    val hdOk: Boolean = false,
    val hdNow: Boolean = false,
    /** Messages of the chats that are not open, by "gid/ch", and how many arrived unseen per chat. Memory only. */
    val inbox: Map<String, List<TextMsg>> = emptyMap(),
    val unread: Map<String, Int> = emptyMap(),
    val recent: List<Recent> = emptyList(),
    /** Chat the current incoming voice belongs to (it can be a chat that is not open). */
    val talkGid: String? = null,
    /** "available" or "dnd" (Do not disturb: incoming talk becomes voice messages instead of playing). */
    val status: String = "available",
    val call: Call? = null,
    /** Why the last call ended, shown briefly ("No answer", "Busy", "Call ended"). */
    val callNote: String? = null,
    /** Voice quality of the current transmission, e.g. "HD voice"; empty when not talking. */
    val txQuality: String = "",
    /** Talking with no link: the voice is kept in memory and sent when a link is back. */
    val recording: Boolean = false,
) {
    val group: Group? get() = groups.firstOrNull { it.id == tunedGroup }
    /** A group's name, or for a one-to-one chat the other person's name. */
    fun titleOf(g: Group): String = if (g.direct) g.members.firstOrNull { it.id != me?.id }?.let { m -> users[m.id]?.name ?: m.name } ?: g.name else g.name
    fun directWith(uid: String): Group? = groups.firstOrNull { g -> g.direct && g.members.size == 2 && g.members.any { it.id == uid } }
    fun otherIn(g: Group): String? = if (g.direct) g.members.firstOrNull { it.id != me?.id }?.id else null
    /** Last time anything happened in this chat (talk, message, opened). */
    fun lastAt(gid: String): Long = recent.firstOrNull { it.gid == gid }?.at ?: 0
    fun lockOf(gid: String?, ch: Int): Lock? = locks[gid]?.firstOrNull { it.ch == ch }
    fun occupants(gid: String, ch: Int): List<String> = presence[gid]?.filter { it.value.ch == ch }?.keys?.toList() ?: emptyList()
    fun nameOf(id: String): String =
        if (id == me?.id) me.name else groups.firstNotNullOfOrNull { g -> g.members.firstOrNull { it.id == id }?.name } ?: users[id]?.name ?: id.take(6)
}

sealed class Event {
    data class InviteReceived(val invite: Invite) : Event()
    data class Summoned(val gid: String, val groupName: String, val ch: Int, val by: String, val autoTuned: Boolean) : Event()
    /** Someone else's text, picture, location or delayed voice message arrived. */
    data class Message(val from: String, val gid: String = "", val chat: String = "", val text: String = "") : Event()
    data class IncomingCall(val id: Long, val gid: String, val from: String) : Event()
    data class CallOver(val id: Long) : Event()
    /** Someone started talking in one of this phone's chats. */
    data class Talk(val gid: String, val chat: String, val from: String) : Event()
}

fun chName(ch: Int) = if (ch == 0) "MAIN" else "CH " + ch.toString().padStart(2, '0')

fun Member.json(): JSONObject = JSONObject().put("id", id).put("name", name).put("pub", pub).put("joined", joined)
fun memberOf(o: JSONObject) = Member(o.getString("id"), o.getString("name").take(24), o.getString("pub"), o.optBoolean("joined"))
fun Group.json(): JSONObject = JSONObject().put("id", id).put("name", name).put("key", key).put("owner", owner)
    .put("members", JSONArray().also { a -> members.forEach { a.put(it.json()) } }).also { if (direct) it.put("direct", true); if (nameAt > 0) it.put("nv", nameAt) }
fun groupOf(o: JSONObject): Group {
    val a = o.getJSONArray("members")
    val g = Group(o.getString("id"), o.getString("name").take(24), o.getString("key"), o.getString("owner"),
        (0 until a.length()).map { memberOf(a.getJSONObject(it)) }, o.optBoolean("direct"), o.optLong("nv"))
    require(isId(g.id) && g.members.all { isId(it.id) } && unb64(g.key).size == 32)
    return g
}

interface KV { fun get(k: String): String?; fun put(k: String, v: String?) }

interface AudioIO {
    /** Starts the microphone; frames of 960 samples (60 ms @ 16 kHz) are delivered until [stopCapture]. */
    fun startCapture(onFrame: (ShortArray) -> Unit): Boolean
    fun stopCapture()
    fun play(pcm: ShortArray)
    fun endOfTalk(beep: Boolean)
    /** Phone-call audio: echo cancelling, earpiece or speaker, short buffer. Off = walkie-talkie audio. */
    fun callMode(on: Boolean, speaker: Boolean) {}
}

/** A one-to-one audio call. [state]: "calling" (ringing out), "ringing" (incoming), "active". */
data class Call(val id: Long, val gid: String, val peer: String, val state: String, val since: Long,
                val muted: Boolean = false, val speaker: Boolean = false, val opus: Boolean = false)

/** Optional low-data voice codec supplied by the platform (Opus on Android). All calls come from the radio thread. */
interface VoiceCodec {
    /** True once the device has proven it can encode and decode. */
    val ok: Boolean
    /** A bundle of zero or more packets for one microphone frame, or null on failure. */
    fun encode(pcm: ShortArray): ByteArray?
    fun decode(data: ByteArray, off: Int, len: Int): ShortArray
    fun resetEnc()
    fun resetDec()
    /** Bits per second for the next transmission. */
    fun setBitrate(bps: Int) {}
}

interface TransportListener {
    fun onPacket(raw: ByteArray, t: Transport, src: String?)
    fun onUser(id: String, name: String, pub: String, online: Boolean, ts: Long, pic: String = "", status: String = "")
    fun onLink(t: Transport)
}

interface Transport {
    val name: String
    /** True for mesh links where each phone forwards packets to its other peers. */
    val relays: Boolean
    val isUp: Boolean
    var listener: TransportListener?
    fun setProfile(id: String, name: String, pub: String)
    /** Profile photo shown to others (base64 JPEG, empty for none). */
    fun setPicture(b64: String) {}
    /** Availability shown to others ("available" / "dnd"). */
    fun setStatus(st: String) {}
    fun setSubscriptions(topics: Set<String>)
    /** An empty [raw] with [retained] clears a stored message. */
    fun send(topic: String, raw: ByteArray, retained: Boolean)
    fun relay(raw: ByteArray, src: String?)
}
