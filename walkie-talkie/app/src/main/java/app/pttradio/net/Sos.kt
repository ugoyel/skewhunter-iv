package app.pttradio.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.pttradio.core.hex
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** An emergency message heard from another phone. [pct] is its received Bluetooth signal, [hops] how many phones passed it on. */
data class SosAlert(val key: String, val from: String, val name: String, val code: Int, val lat: Double?, val lon: Double?,
                    val hops: Int, val firstAt: Long, val lastAt: Long, val pct: Int)

/** This phone's own emergency broadcast, while it runs. */
data class SosSending(val code: Int, val until: Long, val hasFix: Boolean, val anyone: Boolean = false)

object SosCodes {
    val text = listOf(
        "Need help now (SOS)",
        "Come to my location",
        "I'm safe",
        "Regroup at my location",
        "Stay where you are",
        "Follow me, moving on",
        "Low battery",
        "Open ZeroBar, let's connect",
    )
    fun of(code: Int) = text.getOrElse(code) { "Alert" }
}

/**
 * Emergency messages that need no connection: a few bytes (message number, sender, GPS position) broadcast as a
 * Bluetooth LE advertisement that every phone with the app picks up while scanning. Where both phones support it,
 * it is also sent on Bluetooth's long-range "coded" mode, which reaches several times further than normal
 * Bluetooth. Phones that hear an alert repeat it for a while (up to 2 extra hops) so it travels across a group
 * that is spread out. Alerts are not encrypted: any phone running the app can read them, which is the point.
 */
@SuppressLint("MissingPermission")
class Sos(
    private val ctx: Context,
    private val myId: () -> String,
    private val nameOf: (String) -> String?,
    /** True for people this phone shares a group or chat with. */
    private val isContact: (String) -> Boolean,
    private val onNew: (SosAlert) -> Unit,
) {
    val alerts = MutableStateFlow<List<SosAlert>>(emptyList())
    val sending = MutableStateFlow<SosSending?>(null)

    companion object {
        const val MFR = 0xFFFF            // Bluetooth SIG company id reserved for testing / non-commercial use
        const val MAGIC: Byte = 0x53
        const val MAX_HOPS = 2
        const val SEND_MS = 10 * 60_000L
        const val RELAY_MS = 5 * 60_000L
        const val KEEP_MS = 30 * 60_000L

        fun encode(code: Int, hops: Int, id: Long, seq: Int, lat: Double?, lon: Double?): ByteArray =
            ByteBuffer.allocate(21).put(MAGIC).put(code.toByte()).put(hops.toByte()).putLong(id).putShort(seq.toShort())
                .putInt(if (lat == null) Int.MAX_VALUE else (lat * 1e7).roundToInt())
                .putInt(if (lon == null) Int.MAX_VALUE else (lon * 1e7).roundToInt()).array()
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var wantListen = false
    private var listening = false
    private var seq = (System.currentTimeMillis() / 1000).toInt() and 0xffff
    private var lat: Double? = null
    private var lon: Double? = null
    private val ownCbs = ArrayList<AdvertisingSetCallback>()
    private val relayCbs = ArrayList<AdvertisingSetCallback>()
    private val relaySets = ArrayList<AdvertisingSet>()
    private val relayPayload = LinkedHashMap<String, ByteArray>()
    private val relayUntil = HashMap<String, Long>()
    private val latestSeq = HashMap<String, Int>()
    private val dismissed = HashSet<String>()
    private var relayIdx = 0

    init {
        // Bluetooth was switched off and on: scanning and advertising stopped with it, start them again.
        try {
            ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) return
                    main.postDelayed({
                        listening = false
                        if (wantListen) startScan()
                        if (sending.value != null) { stopOwn(); startOwn() }
                        if (relayPayload.isNotEmpty()) { stopRelay(); main.post(rotate) }
                    }, 600)
                }
            }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ sending

    /** [anyone] = shown on every phone with the app; otherwise only phones of people in your groups and chats show it. */
    fun send(code: Int, anyone: Boolean) = main.post {
        stopOwn()
        seq = (seq + 1) and 0xffff
        sending.value = SosSending(code, System.currentTimeMillis() + SEND_MS, lat != null, anyone)
        startOwn()
        main.removeCallbacks(expire)
        main.postDelayed(expire, SEND_MS)
    }

    fun stop() = main.post { main.removeCallbacks(expire); stopOwn(); sending.value = null }

    private val expire = Runnable { stopOwn(); sending.value = null }

    /** A GPS position for this phone: included in the broadcast from now on. */
    fun setFix(la: Double, lo: Double) = main.post {
        lat = la; lon = lo
        sending.value?.let { sending.value = it.copy(hasFix = true); stopOwn(); startOwn() }
    }

    private fun startOwn() {
        val s = sending.value ?: return
        val id = myId().takeIf { it.length == 16 } ?: return
        // top bit of the message number: "contacts only"
        advertise(encode(s.code or (if (s.anyone) 0 else 0x80), 0, java.lang.Long.parseUnsignedLong(id, 16), seq, lat, lon), ownCbs, null)
    }

    private fun stopOwn() {
        val adv = adapter?.bluetoothLeAdvertiser
        ownCbs.forEach { try { adv?.stopAdvertisingSet(it) } catch (e: Exception) { } }
        ownCbs.clear()
    }

    private fun codedOk() = try { adapter?.let { it.isLeCodedPhySupported && it.isLeExtendedAdvertisingSupported } == true } catch (e: Exception) { false }

    private fun params(coded: Boolean) = AdvertisingSetParameters.Builder().apply {
        setConnectable(false)
        setScannable(false)
        setInterval(AdvertisingSetParameters.INTERVAL_LOW)          // every ~100 ms: best chance to be caught far away
        setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
        if (coded) { setLegacyMode(false); setPrimaryPhy(BluetoothDevice.PHY_LE_CODED); setSecondaryPhy(BluetoothDevice.PHY_LE_CODED) }
        else setLegacyMode(true)
    }.build()

    private fun data(payload: ByteArray) = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        .addManufacturerData(MFR, payload).build()

    /** Normal Bluetooth for every phone, plus long-range Bluetooth when this phone has it. */
    private fun advertise(payload: ByteArray, cbs: MutableList<AdvertisingSetCallback>, sets: MutableList<AdvertisingSet>?) {
        val adv = adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser ?: return
        for (coded in if (codedOk()) listOf(false, true) else listOf(false)) {
            val cb = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                    if (status == ADVERTISE_SUCCESS && set != null) sets?.add(set)
                }
            }
            try { adv.startAdvertisingSet(params(coded), data(payload), null, null, null, cb); cbs.add(cb) } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------------ passing alerts on

    /** One advertisement that cycles through every alert still being passed on, so few radio slots are used. */
    private val rotate: Runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            relayUntil.entries.removeAll { it.value < now }
            relayPayload.keys.retainAll(relayUntil.keys)
            if (relayPayload.isEmpty()) { stopRelay(); return }
            val list = relayPayload.values.toList()
            val p = list[relayIdx++ % list.size]
            if (relayCbs.isEmpty()) advertise(p, relayCbs, relaySets)
            else relaySets.forEach { try { it.setAdvertisingData(data(p)) } catch (e: Exception) { } }
            main.removeCallbacks(this)
            main.postDelayed(this, 2000)
        }
    }

    private fun stopRelay() {
        main.removeCallbacks(rotate)
        val adv = adapter?.bluetoothLeAdvertiser
        relayCbs.forEach { try { adv?.stopAdvertisingSet(it) } catch (e: Exception) { } }
        relayCbs.clear(); relaySets.clear()
    }

    // ------------------------------------------------------------------ listening

    private var foreground = false

    /** Scanning is lighter while the app is in the background. */
    fun setForeground(fg: Boolean) = main.post {
        if (fg == foreground) return@post
        foreground = fg
        if (listening) { stopScan(); startScan() }
    }

    fun listen(on: Boolean) = main.post {
        wantListen = on
        if (on) startScan() else stopScan()
    }

    private fun startScan() {
        if (listening) return
        val a = adapter?.takeIf { it.isEnabled } ?: return
        val sc = a.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setManufacturerData(MFR, byteArrayOf(MAGIC), byteArrayOf(0xFF.toByte())).build()
        val settings = ScanSettings.Builder().setScanMode(if (foreground) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER).apply {
            // extended scanning is needed to hear the long-range broadcasts
            if (try { a.isLeExtendedAdvertisingSupported } catch (e: Exception) { false }) { setLegacy(false); setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }
        }.build()
        try { sc.startScan(listOf(filter), settings, scanCb); listening = true } catch (e: Exception) { }
        main.removeCallbacks(cleanup); main.postDelayed(cleanup, 60_000)
    }

    private fun stopScan() {
        if (!listening) return
        listening = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { }
    }

    private val cleanup: Runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            alerts.value = alerts.value.filter { now - it.lastAt < KEEP_MS }
            if (wantListen) main.postDelayed(this, 60_000)
        }
    }

    fun dismiss(key: String) = main.post { dismissed += key; alerts.value = alerts.value.filter { it.key != key } }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) { handle(r) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handle(it) } }
    }

    private fun handle(r: ScanResult) {
        val d = r.scanRecord?.getManufacturerSpecificData(MFR) ?: return
        if (d.size < 21 || d[0] != MAGIC) return
        val b = ByteBuffer.wrap(d)
        b.get()
        val raw = b.get().toInt() and 0xff
        val code = raw and 0x7f
        val contactsOnly = raw and 0x80 != 0
        val hops = b.get().toInt() and 0xff
        val from = b.long.hex()
        val sq = b.short.toInt() and 0xffff
        val la = b.int; val lo = b.int
        if (code >= SosCodes.text.size + 32 || from == myId()) return
        val pct = (((r.rssi + 100) / 60.0) * 100).toInt().coerceIn(1, 100)
        val now = System.currentTimeMillis()
        main.post {
            // a newer message from the same phone replaces the older one; stragglers of the old one are ignored
            val last = latestSeq[from]
            if (last != null && last != sq && ((sq - last) and 0xffff) > 0x8000) return@post
            latestSeq[from] = sq
            val key = "$from/$sq"
            val list = alerts.value
            val old = list.firstOrNull { it.key == key }
            if (old == null && hops < MAX_HOPS) {
                relayPayload[key] = d.copyOf(21).also { it[2] = (hops + 1).toByte() }
                relayUntil[key] = now + RELAY_MS
                if (relayCbs.isEmpty()) main.post(rotate)
            }
            // strangers still pass a contacts-only alert on (it may reach a contact further away) but do not show it
            if (key in dismissed || (contactsOnly && !isContact(from))) return@post
            val a = SosAlert(key, from, nameOf(from) ?: "Radio ${from.take(4).uppercase()}", code,
                if (la == Int.MAX_VALUE) old?.lat else la / 1e7, if (lo == Int.MAX_VALUE) old?.lon else lo / 1e7,
                minOf(hops, old?.hops ?: hops), old?.firstAt ?: now, now, if (hops == 0) pct else old?.pct ?: -1)
            alerts.value = (list.filter { it.key != key && it.from != from } + a).sortedByDescending { it.firstAt }
            if (old == null) onNew(a)
        }
    }
}
