package com.cleartether.app.core.proxy

import java.util.Base64
import java.util.UUID

/** Live facts the setup site shows. Supplied by the host service. */
data class HostSiteInfo(
    val endpoint: TetherEndpoint,
    val paused: Boolean = false,
    val statusLine: String? = null,
)

/**
 * The small web site the proxy serves at `http://192.168.49.1:8228/`. A freshly joined client
 * that has not configured a proxy yet can still reach this address directly on the local subnet.
 *
 * Routes:
 *  - `/`                        setup portal with per-OS one-tap configuration
 *  - `/proxy.pac`, `/wpad.dat`  proxy auto-config script
 *  - `/cleartether.mobileconfig` Apple profile (Wi-Fi + automatic proxy)
 *  - `/setup/windows-*.cmd`     per-user Windows enable/disable scripts
 *  - `/status.json`             machine-readable capabilities (used by ClearTether Client mode)
 *  - `/dns-query`               RFC 8484 DNS-over-HTTP endpoint (plain HTTP on the local link)
 */
class LocalSite(private val info: () -> HostSiteInfo) {

    suspend fun handle(head: HttpRequestHead, body: ByteArray, dns: DnsBackend?): HttpResponse {
        val path = head.path.lowercase()
        if (head.method != "GET" && head.method != "HEAD" && !(path == "/dns-query" && head.method == "POST")) {
            return HttpResponse.text(405, "Method Not Allowed", "Method not allowed\n")
        }
        val site = info()
        return when (path) {
            "/", "/index.html", "/setup" -> HttpResponse.html(200, "OK", portalHtml(site, ClientPlatform.fromUserAgent(head.header("User-Agent"))))
            "/proxy.pac", "/wpad.dat", "/proxy.pac.js" ->
                HttpResponse.text(200, "OK", pac(site.endpoint), "application/x-ns-proxy-autoconfig")
            "/cleartether.mobileconfig" -> HttpResponse(
                200, "OK", "application/x-apple-aspen-config",
                appleProfile(site.endpoint).toByteArray(Charsets.UTF_8),
                listOf("Content-Disposition" to "attachment; filename=\"ClearTether.mobileconfig\""),
            )
            "/setup/windows-enable.cmd" -> attachment(windowsEnableScript(site.endpoint), "ClearTether-enable.cmd")
            "/setup/windows-disable.cmd" -> attachment(windowsDisableScript(), "ClearTether-disable.cmd")
            "/status.json" -> HttpResponse.text(200, "OK", statusJson(site), "application/json")
            "/dns-query" -> dnsQuery(head, body, dns)
            "/generate_204", "/favicon.ico" -> HttpResponse(204, "No Content")
            else -> HttpResponse.errorPage(404, "Not found", "There is nothing at ${head.path}.")
        }
    }

    private suspend fun dnsQuery(head: HttpRequestHead, body: ByteArray, dns: DnsBackend?): HttpResponse {
        if (dns == null) return HttpResponse.text(501, "Not Implemented", "DNS relay unavailable\n")
        val query: ByteArray = if (head.method == "POST") body else {
            val param = head.query?.split('&')?.firstOrNull { it.startsWith("dns=") }?.substring(4)
                ?: return HttpResponse.text(400, "Bad Request", "Missing dns parameter\n")
            runCatching { Base64.getUrlDecoder().decode(param) }.getOrNull()
                ?: return HttpResponse.text(400, "Bad Request", "Invalid dns parameter\n")
        }
        if (query.size < 12 || query.size > 65_535) return HttpResponse.text(400, "Bad Request", "Invalid DNS message\n")
        return try {
            HttpResponse(200, "OK", "application/dns-message", dns.query(query))
        } catch (e: Exception) {
            HttpResponse.text(502, "Bad Gateway", "DNS resolution failed\n")
        }
    }

    private fun attachment(text: String, filename: String) = HttpResponse(
        200, "OK", "application/octet-stream", text.replace("\n", "\r\n").toByteArray(Charsets.UTF_8),
        listOf("Content-Disposition" to "attachment; filename=\"$filename\""),
    )

    companion object {
        /** Requests through the proxy for this host are answered by the proxy itself (setup-page probe). */
        const val PROXY_CHECK_HOST = "check.cleartether.invalid"

        fun pac(endpoint: TetherEndpoint): String {
            val subnet = endpoint.hostIp.substringBeforeLast('.') + ".*"
            return """
                |// ClearTether automatic proxy configuration.
                |// Everything goes through the tether; local names stay direct. "DIRECT" is the fallback
                |// when the tether is unreachable (for example on another network).
                |function FindProxyForURL(url, host) {
                |  if (isPlainHostName(host) ||
                |      host == "localhost" ||
                |      shExpMatch(host, "*.local") ||
                |      shExpMatch(host, "127.*") ||
                |      shExpMatch(host, "$subnet")) {
                |    return "DIRECT";
                |  }
                |  return "PROXY ${endpoint.proxyAddress}; DIRECT";
                |}
                |""".trimMargin()
        }

        fun appleProfile(endpoint: TetherEndpoint): String {
            val wifiUuid = UUID.nameUUIDFromBytes("cleartether-wifi:${endpoint.networkName}".toByteArray()).toString().uppercase()
            val profileUuid = UUID.nameUUIDFromBytes("cleartether-profile:${endpoint.networkName}".toByteArray()).toString().uppercase()
            val ssid = xml(endpoint.networkName)
            return """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                |<plist version="1.0">
                |<dict>
                |  <key>PayloadContent</key>
                |  <array>
                |    <dict>
                |      <key>AutoJoin</key><true/>
                |      <key>EncryptionType</key><string>WPA2</string>
                |      <key>HIDDEN_NETWORK</key><false/>
                |      <key>IsHotspot</key><false/>
                |      <key>Password</key><string>${xml(endpoint.passphrase)}</string>
                |      <key>ProxyType</key><string>Auto</string>
                |      <key>ProxyPACURL</key><string>${xml(endpoint.pacUrl)}</string>
                |      <key>ProxyPACFallbackAllowed</key><false/>
                |      <key>SSID_STR</key><string>$ssid</string>
                |      <key>PayloadDescription</key><string>Joins $ssid and routes traffic through the ClearTether proxy.</string>
                |      <key>PayloadDisplayName</key><string>ClearTether Wi-Fi</string>
                |      <key>PayloadIdentifier</key><string>app.cleartether.wifi.$wifiUuid</string>
                |      <key>PayloadType</key><string>com.apple.wifi.managed</string>
                |      <key>PayloadUUID</key><string>$wifiUuid</string>
                |      <key>PayloadVersion</key><integer>1</integer>
                |    </dict>
                |  </array>
                |  <key>PayloadDescription</key><string>Connects this device to the internet shared by a ClearTether phone.</string>
                |  <key>PayloadDisplayName</key><string>ClearTether</string>
                |  <key>PayloadIdentifier</key><string>app.cleartether.profile.$profileUuid</string>
                |  <key>PayloadOrganization</key><string>ClearTether</string>
                |  <key>PayloadRemovalDisallowed</key><false/>
                |  <key>PayloadType</key><string>Configuration</string>
                |  <key>PayloadUUID</key><string>$profileUuid</string>
                |  <key>PayloadVersion</key><integer>1</integer>
                |</dict>
                |</plist>
                |""".trimMargin()
        }

        fun windowsEnableScript(endpoint: TetherEndpoint): String = """
            |@echo off
            |rem ClearTether: enables the automatic proxy for the current Windows user (no admin rights needed).
            |set KEY=HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings
            |reg add "%KEY%" /v AutoConfigURL /t REG_SZ /d "${endpoint.pacUrl}" /f >nul
            |reg add "%KEY%" /v ProxyEnable /t REG_DWORD /d 0 /f >nul
            |echo.
            |echo  ClearTether proxy enabled.
            |echo  Restart any browser that was already open.
            |echo  On other networks Windows falls back to a direct connection automatically.
            |echo.
            |pause
            |""".trimMargin()

        fun windowsDisableScript(): String = """
            |@echo off
            |rem ClearTether: removes the automatic proxy for the current Windows user.
            |reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v AutoConfigURL /f >nul 2>&1
            |echo ClearTether proxy removed.
            |pause
            |""".trimMargin()

        fun statusJson(site: HostSiteInfo): String =
            """{"app":"ClearTether","protocol":1,"proxy":"${site.endpoint.proxyAddress}","paused":${site.paused},""" +
                """"features":["http","connect","socks5","socks5-udp","socks4","doh","pac","captive-portal"]}"""

        private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

        fun portalHtml(site: HostSiteInfo, detected: ClientPlatform?): String {
            val e = site.endpoint
            val order = ClientPlatform.entries.sortedBy { if (it == detected) 0 else 1 }
            val sections = order.joinToString("\n") { platform ->
                val steps = SetupInstructions.steps(platform, e).joinToString("") { step ->
                    buildString {
                        append("<li><p>").append(escapeHtml(step.text)).append("</p>")
                        if (step.command != null) {
                            append("<div class=\"cmd\"><code>").append(escapeHtml(step.command))
                                .append("</code><button onclick=\"copyText(this)\">Copy</button></div>")
                        }
                        if (step.linkPath != null) {
                            append("<a class=\"btn\" href=\"").append(step.linkPath).append("\">")
                                .append(escapeHtml(step.linkLabel ?: "Open")).append("</a>")
                        }
                        append("</li>")
                    }
                }
                val open = if (platform == detected || (detected == null && platform == ClientPlatform.WINDOWS)) " open" else ""
                """<details$open><summary>${escapeHtml(platform.displayName)}${if (platform == detected) " <span class=\"tag\">This device</span>" else ""}</summary><ol>$steps</ol></details>"""
            }
            val status = site.statusLine?.let { "<p class=\"muted\">${escapeHtml(it)}</p>" } ?: ""
            val paused = if (site.paused) "<div class=\"warn\">The free session on the host phone has ended. Open ClearTether on the host to continue.</div>" else ""
            return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ClearTether setup</title>
<style>
:root{--bg:#f4faf8;--card:#fff;--fg:#0c1c1a;--muted:#55706b;--accent:#0b9e8a;--accent2:#3d5afe;--line:#dbe8e5}
@media (prefers-color-scheme:dark){:root{--bg:#0b1416;--card:#122023;--fg:#e6f4f1;--muted:#93aea9;--line:#21363a}}
*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--fg)}
header{background:linear-gradient(135deg,var(--accent),var(--accent2));color:#fff;padding:28px 20px 36px}
header h1{margin:0;font-size:24px}header p{margin:6px 0 0;opacity:.9}
main{max-width:720px;margin:-20px auto 40px;padding:0 16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px 18px;margin-bottom:14px}
.row{display:flex;justify-content:space-between;gap:12px;padding:6px 0;border-bottom:1px solid var(--line)}.row:last-child{border:0}
.k{color:var(--muted)}.v{font-weight:600;word-break:break-all;text-align:right}
details{background:var(--card);border:1px solid var(--line);border-radius:16px;margin-bottom:10px;padding:4px 18px}
summary{cursor:pointer;font-weight:700;padding:12px 0;font-size:17px}ol{padding-left:20px}li{margin-bottom:12px}li p{margin:0 0 8px;line-height:1.45}
.cmd{display:flex;gap:8px;align-items:stretch}.cmd code{flex:1;background:var(--bg);border:1px solid var(--line);border-radius:10px;padding:10px;font-size:13px;overflow-x:auto;white-space:pre-wrap;word-break:break-all}
button,.btn{background:var(--accent);color:#fff;border:0;border-radius:10px;padding:10px 14px;font-weight:600;text-decoration:none;display:inline-block;cursor:pointer}
.tag{font-size:12px;background:var(--accent);color:#fff;border-radius:999px;padding:2px 8px;margin-left:6px;vertical-align:middle}
.muted{color:var(--muted)}.ok{color:#12b886;font-weight:700}.bad{color:#f59f00;font-weight:700}
.warn{background:#fff4e5;color:#7a4b00;border-radius:12px;padding:12px 14px;margin-bottom:14px}
</style></head><body>
<header><h1>ClearTether</h1><p>Use this phone's internet on any device, with no hotspot plan.</p></header>
<main>
$paused
<div class="card">
<div class="row"><span class="k">Wi-Fi network</span><span class="v">${escapeHtml(e.networkName)}</span></div>
<div class="row"><span class="k">Password</span><span class="v">${escapeHtml(e.passphrase)}</span></div>
<div class="row"><span class="k">Proxy</span><span class="v">${escapeHtml(e.proxyAddress)}</span></div>
<div class="row"><span class="k">Auto-config URL</span><span class="v">${escapeHtml(e.pacUrl)}</span></div>
<div class="row"><span class="k">This device</span><span class="v" id="check">Checking...</span></div>
$status
</div>
$sections
<p class="muted" style="font-size:13px">ClearTether tunnels HTTPS without decrypting it. Certificates, SSL pinning and corporate security tools keep working.</p>
</main>
<script>
function copyText(b){var t=b.previousElementSibling.textContent;function done(){b.textContent='Copied';setTimeout(function(){b.textContent='Copy'},1500)}
if(navigator.clipboard){navigator.clipboard.writeText(t).then(done,function(){fallback(t);done()})}else{fallback(t);done()}}
function fallback(t){var a=document.createElement('textarea');a.value=t;document.body.appendChild(a);a.select();try{document.execCommand('copy')}catch(e){}document.body.removeChild(a)}
(function(){var el=document.getElementById('check');var done=false;
function set(ok){if(done)return;done=true;el.className=ok?'v ok':'v bad';el.textContent=ok?'Proxy active: you are online':'Proxy not configured yet. Follow the steps below.'}
setTimeout(function(){set(false)},4000);
fetch('http://$PROXY_CHECK_HOST/ping?'+Date.now(),{cache:'no-store'}).then(function(r){return r.json()}).then(function(j){set(!!j.proxied)}).catch(function(){set(false)})})();
</script>
</body></html>"""
        }
    }
}
