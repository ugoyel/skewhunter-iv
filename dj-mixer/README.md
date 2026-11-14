# CrossDeck — DJ mixer for YouTube players

A multi-deck DJ mixer that runs in the browser and plays tracks through the official
YouTube IFrame Player. No build step: it is plain HTML, CSS and JavaScript.

## Run it

```
cd dj-mixer
python3 -m http.server 8080      # then open http://localhost:8080
```

It must be served over http(s); the YouTube player does not work from a `file://` page.

## Features

- 2 decks (A, B), switchable to 4 (C, D)
- Crossfader with Blend / Linear / Cut curves, snap-cut buttons, master volume
- Timed fade button (2–24 s) that brings in the other deck and fades across
- Auto DJ: plays a queue across decks A and B with fade-out / fade-in transitions
- Per deck: play/pause, cue point, 4 hot cues, manual loop (In/Out), beat loops (4/8/16),
  tempo 0.5×–1.5×, tap BPM, BPM sync, volume, mute, fade in/out, crossfader bypass, seek, ±5 s
- Library and queue saved on the device; add tracks by pasting a link
- Search by name with your own YouTube Data API key (Settings)
- FX pads (horn, siren, laser, riser) synthesised in the browser
- Installable PWA (manifest + service worker), privacy page included

## What it deliberately does not do

YouTube does not give apps access to a video's audio, and its terms forbid extracting it.
So there is no EQ, filters, effects on the music, waveform, automatic BPM detection,
key lock, recording, or background/lock-screen playback. BPM is tapped by hand, and
loops are timed by polling (accurate to roughly a tenth of a second, not sample-accurate).

## How it stays inside YouTube's API policies

- Every deck is a standard embedded player, always visible, at least 200×200 px, with
  YouTube's own controls and branding; nothing is drawn on top of it.
- Mixing uses only the player's volume control. Audio and video are never separated,
  downloaded, cached or modified. Ads are left alone.
- Playback pauses when the app is hidden or in the background.
- A second deck only starts over a playing one when the user taps (Play or Fade).
  Auto DJ fades the old track out completely before starting the next, so two players
  never auto-play at once.
- The app name and icon do not use YouTube's name or logo.

Read the current rules yourself before publishing; they change:
<https://developers.google.com/youtube/terms/developer-policies> and
<https://developers.google.com/youtube/terms/required-minimum-functionality>.

## Publishing on Google Play

1. Host this folder on an HTTPS domain you control.
2. Fill in your name and contact email in `privacy.html`.
3. Wrap the hosted site as an Android app with Bubblewrap (Trusted Web Activity):
   `npm i -g @bubblewrap/cli && bubblewrap init --manifest https://YOUR-DOMAIN/manifest.webmanifest && bubblewrap build`
4. Publish the `assetlinks.json` Bubblewrap generates at `https://YOUR-DOMAIN/.well-known/assetlinks.json`.
5. Upload the `.aab` in Play Console, link the privacy page, and describe the app as using
   the embedded YouTube player. Do not put "YouTube" in the app title.

## Not yet tested

The mixer logic was tested against a simulated player. It has not been run against real
YouTube playback or on an Android phone. Check on a device that the tempo slider's
in-between speeds are honoured and that Fade / Auto DJ can start a deck you have not
tapped yet (mobile browsers can block playback that does not follow a tap).
