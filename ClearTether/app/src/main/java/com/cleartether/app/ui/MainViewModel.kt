package com.cleartether.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cleartether.app.AppGraph
import com.cleartether.app.billing.BillingState
import com.cleartether.app.core.proxy.ClientStats
import com.cleartether.app.core.session.FreeTierPolicy
import com.cleartether.app.core.session.LimitReason
import com.cleartether.app.core.session.SessionEligibility
import com.cleartether.app.core.session.SessionState
import com.cleartether.app.core.share.WifiCredentials
import com.cleartether.app.data.AppMode
import com.cleartether.app.data.ClientSettings
import com.cleartether.app.data.ClientStatus
import com.cleartether.app.data.ClientTraffic
import com.cleartether.app.data.HostSettings
import com.cleartether.app.data.HostStatus
import com.cleartether.app.data.WifiBand
import com.cleartether.app.service.TetherForegroundService
import com.cleartether.app.service.TransparentTetherVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MainUiState(
    val mode: AppMode = AppMode.HOST,
    val host: HostStatus = HostStatus.Idle,
    val client: ClientStatus = ClientStatus.Idle,
    val session: SessionState = SessionState(),
    val premium: Boolean = false,
    val billing: BillingState = BillingState(),
    val hostSettings: HostSettings? = null,
    val clientSettings: ClientSettings = ClientSettings(),
    val devices: List<ClientStats> = emptyList(),
    val clientTraffic: ClientTraffic = ClientTraffic(),
    val freeTier: FreeTierPolicy = FreeTierPolicy(),
)

/** Why the paywall is showing. */
enum class PaywallReason { UPGRADE, TIME_LIMIT, DATA_LIMIT, COOLDOWN }

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AppGraph.from(app)

    private val _paywall = MutableStateFlow<PaywallReason?>(null)
    val paywall: StateFlow<PaywallReason?> = _paywall.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val core = combine(
        graph.settings.mode, graph.runtime.host, graph.runtime.client, graph.session.state, graph.billing.isPremium,
    ) { mode, host, client, session, premium -> Core(mode, host, client, session, premium) }

    private val extra = combine(
        graph.billing.state, graph.settings.host, graph.settings.client, graph.runtime.devices, graph.runtime.clientTraffic,
    ) { billing, hostSettings, clientSettings, devices, traffic -> Extra(billing, hostSettings, clientSettings, devices, traffic) }

    val state: StateFlow<MainUiState> = combine(core, extra) { c, e ->
        MainUiState(
            mode = c.mode, host = c.host, client = c.client, session = c.session, premium = c.premium,
            billing = e.billing, hostSettings = e.hostSettings.takeIf { HostSettings.isValidSuffix(it.networkSuffix) },
            clientSettings = e.clientSettings, devices = e.devices, clientTraffic = e.traffic, freeTier = graph.freeTier,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private data class Core(val mode: AppMode, val host: HostStatus, val client: ClientStatus, val session: SessionState, val premium: Boolean)
    private data class Extra(
        val billing: BillingState, val hostSettings: HostSettings, val clientSettings: ClientSettings,
        val devices: List<ClientStats>, val traffic: ClientTraffic,
    )

    init {
        viewModelScope.launch { graph.settings.hostSettings() } // generate credentials on first launch
        viewModelScope.launch {
            graph.session.limitEvents.collect { reason ->
                if (!graph.billing.isPremium.value) {
                    _paywall.value = if (reason == LimitReason.TIME) PaywallReason.TIME_LIMIT else PaywallReason.DATA_LIMIT
                }
            }
        }
        viewModelScope.launch {
            graph.billing.isPremium.collect { premium ->
                if (premium) _paywall.value = null
                graph.session.refreshIdleState(premium)
            }
        }
        // Keep the idle allowance/cooldown fresh while nothing runs.
        viewModelScope.launch {
            while (isActive) {
                graph.session.refreshIdleState(graph.billing.isPremium.value)
                delay(10_000)
            }
        }
    }

    fun onResume() {
        graph.billing.connect()
        viewModelScope.launch { graph.session.refreshIdleState(graph.billing.isPremium.value) }
    }

    fun setMode(mode: AppMode) = viewModelScope.launch { graph.settings.setMode(mode) }

    /** Checks the free-tier allowance, then starts the host service. Returns via [onBlocked] if cooling down. */
    fun startHost() = viewModelScope.launch {
        val premium = graph.billing.currentPremium()
        when (graph.session.eligibility(premium)) {
            is SessionEligibility.CoolingDown -> {
                graph.session.refreshIdleState(premium)
                _paywall.value = PaywallReason.COOLDOWN
            }
            is SessionEligibility.Allowed -> TetherForegroundService.start(getApplication())
        }
    }

    fun stopHost() = TetherForegroundService.stop(getApplication())

    fun startClient() = TransparentTetherVpnService.start(getApplication())

    fun stopClient() = TransparentTetherVpnService.stop(getApplication())

    fun onVpnPermissionDenied() {
        graph.runtime.client.value = ClientStatus.Error(com.cleartether.app.data.ClientErrorKind.VPN_DENIED)
    }

    fun saveScannedHost(credentials: WifiCredentials) = viewModelScope.launch {
        graph.settings.updateClient { it.copy(ssid = credentials.ssid, passphrase = credentials.passphrase, joinFromApp = true) }
        _events.tryEmit(UiEvent.Message(getApplication<Application>().getString(com.cleartether.app.R.string.msg_host_saved, credentials.ssid)))
    }

    fun saveClientSettings(settings: ClientSettings) = viewModelScope.launch { graph.settings.updateClient { settings } }

    fun setBand(band: WifiBand) = updateHost { it.copy(band = band) }
    fun setNfcSharing(enabled: Boolean) = updateHost { it.copy(nfcSharing = enabled) }
    fun setCaptiveAssist(enabled: Boolean) = updateHost { it.copy(captivePortalAssist = enabled) }
    fun setNetworkSuffix(suffix: String) = updateHost { it.copy(networkSuffix = suffix.trim()) }
    fun setPassphrase(passphrase: String) = updateHost { it.copy(passphrase = passphrase) }
    fun setPort(port: Int) = updateHost { it.copy(port = port) }
    fun regeneratePassphrase() = viewModelScope.launch { graph.settings.regenerateCredentials() }

    private fun updateHost(transform: (HostSettings) -> HostSettings) = viewModelScope.launch {
        runCatching { graph.settings.updateHost(transform) }
            .onFailure { _events.tryEmit(UiEvent.Message(it.message ?: "Invalid value")) }
    }

    fun setDebugPremium(enabled: Boolean) = viewModelScope.launch { graph.settings.setDebugPremium(enabled) }

    fun showPaywall(reason: PaywallReason = PaywallReason.UPGRADE) {
        if (!graph.billing.isPremium.value) _paywall.value = reason
    }

    fun dismissPaywall() { _paywall.value = null }

    fun restorePurchases() = viewModelScope.launch {
        graph.billing.connect()
        graph.billing.restorePurchases()
        val app = getApplication<Application>()
        _events.tryEmit(
            UiEvent.Message(
                app.getString(if (graph.billing.isPremium.value) com.cleartether.app.R.string.msg_restored else com.cleartether.app.R.string.msg_nothing_to_restore),
            ),
        )
    }

    fun clearBillingMessage() = graph.billing.clearMessage()
}
