# Publishing ClearTether on Google Play

What's ready in this repo: a signed release bundle (built with your upload key), store listing
text ([`listing.md`](listing.md)), graphics ([`graphics/`](graphics)), the privacy policy
([`../PRIVACY.md`](../PRIVACY.md)), pricing ([`PRICING.md`](PRICING.md)) and a one-click CI upload
workflow. Steps that need your Google account are marked **You**.

## 1. Developer account (one time) — You
1. Sign up at <https://play.google.com/console/signup> (US$25 one-time fee) and complete identity verification.
2. **Personal accounts created after Nov 2023** must run a **closed test with at least 12 testers
   for 14 consecutive days** before they can publish to Production. Organisation accounts skip this.
3. Set up a payments profile (Monetize → Payments profile) so you can sell subscriptions.

## 2. Create the app — You
Home → Create app → name **ClearTether: Share Internet**, default language English (US), **App**,
**Free** (subscriptions are in-app), and accept the declarations.

## 3. App content (Policy → App content)
| Section | Answer |
|---|---|
| Privacy policy | Host `docs/PRIVACY.md` (for example on GitHub Pages) and paste the URL |
| Ads | No ads |
| App access | All functionality available without special access (no login) |
| Content rating | Questionnaire category **Utility, Productivity, Communication or Other**; answer No to everything. Result: Everyone / PEGI 3 |
| Target audience | 18 and over |
| Data safety | **No** data collected, **no** data shared (traffic never reaches developer servers; purchases are handled by Google Play) |
| Government / financial / health / news apps | No |
| **Foreground service** | Type `connectedDevice`. *"Keeps the Wi-Fi Direct network and relay running while the user shares this phone's connection with their own nearby devices; started by the user and stopped from the notification."* Attach a short screen recording: start sharing, show the notification, stop. |
| **VPN service** | Category: network connectivity tool. *"ClearTether's optional 'Use internet' mode creates an on-device VPN that routes this device's traffic to the user's own ClearTether phone over Wi-Fi Direct. No traffic goes to developer servers and no data is collected."* |

Keep the listing wording about *sharing your connection with your own devices*. Avoid "bypass
carrier/hotspot limits": Google's Device and Network Abuse policy targets that framing.

## 4. Subscriptions (Monetize → Products → Subscriptions) — You
1. **Create subscription**: product ID `cleartether_premium`, name "ClearTether Premium".
2. **Base plan `monthly`**: auto-renewing, 1 month, $2.99. Edit India manually to ₹99. Enable a grace period (7 days) and account hold.
3. **Base plan `yearly`**: auto-renewing, 1 year, $14.99. India ₹499.
4. **Offer on `yearly`**: ID `trial-3d`, eligibility *New customer acquisition*, phase *Free trial, 3 days*. Activate.
5. Optional: an India-only intro offer on `monthly` (₹49 for the first month).
6. Optional: one-time product `cleartether_premium_lifetime`. The app shows it automatically if it exists.
7. Monetization setup → Licensing: copy the **base64 license key** into the GitHub secret
   `CLEARTETHER_PLAY_LICENSE_KEY` (builds then verify every purchase signature on-device).
8. Setup → License testing: add tester Gmail accounts so they can buy without being charged.

The IDs must match exactly; they're set in `app/build.gradle.kts`.

## 5. Store listing (Grow → Store presence → Main store listing)
Copy the text from [`listing.md`](listing.md) and upload the graphics from [`graphics/`](graphics).

## 6. First upload — You
1. Testing → **Closed testing** → create a track, add ≥ 12 testers (Google Group or email list).
2. Create release → choose **Use Play App Signing** (recommended; Google keeps the app signing key,
   and you keep the *upload* key) → upload `ClearTether-1.1.0-release.aab` → release notes from
   `listing.md` → Review → Start rollout.
3. Share the opt-in link with testers; keep the test running 14 days (personal accounts).
4. Dashboard → **Apply for production**, answer the questions, then promote the release to Production
   (start with a staged rollout, for example 20%).

## 7. Later releases: one click
After the first manual upload, `.github/workflows/cleartether-release.yml` builds, signs and
uploads to any track. Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `CLEARTETHER_UPLOAD_KEYSTORE_BASE64` | base64 of the upload keystore file |
| `CLEARTETHER_KEYSTORE_PASSWORD` / `CLEARTETHER_KEY_PASSWORD` | keystore/key password |
| `CLEARTETHER_KEY_ALIAS` | `upload` |
| `CLEARTETHER_PLAY_LICENSE_KEY` | license key from step 4.7 |
| `PLAY_SERVICE_ACCOUNT_JSON` | JSON key of a Google Cloud service account invited in Play Console → Users and permissions with *Release* rights |

Then Actions → **ClearTether release** → Run workflow → pick a track.

**Keep the upload keystore and its password safe** (password manager plus an offline backup). If
it's ever lost, Play App Signing lets you request an upload-key reset from Play Console support.
