package com.cleartether.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cleartether.app.core.net.AddressPolicy
import com.cleartether.app.core.proxy.ProxyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cleartether")

enum class AppMode { HOST, CLIENT }

enum class WifiBand { AUTO, BAND_2_4_GHZ, BAND_5_GHZ }

/** Host ("share internet") configuration. Credentials are stable, so clients only set up once. */
data class HostSettings(
    val networkSuffix: String,
    val passphrase: String,
    val band: WifiBand = WifiBand.BAND_5_GHZ,
    val port: Int = ProxyConfig.DEFAULT_PORT,
    val nfcSharing: Boolean = true,
    val captivePortalAssist: Boolean = true,
) {
    /** Android requires Wi-Fi Direct network names to start with "DIRECT-xy". */
    val networkName: String get() = NETWORK_PREFIX + networkSuffix

    companion object {
        const val NETWORK_PREFIX = "DIRECT-CT-"
        const val MAX_SUFFIX_LENGTH = 32 - NETWORK_PREFIX.length

        fun isValidSuffix(suffix: String): Boolean =
            suffix.isNotBlank() && suffix == suffix.trim() && suffix.toByteArray().size <= MAX_SUFFIX_LENGTH &&
                suffix.all { it.code in 0x20..0x7E }

        fun isValidPassphrase(passphrase: String): Boolean =
            passphrase.length in 8..63 && passphrase.all { it.code in 0x20..0x7E }
    }
}

/** Client ("use internet") configuration: which host to join and how. */
data class ClientSettings(
    val ssid: String? = null,
    val passphrase: String? = null,
    val hostIp: String = AddressPolicy.DEFAULT_GROUP_OWNER_IP,
    val port: Int = ProxyConfig.DEFAULT_PORT,
    /** Join the host's Wi-Fi from inside the app (local-only network request) vs. use system Wi-Fi. */
    val joinFromApp: Boolean = true,
) {
    val hasCredentials: Boolean get() = !ssid.isNullOrEmpty()
}

/** All persisted preferences (DataStore). */
class SettingsRepository(private val context: Context) {

    private val store get() = context.dataStore

    val mode: Flow<AppMode> = store.data.map { p ->
        p[Keys.MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() } ?: AppMode.HOST
    }.distinctUntilChanged()

    val host: Flow<HostSettings> = store.data.map { p ->
        HostSettings(
            networkSuffix = p[Keys.HOST_SUFFIX] ?: "",
            passphrase = p[Keys.HOST_PASSPHRASE] ?: "",
            band = p[Keys.HOST_BAND]?.let { runCatching { WifiBand.valueOf(it) }.getOrNull() } ?: WifiBand.BAND_5_GHZ,
            port = p[Keys.HOST_PORT] ?: ProxyConfig.DEFAULT_PORT,
            nfcSharing = p[Keys.HOST_NFC] ?: true,
            captivePortalAssist = p[Keys.HOST_CAPTIVE] ?: true,
        )
    }.distinctUntilChanged()

    val client: Flow<ClientSettings> = store.data.map { p ->
        ClientSettings(
            ssid = p[Keys.CLIENT_SSID],
            passphrase = p[Keys.CLIENT_PASSPHRASE],
            hostIp = p[Keys.CLIENT_HOST] ?: AddressPolicy.DEFAULT_GROUP_OWNER_IP,
            port = p[Keys.CLIENT_PORT] ?: ProxyConfig.DEFAULT_PORT,
            joinFromApp = p[Keys.CLIENT_JOIN_FROM_APP] ?: true,
        )
    }.distinctUntilChanged()

    val onboardingDone: Flow<Boolean> = store.data.map { it[Keys.ONBOARDING_DONE] ?: false }.distinctUntilChanged()

    /** Current host settings, generating and persisting credentials on first use. */
    suspend fun hostSettings(): HostSettings {
        val current = host.first()
        if (HostSettings.isValidSuffix(current.networkSuffix) && HostSettings.isValidPassphrase(current.passphrase)) return current
        val generated = current.copy(
            networkSuffix = current.networkSuffix.takeIf(HostSettings::isValidSuffix) ?: defaultSuffix(),
            passphrase = current.passphrase.takeIf(HostSettings::isValidPassphrase) ?: generatePassphrase(),
        )
        store.edit {
            it[Keys.HOST_SUFFIX] = generated.networkSuffix
            it[Keys.HOST_PASSPHRASE] = generated.passphrase
        }
        return generated
    }

    suspend fun clientSettings(): ClientSettings = client.first()

    suspend fun setMode(mode: AppMode) = store.edit { it[Keys.MODE] = mode.name }

    suspend fun updateHost(transform: (HostSettings) -> HostSettings) {
        val next = transform(hostSettings())
        require(HostSettings.isValidSuffix(next.networkSuffix)) { "Invalid network name" }
        require(HostSettings.isValidPassphrase(next.passphrase)) { "Invalid password" }
        require(next.port in 1024..65535) { "Invalid port" }
        store.edit {
            it[Keys.HOST_SUFFIX] = next.networkSuffix
            it[Keys.HOST_PASSPHRASE] = next.passphrase
            it[Keys.HOST_BAND] = next.band.name
            it[Keys.HOST_PORT] = next.port
            it[Keys.HOST_NFC] = next.nfcSharing
            it[Keys.HOST_CAPTIVE] = next.captivePortalAssist
        }
    }

    suspend fun regenerateCredentials() = updateHost { it.copy(passphrase = generatePassphrase()) }

    suspend fun updateClient(transform: (ClientSettings) -> ClientSettings) {
        val next = transform(clientSettings())
        store.edit { p ->
            if (next.ssid.isNullOrEmpty()) p.remove(Keys.CLIENT_SSID) else p[Keys.CLIENT_SSID] = next.ssid
            if (next.passphrase.isNullOrEmpty()) p.remove(Keys.CLIENT_PASSPHRASE) else p[Keys.CLIENT_PASSPHRASE] = next.passphrase
            p[Keys.CLIENT_HOST] = next.hostIp
            p[Keys.CLIENT_PORT] = next.port
            p[Keys.CLIENT_JOIN_FROM_APP] = next.joinFromApp
        }
    }

    suspend fun setOnboardingDone() = store.edit { it[Keys.ONBOARDING_DONE] = true }

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val HOST_SUFFIX = stringPreferencesKey("host_suffix")
        val HOST_PASSPHRASE = stringPreferencesKey("host_passphrase")
        val HOST_BAND = stringPreferencesKey("host_band")
        val HOST_PORT = intPreferencesKey("host_port")
        val HOST_NFC = booleanPreferencesKey("host_nfc")
        val HOST_CAPTIVE = booleanPreferencesKey("host_captive")
        val CLIENT_SSID = stringPreferencesKey("client_ssid")
        val CLIENT_PASSPHRASE = stringPreferencesKey("client_passphrase")
        val CLIENT_HOST = stringPreferencesKey("client_host")
        val CLIENT_PORT = intPreferencesKey("client_port")
        val CLIENT_JOIN_FROM_APP = booleanPreferencesKey("client_join_from_app")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    companion object {
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        /** 12 random characters in 3 groups (about 69 bits): easy to type on a laptop. */
        fun generatePassphrase(): String = (0 until 3).joinToString("-") {
            (0 until 4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }

        fun defaultSuffix(): String = "ClearTether-" + (0 until 4).map { "0123456789ABCDEF"[random.nextInt(16)] }.joinToString("")
    }
}
