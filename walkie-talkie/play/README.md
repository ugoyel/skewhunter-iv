# Publishing ZeroBar to Google Play (internal testing)

Everything Play Console asks for, ready to paste. Files in this folder:

| File | Used for |
| --- | --- |
| `icon-512.png` | App icon (512 × 512) |
| `feature-graphic-1024x500.png` | Feature graphic |
| `privacy-policy.md` | Privacy policy (host it at a public URL first, see step 3) |
| the `.aab` from the **ptt-radio-play-bundle** artifact of the latest GitHub Actions run | The app bundle to upload |

## 1. One-time setup

1. Create a developer account at <https://play.google.com/console/signup> (one-time fee of US $25, identity check).
2. **Create app** → name, default language, *App*, *Free* → accept the declarations.

App name: **ZeroBar**. The package name inside the bundle is `app.zerobar.ptt`. It becomes permanent with the first
upload; the name shown in the store can be changed at any time. Developer contact e-mail: ugoyel@gmail.com.

## 2. Internal testing (no review wait, up to 100 testers)

1. **Test and release → Testing → Internal testing → Create new release.**
2. Accept **Play App Signing**, upload the `.aab`, add release notes, **Save → Review release → Start rollout**.
3. **Testers** tab → create an e-mail list with your testers' Google accounts → save.
4. Copy the **"Join on the web"** link from the Testers tab. That is the test link to share. Testers open it,
   accept, then install from the Play Store.

Personal developer accounts created after November 2023 must also run a closed test with at least 12 testers for
14 days before they can publish to everyone. Internal testing does not need that.

## 3. App content (Policy → App content)

- **Privacy policy:** put `privacy-policy.md` on a public page (Google Sites, GitHub Pages, your website) and paste
  the URL.
- **Ads:** No ads.
- **App access:** All functionality is available without special access (no login).
- **Content rating:** questionnaire → Communication app; users can interact and share location → typically rated
  for teens and up.
- **Target audience:** 13+ (not designed for children).
- **Data safety** (answers for the current version):
  - Data collected: **Personal info → Name** (the name you choose; shown to other users so they can reach you) and
    **Location → Precise location** (only when the user shares it, sends a photo or an SOS). Both are needed for
    app functionality, are not optional for those features, and are not used for ads or analytics.
  - Audio, messages and photos are sent end-to-end encrypted directly between users and are not collected by the
    developer.
  - Data is **encrypted in transit**. Users can delete their data by deleting chats or uninstalling.
  - No data is shared with third parties for their own use. The relay server only forwards encrypted traffic.
- **Foreground service declaration** (Android 14+):
  - *Media playback:* "Keeps the radio receiving so incoming push-to-talk voice plays immediately while the app is
    closed, like a walkie-talkie." Provide a short screen recording of a call arriving with the app closed.
  - *Microphone:* "Captures audio only while the user holds the talk button, including from the home-screen
    widget and the notification's Talk button."
- **Battery optimisation exemption:** core function is real-time voice that must arrive with the screen off;
  without it Android delays or drops incoming calls.

## 4. Store listing (Grow → Store presence → Main store listing)

**App name (≤ 30 characters)**

> ZeroBar: Offline Walkie Talkie

**Short description (≤ 80 characters)**

> Push-to-talk walkie talkie that works without internet, over Bluetooth & Wi-Fi

**Full description**

> Talk to your group instantly: hold the button, speak, release. It works over the internet like any chat app,
> and when there is no network at all it connects phones directly over Bluetooth and Wi-Fi, passing your voice on
> from phone to phone.
>
> • Push to talk to groups or one person, with a round talk button, home-screen widget and notification Talk button
> • Works with no internet or SIM: nearby phones link up automatically over Bluetooth and Wi-Fi
> • Field Wi-Fi range extender: one phone's hotspot becomes the hub for everyone in range
> • SOS: send an emergency message with your GPS position to phones nearby, no connection needed
> • Channels inside groups: pull people onto a side channel and lock it
> • Messages, photos and locations that delete themselves after up to 2 minutes; nothing is saved
> • Photo with location: address, coordinates, time and map link in one tap
> • Low-data HD voice and noise reduction
> • End-to-end encrypted; no ads, no tracking
>
> Range without internet depends on the phones and surroundings: tens of metres over Bluetooth, more over Wi-Fi
> and long-range Bluetooth, and further when people in between also run the app.

**Graphics:** the icon and feature graphic from this folder, plus at least 2 phone screenshots (take them on your phone).

**Category:** Communication. **Tags:** walkie talkie, push to talk.
