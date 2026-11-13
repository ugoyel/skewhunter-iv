# Hearby wire protocol, version 1

Everything travels as UDP datagrams between a **host** (the DJ's Android phone) and its
**listeners** (Android or iOS), on one port. The reference implementations are

- Android: `android/app/src/main/java/io/github/ugoyel/hearby/core/Protocol.kt`
- iOS: `ios/Hearby/Core/Protocol.swift`

Both are checked against the same hex vectors (`ProtocolTest.kt` / `ProtocolTests.swift`), listed at
the end of this document.

## Conventions

| | |
|---|---|
| Transport | UDP, unicast. Default host port **47800** (a different port is possible; it is advertised over mDNS). |
| Byte order | All multi-byte integers are **unsigned, big-endian** (network order). |
| Strings | UTF-8, length-prefixed with one byte, at most 48 bytes, truncated on a character boundary. |
| Time | Microseconds on the sender's **monotonic** clock (`System.nanoTime()` on Android, `mach_absolute_time()` on iOS). Epochs are arbitrary; clock sync works out the difference. |
| Audio | Opus, 48 kHz, stereo, **one 20 ms frame (960 samples) per datagram**. |

Every datagram starts with a 4-byte prelude. Datagrams with a different magic or version are ignored.

```
 offset  size  field
 0       1     magic[0] = 0x48 'H'
 1       1     magic[1] = 0x42 'B'
 2       1     version  = 0x01
 3       1     type
```

## 0x01 AUDIO (host → listener)

The hot path: 16 bytes of header, then the Opus packet.

```
  0                   1                   2                   3
  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 +---------------+---------------+---------------+---------------+
 |   'H' (0x48)  |   'B' (0x42)  | version (0x01)|  type (0x01)  |   bytes 0-3
 +---------------+---------------+---------------+---------------+
 |                     sequence number (u32)                     |   bytes 4-7
 +---------------------------------------------------------------+
 |                                                               |
 +                       timestamp (u64)                         +   bytes 8-15
 |                                                               |
 +---------------------------------------------------------------+
 |                  Opus payload (1 ... 1275 bytes)              |   bytes 16-
 +---------------------------------------------------------------+
```

| Offset | Size | Field | Meaning |
|---|---|---|---|
| 4 | 4 | `seq` | Starts at 0 for a stream, +1 per frame, wraps at 2³². Used for ordering and loss detection. |
| 8 | 8 | `timestamp` | Host clock, µs: the media time of the **first PCM sample this packet decodes to**. The host produces one frame every 20 ms on its monotonic clock, music or silence, and never stops while the party runs. It has already subtracted the encoder look-ahead (the Opus pre-skip, 312 samples = 6.5 ms), so receivers decode with a pre-skip of 0 and packet *N* maps exactly to samples `[960·N, 960·N+960)`. |
| 16 | … | payload | One Opus packet. Its length is the datagram length minus 16; there is no length field. |

A listener plays the first sample of the packet at

```
local_play_time = (timestamp − clock_offset) + playoutDelayMs + correction + userOffsetMs
```

where `clock_offset = host clock − local clock` comes from SYNC, `playoutDelayMs` from the host,
`correction` from the sound check (OFFSET), and `userOffsetMs` is the per-device slider
(−500 … +500). The host plays its own stream the same way, with a clock offset of zero.

Timestamps of consecutive packets are 20 000 µs apart. A jump of more than 5 ms against
`previous + 20 000` means the host timeline broke (its sending thread was starved) and the listener
re-aligns.

At 96 kbit/s a packet averages 240 bytes of Opus, 256 with the header, 284 on the wire with UDP/IP:
about 114 kbit/s per listener.

## Control messages

### 0x02 JOIN (listener → host)

```
 4   u32  clientId      random, chosen by the listener for this run
 8   u8   platform      1 = Android, 2 = iOS
 9   u8   nameLen
 10  ...  name          device name shown on the host
```

Sent every 400 ms until a JOIN_ACK arrives. The host starts sending AUDIO to the datagram's source
address and port as soon as it has seen a JOIN.

### 0x03 JOIN_ACK (host → listener)

```
 4   u32  streamId        random per hosting session
 8   u32  sampleRate      48000
 12  u8   channels        2
 13  u8   frameMs         20
 14  u16  playoutDelayMs  common delay every listener applies
 16  u8   nameLen
 17  ...  hostName
```

A `streamId` different from the one a listener knows means a new session: it clears its jitter
buffer and clock estimate and starts over.

### 0x04 SYNC_REQ (listener → host) and 0x05 SYNC_RESP (host → listener)

NTP-style clock exchange. Eight requests 40 ms apart right after joining, then one per second, which
also serves as the keep-alive (the host drops a listener it has not heard from for 6 s; a listener
that hears nothing for 4 s goes back to sending JOIN).

```
 SYNC_REQ  (28 bytes)                       SYNC_RESP (36 bytes)
 4   u32  clientId                          4   u32  streamId
 8   u64  t0   listener send time           8   u64  t0   echoed
 16  u32  rttUs      (for the host's UI)    16  u64  t1   host receive time
 20  u16  bufferMs   (for the host's UI)    24  u64  t2   host send time
 22  u8   status     0 connecting           32  u16  playoutDelayMs
                     1 buffering            34  u16  reserved (0)
                     2 in sync
                     3 playing late
 23  u8   reserved (0)
 24  i32  adjustUs   everything the listener adds to playoutDelayMs:
                     slider + sound-check correction + fallback (signed)
```

Hosts also accept the original 24-byte SYNC_REQ without `adjustUs` (treated as 0). The host uses
`adjustUs` only to know where to listen for a phone's chirp during a sound check.

With `t3` = the listener's receive time:

```
rtt    = (t3 − t0) − (t2 − t1)
offset = ((t1 − t0) + (t2 − t3)) / 2          // host clock − listener clock
```

The offset is only exact when both directions take equally long; its error is at most `rtt / 2`.
Listeners keep the last 12 exchanges, trust the one with the smallest `rtt`, and low-pass the result
(stepping immediately if it moves by more than 20 ms).

### 0x06 LEAVE (listener → host), 0x07 BYE (host → listeners)

```
 LEAVE:  4  u32 clientId          BYE:  4  u32 streamId
```

Best effort. BYE is sent three times when the host stops.

### 0x08 DISCOVER (anyone → host) and 0x09 DISCOVER_RESP (host → sender)

Used by Android listeners in addition to mDNS. DISCOVER may be sent to the broadcast address, or
unicast to the Wi-Fi gateway (on a phone hotspot the gateway *is* the host).

```
 DISCOVER:       4  u32 nonce
 DISCOVER_RESP:  4  u32 nonce (echo)   8  u32 streamId   12  u8 listeners   13  u8 nameLen   14 ... hostName
```

### 0x0A NOW_PLAYING (host → listeners)

Sent when the song or play state changes, and once a second for anyone who missed it.

```
 4   u32  streamId
 8   u8   flags        bit 0: playing (clear while paused or during a sound check)
 9   u8   titleLen     then title (UTF-8)
 ..  u8   artistLen    then artist (UTF-8)
```

### 0x0B CHIRP and 0x0C OFFSET (host → one listener): the sound check

The host asks one phone at a time to play the sound-check chirp and times it with its microphone;
then it tells each phone how much to shift.

```
 CHIRP (24 bytes)                                   OFFSET (20 bytes)
 4   u32  streamId                                  4   u32  streamId
 8   u32  clientId    the phone that should chirp   8   u32  clientId
 12  u64  mediaTimeUs play the chirp's first        12  i32  adjustUs   add to the current correction
          sample when this media time is heard      16  u16  checkId    apply each id once
 20  u16  checkId                                   18  u16  errorUs    |error| measured (capped)
 22  u16  reserved (0)
```

The chirp is fixed: 1920 samples (40 ms at 48 kHz), `0.5 · w(i) · sin(2π(1500·t + 2750·t²/0.04))`
with `t = i / 48000` and `w` a 96-sample raised-cosine fade in and out. Every phone mixes it into its
output at exactly `mediaTimeUs` on the shared timeline, so it goes through the same path, and the
same timing, as the music. Each message is sent three times; phones ignore repeats (same
`mediaTimeUs`, or same `checkId`).

The host computes, for each phone, `heard − (mediaTimeUs + playoutDelay)` and sends each phone the
difference between its own value and the host phone's (`adjustUs`, negative = play earlier). The
host phone chirps first and last in each round, which cancels its microphone's latency and any
clock drift during the round. Corrections reset when a new stream starts.

### 0x0D VIDEO (host → listeners): YouTube mode

In YouTube mode the host sends no music: every phone plays the same YouTube video itself, over the
internet, in YouTube's official embedded player, and this message says where the video should be on
the host's clock. Sent when anything changes (`version` goes up), and once a second for anyone who
missed it. AUDIO keeps flowing (silence) so the clock sync and the playout keep running.

```
 4   u32  streamId
 8   u32  version      bumped on every change
 12  u8   flags        bit 0: playing   bit 1: a video is on (clear: back to music, ignore the rest)
 13  u64  anchorHostUs host clock, µs
 21  i64  positionUs   video position at anchorHostUs, µs
 29  u8   idLen        then the 11-character YouTube video id (ASCII)
 ..  u8   titleLen     then the title (UTF-8, may be empty until the host's player knows it)
```

While playing, the video should be at `positionUs + max(0, hostNow − anchorHostUs)`: a start or a
resume is announced with an anchor a little in the future (2.5 s for a new video, 0.8 s for a
resume), and phones hold at `positionUs` until then. While paused it stays at `positionUs`. Each
phone converts host time to its own clock with the SYNC offset and applies its own correction
(slider plus sound check, positive = later) before steering its player.

## Browser guests (TCP 47801)

Phones without the app (iPhones in Safari) join through a page the DJ phone serves over plain HTTP
on port 47801 (or any free port; the DJ screen shows it, with a QR code):

| Request | |
|---|---|
| `GET /` | the guest page |
| `GET /hearby-web.js` | its sync logic |
| `GET /ws` | a WebSocket (RFC 6455) for everything else |

On the WebSocket, text messages are JSON objects with a type `t`; times are microseconds, on the
host's clock (`r`, `s`, `anchor`, `media`) or the browser's (`c`, `performance.now() × 1000`).

| Direction | Message |
|---|---|
| host → guest | `{"t":"hello","id":<guest id>,"stream":<streamId>,"host":"<name>","delay":<playout delay ms>}` on connect |
| guest → host | `{"t":"hello","name":"<name>"}` when the guest joins (it is not counted before) |
| guest → host | `{"t":"sync","c":<t0>}`: clock sync, a burst of 12 then once a second |
| host → guest | `{"t":"sync","c":<t0 echoed>,"r":<t1>,"s":<t2>}`, sent ahead of any queued audio |
| host → guest | `{"t":"state","title":..,"artist":..,"playing":<bool>,"video":null \| {"id","title","playing","anchor","pos","v"}}` on join and on every change; `video` is the VIDEO timeline |
| guest → host | `{"t":"stat","buf":<ms scheduled>,"st":<status as in SYNC_REQ>,"adj":<adjustments µs>,"rtt":<µs>}` once a second |
| host → guest | `{"t":"chirp","media":<mediaTimeUs>,"check":<id>}` and `{"t":"offset","adjust":<µs>,"check":<id>,"err":<µs>}`: the sound check, as CHIRP and OFFSET |
| host → guest | `{"t":"bye","msg":".."}` when the party ends, `{"t":"full"}` when 16 browsers are already in |
| guest → host | `{"t":"leave"}` |

Binary messages (host → guest) carry the audio: `u32 seq`, `u64 timestampUs` (big-endian, as in
AUDIO), then 960 stereo samples of 16-bit little-endian PCM, 3852 bytes in all. A guest plays the
frame at `timestampUs + delay + adjustments` on the shared timeline. Silent frames are not sent
(`seq` still advances), so a gap simply means silence.

## Discovery

| Mechanism | Host | Android listener | iOS listener |
|---|---|---|---|
| mDNS / DNS-SD, service type `_hearby._udp`, instance name `Hearby <device name>` | `NsdManager.registerService` | `NsdManager.discoverServices` | `NWBrowser` (Bonjour) |
| DISCOVER probe to broadcast addresses and the Wi-Fi gateway / DHCP server | answers | yes | no (sending broadcasts on iOS needs Apple's multicast entitlement) |
| Typing the host's address | shows its address | yes | yes |

## Test vectors

Hex, exactly as on the wire.

| Message | Bytes |
|---|---|
| AUDIO `seq=0x01020304`, `timestamp=0x1122334455`, payload `fc ff fe` | `48420101 01020304 0000001122334455 fcfffe` |
| JOIN `clientId=0xDEADBEEF`, iOS, `"Pixel 8"` | `48420102 deadbeef 02 07 506978656c2038` |
| JOIN_ACK `streamId=0x0A0B0C0D`, delay 350, `"Host"` | `48420103 0a0b0c0d 0000bb80 02 14 015e 04 486f7374` |
| SYNC_REQ `clientId=0xDEADBEEF`, `t0=0x0102030405`, rtt 2500, buffer 180, status 2, adjust −120 000 µs | `48420104 deadbeef 0000000102030405 000009c4 00b4 02 00 fffe2b40` |
| SYNC_RESP `streamId=0x0A0B0C0D`, same `t0`, `t1=1000000`, `t2=1001000`, delay 350 | `48420105 0a0b0c0d 0000000102030405 00000000000f4240 00000000000f4628 015e 0000` |
| LEAVE `clientId=0xDEADBEEF` | `48420106 deadbeef` |
| BYE `streamId=0x0A0B0C0D` | `48420107 0a0b0c0d` |
| DISCOVER `nonce=7` | `48420108 00000007` |
| DISCOVER_RESP `nonce=7`, `streamId=0x0A0B0C0D`, 3 listeners, `"Mia's phone"` | `48420109 00000007 0a0b0c0d 03 0b 4d696127732070686f6e65` |
| NOW_PLAYING `streamId=0x0A0B0C0D`, playing, `"Song"`, `"Band"` | `4842010a 0a0b0c0d 01 04 536f6e67 04 42616e64` |
| CHIRP `streamId=0x0A0B0C0D`, `clientId=0xDEADBEEF`, `mediaTimeUs=0x0102030405`, check 7 | `4842010b 0a0b0c0d deadbeef 0000000102030405 0007 0000` |
| OFFSET `streamId=0x0A0B0C0D`, `clientId=0xDEADBEEF`, adjust −6 500 µs, check 7, error 6 500 µs | `4842010c 0a0b0c0d deadbeef ffffe69c 0007 1964` |
| VIDEO `streamId=0x0A0B0C0D`, version 3, playing, `anchorHostUs=0x0102030405`, `positionUs=−2 000 000`, `"dQw4w9WgXcQ"`, `"Hit"` | `4842010d 0a0b0c0d 00000003 03 0000000102030405 ffffffffffe17b80 0b 6451773477395767586351 03 486974` |
| VIDEO, no video on | `4842010d 0a0b0c0d 00000000 00 0000000000000000 0000000000000000 00 00` |
| Chirp samples 50, 100, 500, 1500, 1900 | −0.2020977, 0.2314788, 0.2541216, 0.0428987, 0.0263643 |

## Security note

Version 1 has no authentication or encryption: anyone on the same network segment can join a host
and hear what it is playing. That matches the "everyone in the room" use case but is worth knowing
on shared Wi-Fi. A session PIN mixed into a per-packet MAC is the natural next step. Only the host
acts on JOIN and SYNC; listeners only accept CHIRP and OFFSET from the host they joined (a
connected socket), and corrections are bounded to ±1 s.
