import requests
import pandas as pd
import os
import time
from datetime import datetime
import pytz

IST = pytz.timezone('Asia/Kolkata')
now_ist = datetime.now(IST)
CSV_FILE = "iv_data.csv"

def fetch_option_chain():
    session = requests.Session()
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Accept': '*/*',
        'Referer': 'https://www.nseindia.com/option-chain'
    }
    try:
        session.get("https://www.nseindia.com", headers=headers, timeout=10)
        time.sleep(2)
        r = session.get(
            "https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY",
            headers=headers, timeout=10
        )
        data    = r.json()
        spot    = data['records']['underlyingValue']
        records = data['records']['data']
        rows = []
        for rec in records:
            row = {'strike': rec['strikePrice']}
            for side in ['CE', 'PE']:
                p = side.lower()
                row[f'{p}_ltp'] = rec[side].get('lastPrice', 0)         if side in rec else 0
                row[f'{p}_oi']  = rec[side].get('openInterest', 0)      if side in rec else 0
                row[f'{p}_vol'] = rec[side].get('totalTradedVolume', 0) if side in rec else 0
                row[f'{p}_iv']  = rec[side].get('impliedVolatility', 0) if side in rec else 0
            rows.append(row)
        return pd.DataFrame(rows), spot
    except Exception as e:
        print(f"Error: {e}")
        return pd.DataFrame(), None

def get_atm_iv(df_chain, spot):
    import math
    atm    = round(spot / 50) * 50
    otm_ce = math.ceil(spot / 50) * 50
    if otm_ce == int(spot): otm_ce += 50
    otm_pe = math.floor(spot / 50) * 50
    if otm_pe == int(spot): otm_pe -= 50

    ce = df_chain[df_chain['strike'] == otm_ce]
    pe = df_chain[df_chain['strike'] == otm_pe]
    atm_ce = df_chain[df_chain['strike'] == atm]
    atm_pe = df_chain[df_chain['strike'] == atm]

    return {
        'timestamp'   : now_ist.strftime('%Y-%m-%d %H:%M:%S'),
        'date'        : now_ist.strftime('%Y-%m-%d'),
        'time'        : now_ist.strftime('%H:%M'),
        'spot'        : spot,
        'atm_strike'  : atm,
        'otm_ce'      : otm_ce,
        'otm_pe'      : otm_pe,
        'ce_ltp'      : ce['ce_ltp'].iloc[0]  if not ce.empty  else 0,
        'pe_ltp'      : pe['pe_ltp'].iloc[0]  if not pe.empty  else 0,
        'ce_iv'       : ce['ce_iv'].iloc[0]   if not ce.empty  else 0,
        'pe_iv'       : pe['pe_iv'].iloc[0]   if not pe.empty  else 0,
        'atm_ce_iv'   : atm_ce['ce_iv'].iloc[0] if not atm_ce.empty else 0,
        'atm_pe_iv'   : atm_pe['pe_iv'].iloc[0] if not atm_pe.empty else 0,
        'ce_oi'       : ce['ce_oi'].iloc[0]   if not ce.empty  else 0,
        'pe_oi'       : pe['pe_oi'].iloc[0]   if not pe.empty  else 0,
        'iv_skew'     : (ce['ce_iv'].iloc[0] - pe['pe_iv'].iloc[0]) if not ce.empty and not pe.empty else 0
    }

# Only run during market hours IST
hour, minute = now_ist.hour, now_ist.minute
market_open  = (hour == 9 and minute >= 15) or (10 <= hour <= 15) or (hour == 15 and minute <= 30)
weekday      = now_ist.weekday()  # 0=Mon, 6=Sun

if weekday >= 5:
    print("Weekend — skipping.")
elif not market_open:
    print(f"Outside market hours ({now_ist.strftime('%H:%M')} IST) — skipping.")
else:
    print(f"Fetching IV at {now_ist.strftime('%H:%M:%S')} IST...")
    df_chain, spot = fetch_option_chain()
    if not df_chain.empty and spot:
        row = get_atm_iv(df_chain, spot)
        print(f"Spot: {spot} | CE IV: {row['ce_iv']}% | PE IV: {row['pe_iv']}% | Skew: {row['iv_skew']:.2f}")

        # Append to CSV
        df_new = pd.DataFrame([row])
        if os.path.exists(CSV_FILE):
            df_existing = pd.read_csv(CSV_FILE)
            # Avoid duplicate timestamps
            if row['timestamp'] not in df_existing['timestamp'].values:
                df_out = pd.concat([df_existing, df_new], ignore_index=True)
            else:
                df_out = df_existing
                print("Duplicate — skipping write.")
        else:
            df_out = df_new

        df_out.to_csv(CSV_FILE, index=False)
        print(f"Saved {len(df_out)} rows to {CSV_FILE}")
    else:
        print("Failed to fetch data.")
