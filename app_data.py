"""Build app_data.json -- the compact feed the mobile app (app.html) fetches.

The phone should not be downloading and parsing the full CSV history, so this
trims iv_data.csv / trades.csv / trade_state.json down to:

  * latest   -- the newest tick, plus its move against the session open
  * series   -- intraday points for the last INTRADAY_SESSIONS sessions, stored
                columnar (arrays per field) because that is roughly half the
                bytes of an array of row objects
  * daily    -- one session-close row per day, for the whole history
  * trades / open_position / summary -- the paper-trading side

Timestamps go out as ISO strings carrying the +05:30 offset so the app can
render them in IST no matter what timezone the phone is set to.

Run after collector.py and paper_trade.py; writes app_data.json.
"""
import json
import os
from datetime import datetime

import pandas as pd
import pytz

IST                 = pytz.timezone('Asia/Kolkata')
CSV_FILE            = "iv_data.csv"
TRADES_FILE         = "trades.csv"
STATE_FILE          = "trade_state.json"
OUT_FILE            = "app_data.json"

SCHEMA_VERSION      = 1
# Fields that change on every run even when the data does not. The workflow
# runs every 3 minutes from 08:30 IST, but the collector only writes between
# 09:15 and 15:30 -- without this the feed would produce an empty-but-different
# commit on every run outside those hours.
VOLATILE_FIELDS     = ('generated_at', 'market_open')
INTRADAY_SESSIONS   = 7      # sessions of 3-minute points kept in `series`
MAX_TRADES          = 100    # most recent trades kept
START_CAPITAL       = 500000
NIFTY_LOT           = 75
# Mirrors the premium proxy in paper_trade.py -- keep the two in step if that
# strategy changes, otherwise the app's unrealised P&L will disagree with the
# exit prices the trader actually books.
DELTA_PROXY         = 8

SERIES_FIELDS = ['spot', 'ce_iv', 'pe_iv', 'atm_ce_iv', 'atm_pe_iv',
                 'skew', 'ce_oi', 'pe_oi']


def num(value, digits=2):
    """JSON-safe number: NaN/inf become None, floats rounded down to size.

    digits=0 returns an int, so strikes and lot counts don't ship as `3.0`.
    """
    if value is None:
        return None
    try:
        f = float(value)
    except (TypeError, ValueError):
        return None
    if f != f or f in (float('inf'), float('-inf')):
        return None
    return int(round(f)) if digits == 0 else round(f, digits)


def columnar(frame, fields, label_col):
    out = {'t': [t for t in frame[label_col]]}
    for field in fields:
        digits = 0 if field.endswith('_oi') else 2
        out[field] = [num(v, digits) for v in frame[field]] if field in frame else []
    return out


def market_is_open(now):
    if now.weekday() >= 5:
        return False
    minutes = now.hour * 60 + now.minute
    return 9 * 60 + 15 <= minutes <= 15 * 60 + 30


def load_iv():
    if not os.path.exists(CSV_FILE):
        return pd.DataFrame()
    df = pd.read_csv(CSV_FILE)
    if df.empty:
        return df
    df['timestamp'] = pd.to_datetime(df['timestamp'], errors='coerce')
    df = df.dropna(subset=['timestamp'])
    df = df.sort_values('timestamp').drop_duplicates('timestamp').reset_index(drop=True)
    # iv_skew is the collector's column name; the app calls it `skew`.
    df['skew'] = df['iv_skew'] if 'iv_skew' in df else df['ce_iv'] - df['pe_iv']
    df['session'] = df['timestamp'].dt.strftime('%Y-%m-%d')
    df['iso'] = df['timestamp'].dt.strftime('%Y-%m-%dT%H:%M:%S+05:30')
    return df


def build_latest(df):
    last = df.iloc[-1]
    session = df[df['session'] == last['session']]
    session_open = float(session.iloc[0]['spot']) if not session.empty else float(last['spot'])
    spot = float(last['spot'])
    return {
        'timestamp'   : last['iso'],
        'session'     : last['session'],
        'spot'        : num(spot),
        'atm_strike'  : num(last.get('atm_strike'), 0),
        'otm_ce'      : num(last.get('otm_ce'), 0),
        'otm_pe'      : num(last.get('otm_pe'), 0),
        'ce_iv'       : num(last.get('ce_iv')),
        'pe_iv'       : num(last.get('pe_iv')),
        'atm_ce_iv'   : num(last.get('atm_ce_iv')),
        'atm_pe_iv'   : num(last.get('atm_pe_iv')),
        'ce_oi'       : num(last.get('ce_oi'), 0),
        'pe_oi'       : num(last.get('pe_oi'), 0),
        'skew'        : num(last.get('skew')),
        'spot_change' : num(spot - session_open),
        'spot_change_pct': num((spot - session_open) / session_open * 100 if session_open else 0),
        'session_points' : int(len(session)),
    }


def build_daily(df):
    """One row per session, taking each field's closing value."""
    closes = df.groupby('session', as_index=False).last()
    closes = closes.sort_values('session')
    return columnar(closes, SERIES_FIELDS, 'session')


def build_trades():
    if not os.path.exists(TRADES_FILE):
        return [], None
    trades = pd.read_csv(TRADES_FILE)
    if trades.empty:
        return [], None
    rows = []
    for _, t in trades.tail(MAX_TRADES).iterrows():
        rows.append({
            'date'    : str(t.get('Date', '')),
            'entry_at': str(t.get('Entry Time', '')),
            'exit_at' : str(t.get('Exit Time', '')),
            'signal'  : str(t.get('Signal', '')),
            'strike'  : num(t.get('Strike'), 0),
            'entry'   : num(t.get('Entry ₹')),
            'exit'    : num(t.get('Exit ₹')),
            'sl'      : num(t.get('SL ₹')),
            'target'  : num(t.get('Target ₹')),
            'lots'    : num(t.get('Lots'), 0),
            'qty'     : num(t.get('Qty'), 0),
            'reason'  : str(t.get('Exit Reason', '')),
            'pnl'     : num(t.get('P&L ₹')),
            'capital' : num(t.get('Capital After')),
        })
    return rows, trades


def build_summary(trades, trade_rows, latest_session):
    pnl = pd.to_numeric(trades['P&L ₹'], errors='coerce').fillna(0) if trades is not None else pd.Series(dtype=float)
    total_pnl = float(pnl.sum()) if len(pnl) else 0.0
    wins = int((pnl > 0).sum()) if len(pnl) else 0
    count = int(len(pnl))
    today_pnl = sum(r['pnl'] or 0 for r in trade_rows if r['date'] == latest_session)
    return {
        'start_capital': START_CAPITAL,
        'capital'      : num(START_CAPITAL + total_pnl),
        'total_pnl'    : num(total_pnl),
        'today_pnl'    : num(today_pnl),
        'trades'       : count,
        'wins'         : wins,
        'losses'       : count - wins,
        'win_rate'     : num(wins / count * 100 if count else 0, 1),
        'roi_pct'      : num(total_pnl / START_CAPITAL * 100, 2),
    }


def build_open_position(latest):
    if not os.path.exists(STATE_FILE):
        return None
    try:
        with open(STATE_FILE) as f:
            state = json.load(f)
    except (json.JSONDecodeError, OSError):
        return None
    if not state.get('in_trade'):
        return None

    entry = float(state.get('entry_price', 0) or 0)
    entry_spot = float(state.get('entry_spot', 0) or 0)
    lots = int(state.get('lots', 0) or 0)
    spot = latest['spot'] or 0

    current = None
    unrealised = None
    if entry and entry_spot:
        spot_change = (spot - entry_spot) / entry_spot
        direction = 1 if state.get('signal') == 'BUY CE' else -1
        current = max(entry * (1 + direction * spot_change * DELTA_PROXY), 0.5)
        unrealised = (current - entry) * NIFTY_LOT * lots

    return {
        'signal'     : state.get('signal'),
        'strike'     : num(state.get('strike'), 0),
        'entry'      : num(entry),
        'entry_spot' : num(entry_spot),
        'entry_at'   : state.get('entry_time'),
        'entry_date' : state.get('entry_date'),
        'sl'         : num(state.get('sl')),
        'target'     : num(state.get('target')),
        'lots'       : lots,
        'qty'        : lots * NIFTY_LOT,
        'current'    : num(current),
        'unrealised' : num(unrealised),
    }


def unchanged(payload):
    """True when the only difference from the file on disk is a timestamp."""
    if not os.path.exists(OUT_FILE):
        return False
    try:
        with open(OUT_FILE) as f:
            existing = json.load(f)
    except (json.JSONDecodeError, OSError):
        return False
    strip = lambda d: {k: v for k, v in d.items() if k not in VOLATILE_FIELDS}
    return strip(existing) == strip(payload)


def main():
    now = datetime.now(IST)
    payload = {
        'schema'       : SCHEMA_VERSION,
        'generated_at' : now.strftime('%Y-%m-%dT%H:%M:%S+05:30'),
        'market_open'  : market_is_open(now),
        'latest'       : None,
        'series'       : {'t': [], **{f: [] for f in SERIES_FIELDS}},
        'daily'        : {'t': [], **{f: [] for f in SERIES_FIELDS}},
        'open_position': None,
        'trades'       : [],
        'summary'      : build_summary(None, [], ''),
    }

    df = load_iv()
    if not df.empty:
        payload['latest'] = build_latest(df)

        recent_sessions = sorted(df['session'].unique())[-INTRADAY_SESSIONS:]
        intraday = df[df['session'].isin(recent_sessions)]
        payload['series'] = columnar(intraday, SERIES_FIELDS, 'iso')
        payload['daily'] = build_daily(df)
        payload['open_position'] = build_open_position(payload['latest'])

    trade_rows, trades = build_trades()
    payload['trades'] = trade_rows
    latest_session = payload['latest']['session'] if payload['latest'] else ''
    payload['summary'] = build_summary(trades, trade_rows, latest_session)

    if unchanged(payload):
        print(f"App feed unchanged -- leaving {OUT_FILE} alone.")
        return

    with open(OUT_FILE, 'w') as f:
        json.dump(payload, f, separators=(',', ':'))

    size_kb = os.path.getsize(OUT_FILE) / 1024
    print(f"App feed -> {OUT_FILE} ({size_kb:.1f} KB): "
          f"{len(payload['series']['t'])} intraday points, "
          f"{len(payload['daily']['t'])} sessions, "
          f"{len(payload['trades'])} trades, "
          f"open={'yes' if payload['open_position'] else 'no'}")


if __name__ == '__main__':
    main()
