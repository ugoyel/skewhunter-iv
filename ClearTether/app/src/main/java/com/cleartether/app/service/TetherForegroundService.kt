package com.cleartether.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cleartether.app.AppGraph
import com.cleartether.app.R
import com.cleartether.app.core.net.DirectUpstreamConnector
import com.cleartether.app.core.proxy.ClientRegistry
import com.cleartether.app.core.proxy.CompositeTrafficMeter
import com.cleartether.app.core.proxy.HostSiteInfo
import com.cleartether.app.core.proxy.LocalSite
import com.cleartether.app.core.proxy.ProxyConfig
import com.cleartether.app.core.proxy.TetherEndpoint
import com.cleartether.app.core.proxy.TetherProxyEngine
import com.cleartether.app.core.session.LimitReason
import com.cleartether.app.core.session.SessionEligibility
import com.cleartether.app.core.share.Type4TagEmulator
import com.cleartether.app.core.share.WifiCredentials
import com.cleartether.app.data.HostErrorKind
import com.cleartether.app.data.HostNetwork
import com.cleartether.app.data.HostSettings
import com.cleartether.app.data.HostStartStep
import com.cleartether.app.data.HostStatus
import com.cleartether.app.util.Format
import com.cleartether.app.wifi.GroupDetails
import com.cleartether.app.wifi.GroupRequest
import com.cleartether.app.wifi.P2pEvent
import com.cleartether.app.wifi.P2pException
import com.cleartether.app.wifi.WifiDirectManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.BindException

/**
 * Host-mode foreground service: owns the Wi-Fi Direct group, the proxy engine and the session
 * meter for as long as tethering is on.
 *
 * Foreground service type is `connectedDevice`, not `dataSync`. Android 15+ caps `dataSync`
 * services at 6 hours per day (`Service.onTimeout`), which would cut a tethering session
 * mid-meeting, and Google Play reserves `dataSync` for upload/download/backup jobs. Serving
 * nearby devices over Wi-Fi is exactly what `connectedDevice` is for (prerequisite:
 * CHANGE_WIFI_STATE).
 *
 * Lifecycle and teardown order (reverse of start-up):
 *   session meter, then proxy sockets, then the Wi-Fi Direct group, then the framework channel,
 *   then the wake and Wi-Fi locks, then the foreground notification.
 */
class TetherForegroundService : Service() {

    private val graph by lazy { AppGraph.from(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mainJob: Job? = null
    private var wifiDirect: WifiDirectManager? = null
    private var engine: TetherProxyEngine? = null
    private val registry = ClientRegistry()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var limitGraceJob: Job? = null
    private var teardownJob: Job? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTethering()
            return START_NOT_STICKY
        }
        enterForeground(getString(R.string.notif_starting_title), getString(R.string.notif_starting_text))
        if (mainJob?.isActive != true) {
            // A quick off/on toggle: let the previous teardown finish before starting again.
            val previousTeardown = teardownJob
            stopping = false
            mainJob = scope.launch {
                previousTeardown?.join()
                runTether()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        releaseNow()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ main flow

    private suspend fun runTether() {
        val runtime = graph.runtime
        runtime.host.value = HostStatus.Starting(HostStartStep.PREPARING)
        val premium = graph.billing.currentPremium()
        val settings = graph.settings.hostSettings()

        when (val eligibility = graph.session.eligibility(premium)) {
            is SessionEligibility.CoolingDown -> return fail(HostErrorKind.FREE_LIMIT_COOLDOWN, retryAt = eligibility.availableAtWallMs)
            is SessionEligibility.Allowed -> Unit
        }
        val wd = WifiDirectManager(this).also { wifiDirect = it }
        if (!wd.isSupported) return fail(HostErrorKind.WIFI_DIRECT_UNSUPPORTED)
        if (!wd.hasPermission()) return fail(HostErrorKind.PERMISSION_MISSING)
        if (!wd.isWifiEnabled) return fail(HostErrorKind.WIFI_DISABLED)

        acquireLocks()
        if (!graph.session.start(scope, premium)) return fail(HostErrorKind.FREE_LIMIT_COOLDOWN)
        graph.session.onLimitReached = { reason ->
            engine?.pause(limitMessage(reason))
            scope.launch { onLimitReached(reason) }
        }
        coroutineScope {
            val observers = listOf(launch { observePremium() }, launch { statsLoop() })
            try {
                superviseGroups(wd, settings)
            } finally {
                observers.forEach { it.cancel() }
            }
        }
    }

    /** Runs the group, transparently rebuilding it if the framework drops it. */
    private suspend fun superviseGroups(wd: WifiDirectManager, settings: HostSettings) {
        val runtime = graph.runtime
        var recoveries = 0
        while (!stopping) {
            val outcome = try {
                runGroup(wd, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: P2pException) {
                Log.w(TAG, "Wi-Fi Direct failure", e)
                return fail(
                    when {
                        e.isUnsupported -> HostErrorKind.WIFI_DIRECT_UNSUPPORTED
                        e.isBusy -> HostErrorKind.BUSY
                        !wd.isWifiEnabled -> HostErrorKind.WIFI_DISABLED
                        else -> HostErrorKind.GROUP_FAILED
                    },
                    detail = e.message,
                )
            } catch (e: BindException) {
                return fail(HostErrorKind.PORT_IN_USE, detail = e.message)
            } catch (e: Exception) {
                Log.e(TAG, "Tethering failed", e)
                return fail(HostErrorKind.UNKNOWN, detail = e.message)
            }
            if (stopping) break
            when (outcome) {
                GroupOutcome.WIFI_OFF -> return fail(HostErrorKind.WIFI_DISABLED)
                GroupOutcome.LOST -> {
                    // The framework tore the group down (radio reset, other app, OEM power saver):
                    // rebuild it transparently. Credentials are unchanged, so clients rejoin.
                    if (++recoveries > MAX_RECOVERIES) return fail(HostErrorKind.GROUP_LOST)
                    runtime.host.value = HostStatus.Starting(HostStartStep.RECOVERING)
                    delay(RECOVERY_BACKOFF_MS * recoveries)
                }
            }
        }
    }

    private enum class GroupOutcome { LOST, WIFI_OFF }

    /** Brings up one group + proxy and suspends until the group goes away. */
    private suspend fun runGroup(wd: WifiDirectManager, settings: HostSettings): GroupOutcome = coroutineScope {
        val runtime = graph.runtime
        runtime.host.value = HostStatus.Starting(HostStartStep.CREATING_GROUP)
        val group = wd.createGroup(GroupRequest(settings.networkName, settings.passphrase, settings.band))

        runtime.host.value = HostStatus.Starting(HostStartStep.STARTING_PROXY)
        val endpoint = TetherEndpoint(group.networkName, group.passphrase, group.ownerAddress.hostAddress!!, settings.port)
        val config = ProxyConfig(
            bindAddress = group.ownerAddress,
            port = settings.port,
            captivePortalMitigation = settings.captivePortalAssist,
        )
        val proxy = TetherProxyEngine(
            config = config,
            upstream = DirectUpstreamConnector(config.policy),
            meter = CompositeTrafficMeter(graph.session, registry),
            dns = AndroidDnsBackend(),
            site = LocalSite { siteInfo(endpoint) },
            log = { Log.d(TAG, it) },
        )
        val outcome = CompletableDeferred<GroupOutcome>()
        try {
            withContext(Dispatchers.IO) { proxy.start() }
            engine = proxy
            if (graph.session.state.value.limitReached != null) proxy.pause(limitMessage(graph.session.state.value.limitReached!!))

            runtime.nfcPayload.value = if (settings.nfcSharing) {
                Type4TagEmulator.hostMessage(WifiCredentials(group.networkName, group.passphrase), endpoint.baseUrl + "/", packageName)
            } else {
                null
            }
            publishRunning(group, endpoint)

            val watcher = launch {
                wd.events().collect { event ->
                    when (event) {
                        is P2pEvent.StateChanged -> if (!event.enabled) outcome.complete(GroupOutcome.WIFI_OFF)
                        is P2pEvent.ConnectionChanged -> if (!event.groupFormed && wd.requestGroupInfo() == null) {
                            outcome.complete(GroupOutcome.LOST)
                        }
                        P2pEvent.ChannelLost -> outcome.complete(GroupOutcome.LOST)
                    }
                }
            }
            val poller = launch {
                while (isActive) {
                    delay(GROUP_POLL_MS)
                    val info = wd.requestGroupInfo()
                    if (info == null) {
                        if (!wd.isWifiEnabled) outcome.complete(GroupOutcome.WIFI_OFF) else outcome.complete(GroupOutcome.LOST)
                    } else {
                        updateRunning { it.copy(network = it.network.copy(wifiClients = info.clientList?.size ?: 0)) }
                    }
                }
            }
            val result = outcome.await()
            watcher.cancel()
            poller.cancel()
            result
        } finally {
            engine = null
            proxy.stop()
            withContext(NonCancellable) { wd.removeGroup() }
        }
    }

    private fun publishRunning(group: GroupDetails, endpoint: TetherEndpoint) {
        val paused = graph.session.state.value.limitReached
        graph.runtime.host.value = HostStatus.Running(
            HostNetwork(endpoint, group.frequencyMhz, group.interfaceName, group.clientCount),
            pausedFor = if (graph.billing.isPremium.value) null else paused,
        )
        updateNotification()
    }

    private inline fun updateRunning(transform: (HostStatus.Running) -> HostStatus.Running) {
        val current = graph.runtime.host.value
        if (current is HostStatus.Running) graph.runtime.host.value = transform(current)
    }

    private fun siteInfo(endpoint: TetherEndpoint): HostSiteInfo {
        val session = graph.session.state.value
        val status = when {
            session.premium -> getString(R.string.site_status_premium)
            session.limitReached != null -> getString(R.string.site_status_ended)
            else -> getString(R.string.site_status_free, Format.duration(session.remainingMs), Format.bytes(session.remainingBytes))
        }
        return HostSiteInfo(endpoint, paused = engine?.isPaused == true, statusLine = status)
    }

    // ------------------------------------------------------------------ monetisation hooks

    private suspend fun onLimitReached(reason: LimitReason) {
        updateRunning { it.copy(pausedFor = reason) }
        Notifications.limitReached(this, limitMessage(reason))
        updateNotification()
        // Keep the group up for a while so an in-app upgrade resumes instantly without clients
        // having to rejoin; then stop to save battery.
        limitGraceJob?.cancel()
        limitGraceJob = scope.launch {
            delay(LIMIT_GRACE_MS)
            if (!graph.billing.isPremium.value) stopTethering()
        }
    }

    private suspend fun observePremium() {
        graph.billing.isPremium.collect { premium ->
            graph.session.setPremium(premium)
            if (premium && engine?.isPaused == true) {
                limitGraceJob?.cancel()
                engine?.resume()
                Notifications.cancelAlert(this)
                updateRunning { it.copy(pausedFor = null) }
                updateNotification()
            }
        }
    }

    private fun limitMessage(reason: LimitReason) = when (reason) {
        LimitReason.TIME -> getString(R.string.limit_time_message)
        LimitReason.DATA -> getString(R.string.limit_data_message)
    }

    // ------------------------------------------------------------------ stats + notification

    private suspend fun statsLoop() {
        var lastText = ""
        while (scope.isActive) {
            delay(STATS_INTERVAL_MS)
            graph.runtime.devices.value = registry.snapshot()
            val text = notificationText()
            if (text != lastText) {
                lastText = text
                updateNotification()
            }
        }
    }

    private fun notificationText(): String {
        val session = graph.session.state.value
        val status = graph.runtime.host.value
        val devices = graph.runtime.devices.value.count { it.activeConnections > 0 }
        val clients = (status as? HostStatus.Running)?.network?.wifiClients ?: 0
        val deviceCount = maxOf(devices, clients)
        val used = Format.bytes(session.totalBytes)
        return when {
            status is HostStatus.Running && status.pausedFor != null -> getString(R.string.notif_paused_text)
            session.premium -> resources.getQuantityString(R.plurals.notif_running_premium, deviceCount, deviceCount, used)
            else -> resources.getQuantityString(R.plurals.notif_running_free, deviceCount, deviceCount, used, Format.duration(session.remainingMs))
        }
    }

    private fun updateNotification() {
        val status = graph.runtime.host.value
        val session = graph.session.state.value
        val title = when (status) {
            is HostStatus.Running -> if (status.pausedFor != null) getString(R.string.notif_paused_title) else getString(R.string.notif_running_title, status.network.endpoint.networkName)
            is HostStatus.Starting -> getString(R.string.notif_starting_title)
            else -> getString(R.string.app_name)
        }
        val progress = if (!session.premium && session.active) ((1f - maxOf(session.timeProgress, session.dataProgress)) * 100).toInt() else null
        val notification = Notifications.host(
            this, title, notificationText(), progress,
            upgradeAction = !session.premium,
        )
        Notifications.post(this, Notifications.ID_HOST, notification)
    }

    private fun enterForeground(title: String, text: String) {
        ServiceCompat.startForeground(
            this, Notifications.ID_HOST, Notifications.host(this, title, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    // ------------------------------------------------------------------ teardown

    private fun fail(kind: HostErrorKind, detail: String? = null, retryAt: Long? = null) {
        Log.w(TAG, "Tethering stopped: $kind $detail")
        graph.runtime.host.value = HostStatus.Error(kind, detail, retryAt)
        stopTethering(finalStatus = graph.runtime.host.value)
    }

    /** Stops everything, in reverse order of start-up. Safe to call repeatedly. */
    private fun stopTethering(finalStatus: HostStatus = HostStatus.Idle) {
        if (stopping && mainJob == null) return
        stopping = true
        val job = mainJob
        mainJob = null
        if (finalStatus is HostStatus.Idle) graph.runtime.host.value = HostStatus.Stopping
        teardownJob = scope.launch { withContext(NonCancellable) {
            graph.session.onLimitReached = null
            graph.session.stop()
            job?.cancel()
            job?.join()
            engine?.stop()
            engine = null
            wifiDirect?.removeGroup()
            releaseNow()
            if (mainJob == null) {
                // No new session was requested meanwhile: we're done.
                graph.runtime.host.value = finalStatus
                ServiceCompat.stopForeground(this@TetherForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } }
    }

    /** Synchronous part of teardown (also used from onDestroy). */
    private fun releaseNow() {
        limitGraceJob?.cancel()
        engine?.stop()
        engine = null
        wifiDirect?.close()
        wifiDirect = null
        graph.runtime.nfcPayload.value = null
        graph.runtime.devices.value = emptyList()
        registry.clear()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClearTether:tether").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ClearTether:tether").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    companion object {
        private const val TAG = "ClearTetherHost"
        const val ACTION_START = "com.cleartether.app.action.START_HOST"
        const val ACTION_STOP = "com.cleartether.app.action.STOP_HOST"
        private const val MAX_RECOVERIES = 5
        private const val RECOVERY_BACKOFF_MS = 1_500L
        private const val GROUP_POLL_MS = 5_000L
        private const val STATS_INTERVAL_MS = 2_000L
        private const val LIMIT_GRACE_MS = 5 * 60_000L
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TetherForegroundService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TetherForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
