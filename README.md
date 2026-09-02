# skewhunter-iv

NIFTY implied-volatility skew tracker with a paper-trading engine, plus a
mobile app you can install on an Android phone.

A GitHub Actions workflow runs every 3 minutes during market hours, pulls the
NIFTY option chain from NSE, records ATM/OTM implied volatilities, runs the
paper-trading strategy, and pushes the results back to the repository.

## The Android app

`app.html` is an installable **Progressive Web App** — it adds a real icon to
your home screen, opens full-screen with no browser chrome, and works offline
on the last data it saw. There is no APK to sideload and nothing to re-install
when the data or the app changes.

### One-time setup

1. **Enable GitHub Pages** — repository **Settings → Pages → Build and
   deployment → Deploy from a branch**, branch `main`, folder `/ (root)`.
   The app files must be on whichever branch you point Pages at.
2. Wait a minute for the first deploy, then open the app URL on your phone in
   **Chrome**:

   ```
   https://ugoyel.github.io/skewhunter-iv/app.html
   ```

3. Tap the **⋮** menu → **Add to Home screen** (Chrome may also show an
   "Install" banner in the app itself). The Skew Hunter icon appears on your
   home screen.

On iPhone the same URL works in Safari via **Share → Add to Home Screen**.

### What the app shows

| Section | Content |
|---|---|
| Hero | NIFTY spot and its move since the session open |
| Stat tiles | OTM call IV, OTM put IV, IV skew, and the paper-trading book |
| Open position | Signal, strike, entry, live premium, stop, target, and where the premium sits between stop and target |
| Charts | Spot, call vs put IV, IV skew, cumulative P&L, and call vs put open interest |
| Closed trades | Every closed paper trade with its exit reason and P&L |

A time-range row (Today / 1 week / 1 month / All) scopes every chart at once.
Charts have a crosshair and tooltip on tap, respond to arrow keys, and each one
has a **Table** toggle showing the same numbers as text. The app follows your
phone's light/dark setting, with a manual override in the header.

The app refreshes itself every 60 seconds while it is open and whenever you
switch back to it, so it picks up each 3-minute push without a reload.

## How it fits together

```
collector.py   → iv_data.csv        option-chain snapshot every 3 minutes
paper_trade.py → trades.csv         closed trades
                 trade_state.json   the currently open position
chart.py       → index.html         full Plotly dashboard (desktop)
app_data.py    → app_data.json      trimmed feed for the phone app
```

`app_data.py` keeps the phone payload small: the last 7 sessions of 3-minute
points plus one closing row per session for the whole history, stored as
columnar arrays. It only rewrites `app_data.json` when the data actually
changed, so runs outside market hours don't produce empty commits.

| File | Purpose |
|---|---|
| `app.html` | The mobile app — self-contained, no external dependencies |
| `manifest.webmanifest` | App name, icons, and standalone display mode |
| `sw.js` | Service worker: app shell cached, data fetched network-first |
| `icons/` | Generated PNG icons, including Android maskable variants |
| `tools/make_icons.py` | Regenerates `icons/` — standard library only |

## Running locally

```bash
pip install requests pandas plotly pytz

python collector.py     # only writes during market hours, weekdays
python paper_trade.py
python chart.py
python app_data.py

python -m http.server 8000
# then open http://localhost:8000/app.html
```

Service workers need `http://localhost` or HTTPS — opening `app.html` as a
`file://` URL will render the dashboard but skip the offline layer.

To change the icon, edit the colours or column geometry at the top of
`tools/make_icons.py` and re-run it.

## Strategy parameters

The paper trader's risk settings live at the top of `paper_trade.py`: ₹5,00,000
notional capital, 2% risk per trade, a 40% stop and 120% target on the option
premium, entries between 10:15 and 14:15 IST, and a forced exit at 15:15.
`app_data.py` mirrors one value from it — the delta proxy used to mark an open
position to market — so if you change the strategy's premium model, change it
in both places or the app's unrealised P&L will disagree with the trader's.

## Disclaimer

Everything here is **paper trading** on simulated positions. Implied
volatilities come from NSE's public option-chain endpoint and can be stale or
missing. Nothing in this repository is investment advice or a live order.
