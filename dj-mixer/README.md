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

- Three players. The mix slider moves the sound from the live player to the next one,
  in a cycle: 1 → 2 → 3 → 1
- Picking a track (buttons 1 / 2 / 3 on any list row) plays it at once: full volume if
  nothing else is playing, otherwise it joins at 10%
- YouTube search inside the app, in YouTube's relevance order, with "Show more results",
  clips under a minute and live streams filtered out, the last query kept in the box and
  a history of the last 10 searches
- Saved list and queue stored on the device; Auto DJ plays the queue round the players
- Your own music files: tempo and beat analysis, beat-matched automatic mixing, waveform,
  3-band EQ, headphone preview
- Per player (under ⋯): volume, mute, fade, saved start / mix-out points, 4 hot cues,
  loops, tempo, tap BPM and sync
- FX pads (horn, siren, laser, riser) synthesised in the browser
- `help.html`: every button explained with screenshots
- Installable PWA (manifest + service worker), privacy page included

## The main screen

1. Search, then tap 1 on a track. It plays on Player 1 ("On speaker").
2. Tap 2 on another track. It joins at 10% while Player 1 drops to 90%.
3. Drag the mix slider right: Player 2 rises as Player 1 falls. Released at the far end,
   Player 2 becomes the live one, Player 1 stops, and the slider now points at Player 3.

Each YouTube player is shown at 200×200, the smallest size YouTube's rules allow.
Players holding your own music files use a smaller waveform view.

### Search quota

The built-in YouTube Data API key is shared by every user of the app. A search or a
"Show more results" page costs about 101 quota units; the default free quota is 10,000
units a day, i.e. roughly 100 searches a day across all users. Apply for a quota
extension in Google Cloud before publishing.

**Headphone preview** (Settings → Speaker and headphones): the next track plays in a
second sound device, with its own volume, before and while it is brought in. This works
only for your own music files and only where the browser can pick sound devices (Chrome
or Edge on a computer). It cannot work for YouTube tracks, and not in phone browsers.

## Two kinds of tracks

**Your own music files** ("Open music folder" or "Add music files"; a pen drive works).
Every file is analysed on the device for tempo, beat positions, where the music really
starts and where it winds down. "Auto-mix this folder" then plays them all: the next
track starts on the beat, its speed is matched (key preserved), the bass is swapped half
way and the blend runs for 16 beats. If two tempos are more than 8% apart it does a short
blend at normal speed instead. These decks also get a waveform and a 3-band EQ.
Analysis results are remembered per file; the files themselves must be re-opened after a
reload because browsers do not keep access to them.

**YouTube tracks.** YouTube does not give apps a video's audio, and its terms forbid
extracting it, so nothing can be detected automatically. Instead you teach each track once
under More controls: Set start, Set mix-out, and tap the BPM. The app remembers these per
video, and Auto DJ then starts each track at its start point and leaves at its mix-out
point with a fade-out followed by a fade-in (no overlap). YouTube decks have no EQ,
waveform, recording, or background/lock-screen playback, and loops are timed by polling
(accurate to roughly a tenth of a second).

The beat analysis assumes a steady tempo (most electronic, pop and hip-hop). Live
recordings with a drifting tempo will be detected less reliably.

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
