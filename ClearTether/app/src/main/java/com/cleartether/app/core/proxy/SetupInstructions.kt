package com.cleartether.app.core.proxy

/** Platforms a tethered client can run. */
enum class ClientPlatform(val displayName: String) {
    WINDOWS("Windows"),
    MACOS("macOS"),
    IOS("iPhone & iPad"),
    ANDROID("Android"),
    LINUX("Linux"),
    ;

    companion object {
        fun fromUserAgent(userAgent: String?): ClientPlatform? {
            val ua = userAgent ?: return null
            return when {
                ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod") -> IOS
                ua.contains("Android") -> ANDROID
                ua.contains("Windows") -> WINDOWS
                ua.contains("Macintosh") || ua.contains("Mac OS X") -> MACOS
                ua.contains("Linux") || ua.contains("X11") -> LINUX
                else -> null
            }
        }
    }
}

/** One step of a client setup guide. Rendered both by the in-app guide and the setup web page. */
data class SetupStep(
    val text: String,
    val command: String? = null,
    val linkLabel: String? = null,
    val linkPath: String? = null,
)

/** Endpoint details shared with clients. */
data class TetherEndpoint(
    val networkName: String,
    val passphrase: String,
    val hostIp: String,
    val port: Int,
) {
    val proxyAddress: String get() = "$hostIp:$port"
    val baseUrl: String get() = "http://$hostIp:$port"
    val pacUrl: String get() = "$baseUrl/proxy.pac"
}

/**
 * Per-platform zero-config recipes.
 *
 * A non-rooted phone can't NAT packets for other devices, so every client sends its traffic to
 * the ClearTether proxy. The recipes below make that a one-time, one-tap step: an automatic
 * PAC URL (falls back to DIRECT on other networks, so it can stay on), an Apple configuration
 * profile that sets Wi-Fi and proxy together, or ClearTether's own Client mode on Android,
 * which is fully transparent through a local VPN.
 */
object SetupInstructions {

    fun steps(platform: ClientPlatform, endpoint: TetherEndpoint): List<SetupStep> = when (platform) {
        ClientPlatform.WINDOWS -> listOf(
            SetupStep("Join the Wi-Fi network \"${endpoint.networkName}\" with password \"${endpoint.passphrase}\"."),
            SetupStep(
                "Open http://${endpoint.proxyAddress} in a browser and run the one-click setup script. Or paste this into PowerShell (no admin needed):",
                command = "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' AutoConfigURL '${endpoint.pacUrl}'",
                linkLabel = "Download setup script",
                linkPath = "/setup/windows-enable.cmd",
            ),
            SetupStep("Or open Settings > Network & internet > Proxy > Use setup script, and enter the address ${endpoint.pacUrl}."),
            SetupStep("Restart browsers that were already open. The script falls back to a direct connection on other networks, so you can leave it enabled."),
            SetupStep("Corporate VPN: ClearTether tunnels TLS blindly (no certificate interception). VPN clients that support TCP/SSL transport through the system proxy (AnyConnect, GlobalProtect, Zscaler and others) connect normally. UDP-only VPN tunnels need an HTTPS/TCP fallback enabled."),
        )
        ClientPlatform.MACOS -> listOf(
            SetupStep("Join the Wi-Fi network \"${endpoint.networkName}\" with password \"${endpoint.passphrase}\"."),
            SetupStep(
                "Install the configuration profile. It sets the Wi-Fi network and the automatic proxy in one step. Then open System Settings > Privacy & Security > Profiles to approve it.",
                linkLabel = "Download profile",
                linkPath = "/cleartether.mobileconfig",
            ),
            SetupStep(
                "Or run this in Terminal:",
                command = "networksetup -setautoproxyurl Wi-Fi ${endpoint.pacUrl}",
            ),
            SetupStep("Or set it manually: System Settings > Wi-Fi > Details > Proxies > Automatic proxy configuration > ${endpoint.pacUrl}"),
        )
        ClientPlatform.IOS -> listOf(
            SetupStep("Point the Camera at the \"Join Wi-Fi\" QR code on the host phone and tap the banner to join \"${endpoint.networkName}\"."),
            SetupStep(
                "Open http://${endpoint.proxyAddress} in Safari (or scan the \"Setup page\" QR code) and install the profile. Then go to Settings > General > VPN & Device Management to install it.",
                linkLabel = "Install profile",
                linkPath = "/cleartether.mobileconfig",
            ),
            SetupStep("Or set it manually: Settings > Wi-Fi > (i) next to ${endpoint.networkName} > Configure Proxy > Automatic, and enter URL ${endpoint.pacUrl}"),
            SetupStep("iOS may label the network \"No Internet Connection\". Apps and Safari still work through the proxy."),
        )
        ClientPlatform.ANDROID -> listOf(
            SetupStep("Best: install ClearTether on the other phone and choose Connect. Scan this QR code or tap the phones together (NFC). Every app works with no proxy setup, through a local VPN."),
            SetupStep("Without the app: join \"${endpoint.networkName}\" by scanning the Wi-Fi QR code with the camera."),
            SetupStep("Then long-press the network > Modify > Advanced > Proxy > Proxy Auto-Config, and enter PAC URL ${endpoint.pacUrl}. Or choose Manual with host ${endpoint.hostIp} and port ${endpoint.port}."),
        )
        ClientPlatform.LINUX -> listOf(
            SetupStep("Join the Wi-Fi network \"${endpoint.networkName}\" with password \"${endpoint.passphrase}\"."),
            SetupStep(
                "GNOME / Ubuntu desktop:",
                command = "gsettings set org.gnome.system.proxy mode 'auto' && gsettings set org.gnome.system.proxy autoconfig-url '${endpoint.pacUrl}'",
            ),
            SetupStep(
                "Terminal tools:",
                command = "export http_proxy=http://${endpoint.proxyAddress} https_proxy=http://${endpoint.proxyAddress} all_proxy=socks5h://${endpoint.proxyAddress}",
            ),
        )
    }
}
