package com.cleartether.app.core.proxy

/**
 * Every kind of device someone may want to connect, with the best way to do it.
 * [shortName] is used on chips/tabs and [displayName] as a heading.
 */
enum class ClientPlatform(val shortName: String, val displayName: String) {
    ANDROID_APP_NFC("Android + app (NFC)", "Android phone or tablet with ClearTether: tap to connect (NFC)"),
    ANDROID_APP_QR("Android + app (QR)", "Android phone or tablet with ClearTether: scan a QR code (no NFC needed)"),
    ANDROID_NO_APP("Android (no app)", "Android phone or tablet without the ClearTether app"),
    IOS("iPhone / iPad", "iPhone & iPad (any model, with or without NFC)"),
    WINDOWS("Windows", "Windows laptop or PC (personal)"),
    WINDOWS_WORK("Work Windows", "Work / corporate Windows laptop"),
    MAC("Mac", "Mac / MacBook (personal)"),
    MAC_WORK("Work Mac", "Work / company-managed Mac"),
    CHROMEBOOK("Chromebook", "Chromebook"),
    LINUX("Linux", "Linux laptop or PC"),
    OTHER("TV & consoles", "Game consoles, TV boxes and other devices"),
    ;

    companion object {
        /** Best guess from a browser User-Agent (used by the setup web page). */
        fun fromUserAgent(userAgent: String?): ClientPlatform? {
            val ua = userAgent ?: return null
            return when {
                ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod") -> IOS
                ua.contains("Android") -> ANDROID_NO_APP
                ua.contains("CrOS") -> CHROMEBOOK
                ua.contains("Windows") -> WINDOWS
                ua.contains("Macintosh") || ua.contains("Mac OS X") -> MAC
                ua.contains("PlayStation") || ua.contains("Nintendo") || ua.contains("SMART-TV") -> OTHER
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
    /** A hint or caveat rather than an action. */
    val isTip: Boolean = false,
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
 * Per-device recipes, from fully automatic (Android with the app) to one-time proxy setup
 * (laptops, iPhones). A non-rooted phone can't route other devices' packets, so non-Android
 * devices send their traffic to the ClearTether relay via an automatic proxy (PAC) setting. It
 * falls back to a direct connection on other networks, so it can stay switched on.
 */
object SetupInstructions {

    fun steps(platform: ClientPlatform, e: TetherEndpoint): List<SetupStep> = when (platform) {
        ClientPlatform.ANDROID_APP_NFC -> listOf(
            SetupStep("Install ClearTether on the other phone, open it and choose \"Use internet\". Check NFC is on (Settings > Connected devices > NFC)."),
            SetupStep("On this phone, keep ClearTether open with sharing on, and unlock it."),
            SetupStep("Hold the two phones back to back for a second. The other phone saves the network and starts connecting."),
            SetupStep("First time only: approve the \"Connect to device\" and VPN prompts. Next time, just tap the power button in \"Use internet\"."),
            SetupStep("Every app works (browser, WhatsApp, YouTube, games and UDP apps) with no proxy settings.", isTip = true),
        )
        ClientPlatform.ANDROID_APP_QR -> listOf(
            SetupStep("Install ClearTether on the other device, open it and choose \"Use internet\"."),
            SetupStep("Tap \"Scan QR\" and scan the \"Join Wi-Fi\" code on this phone. No camera? Tap \"Enter manually\" and type network \"${e.networkName}\" and password \"${e.passphrase}\"."),
            SetupStep("Tap the power button. First time only: approve the \"Connect to device\" and VPN prompts."),
            SetupStep("Every app works with no proxy settings. Works on tablets and phones without NFC.", isTip = true),
        )
        ClientPlatform.ANDROID_NO_APP -> listOf(
            SetupStep("Join the network: point the camera at the \"Join Wi-Fi\" QR code. Or, if both phones have NFC, tap them together and accept \"Connect to network?\". Or pick \"${e.networkName}\" in Wi-Fi settings and enter password \"${e.passphrase}\"."),
            SetupStep("In Wi-Fi settings, open the network's details (gear or pencil icon) > Advanced / View more > Proxy > Proxy Auto-Config, and enter:", command = e.pacUrl),
            SetupStep("Or choose Proxy > Manual with hostname ${e.hostIp} and port ${e.port}."),
            SetupStep("Browsers and most apps now work. For every app (including games and calls), install ClearTether and use \"Use internet\" mode instead.", isTip = true),
        )
        ClientPlatform.IOS -> listOf(
            SetupStep("Open the Camera, point it at the \"Join Wi-Fi\" QR code on this phone and tap \"Join Network\". (Or Settings > Wi-Fi > \"${e.networkName}\" > password \"${e.passphrase}\".)"),
            SetupStep(
                "Scan the \"Setup page\" QR code (or open ${e.baseUrl} in Safari) and tap \"Install profile\". Then open Settings > General > VPN & Device Management > ClearTether > Install. This sets the Wi-Fi and the automatic proxy in one go.",
                linkLabel = "Install profile",
                linkPath = "/cleartether.mobileconfig",
            ),
            SetupStep("Prefer not to install a profile? Settings > Wi-Fi > (i) next to \"${e.networkName}\" > Configure Proxy > Automatic, and enter:", command = e.pacUrl),
            SetupStep("NFC: iPhones can't join Wi-Fi from an NFC tap, so use the QR code. It works the same on every iPhone and iPad, with or without NFC.", isTip = true),
            SetupStep("iOS may show \"No Internet Connection\" under the network. Safari and apps still work through ClearTether. If an app seems to use mobile data instead, turn off Settings > Mobile Data > Wi-Fi Assist.", isTip = true),
        )
        ClientPlatform.WINDOWS -> listOf(
            SetupStep("Click the Wi-Fi icon in the taskbar, choose \"${e.networkName}\" and enter password \"${e.passphrase}\". (Windows 11 can also scan the Wi-Fi QR code with the Camera app.)"),
            SetupStep(
                "Open ${e.baseUrl} in your browser and run the one-click setup script (if SmartScreen warns, choose More info > Run anyway). Or paste this into PowerShell (no admin needed):",
                command = "Set-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' AutoConfigURL '${e.pacUrl}'",
                linkLabel = "Download setup script",
                linkPath = "/setup/windows-enable.cmd",
            ),
            SetupStep("Or set it by hand: Settings > Network & internet > Proxy > Use setup script > Set up > On, and enter:", command = e.pacUrl),
            SetupStep("Restart browsers that were already open. On other networks Windows connects directly, so you can leave the setting on. To remove it, run the disable script or switch \"Use setup script\" off.", isTip = true),
        )
        ClientPlatform.WINDOWS_WORK -> listOf(
            SetupStep("Join \"${e.networkName}\" (password \"${e.passphrase}\") from the Wi-Fi menu. If your company only allows approved Wi-Fi networks, ClearTether can't override that: ask IT."),
            SetupStep("Set the automatic proxy for your user account (no admin rights needed): Settings > Network & internet > Proxy > Use setup script, and enter:", command = e.pacUrl),
            SetupStep("If those settings are greyed out (\"Some settings are managed by your organization\"), your proxy is locked by company policy. Firefox can still use its own setting (Settings > Network Settings > Automatic proxy configuration URL), or ask IT to allow it.", isTip = true),
            SetupStep("Then connect your company VPN as usual. VPNs that run over HTTPS/TCP connect through ClearTether: Cisco Secure Client/AnyConnect (SSL), GlobalProtect, Zscaler, FortiClient SSL, Ivanti/Pulse. IPsec- or WireGuard-only VPNs can't. Ask IT for the SSL/TCP option.", isTip = true),
            SetupStep("ClearTether never decrypts HTTPS, so certificates, SSL pinning and endpoint-security tools see a normal connection. Teams and Zoom calls switch to TCP automatically.", isTip = true),
        )
        ClientPlatform.MAC -> listOf(
            SetupStep("Click the Wi-Fi icon in the menu bar, choose \"${e.networkName}\" and enter password \"${e.passphrase}\"."),
            SetupStep(
                "Open ${e.baseUrl} in Safari and download the profile, then go to System Settings > General > Device Management (or Privacy & Security > Profiles) and install it.",
                linkLabel = "Download profile",
                linkPath = "/cleartether.mobileconfig",
            ),
            SetupStep("Or run this in Terminal:", command = "networksetup -setautoproxyurl Wi-Fi ${e.pacUrl}"),
            SetupStep("Or by hand: System Settings > Wi-Fi > Details (next to the network) > Proxies > Automatic proxy configuration > ${e.pacUrl}"),
            SetupStep("To undo: remove the profile, or run \"networksetup -setautoproxystate Wi-Fi off\".", isTip = true),
        )
        ClientPlatform.MAC_WORK -> listOf(
            SetupStep("Join \"${e.networkName}\" (password \"${e.passphrase}\") from the Wi-Fi menu."),
            SetupStep("Company-managed Macs often block new profiles, so set the proxy by hand: System Settings > Wi-Fi > Details > Proxies > Automatic proxy configuration, and enter:", command = e.pacUrl),
            SetupStep("If proxy settings are locked by your company (MDM), ask IT, or use Firefox's own proxy setting.", isTip = true),
            SetupStep("Company VPNs that run over HTTPS/TCP (GlobalProtect, Cisco Secure Client, Zscaler and similar) connect through ClearTether; IPsec/WireGuard-only ones can't.", isTip = true),
        )
        ClientPlatform.CHROMEBOOK -> listOf(
            SetupStep("Open the quick settings > Wi-Fi > \"${e.networkName}\" and enter password \"${e.passphrase}\"."),
            SetupStep("Settings > Network > Wi-Fi > \"${e.networkName}\" > Proxy > Connection type: Automatic proxy configuration, and enter:", command = e.pacUrl),
            SetupStep("School or work Chromebooks may lock proxy settings; ask your administrator.", isTip = true),
        )
        ClientPlatform.LINUX -> listOf(
            SetupStep("Join \"${e.networkName}\" with password \"${e.passphrase}\"."),
            SetupStep(
                "GNOME / Ubuntu desktop:",
                command = "gsettings set org.gnome.system.proxy mode 'auto' && gsettings set org.gnome.system.proxy autoconfig-url '${e.pacUrl}'",
            ),
            SetupStep("KDE: System Settings > Network > Proxy > Use proxy auto configuration URL > ${e.pacUrl}"),
            SetupStep(
                "Terminal tools (curl, git, apt, pip):",
                command = "export http_proxy=http://${e.proxyAddress} https_proxy=http://${e.proxyAddress} all_proxy=socks5h://${e.proxyAddress}",
            ),
        )
        ClientPlatform.OTHER -> listOf(
            SetupStep("Devices with a manual proxy setting (PlayStation, Nintendo Switch, many Android TV boxes): join \"${e.networkName}\" with password \"${e.passphrase}\", then set Proxy to Manual, server ${e.hostIp}, port ${e.port}."),
            SetupStep("Apps that can use a SOCKS5 proxy (such as Telegram or Firefox) can use ${e.proxyAddress} as SOCKS5 too."),
            SetupStep("Devices without any proxy setting (Chromecast, most smart TVs, Xbox, Roku, car head units) can't use ClearTether. Use your phone's regular hotspot for those.", isTip = true),
        )
    }
}
