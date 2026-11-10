# ClearTether: technical blueprint

ClearTether shares a phone's mobile data with laptops, Macs, iPhones and Android devices
over **Wi-Fi Direct**, without the carrier hotspot feature and **without root**. This document
explains how the pieces fit, which parts of the original brief a non-rooted phone can and
cannot do, and the design decisions behind them.

---

## 1. What a non-rooted Android phone can and cannot do

| Capability | Non-root? | Consequence |
|---|---|---|
| Create a WPA2 Wi-Fi network (Wi-Fi Direct group owner) | ✅ `WifiP2pManager` | The network other devices join |
| Forward/NAT packets from that network to cellular (`ip_forward`, iptables) | ❌ | Clients can't get "raw" internet. Every byte must go through a user-space relay |
| Listen on TCP ports ≥ 1024 on the group interface | ✅ | The relay is a proxy server on `192.168.49.1:8228` |
| Listen on port 53/80 (DNS/HTTP interception for *other* devices) | ❌ (privileged ports, and the system already owns 53) | DNS hijacking of clients isn't possible; captive-portal answers happen inside the proxy |
| `VpnService` capturing traffic of **this** device's apps | ✅ | Used on Android *clients* to make them fully transparent |
| `VpnService` capturing traffic **arriving from other devices** on the P2P interface | ❌ | The VPN TUN only sees packets that originate on the same device |

The original brief assumed the last row was possible: a host-side VPN that "captures all
incoming traffic originating from the Wi-Fi Direct network interface". It isn't. Android
routes only locally generated traffic into a VPN TUN, and a non-root app can't change
routing or turn on forwarding. Any app claiming that is either rooted or uses a proxy, as
ClearTether does.

So ClearTether splits "transparency" into the two places where it *is* achievable:

1. **Android clients**: fully transparent. ClearTether's *Use internet* mode runs a local
   `VpnService` + user-space TCP/IP stack on the client and relays every app's traffic to the host.
   There are no proxy settings, and every app works, including UDP apps through SOCKS5 UDP.
2. **Laptops / Macs / iPhones**: zero-touch after one-time setup. A QR code joins the
   network, and a PAC auto-config URL, Apple configuration profile or one-line script points the
   system proxy at the host, once. Credentials are stable, so devices auto-join and auto-proxy afterwards.

## 2. Component map

```
                ┌──────────────────────── HOST PHONE (mobile data) ───────────────────────┐
                │                                                                          │
 laptop ──Wi-Fi─┤  WifiDirectManager ── autonomous GO group  "DIRECT-CT-…" / WPA2           │
 (PAC proxy)    │        │ 192.168.49.1                                                    │
 iPhone ──Wi-Fi─┤        ▼                                                                 │
 (profile)      │  TetherProxyEngine :8228 (coroutines + AsynchronousSocketChannel)        │
 Android ─Wi-Fi─┤    ├ HTTP CONNECT ─ blind L4 tunnel (TLS untouched) ─┐                   │
 (Client VPN)   │    ├ HTTP forward (absolute URI, hop-by-hop stripped)├─► cellular ─► 🌐  │
                │    ├ SOCKS5 CONNECT / UDP ASSOCIATE, SOCKS4a         ┘                   │
                │    ├ CaptivePortal: instant OS connectivity-probe answers                │
                │    └ LocalSite: setup portal, PAC/WPAD, .mobileconfig, scripts, DoH      │
                │  TetherSessionManager ── free-tier meter (15 min / 100 MB) → paywall     │
                │  TetherForegroundService (connectedDevice) ── locks, recovery, teardown  │
                │  NfcShareService (HCE Type 4 tag: WSC credential + URL + AAR)            │
                └──────────────────────────────────────────────────────────────────────────┘

                ┌──────────────── ANDROID CLIENT (ClearTether "Use internet") ──────────────┐
                │ apps ─► TUN 10.0.0.2/32 + fd00:c1ea:7e7e::2/128, routes 0/0 and ::/0      │
                │        ─► UserSpaceNetStack ─► TcpFlow (handshake, windows, retransmit)   │
                │                              ─► CONNECT tunnel to host                    │
                │                           ─► DNS: cache → host /dns-query (DoH fallback)  │
                │                           ─► UDP: SOCKS5 UDP ASSOCIATE (QUIC, VoIP)       │
                │ ClientWifiConnector: WifiNetworkSpecifier (app-local network), process     │
                │ bound to it; ClearTether itself excluded from the VPN (no loops)           │
                └──────────────────────────────────────────────────────────────────────────┘
```

| File | Responsibility |
|---|---|
| `wifi/WifiDirectManager.kt` | `WifiP2pConfig.Builder` group with fixed SSID/passphrase, persistent mode, 5 GHz to AUTO band fallback, BUSY retry with backoff, reads SSID/passphrase/IP back from `requestGroupInfo`, broadcast `Flow` |
| `core/proxy/TetherProxyEngine.kt` | Single-port multi-protocol proxy, non-blocking, per-connection jobs, pause/resume/stop |
| `core/proxy/CaptivePortal.kt` | Canned success responses for Android/ChromeOS, Apple, Windows NCSI, Firefox, NetworkManager probes |
| `core/proxy/LocalSite.kt`, `SetupInstructions.kt` | Setup portal with live "proxy active" check, PAC, Apple profile, Windows scripts, `/status.json`, RFC 8484 DoH |
| `core/net/AddressPolicy.kt` | Client ACL (group subnet only) + destination ACL (no loopback / link-local / multicast / tether subnet, checked after DNS) |
| `core/session/TetherSessionManager.kt` | Throughput + duration accounting, free-tier enforcement, persistence, limit events |
| `core/tun/*` | IPv4/IPv6 parsing, checksums, `TcpFlow` state machine, reassembly, NewReno recovery, DNS forwarder + cache, SOCKS5 UDP client |
| `service/TransparentTetherVpnService.kt` | Client-mode VPN: join, probe host capabilities, `Builder` config, stack lifecycle |
| `service/TetherForegroundService.kt` | Host lifecycle: locks, group supervision and auto-recovery, paywall grace period, ordered teardown |
| `billing/BillingRepository.kt` | Play Billing 9 lifetime product, verification, acknowledgement, restore, offline cache |
| `MainActivity.kt` + `ui/*` | Compose UI: one-switch host/client, QR, NFC, session countdown, paywall, setup guide, settings |

## 3. Network transparency protocol

### 3.1 Blind HTTPS tunnelling (corporate laptops)
`CONNECT host:port` → upstream connect (Happy-Eyeballs-lite across A/AAAA) → `200` →
bidirectional byte copy. The engine never parses TLS, never terminates it and holds no CA.
Certificate pinning, mTLS, EDR "MITM detected" checks and HSTS see an untouched path. Bytes
pipelined behind the `CONNECT` head (TLS 1.3 early data, eager ClientHello) are forwarded
first. Half-closes propagate. Idle tunnels close after 30 minutes with no traffic either way.

**Corporate VPN clients:** anything carried over TCP/TLS that honours the system proxy works
(AnyConnect SSL, GlobalProtect HTTPS, Zscaler, Netskope, browser-based ZTNA). UDP-only tunnels
(IPsec/IKE, WireGuard, DTLS without TCP fallback) can't cross an HTTP proxy from a laptop.
Enable the vendor's TCP fallback. Android devices in Client mode do carry UDP (SOCKS5 UDP).

### 3.2 Captive-portal mitigation
When a client OS validates connectivity *through the proxy*, the probe is answered locally with
the byte-exact success body (`204` for `generate_204`, Apple's `Success` page, `Microsoft
Connect Test`, and so on). The OS shows "Connected" instantly, and no cellular round-trip
is spent on validation. HTTPS probes go through the blind tunnel unchanged. Toggle: *Settings →
Captive-portal assist*.

### 3.3 DNS
- Laptop/iPhone clients resolve names *on the host*: the proxy receives hostnames in
  `CONNECT`/absolute URIs.
- Android clients: the VPN advertises virtual resolver `10.0.0.1`. UDP/53 to *any* address is
  intercepted, cached (TTL-aware, 10 s–5 min) and resolved by the host through
  `DnsResolver.rawQuery`, which honours the host's Private DNS. Fallback for non-ClearTether
  proxies: DoH to `cloudflare-dns.com` through a CONNECT tunnel with full certificate checks.
  Truncated answers switch to TCP/53, which the stack serves locally.

### 3.4 User-space TCP (Client mode)
`TcpFlow` answers SYN only after the upstream tunnel is open, so a refused destination becomes
an immediate RST instead of a hang. It advertises a receive window equal to the free space in
the upstream queue, so a slow cellular uplink pushes back on the app instead of buffering
without limit. It respects the app's window and sends zero-window probes. It buffers
out-of-order segments, retransmits on RTO (250 ms, exponential backoff), fast-retransmits on
3 duplicate ACKs, and does NewReno partial-ACK recovery. It answers keep-alive and
window probes. Window scaling, SACK and timestamps aren't offered: the TUN path RTT is
below a millisecond, so 64 KiB windows are plenty.

These behaviours were found and verified by running the stack against the **real Linux kernel
TCP implementation** through a TUN device with 2% random packet loss in both directions
(`tools/tun-e2e`, `KernelTunE2ETest`). Without the reassembly queue and probe ACKs, Karn's
algorithm drove the kernel's RTO backoff to 13 s and transfers stalled. With them, 16 MiB
full-duplex transfers finish byte-exact in about 4 s under loss and about 1 s without.

## 4. Monetisation

`TetherSessionManager` meters every relayed byte through atomics on the hot path.

- **Free tier:** each session gets **15 minutes or 100 MB**, whichever comes first. Usage
  *carries over* when sharing is stopped and restarted, so toggling can't reset it. The allowance refills
  after **10 minutes** idle (`FreeTierPolicy` sets all three numbers).
- **Data limit:** fires synchronously on the relay thread that crosses it. `TetherProxyEngine.pause()`
  closes every tunnel, and new requests get a friendly `503` page explaining how to upgrade.
- **Time limit:** a 1 Hz ticker drives the countdown in the UI and the notification.
- **Grace period:** the Wi-Fi group stays up for 5 minutes after the limit, so an in-app
  purchase resumes all devices instantly without rejoining.
- **Paywall triggers:** the limit event (in-app overlay), the notification action "Upgrade"
  (`EXTRA_SHOW_PAYWALL`), the cooldown on start, and the toolbar/session-card buttons.
- **Billing:** a one-time product `cleartether_premium_lifetime` (Play Billing 9,
  `enableAutoServiceReconnection`, pending purchases). Purchases are signature-verified with
  the Play license key (`CLEARTETHER_PLAY_LICENSE_KEY`) and acknowledged. Entitlement is cached
  in DataStore for offline use, and re-checked against Play on every launch, so refunds and
  revocations are honoured.

## 5. Foreground service and lifecycle

The brief asked for `FOREGROUND_SERVICE_DATA_SYNC`. ClearTether uses **`connectedDevice`**
instead, deliberately:

- Android 15+ limits `dataSync` services to **6 hours per 24 h** (`Service.onTimeout`). A
  tether would be killed mid-workday.
- Play policy reserves `dataSync` for upload/download/backup. Serving nearby devices over
  Wi-Fi is the documented `connectedDevice` use case. Its prerequisite, `CHANGE_WIFI_STATE`,
  is already held.

While sharing, the service holds a partial wake lock (re-armed periodically) and a
low-latency Wi-Fi lock, polls the group every 5 s and rebuilds it automatically (up to 5
times, with backoff) if the framework drops it.

Teardown runs in reverse order of start-up and survives cancellation: persist session
usage → close proxy listener and every tunnel → `removeGroup()` → close the P2P channel → release
the wake and Wi-Fi locks → remove the notification. A quick off/on toggle waits for the previous
teardown before starting again. The client VPN tears down the stack, the TUN fd (polled
non-blocking so the reader thread exits promptly), the Wi-Fi network request and the process
network binding.

## 6. Security model

- The Wi-Fi network is WPA2 with a 12-character random passphrase (SecureRandom, no ambiguous
  characters). Regenerating it revokes every device.
- The proxy binds to the group-owner IP only and rejects clients outside the group subnet.
  It can't become an open proxy on a cellular interface with a public IP.
- Destinations are filtered *after* DNS: the host's loopback (ADB, other apps' local
  servers), link-local, multicast and the tether subnet itself are blocked.
- NFC tag emulation answers only while sharing is on, NFC sharing is enabled and the host is
  unlocked (`requireDeviceUnlock`).
- No analytics, no ads, no backups of credentials or quota (`data_extraction_rules.xml`).

## 7. Known limits

- iOS may label the network "No Internet Connection" even though Safari and apps work through
  the proxy (iOS validates some paths outside the proxy).
- Apps that ignore the system proxy on laptops (some games, CLI tools, UDP apps) need their own
  proxy settings (SOCKS5 on the same port is supported) or won't connect. Android clients in
  ClearTether Client mode have no such limit for TCP and UDP.
- Wi-Fi Direct group-owner support and band selection vary by chipset. The app falls back from
  5 GHz to AUTO and reports unsupported hardware clearly.
- Carriers may restrict tethering in their terms of service. Users are responsible for using
  their plan within its terms.
