package com.cleartether.app.data

import com.cleartether.app.core.proxy.ClientStats
import com.cleartether.app.core.proxy.TetherEndpoint
import com.cleartether.app.core.session.LimitReason
import kotlinx.coroutines.flow.MutableStateFlow

/** Why tethering could not start or stopped unexpectedly. */
enum class HostErrorKind {
    WIFI_DIRECT_UNSUPPORTED,
    WIFI_DISABLED,
    PERMISSION_MISSING,
    BUSY,
    GROUP_FAILED,
    GROUP_LOST,
    PORT_IN_USE,
    FREE_LIMIT_REACHED,
    UNKNOWN,
}

enum class HostStartStep { PREPARING, CREATING_GROUP, STARTING_PROXY, RECOVERING }

/** Wi-Fi Direct network the host is broadcasting. */
data class HostNetwork(
    val endpoint: TetherEndpoint,
    val frequencyMhz: Int,
    val interfaceName: String?,
    val wifiClients: Int,
)

sealed interface HostStatus {
    data object Idle : HostStatus
    data class Starting(val step: HostStartStep) : HostStatus
    data class Running(val network: HostNetwork, val pausedFor: LimitReason? = null) : HostStatus
    data object Stopping : HostStatus
    data class Error(val kind: HostErrorKind, val detail: String? = null, val retryAtWallMs: Long? = null) : HostStatus
}

enum class ClientErrorKind { NOT_CONFIGURED, NETWORK_NOT_FOUND, HOST_UNREACHABLE, VPN_DENIED, VPN_FAILED, UNKNOWN }

enum class ClientStartStep { JOINING_WIFI, REACHING_HOST, STARTING_VPN, RECONNECTING }

data class ClientTraffic(
    val uploaded: Long = 0,
    val downloaded: Long = 0,
    val tcpConnections: Int = 0,
    val udpFlows: Int = 0,
)

sealed interface ClientStatus {
    data object Idle : ClientStatus
    data class Connecting(val step: ClientStartStep) : ClientStatus
    data class Connected(
        val ssid: String?,
        val proxy: String,
        val hostIsClearTether: Boolean,
        val udpRelay: Boolean,
        val since: Long,
    ) : ClientStatus
    data class Error(val kind: ClientErrorKind, val detail: String? = null) : ClientStatus
}

/** Process-wide live state, written by the services and observed by the UI. */
class TetherRuntime {
    val host = MutableStateFlow<HostStatus>(HostStatus.Idle)
    val client = MutableStateFlow<ClientStatus>(ClientStatus.Idle)
    val devices = MutableStateFlow<List<ClientStats>>(emptyList())
    val clientTraffic = MutableStateFlow(ClientTraffic())

    /** NDEF message the NFC card-emulation service hands out, or null when not sharing. */
    val nfcPayload = MutableStateFlow<ByteArray?>(null)
}
