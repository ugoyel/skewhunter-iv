# Connecting devices to ClearTether

Turn on **Share internet** on the phone with the data. The app shows a **Join Wi-Fi** QR code, a
**Setup page** QR code, and the network name, password and address (normally `192.168.49.1:8228`).
The same guides are in the app (**? → Connect**) and on the setup page `http://192.168.49.1:8228`,
filled in with your real network name and password.

## Quick chooser

| Device | Best way | Proxy setup needed? |
|---|---|---|
| Android **with ClearTether**, has NFC | Tap phones back to back | No, every app works |
| Android **with ClearTether**, no NFC | Scan QR in the app (or type details) | No, every app works |
| Android **without** the app | Camera QR (or NFC tap) → set PAC URL in Wi-Fi settings | Yes, once |
| iPhone / iPad (any model) | Camera QR → install profile from setup page | Profile does it (once) |
| Windows (personal) | Join Wi-Fi → run setup script from setup page | Script does it (once) |
| Windows (work laptop) | Join Wi-Fi → Proxy › Use setup script | Yes, if not locked by IT |
| Mac (personal) | Join Wi-Fi → install profile or one Terminal command | Once |
| Mac (work / managed) | Join Wi-Fi → Proxies › Automatic | Yes, if not locked |
| Chromebook | Join Wi-Fi → Proxy › Automatic | Once |
| Linux | Join Wi-Fi → GNOME/KDE auto proxy or env vars | Once |
| PlayStation, Switch, Android TV box | Join Wi-Fi → Proxy: Manual `192.168.49.1:8228` | Once |
| Chromecast, most smart TVs, Xbox, Roku, car screens | Not supported (no proxy setting) | Use the regular hotspot |

The automatic proxy (PAC) falls back to a direct connection on other networks, so it can stay on.

## Step by step

### Android with ClearTether: NFC tap
1. Install ClearTether on the other device, open it, choose **Use internet**; make sure NFC is on.
2. On the sharing phone keep ClearTether open (sharing on, phone unlocked).
3. Hold the phones back to back. The other phone saves the network and connects.
4. First time only: approve **Connect to device** and the **VPN** prompt. Every app works, including games and calls.

### Android with ClearTether: QR or no NFC (phones and tablets)
1. Install ClearTether, choose **Use internet**, tap **Scan QR** and scan **Join Wi-Fi**. No camera? **Enter manually**.
2. Tap the power button; approve the prompts the first time.

### Android without the app
1. Join: camera on the **Join Wi-Fi** QR, or tap phones (NFC) and accept *Connect to network?*, or pick the network in Wi-Fi settings.
2. Wi-Fi settings → the network → Edit/Advanced → **Proxy → Proxy Auto-Config** → `http://192.168.49.1:8228/proxy.pac`
   (or Manual: host `192.168.49.1`, port `8228`).
3. Browsers and most apps work. For everything (games, calls), install ClearTether and use *Use internet*.

### iPhone & iPad (with or without NFC)
iPhones can't join Wi-Fi by NFC tap, so use the QR code. It works the same on every model.
1. Camera → **Join Wi-Fi** QR → *Join Network* (or Settings › Wi-Fi › network › password).
2. Scan **Setup page** (or open `http://192.168.49.1:8228` in Safari) → **Install profile** →
   Settings › General › VPN & Device Management › ClearTether › Install.
3. Without a profile: Settings › Wi-Fi › (i) › Configure Proxy › **Automatic** › `http://192.168.49.1:8228/proxy.pac`.
4. "No Internet Connection" under the network is normal; Safari and apps still work. If an app uses mobile data, turn off Wi-Fi Assist.

### Windows laptop (personal)
1. Wi-Fi icon → network → password (Windows 11 Camera can also scan the Wi-Fi QR).
2. Open `http://192.168.49.1:8228` → **Download setup script** → run (SmartScreen: *More info › Run anyway*),
   or Settings › Network & internet › Proxy › **Use setup script** → `http://192.168.49.1:8228/proxy.pac`.
3. Restart open browsers. To undo, run the disable script or switch *Use setup script* off.

### Work / corporate Windows laptop
1. Join the Wi-Fi. If the company only allows approved networks, ask IT.
2. Settings › Network & internet › Proxy › **Use setup script** (no admin rights needed) → PAC URL above.
3. Greyed out ("managed by your organization")? The proxy is locked by policy: use Firefox's own proxy setting or ask IT.
4. Connect your company VPN as usual. HTTPS/TCP VPNs work (Cisco Secure Client/AnyConnect SSL, GlobalProtect,
   Zscaler, FortiClient SSL, Ivanti/Pulse); IPsec/WireGuard-only VPNs don't, so ask IT for SSL/TCP mode.
5. ClearTether never decrypts HTTPS: no certificate warnings; Teams/Zoom switch to TCP automatically.

### Mac (personal)
1. Join the Wi-Fi from the menu bar.
2. Safari → `http://192.168.49.1:8228` → download profile → System Settings › General › Device Management → install;
   or `networksetup -setautoproxyurl Wi-Fi http://192.168.49.1:8228/proxy.pac`.
3. Undo: remove the profile or `networksetup -setautoproxystate Wi-Fi off`.

### Work / managed Mac
System Settings › Wi-Fi › Details › Proxies › **Automatic proxy configuration** → PAC URL. If locked by MDM, ask IT.
Same VPN notes as Windows.

### Chromebook
Join the Wi-Fi → Settings › Network › Wi-Fi › network › Proxy › **Automatic proxy configuration** → PAC URL.

### Linux
`gsettings set org.gnome.system.proxy mode 'auto' && gsettings set org.gnome.system.proxy autoconfig-url 'http://192.168.49.1:8228/proxy.pac'`
(KDE: System Settings › Network › Proxy). Terminal tools: `export http_proxy=http://192.168.49.1:8228 https_proxy=http://192.168.49.1:8228`.

### Consoles, TV boxes and other devices
Join the Wi-Fi, then set Proxy → Manual → `192.168.49.1`, port `8228` (PlayStation, Nintendo Switch, many Android TV boxes).
Apps with SOCKS5 support (Telegram, Firefox) can use `192.168.49.1:8228` as SOCKS5. Devices with no proxy setting
can't use ClearTether.

## Why ClearTether instead of the built-in hotspot?

- **Works when the hotspot doesn't:** shares even when the Hotspot option is missing, greyed out or turned off.
- **Keeps Wi-Fi and Android Auto connected:** the hotspot usually switches Wi-Fi off; Wi-Fi Direct runs alongside.
- **Shares whatever the phone is using:** mobile data, or a Wi-Fi network the phone has signed in to.
- **Includes the phone's VPN:** connected devices go through it; the hotspot normally bypasses it.
- **Safe for work laptops:** HTTPS untouched, no certificate warnings, HTTPS-based company VPNs work.
- **Zero setup on Android** with the ClearTether app: every app, games and calls included.
- **Set up once:** fixed network name and password; QR, NFC, one-tap scripts and profiles.
- **Private:** WPA2, devices can't reach the phone's own services, per-device data usage.
- **Free:** no limits, ads, account or servers.

**Good to know:** non-Android devices need a one-time proxy setting; on them, apps that ignore the system proxy,
online games, ping and IPsec/WireGuard VPNs won't work; TVs and consoles without proxy settings can't connect;
long sessions use more battery than the hotspot. If your hotspot works and you need those, use the hotspot.

## FAQ

**Can I share while Android Auto is connected?** Wired (USB) Android Auto: yes, it uses the cable while ClearTether uses
Wi-Fi Direct. Wireless Android Auto: usually yes on recent phones. The car link and ClearTether share the phone's Wi-Fi radio,
and ClearTether matches the car's channel automatically. Heavy passenger downloads can make Android Auto stutter; if it
drops, use a USB cable. The car's own screen can't use ClearTether's internet. *Not tested on every phone and car.*

**How many devices?** Usually up to 8 at once, depending on the phone.

**Screen off?** Keeps running as a foreground service; exempt ClearTether from battery optimization on Samsung/Xiaomi/OnePlus/Huawei.

**Whose data?** The phone's current connection. Check your mobile plan allows using it on your other devices.
