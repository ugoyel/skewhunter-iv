# PTT Radio

A push-to-talk walkie-talkie for Android. Audio only, no calls, nothing recorded.

## What it does

- **Contacts** – everyone who has the app appears in one list (online / nearby / off air). Tick people and create a group.
- **Groups and channels** – a group is the main room (`MAIN`) plus channels `CH 01`–`CH 16`. Move to a channel for a side
  conversation, **Bring** a member onto your channel, and **lock** it so nobody else can enter or listen.
- **Push to talk** – hold the talk key to speak, release to mute. Everyone tuned to the same channel hears it live.
- **Messages** – text on the channel deletes itself after 2 minutes (or 15 s / 30 s / 1 min, in Settings). It lives in memory
  only: not on a server, not on disk.
- **Links, in order** – internet first. When there is no internet the app finds phones around it and links to them over
  Bluetooth and Wi-Fi automatically; phones relay for each other (up to 3 hops).
- **Always on** – a small foreground service keeps the radio listening. The app asks for the battery-optimisation exemption,
  microphone, notifications and nearby-devices permissions, and warns when one is denied.

## How it works

| Part | Choice | Why |
| --- | --- | --- |
| Internet relay | Free public MQTT broker (`broker.emqx.io`, backup `broker.hivemq.com`) | No account, no cost. It only passes packets between phones that are online. |
| No-internet link | Google Nearby Connections, cluster strategy | Uses Bluetooth, BLE and Wi-Fi together and needs no router or SIM. |
| Voice | 16 kHz mono, IMA ADPCM, 60 ms frames (~64 kbit/s) | Tiny pure-Kotlin codec, low delay, each frame decodes on its own. |
| Privacy | AES-256-GCM with a per-group key; invitations sealed with ECDH P-256 | The relay and relaying phones see only ciphertext. A locked channel gets its own key. |

What is stored where:

- **Relay server:** your directory entry (call sign + public key) and group invitations that have not been answered yet.
  Voice and text are sent non-retained, so the server keeps none of it.
- **Phone:** your call sign and keys, your groups, the contact list, settings. Never voice, never text.

The relay is a public, shared server. The contact directory on it is visible to anyone using the app, and the service has no
uptime guarantee. For a private deployment put your own broker address in Settings → Relay server (every group member must
use the same one).

## Build

```
cd walkie-talkie
./gradlew :app:testDebugUnitTest   # protocol tests: groups, PTT, channels, lock, mesh relay, message expiry
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Every push that touches this folder also builds the APK in GitHub Actions (workflow **PTT Radio APK**, artifact
`ptt-radio-apk`). The APK is signed with the build machine's debug key, so it installs directly; an APK from a different
machine needs the old one uninstalled first.

Requires Android 8.0+. The no-internet link needs Google Play services.
