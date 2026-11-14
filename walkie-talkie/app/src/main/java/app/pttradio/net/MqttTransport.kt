package app.pttradio.net

import app.pttradio.core.RadioCore
import app.pttradio.core.Transport
import app.pttradio.core.TransportListener
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Internet link. A free public MQTT broker only relays packets between phones that are online;
 * voice and text are sent non-retained, so the broker keeps nothing. The only stored items are the
 * user's directory entry (name + public key) and still-unanswered group invitations.
 */
class MqttTransport(private val servers: () -> List<String>) : Transport, MqttCallbackExtended {
    override val name = "net"
    override val relays = false
    override var listener: TransportListener? = null
    override val isUp: Boolean get() = client?.isConnected == true

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mqtt").apply { isDaemon = true } }
    private var client: MqttAsyncClient? = null
    private var wanted = false
    private var connecting = false
    private var retry: ScheduledFuture<*>? = null
    private var backoff = 2L
    private var subs: Set<String> = emptySet()
    private var active: Set<String> = emptySet()
    private var id: String? = null
    private var userName = ""
    private var pub = ""

    private fun profile(on: Boolean) = JSONObject().put("id", id).put("name", userName).put("pub", pub).put("on", on)
        .put("ts", System.currentTimeMillis()).toString().toByteArray()

    fun start() = exec.execute { wanted = true; connect() }

    fun stop() = exec.execute {
        wanted = false
        retry?.cancel(false)
        val c = client ?: return@execute
        client = null
        try {
            if (c.isConnected) { id?.let { c.publish("${RadioCore.P}/u/$it", profile(false), 1, true).waitForCompletion(1500) }; c.disconnect(500) }
        } catch (e: Exception) { }
        try { c.close(true) } catch (e: Exception) { }
        listener?.onLink(this)
    }

    /** Reconnect right now, e.g. when the phone regains connectivity. */
    fun kick() = exec.execute { if (wanted && !isUp) { backoff = 2; retry?.cancel(false); connect() } }

    private fun connect() {
        val uid = id
        if (!wanted || uid == null || connecting || isUp) return
        connecting = true
        try {
            try { client?.close(true) } catch (e: Exception) { }
            val uris = servers()
            val c = MqttAsyncClient(uris.first(), "pttr-$uid-${System.nanoTime().toString(36).takeLast(4)}", MemoryPersistence())
            client = c
            c.setCallback(this)
            val o = MqttConnectOptions().apply {
                serverURIs = uris.toTypedArray()
                isCleanSession = true
                isAutomaticReconnect = false
                keepAliveInterval = 90
                connectionTimeout = 15
                maxInflight = 500
                setWill("${RadioCore.P}/u/$uid", profile(false), 1, true)
            }
            c.connect(o, null, object : IMqttActionListener {
                override fun onSuccess(t: IMqttToken?) { exec.execute { connecting = false; backoff = 2 } }
                override fun onFailure(t: IMqttToken?, e: Throwable?) { exec.execute { connecting = false; scheduleRetry() } }
            })
        } catch (e: Exception) { connecting = false; scheduleRetry() }
    }

    private fun scheduleRetry() {
        if (!wanted) return
        retry?.cancel(false)
        retry = exec.schedule({ connect() }, backoff, TimeUnit.SECONDS)
        backoff = (backoff * 2).coerceAtMost(60)
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) = exec.execute {
        val c = client ?: return@execute
        try {
            c.publish("${RadioCore.P}/u/$id", profile(true), 1, true)
            active = emptySet()
            syncSubs()
        } catch (e: Exception) { }
        listener?.onLink(this)
    }

    override fun connectionLost(cause: Throwable?) = exec.execute { active = emptySet(); listener?.onLink(this); scheduleRetry() }

    override fun messageArrived(topic: String, m: MqttMessage) {
        val data = m.payload
        if (data.isEmpty()) return
        if (topic.startsWith("${RadioCore.P}/u/")) {
            try {
                val o = JSONObject(String(data))
                val uid = o.getString("id")
                if (topic.endsWith("/$uid")) listener?.onUser(uid, o.getString("name"), o.getString("pub"), o.optBoolean("on"), o.optLong("ts"))
            } catch (e: Exception) { }
        } else listener?.onPacket(data, this, null)
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {}

    override fun setProfile(id: String, name: String, pub: String) = exec.execute {
        val first = this.id == null
        this.id = id; userName = name; this.pub = pub
        if (first) connect()
        else if (isUp) try { client?.publish("${RadioCore.P}/u/$id", profile(true), 1, true) } catch (e: Exception) { }
    }

    override fun setSubscriptions(topics: Set<String>) = exec.execute { subs = topics; syncSubs() }

    private fun syncSubs() {
        val c = client ?: return
        if (!c.isConnected) return
        try {
            val add = subs - active
            val del = active - subs
            if (del.isNotEmpty()) c.unsubscribe(del.toTypedArray())
            if (add.isNotEmpty()) c.subscribe(add.toTypedArray(), IntArray(add.size) { if (add.elementAt(it).contains("/c/")) 0 else 1 })
            active = subs
        } catch (e: Exception) { }
    }

    override fun send(topic: String, raw: ByteArray, retained: Boolean) {
        val c = client ?: return
        // Voice and text travel at QoS 0 and are never retained: if nobody is listening they are gone.
        c.publish(topic, raw, if (retained) 1 else 0, retained)
    }

    override fun relay(raw: ByteArray, src: String?) {}
}
