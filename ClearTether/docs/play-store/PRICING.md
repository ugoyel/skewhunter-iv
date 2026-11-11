# ClearTether pricing recommendation

## What competitors charge

| App | Model | Price | Notes |
|---|---|---|---|
| EasyTether Pro | One-time | ~$9.99 | USB/Bluetooth tethering, needs a PC driver ([AppFollow listing](https://apps.appfollow.io/android/easytether-pro/eu.easytether.pro?country=sl), [BlueStacks](https://www.bluestacks.com/apps/communication/easy-tether-pro-on-pc.html)) |
| PdaNet+ / FoxFi | One-time | $7.95–$19 historically ([PCWorld](https://www.pcworld.com/article/503371/pdanet_android_app_review.html), [GeeksOnTour](https://geeksontour.com/2012/06/internet-from-your-smartphone-with-pdanetfoxfi/), [FileHorse](https://mac.filehorse.com/download-pdanet/)) | Free version with time limits |
| NetShare | Free + ads + in-app purchases | Not confirmed (third-party listings disagree) | Same Wi-Fi Direct + proxy approach ([App Pricing Lab](https://apppricinglab.com/app/google_play/kha.prog.mikrotik), [AppGrooves](https://appgrooves.com/android/kha.prog.mikrotik/netshare-no-root-tethering/netshare-softwares/negative)) |
| TetherFi | Free, open source | $0 | Same approach, no polish or support ([GitHub](https://github.com/pyamsoft/tetherfi)) |

Market benchmarks:
- 85% of utility apps offer a free trial ([Adapty utilities benchmarks](https://adapty.io/blog/utilities-app-subscription-benchmarks/)).
- Cross-category global median prices: $12.99/month and $38.42/year ([Adapty pricing index](https://adapty.io/app-subscription-pricing-index/)). Utility apps price well below that.
- India: ₹79–₹149/month is the common entry band ([AppsOps South Asia pricing](https://appsops.store/blog/app-store-pricing-south-asia-2026)). Google's own Play Pass sells at ₹99/month, which is a useful anchor ([Google blog](https://blog.google/intl/en-in/products/platforms/google-play-pass-now-india-enjoy-1000-apps-and-games-without-ads-or-app-purchases/)).

## Recommended price points

| Plan (base plan ID) | US / default | India (set manually) | Notes |
|---|---|---|---|
| **Monthly** (`monthly`) | **$2.99** | **₹99** | For occasional travellers. Optional India intro offer: first month at ₹49 |
| **Yearly** (`yearly`) | **$14.99** | **₹499** | About 58% cheaper than monthly; shown first as "Best value". **3-day free trial** for new subscribers |

Why these numbers:
- **Yearly $14.99 is about the price of the one-time unlocks people already know** ($8–$19). It
  reads as fair and still earns recurring revenue, which pays for updates and Android compatibility work.
- **Monthly $2.99 is an impulse price** for "I need my laptop online today", with a large enough
  gap that the yearly plan is the obvious deal (the paywall shows "SAVE 58%").
- **The 3-day trial** removes purchase hesitation. The 30-minute free daily limit already proves the
  product works, so the trial converts users who need it for a full workday.
- **Google's fee on subscriptions is 15%.** Net per yearly subscriber is about $12.74, about ₹424 in India before GST.

If you meant ₹49: use it as an **introductory offer** (first month ₹49, then ₹99/month) rather
than the standing price. That keeps the price low for trying it while protecting long-term revenue.

Prices live entirely in Play Console. The app always shows Play's localized price, so you can
change prices or run offers later without shipping an update.
