package com.cleartether.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cleartether.app.AppGraph
import com.cleartether.app.R
import com.cleartether.app.core.net.AddressPolicy
import com.cleartether.app.core.proxy.ClientStats
import com.cleartether.app.core.proxy.TetherEndpoint
import com.cleartether.app.core.session.SessionState
import com.cleartether.app.core.share.WifiCredentials
import com.cleartether.app.data.AppMode
import com.cleartether.app.data.ClientErrorKind
import com.cleartether.app.data.ClientSettings
import com.cleartether.app.data.ClientStatus
import com.cleartether.app.data.ClientTraffic
import com.cleartether.app.data.HostSettings
import com.cleartether.app.data.HostStatus
import com.cleartether.app.data.WifiBand
import com.cleartether.app.service.TetherForegroundService
import com.cleartether.app.service.TransparentTetherVpnService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val mode: AppMode = AppMode.HOST,
    val host: HostStatus = HostStatus.Idle,
    val client: ClientStatus = ClientStatus.Idle,
    val session: SessionState = SessionState(),
    val hostSettings: HostSettings? = null,
    val clientSettings: ClientSettings = ClientSettings(),
    val devices: List<ClientStats> = emptyList(),
    val clientTraffic: ClientTraffic = ClientTraffic(),
) {
    /** What devices connect to: the live network while sharing, otherwise the saved one. */
    val endpoint: TetherEndpoint?
        get() = (host as? HostStatus.Running)?.network?.endpoint
            ?: hostSettings?.let { TetherEndpoint(it.networkName, it.passphrase, AddressPolicy.DEFAULT_GROUP_OWNER_IP, it.port) }
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AppGraph.from(app)

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val core = combine(
        graph.settings.mode, graph.runtime.host, graph.runtime.client, graph.session.state,
    ) { mode, host, client, session -> Core(mode, host, client, session) }

    private val extra = combine(
        graph.settings.host, graph.settings.client, graph.runtime.devices, graph.runtime.clientTraffic,
    ) { hostSettings, clientSettings, devices, traffic -> Extra(hostSettings, clientSettings, devices, traffic) }

    val state: StateFlow<MainUiState> = combine(core, extra) { c, e ->
        MainUiState(
            mode = c.mode, host = c.host, client = c.client, session = c.session,
            hostSettings = e.hostSettings.takeIf { HostSettings.isValidSuffix(it.networkSuffix) },
            clientSettings = e.clientSettings, devices = e.devices, clientTraffic = e.traffic,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private data class Core(val mode: AppMode, val host: HostStatus, val client: ClientStatus, val session: SessionState)
    private data class Extra(
        val hostSettings: HostSettings, val clientSettings: ClientSettings,
        val devices: List<ClientStats>, val traffic: ClientTraffic,
    )

    init {
        viewModelScope.launch { graph.settings.hostSettings() } // generate credentials on first launch
    }

    fun setMode(mode: AppMode) = viewModelScope.launch { graph.settings.setMode(mode) }

    fun startHost() = TetherForegroundService.start(getApplication())

    fun stopHost() = TetherForegroundService.stop(getApplication())

    fun startClient() = TransparentTetherVpnService.start(getApplication())

    fun stopClient() = TransparentTetherVpnService.stop(getApplication())

    fun onVpnPermissionDenied() {
        graph.runtime.client.value = ClientStatus.Error(ClientErrorKind.VPN_DENIED)
    }

    fun saveScannedHost(credentials: WifiCredentials) = viewModelScope.launch {
        graph.settings.updateClient { it.copy(ssid = credentials.ssid, passphrase = credentials.passphrase, joinFromApp = true) }
        _events.tryEmit(UiEvent.Message(getApplication<Application>().getString(R.string.msg_host_saved, credentials.ssid)))
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
}
