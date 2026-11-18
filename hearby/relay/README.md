# Hearby relay

Lets phones on **different networks** (an iPhone on mobile data, a friend on another Wi-Fi) join a
**YouTube** party. Every phone plays the video from YouTube itself; the relay only passes along what
plays and when, on a clock everyone shares (each phone syncs to the relay's clock). Songs from the
DJ's phone and the karaoke mic are real audio streams and still need the DJ's Wi-Fi or hotspot.

- `src/worker.js`: a Cloudflare Worker plus one Durable Object per party. Guests open
  `https://<relay>/p/<code>` (the same guest page the DJ phone serves on Wi-Fi); the DJ app connects
  with a secret key, so nobody else can drive the party.
- Runs on Cloudflare's **free plan** (Workers + SQLite Durable Objects with WebSocket hibernation).
- Tests: `node --test hearby/relay/test/*.test.mjs`.

## One-time setup (about 10 minutes)

1. Create a free account at <https://dash.cloudflare.com/sign-up>. Open **Workers & Pages** once and
   pick your `workers.dev` subdomain when asked.
2. Copy your **Account ID** (Workers & Pages overview, right-hand side).
3. **My Profile › API Tokens › Create Token**, template **Edit Cloudflare Workers**, then create it and
   copy the token.
4. On GitHub, in this repository: **Settings › Secrets and variables › Actions › New repository
   secret**. Add `CLOUDFLARE_API_TOKEN` (the token) and `CLOUDFLARE_ACCOUNT_ID` (the ID).
5. **Actions › Hearby relay › Run workflow**. When it finishes, its summary shows the relay's address,
   like `https://hearby-relay.<your-subdomain>.workers.dev`.
6. Build the app with that address: `hearby.relayUrl=https://...` in `hearby/android/gradle.properties`
   (or the `HEARBY_RELAY_URL` environment variable). The DJ screen then shows a **Not on this Wi-Fi?**
   card with a link and QR code during every party.

## Try it locally

```sh
cd hearby/relay && mkdir -p public && cp ../android/app/src/main/assets/web/* public/
npx wrangler@4.150.0 dev       # then open http://127.0.0.1:8787/p/<code>
```
