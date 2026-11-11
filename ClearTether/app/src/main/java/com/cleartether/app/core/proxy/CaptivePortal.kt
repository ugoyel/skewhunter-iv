package com.cleartether.app.core.proxy

import java.util.Locale

/**
 * Captive-portal mitigation.
 *
 * Every client OS probes a well-known URL after joining a network and decides from the answer
 * whether it has "real" internet. When a probe arrives through the proxy we answer it locally,
 * instantly, with the exact success response the OS expects. The client never shows a
 * "sign in to network" sheet or a "No internet" badge, and no cellular round trip is spent on
 * validation.
 *
 * Only plain-HTTP probes can be answered (they are plain HTTP by design). HTTPS probes such as
 * Android's `https://www.google.com/generate_204` pass through the blind CONNECT tunnel untouched.
 */
object CaptivePortal {

    private const val APPLE_SUCCESS = "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"

    private data class Probe(val host: String, val path: String?, val response: () -> HttpResponse)

    private val noContent = { HttpResponse(204, "No Content") }
    private val apple = { HttpResponse.text(200, "OK", APPLE_SUCCESS, "text/html") }

    private val probes = listOf(
        // Android / ChromeOS / Chrome
        Probe("connectivitycheck.gstatic.com", "/generate_204", noContent),
        Probe("connectivitycheck.android.com", "/generate_204", noContent),
        Probe("clients3.google.com", "/generate_204", noContent),
        Probe("clients1.google.com", "/generate_204", noContent),
        Probe("www.google.com", "/generate_204", noContent),
        Probe("www.gstatic.com", "/generate_204", noContent),
        Probe("play.googleapis.com", "/generate_204", noContent),
        // OEM variants
        Probe("connect.rom.miui.com", "/generate_204", noContent),
        Probe("connectivitycheck.platform.hicloud.com", "/generate_204", noContent),
        Probe("connectivitycheck.smartisan.com", "/wifi.html", noContent),
        // Apple iOS / iPadOS / macOS
        Probe("captive.apple.com", null, apple),
        Probe("www.apple.com", "/library/test/success.html", apple),
        Probe("www.appleiphonecell.com", null, apple),
        Probe("www.itools.info", null, apple),
        Probe("www.ibook.info", null, apple),
        Probe("www.airport.us", null, apple),
        Probe("www.thinkdifferent.us", null, apple),
        // Windows NCSI
        Probe("www.msftconnecttest.com", "/connecttest.txt") { HttpResponse.text(200, "OK", "Microsoft Connect Test") },
        Probe("ipv6.msftconnecttest.com", "/connecttest.txt") { HttpResponse.text(200, "OK", "Microsoft Connect Test") },
        Probe("www.msftncsi.com", "/ncsi.txt") { HttpResponse.text(200, "OK", "Microsoft NCSI") },
        Probe("ipv6.msftncsi.com", "/ncsi.txt") { HttpResponse.text(200, "OK", "Microsoft NCSI") },
        // Firefox
        Probe("detectportal.firefox.com", "/success.txt") { HttpResponse.text(200, "OK", "success\n") },
        Probe("detectportal.firefox.com", "/canonical.html") {
            HttpResponse.html(
                200, "OK",
                "<meta http-equiv=\"refresh\" content=\"0;url=https://support.mozilla.org/kb/captive-portal\"/>",
            )
        },
        // Linux NetworkManager
        Probe("nmcheck.gnome.org", "/check_network_status.txt") { networkManagerOnline() },
        Probe("network-test.debian.org", "/nm") { networkManagerOnline() },
        Probe("connectivity-check.ubuntu.com", null) {
            HttpResponse(204, "No Content", extraHeaders = listOf("X-NetworkManager-Status" to "online"))
        },
        Probe("fedoraproject.org", "/static/hotspot.txt") { HttpResponse.text(200, "OK", "OK\n") },
    )

    private fun networkManagerOnline() = HttpResponse.text(
        200, "OK", "NetworkManager is online\n",
    )

    /** Returns the canned success response for a probe request, or null if this is not a probe. */
    fun match(host: String, pathAndQuery: String): HttpResponse? {
        val h = host.lowercase(Locale.ROOT).removeSuffix(".")
        val path = pathAndQuery.substringBefore('?')
        return probes.firstOrNull { it.host == h && (it.path == null || it.path.equals(path, ignoreCase = true)) }
            ?.response?.invoke()
    }

    /** Hostnames recognised as probes (exposed for the setup page and tests). */
    val knownHosts: Set<String> get() = probes.mapTo(LinkedHashSet()) { it.host }
}
