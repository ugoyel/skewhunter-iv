package com.cleartether.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cleartether.app.AppGraph
import com.cleartether.app.MainActivity
import com.cleartether.app.R
import com.cleartether.app.core.net.AsyncSocket
import com.cleartether.app.core.net.BufferedAsyncReader
import com.cleartether.app.core.net.Cidr
import com.cleartether.app.core.net.InetAddresses
import com.cleartether.app.core.tun.DnsForwarder
import com.cleartether.app.core.tun.DnsUpstream
import com.cleartether.app.core.tun.HostDohUpstream
import com.cleartether.app.core.tun.ProxyEndpoint
import com.cleartether.app.core.tun.ProxyTunnelDialer
import com.cleartether.app.core.tun.StackConfig
import com.cleartether.app.core.tun.TunneledDohUpstream
import com.cleartether.app.core.tun.UserSpaceNetStack
import com.cleartether.app.data.ClientErrorKind
import com.cleartether.app.data.ClientSettings
import com.cleartether.app.data.ClientStartStep
import com.cleartether.app.data.ClientStatus
import com.cleartether.app.data.ClientTraffic
import com.cleartether.app.util.Format
import com.cleartether.app.wifi.ClientWifiConnector
import com.cleartether.app.wifi.TetherNetworkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress

/**
 * ClearTether **Client mode**: makes every app on this Android device use the host phone's
 * internet with zero proxy configuration.
 *
 * A non-rooted Android device can't route *other* devices' packets, but a VpnService can
 * capture all of *this* device's traffic. So the transparent half of ClearTether runs on the
 * client:
 *
 * ```
 * apps -> TUN (10.0.0.2/32 + fd00::2/128, routes 0.0.0.0/0 and ::/0)
 *      -> UserSpaceNetStack (parses raw IP packets, terminates TCP, answers DNS)
 *      -> CONNECT tunnels / SOCKS5-UDP over the Wi-Fi Direct link
 *      -> ClearTether host proxy (192.168.49.1:8228) -> internet over the host's cellular
 * ```
 *
 * ClearTether excludes itself from the VPN and binds its process to the host Wi-Fi network,
 * so relay sockets never loop back into the tunnel.
 */
class TransparentTetherVpnService : VpnService() {

    private val graph by lazy { AppGraph.from(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connector by lazy { ClientWifiConnector(this) }
    private var sessionJob: Job? = null
    private var networkJob: Job? = null
    private var tun: ParcelFileDescriptor? = null
    private var stack: UserSpaceNetStack? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            disconnect()
            return START_NOT_STICKY
        }
        enterForeground(getString(R.string.notif_client_connecting), getString(R.string.notif_client_connecting_text))
        if (sessionJob?.isActive != true) sessionJob = scope.launch { runSession() }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user turned us off in system settings.
        disconnect()
    }

    override fun onDestroy() {
        teardownNow()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runSession() {
        val runtime = graph.runtime
        val settings = graph.settings.clientSettings()
        val host = InetAddresses.parseLiteral(settings.hostIp)
        if (host == null) return fail(ClientErrorKind.NOT_CONFIGURED, "Invalid host address")
        if (settings.joinFromApp && !settings.hasCredentials) return fail(ClientErrorKind.NOT_CONFIGURED)

        runtime.client.value = ClientStatus.Connecting(ClientStartStep.JOINING_WIFI)
        val network = MutableStateFlow<Network?>(null)
        var lost = false
        networkJob?.cancel()
        networkJob = scope.launch {
            if (settings.joinFromApp) {
                connector.join(settings.ssid!!, settings.passphrase).collect { event ->
                    when (event) {
                        is TetherNetworkEvent.Available -> { network.value = event.network; lost = false; onNetworkChanged(event.network) }
                        TetherNetworkEvent.Lost -> { lost = true; onNetworkLost() }
                        TetherNetworkEvent.Unavailable -> fail(ClientErrorKind.NETWORK_NOT_FOUND)
                    }
                }
            } else {
                network.value = connector.findExisting(host, 5_000) ?: run {
                    fail(ClientErrorKind.NETWORK_NOT_FOUND)
                    null
                }
            }
        }
        val wifi = withTimeoutOrNull(JOIN_TIMEOUT_MS) { network.filterNotNull().first() }
        if (wifi == null) return fail(ClientErrorKind.NETWORK_NOT_FOUND)
        connector.bindProcess(wifi)

        runtime.client.value = ClientStatus.Connecting(ClientStartStep.REACHING_HOST)
        val endpoint = ProxyEndpoint(host, settings.port)
        val capabilities = probeHost(endpoint)
        if (capabilities == null) return fail(ClientErrorKind.HOST_UNREACHABLE, endpoint.toString())

        runtime.client.value = ClientStatus.Connecting(ClientStartStep.STARTING_VPN)
        val pfd = try {
            establishTun(wifi)
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (pfd == null) return fail(ClientErrorKind.VPN_FAILED)
        tun = pfd

        val dnsUpstreams = buildList<DnsUpstream> {
            if (capabilities.dnsOverHttp) add(HostDohUpstream(endpoint))
            add(TunneledDohUpstream(endpoint))
        }
        val netStack = UserSpaceNetStack(
            io = TunPacketIo(pfd),
            config = StackConfig(mtu = MTU, virtualDns = VIRTUAL_DNS_V4),
            tcpDialer = ProxyTunnelDialer(endpoint, directSubnet = Cidr(host, 24)),
            dns = DnsForwarder(dnsUpstreams),
            udpProxy = if (capabilities.socksUdp) endpoint else null,
            log = { Log.d(TAG, it) },
        )
        stack = netStack
        netStack.start()
        val since = System.currentTimeMillis()
        runtime.client.value = ClientStatus.Connected(
            ssid = settings.ssid, proxy = endpoint.toString(),
            hostIsClearTether = capabilities.clearTether, udpRelay = capabilities.socksUdp, since = since,
        )

        // Stats + notification until disconnect.
        while (scope.isActive) {
            val s = netStack.stats
            runtime.clientTraffic.value = ClientTraffic(s.uploaded.get(), s.downloaded.get(), s.tcpFlows, s.udpAssociations)
            val text = getString(
                R.string.notif_client_text,
                Format.bytes(s.downloaded.get()), Format.bytes(s.uploaded.get()),
            )
            val title = if (lost) getString(R.string.notif_client_reconnecting) else getString(R.string.notif_client_connected)
            Notifications.post(this, Notifications.ID_CLIENT, Notifications.client(this, title, text))
            delay(STATS_INTERVAL_MS)
        }
    }

    private fun onNetworkChanged(network: Network) {
        // (Re)joined the host network: rebind relay sockets and tell the system what we ride on.
        connector.bindProcess(network)
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
    }

    private fun onNetworkLost() {
        // Keep the tunnel up so apps don't leak onto other networks; traffic resumes on rejoin.
        val status = graph.runtime.client.value
        if (status is ClientStatus.Connected) {
            Notifications.post(
                this, Notifications.ID_CLIENT,
                Notifications.client(this, getString(R.string.notif_client_reconnecting), getString(R.string.notif_client_reconnecting_text)),
            )
        }
    }

    private fun establishTun(wifi: Network): ParcelFileDescriptor? {
        val configure = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Builder()
            .setSession(getString(R.string.vpn_session_name))
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS_V4, 32)
            .addAddress(TUN_ADDRESS_V6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(VIRTUAL_DNS_V4_TEXT)
            .addDisallowedApplication(packageName) // our relay sockets must bypass the tunnel
            .setUnderlyingNetworks(arrayOf(wifi))
            .setMetered(true) // the host's data is cellular
            .setBlocking(false)
            .setConfigureIntent(configure)
            .establish()
    }

    /** Who is on the other end: a ClearTether host (DoH + SOCKS UDP), or any HTTP CONNECT proxy. */
    private data class HostCapabilities(val clearTether: Boolean, val dnsOverHttp: Boolean, val socksUdp: Boolean)

    private suspend fun probeHost(endpoint: ProxyEndpoint): HostCapabilities? = withContext(Dispatchers.IO) {
        repeat(PROBE_ATTEMPTS) { attempt ->
            val result = runCatching {
                withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    AsyncSocket.connect(InetSocketAddress(endpoint.address, endpoint.port)).use { socket ->
                        socket.writeFully("GET /status.json HTTP/1.1\r\nHost: $endpoint\r\nConnection: close\r\n\r\n")
                        val reader = BufferedAsyncReader(socket)
                        while (reader.fillMore(PROBE_TIMEOUT_MS)) {
                            if (reader.buffered > 16 * 1024) break
                        }
                        String(reader.takeBuffered(), Charsets.UTF_8)
                    }
                }
            }.getOrNull()
            if (result != null) {
                val clearTether = result.contains("\"app\":\"ClearTether\"")
                return@withContext HostCapabilities(
                    clearTether = clearTether,
                    dnsOverHttp = clearTether && result.contains("\"doh\""),
                    socksUdp = clearTether && result.contains("\"socks5-udp\""),
                )
            }
            delay(PROBE_RETRY_MS * (attempt + 1))
        }
        null
    }

    private fun fail(kind: ClientErrorKind, detail: String? = null) {
        Log.w(TAG, "Client mode failed: $kind $detail")
        graph.runtime.client.value = ClientStatus.Error(kind, detail)
        stopSession(keepStatus = true)
    }

    private fun disconnect() = stopSession(keepStatus = false)

    private fun stopSession(keepStatus: Boolean) {
        val job = sessionJob
        sessionJob = null
        scope.launch { withContext(NonCancellable) {
            job?.cancel()
            teardownNow()
            if (!keepStatus) graph.runtime.client.value = ClientStatus.Idle
            graph.runtime.clientTraffic.value = ClientTraffic()
            ServiceCompat.stopForeground(this@TransparentTetherVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } }
    }

    private fun teardownNow() {
        networkJob?.cancel() // releases the Wi-Fi network request
        networkJob = null
        stack?.stop() // closes the TUN fd through TunPacketIo
        stack = null
        runCatching { tun?.close() }
        tun = null
        runCatching { connector.bindProcess(null) }
    }

    private fun enterForeground(title: String, text: String) {
        ServiceCompat.startForeground(
            this, Notifications.ID_CLIENT, Notifications.client(this, title, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    companion object {
        private const val TAG = "ClearTetherClient"
        const val ACTION_CONNECT = "com.cleartether.app.action.CONNECT_CLIENT"
        const val ACTION_DISCONNECT = "com.cleartether.app.action.DISCONNECT_CLIENT"
        private const val MTU = 1500
        private const val TUN_ADDRESS_V4 = "10.0.0.2"
        private const val TUN_ADDRESS_V6 = "fd00:c1ea:7e7e::2"
        private const val VIRTUAL_DNS_V4_TEXT = "10.0.0.1"
        private val VIRTUAL_DNS_V4 = byteArrayOf(10, 0, 0, 1)
        private const val JOIN_TIMEOUT_MS = 130_000L
        private const val PROBE_ATTEMPTS = 4
        private const val PROBE_TIMEOUT_MS = 4_000L
        private const val PROBE_RETRY_MS = 750L
        private const val STATS_INTERVAL_MS = 2_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TransparentTetherVpnService::class.java).setAction(ACTION_CONNECT),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TransparentTetherVpnService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
