import pandas as pd
import numpy as np
import os
import json
from datetime import datetime
import pytz
import math

IST            = pytz.timezone('Asia/Kolkata')
now_ist        = datetime.now(IST)
CSV_FILE       = "iv_data.csv"
TRADES_FILE    = "trades.csv"
STATE_FILE     = "trade_state.json"   # Tracks open position between runs

# ── Risk Management ──────────────────────────────────
CAPITAL        = 500000
NIFTY_LOT      = 75
MARGIN_PER_LOT = 13000
STOP_LOSS_PCT  = 0.40
TARGET_PCT     = 1.20
MIN_PREM       = 50.0
ALPHA1_BULL    = 0.62
ALPHA2_BULL    = 0.60
ALPHA1_BEAR    = 0.38
ALPHA2_BEAR    = 0.40
TRADE_START_H  = 10
TRADE_START_M  = 15
TRADE_END_H    = 14
TRADE_END_M    = 15
EOD_EXIT_H     = 15
EOD_EXIT_M     = 15

def get_strikes(spot):
    atm    = round(spot / 50) * 50
    otm_ce = math.ceil(spot / 50) * 50
    if otm_ce == int(spot): otm_ce += 50
    otm_pe = math.floor(spot / 50) * 50
    if otm_pe == int(spot): otm_pe -= 50
    return atm, otm_ce, otm_pe

def compute_alphas(df_iv, lookback=10):
    """Compute alpha1 and alpha2 from last N rows of iv_data.csv"""
    if len(df_iv) < 3:
        return 0.5, 0.5

    recent = df_iv.tail(lookback).copy()

    # Alpha1: IV momentum + OI flow
    ce_iv_chg  = recent['ce_iv'].diff().fillna(0).tail(3).mean()
    pe_iv_chg  = recent['pe_iv'].diff().fillna(0).tail(3).mean()
    ce_oi_chg  = recent['ce_oi'].diff().fillna(0).tail(3).mean()
    pe_oi_chg  = recent['pe_oi'].diff().fillna(0).tail(3).mean()

    flow_bull  = (ce_iv_chg - pe_iv_chg) + (ce_oi_chg - pe_oi_chg) / 1e6
    alpha1     = min(max((flow_bull + 2) / 4, 0), 1)

    # Alpha2: IV skew direction
    avg_skew   = recent['iv_skew'].tail(5).mean()
    alpha2     = min(max((avg_skew + 20) / 40, 0), 1)  # Normalize -20 to +20 → 0 to 1

    return round(alpha1, 4), round(alpha2, 4)

def load_state():
    if os.path.exists(STATE_FILE):
        with open(STATE_FILE, 'r') as f:
            return json.load(f)
    return {'in_trade': False}

def save_state(state):
    with open(STATE_FILE, 'w') as f:
        json.dump(state, f, indent=2)

def load_trades():
    if os.path.exists(TRADES_FILE):
        return pd.read_csv(TRADES_FILE)
    return pd.DataFrame(columns=[
        'Date', 'Entry Time', 'Exit Time', 'Signal', 'Strike',
        'Entry ₹', 'Exit ₹', 'SL ₹', 'Target ₹',
        'Lots', 'Qty', 'Exit Reason', 'P&L ₹', 'Capital After'
    ])

def save_trade(trade_row, trades_df):
    new_row    = pd.DataFrame([trade_row])
    trades_df  = pd.concat([trades_df, new_row], ignore_index=True)
    trades_df.to_csv(TRADES_FILE, index=False)
    return trades_df

def estimate_premium_bs(spot, strike, option_type, iv_pct, days_to_expiry=3):
    """Real Black-Scholes premium using live IV from NSE"""
    S  = spot
    K  = strike
    T  = max(days_to_expiry / 365, 1/365)
    r  = 0.065
    v  = max(iv_pct / 100, 0.01)
    d1 = (math.log(S/K) + (r + 0.5*v**2)*T) / (v * math.sqrt(T))
    d2 = d1 - v * math.sqrt(T)
    def N(x): return 0.5 * (1 + math.erf(x / math.sqrt(2)))
    if option_type == 'CE':
        return max(round(S * N(d1) - K * math.exp(-r*T) * N(d2), 2), 0.5)
    else:
        return max(round(K * math.exp(-r*T) * N(-d2) - S * N(-d1), 2), 0.5)

def days_to_expiry():
    """Calculate days to nearest Thursday expiry"""
    today   = now_ist.date()
    weekday = today.weekday()   # 0=Mon, 3=Thu
    days    = (3 - weekday) % 7
    if days == 0: days = 7
    return max(days, 1)

# ── Main Logic ────────────────────────────────────────
hour, minute = now_ist.hour, now_ist.minute
weekday      = now_ist.weekday()
timestamp    = now_ist.strftime('%Y-%m-%d %H:%M:%S')
time_str     = now_ist.strftime('%H:%M')
date_str     = now_ist.strftime('%Y-%m-%d')

in_window    = (
    (hour == TRADE_START_H and minute >= TRADE_START_M) or
    (TRADE_START_H < hour < TRADE_END_H) or
    (hour == TRADE_END_H and minute <= TRADE_END_M)
)
eod_exit_now = (hour == EOD_EXIT_H and minute >= EOD_EXIT_M)

if weekday >= 5:
    print("Weekend — skipping paper trade.")
    exit()

if not os.path.exists(CSV_FILE):
    print("No IV data yet — collector.py must run first.")
    exit()

df_iv     = pd.read_csv(CSV_FILE)
df_iv['timestamp'] = pd.to_datetime(df_iv['timestamp'])
df_iv     = df_iv.sort_values('timestamp').drop_duplicates('timestamp')

if df_iv.empty:
    print("Empty IV data.")
    exit()

last       = df_iv.iloc[-1]
spot       = float(last['spot'])
ce_iv      = float(last['ce_iv'])
pe_iv      = float(last['pe_iv'])
ce_oi      = float(last['ce_oi'])
pe_oi      = float(last['pe_oi'])
atm, otm_ce, otm_pe = get_strikes(spot)
dte        = days_to_expiry()

# BS premium using real IV from NSE
ce_prem    = estimate_premium_bs(spot, otm_ce, 'CE', ce_iv, dte)
pe_prem    = estimate_premium_bs(spot, otm_pe, 'PE', pe_iv, dte)

alpha1, alpha2 = compute_alphas(df_iv)

# Lot sizing: 2% risk per trade
risk_amt   = CAPITAL * 0.02
lots       = max(1, min(
    int(risk_amt / (max(ce_prem, MIN_PREM) * NIFTY_LOT * STOP_LOSS_PCT)),
    int(CAPITAL / MARGIN_PER_LOT)
))

state      = load_state()
trades_df  = load_trades()
capital    = float(state.get('capital', CAPITAL))

print(f"[{timestamp}] Spot:{spot} | ATM:{atm} | CE:{otm_ce}@₹{ce_prem}(IV:{ce_iv}%) | PE:{otm_pe}@₹{pe_prem}(IV:{pe_iv}%)")
print(f"  Alpha1:{alpha1} Alpha2:{alpha2} | In_Window:{in_window} | In_Trade:{state['in_trade']}")

# ── EOD Force Exit ────────────────────────────────────
if eod_exit_now and state['in_trade']:
    ep    = float(state['entry_price'])
    sig   = state['signal']
    spot0 = float(state['entry_spot'])
    spot_chg = (spot - spot0) / spot0
    if sig == 'BUY CE':
        curr_prem = ep * (1 + spot_chg * 8)
    else:
        curr_prem = ep * (1 - spot_chg * 8)
    curr_prem = max(curr_prem, 0.5)
    exit_price = round(curr_prem, 2)
    pnl = round((exit_price - ep) * NIFTY_LOT * int(state['lots']), 2)
    capital += pnl
    trade_row = {
        'Date'         : date_str,
        'Entry Time'   : state['entry_time'],
        'Exit Time'    : time_str,
        'Signal'       : sig,
        'Strike'       : state['strike'],
        'Entry ₹'      : ep,
        'Exit ₹'       : exit_price,
        'SL ₹'         : state['sl'],
        'Target ₹'     : state['target'],
        'Lots'         : state['lots'],
        'Qty'          : int(state['lots']) * NIFTY_LOT,
        'Exit Reason'  : 'EOD EXIT',
        'P&L ₹'        : pnl,
        'Capital After': round(capital, 2)
    }
    trades_df = save_trade(trade_row, trades_df)
    save_state({'in_trade': False, 'capital': capital})
    print(f"  🔴 EOD EXIT | P&L: ₹{pnl} | Capital: ₹{capital:,.0f}")
    exit()

# ── Check SL/Target if in trade ───────────────────────
if state['in_trade']:
    ep        = float(state['entry_price'])
    sl        = float(state['sl'])
    target    = float(state['target'])
    sig       = state['signal']
    spot0     = float(state['entry_spot'])
    spot_chg  = (spot - spot0) / spot0

    if sig == 'BUY CE':
        curr_prem = ep * (1 + spot_chg * 8)
    else:
        curr_prem = ep * (1 - spot_chg * 8)
    curr_prem = max(curr_prem, 0.5)

    exit_reason = None
    exit_price  = round(curr_prem, 2)

    if curr_prem <= sl:
        exit_reason = 'SL HIT'
        exit_price  = sl
    elif curr_prem >= target:
        exit_reason = 'TARGET HIT'
        exit_price  = target

    if exit_reason:
        pnl = round((exit_price - ep) * NIFTY_LOT * int(state['lots']), 2)
        capital += pnl
        trade_row = {
            'Date'         : date_str,
            'Entry Time'   : state['entry_time'],
            'Exit Time'    : time_str,
            'Signal'       : sig,
            'Strike'       : state['strike'],
            'Entry ₹'      : ep,
            'Exit ₹'       : exit_price,
            'SL ₹'         : sl,
            'Target ₹'     : target,
            'Lots'         : state['lots'],
            'Qty'          : int(state['lots']) * NIFTY_LOT,
            'Exit Reason'  : exit_reason,
            'P&L ₹'        : pnl,
            'Capital After': round(capital, 2)
        }
        trades_df = save_trade(trade_row, trades_df)
        save_state({'in_trade': False, 'capital': capital})
        emoji = '✅' if pnl > 0 else '🔴'
        print(f"  {emoji} {exit_reason} | Entry:₹{ep} Exit:₹{exit_price} | P&L:₹{pnl} | Capital:₹{capital:,.0f}")
    else:
        unrealised = round((curr_prem - ep) * NIFTY_LOT * int(state['lots']), 2)
        print(f"  📊 HOLDING {sig} {state['strike']} | Curr prem:₹{curr_prem:.1f} | Unrealised:₹{unrealised}")

# ── New Entry Signal ──────────────────────────────────
elif in_window and not state['in_trade']:
    signal = None
    if alpha1 > ALPHA1_BULL and alpha2 > ALPHA2_BULL and ce_prem >= MIN_PREM:
        signal = 'BUY CE'
        strike = otm_ce
        prem   = ce_prem
    elif alpha1 < ALPHA1_BEAR and alpha2 < ALPHA2_BEAR and pe_prem >= MIN_PREM:
        signal = 'BUY PE'
        strike = otm_pe
        prem   = pe_prem

    if signal:
        sl_price  = round(prem * (1 - STOP_LOSS_PCT), 2)
        tgt_price = round(prem * (1 + TARGET_PCT), 2)
        new_state = {
            'in_trade'    : True,
            'signal'      : signal,
            'strike'      : strike,
            'entry_price' : prem,
            'entry_spot'  : spot,
            'entry_time'  : time_str,
            'entry_date'  : date_str,
            'sl'          : sl_price,
            'target'      : tgt_price,
            'lots'        : lots,
            'capital'     : capital
        }
        save_state(new_state)
        print(f"  🟢 NEW SIGNAL: {signal} {strike} @ ₹{prem}")
        print(f"     SL:₹{sl_price} | Target:₹{tgt_price} | Lots:{lots} | Qty:{lots*NIFTY_LOT}")
        print(f"     Max Risk:₹{round((prem-sl_price)*NIFTY_LOT*lots, 2)}")
