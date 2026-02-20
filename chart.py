import pandas as pd
import plotly.graph_objects as go
from plotly.subplots import make_subplots
import os
import json

CSV_FILE    = "iv_data.csv"
TRADES_FILE = "trades.csv"
STATE_FILE  = "trade_state.json"
HTML_FILE   = "index.html"
CAPITAL     = 500000

# Load IV data
if not os.path.exists(CSV_FILE):
    print("No IV data.")
    exit()
df = pd.read_csv(CSV_FILE)
df['timestamp'] = pd.to_datetime(df['timestamp'])
df = df.sort_values('timestamp').drop_duplicates('timestamp')

# Load trades
trades_df = pd.read_csv(TRADES_FILE) if os.path.exists(TRADES_FILE) else pd.DataFrame()

# Load state (open position)
state = {}
if os.path.exists(STATE_FILE):
    with open(STATE_FILE) as f:
        state = json.load(f)

fig = make_subplots(
    rows=5, cols=1,
    shared_xaxes=True,
    row_heights=[0.30, 0.20, 0.18, 0.18, 0.14],
    vertical_spacing=0.025,
    subplot_titles=(
        'NIFTY Spot + Trade Signals',
        'Real IV — CE vs PE (from NSE)',
        'IV Skew (CE IV − PE IV)',
        'Running P&L (Paper Trading)',
        'OI — CE vs PE'
    )
)

# ── Row 1: Spot + Signals ─────────────────────────────
fig.add_trace(go.Scatter(
    x=df['timestamp'], y=df['spot'],
    name='NIFTY Spot', line=dict(color='#00bcd4', width=2)
), row=1, col=1)

# Plot trade entries/exits on spot chart
if not trades_df.empty:
    for _, t in trades_df.iterrows():
        color = 'lime' if 'CE' in str(t['Signal']) else 'red'
        symbol = 'triangle-up' if 'CE' in str(t['Signal']) else 'triangle-down'
        pnl_color = '#00e676' if float(t['P&L ₹']) >= 0 else '#ff1744'

        # Entry dot
        fig.add_trace(go.Scatter(
            x=[f"{t['Date']} {t['Entry Time']}:00"],
            y=[df[df['timestamp'] <= f"{t['Date']} {t['Entry Time']}:00"]['spot'].iloc[-1]
               if len(df[df['timestamp'] <= f"{t['Date']} {t['Entry Time']}:00"]) > 0 else None],
            mode='markers+text',
            marker=dict(symbol=symbol, size=14, color=color),
            text=[f"{'▲' if 'CE' in str(t['Signal']) else '▼'}{t['Strike']}@₹{t['Entry ₹']}"],
            textposition='bottom center' if 'CE' in str(t['Signal']) else 'top center',
            textfont=dict(size=8, color=color),
            name=f"{t['Signal']} {t['Date']}",
            showlegend=False,
            hovertext=f"ENTRY {t['Signal']}<br>Strike:{t['Strike']}<br>₹{t['Entry ₹']}<br>SL:₹{t['SL ₹']} Tgt:₹{t['Target ₹']}<br>Lots:{t['Lots']}",
            hoverinfo='text'
        ), row=1, col=1)

        # Exit dot
        fig.add_trace(go.Scatter(
            x=[f"{t['Date']} {t['Exit Time']}:00"],
            y=[df[df['timestamp'] <= f"{t['Date']} {t['Exit Time']}:00"]['spot'].iloc[-1]
               if len(df[df['timestamp'] <= f"{t['Date']} {t['Exit Time']}:00"]) > 0 else None],
            mode='markers',
            marker=dict(symbol='x', size=12, color=pnl_color,
                        line=dict(width=2, color=pnl_color)),
            name=f"Exit {t['Exit Reason']}",
            showlegend=False,
            hovertext=f"EXIT {t['Exit Reason']}<br>₹{t['Exit ₹']}<br>P&L: ₹{t['P&L ₹']}",
            hoverinfo='text'
        ), row=1, col=1)

# Open trade indicator
if state.get('in_trade'):
    fig.add_annotation(
        text=f"🟢 OPEN: {state['signal']} {state['strike']} @ ₹{state['entry_price']} | "
             f"SL:₹{state['sl']} | Tgt:₹{state['target']} | Lots:{state['lots']}",
        xref='paper', yref='paper', x=0, y=-0.01,
        showarrow=False, font=dict(size=11, color='lime'),
        bgcolor='rgba(0,50,0,0.7)'
    )

# ── Row 2: Real IV ────────────────────────────────────
fig.add_trace(go.Scatter(x=df['timestamp'], y=df['atm_ce_iv'],
    name='ATM CE IV', line=dict(color='lime', width=1.5)), row=2, col=1)
fig.add_trace(go.Scatter(x=df['timestamp'], y=df['atm_pe_iv'],
    name='ATM PE IV', line=dict(color='red', width=1.5, dash='dash')), row=2, col=1)
fig.add_trace(go.Scatter(x=df['timestamp'], y=df['ce_iv'],
    name='OTM CE IV', line=dict(color='#76ff03', width=1, dash='dot')), row=2, col=1)
fig.add_trace(go.Scatter(x=df['timestamp'], y=df['pe_iv'],
    name='OTM PE IV', line=dict(color='#ff5252', width=1, dash='dot')), row=2, col=1)

# ── Row 3: IV Skew ────────────────────────────────────
colors_skew = ['lime' if v >= 0 else 'red' for v in df['iv_skew']]
fig.add_trace(go.Bar(x=df['timestamp'], y=df['iv_skew'],
    name='IV Skew', marker_color=colors_skew, opacity=0.8), row=3, col=1)
fig.add_hline(y=0, line_dash='dot', line_color='white', row=3, col=1)

# ── Row 4: Running P&L curve ──────────────────────────
if not trades_df.empty:
    trades_df['P&L ₹']      = pd.to_numeric(trades_df['P&L ₹'], errors='coerce').fillna(0)
    trades_df['Capital After'] = pd.to_numeric(trades_df['Capital After'], errors='coerce')
    trades_df['cumulative_pnl'] = trades_df['P&L ₹'].cumsum()
    pnl_colors = ['#00e676' if p >= 0 else '#ff1744' for p in trades_df['P&L ₹']]

    fig.add_trace(go.Scatter(
        x=[f"{r['Date']} {r['Exit Time']}:00" for _, r in trades_df.iterrows()],
        y=trades_df['cumulative_pnl'],
        name='Cumulative P&L', mode='lines+markers',
        line=dict(color='gold', width=2),
        marker=dict(color=pnl_colors, size=8),
        fill='tozeroy', fillcolor='rgba(255,215,0,0.05)',
        hovertext=[f"Trade {i+1}: {r['Signal']} {r['Strike']}<br>"
                   f"P&L: ₹{r['P&L ₹']:,.0f}<br>"
                   f"Cumulative: ₹{r['cumulative_pnl']:,.0f}<br>"
                   f"Capital: ₹{r['Capital After']:,.0f}<br>"
                   f"Reason: {r['Exit Reason']}"
                   for i, (_, r) in enumerate(trades_df.iterrows())],
        hoverinfo='text'
    ), row=4, col=1)
    fig.add_hline(y=0, line_dash='dot', line_color='white', row=4, col=1)

    total_pnl = trades_df['P&L ₹'].sum()
    roi = (total_pnl / CAPITAL) * 100
    wins = len(trades_df[trades_df['P&L ₹'] > 0])
    fig.add_annotation(
        text=f"Total Trades:{len(trades_df)} | Wins:{wins} | "
             f"Win Rate:{wins/len(trades_df)*100:.0f}% | "
             f"Total P&L:₹{total_pnl:,.0f} | ROI:{roi:.2f}%",
        xref='paper', yref='paper', x=0, y=1.02,
        showarrow=False, font=dict(size=11, color='gold'),
        bgcolor='rgba(0,0,0,0.6)', row=4, col=1
    )

# ── Row 5: OI ─────────────────────────────────────────
fig.add_trace(go.Scatter(x=df['timestamp'], y=df['ce_oi'],
    name='CE OI', line=dict(color='lime', width=1),
    fill='tozeroy', fillcolor='rgba(0,255,0,0.05)'), row=5, col=1)
fig.add_trace(go.Scatter(x=df['timestamp'], y=df['pe_oi'],
    name='PE OI', line=dict(color='red', width=1),
    fill='tozeroy', fillcolor='rgba(255,0,0,0.05)'), row=5, col=1)

# Last update info
last_row  = df.iloc[-1]
last_time = last_row['timestamp'].strftime('%d %b %Y %H:%M IST')
fig.add_annotation(
    text=f"Last Update: {last_time} | Spot:{last_row['spot']:.0f} | "
         f"CE IV:{last_row['ce_iv']:.1f}% | PE IV:{last_row['pe_iv']:.1f}% | "
         f"Skew:{last_row['iv_skew']:.2f}",
    xref='paper', yref='paper', x=0.5, y=1.005, xanchor='center',
    showarrow=False, font=dict(size=10, color='white'),
    bgcolor='rgba(30,30,30,0.8)'
)

fig.update_layout(
    template='plotly_dark', height=1100,
    xaxis_rangeslider_visible=False,
    title=dict(
        text='Skew Hunter — NIFTY Real IV + Paper Trading Dashboard (Auto-Updated Every 3 Min)',
        font=dict(size=13)
    ),
    legend=dict(orientation='h', y=1.03),
    hovermode='x unified',
    updatemenus=[dict(
        type='buttons', direction='right',
        x=0.0, y=1.08, showactive=True,
        buttons=[
            dict(label='All',     method='relayout', args=[{'xaxis.autorange': True}]),
            dict(label='Today',   method='relayout', args=[{'xaxis.range': [
                df['timestamp'].dt.date.max().strftime('%Y-%m-%d 09:15:00'),
                df['timestamp'].dt.date.max().strftime('%Y-%m-%d 15:30:00')
            ]}]),
            dict(label='1 Week',  method='relayout', args=[{'xaxis.range': [
                (df['timestamp'].max() - pd.Timedelta(days=7)).isoformat(),
                df['timestamp'].max().isoformat()
            ]}]),
            dict(label='1 Month', method='relayout', args=[{'xaxis.range': [
                (df['timestamp'].max() - pd.Timedelta(days=30)).isoformat(),
                df['timestamp'].max().isoformat()
            ]}]),
        ]
    )]
)
fig.update_xaxes(rangebreaks=[
    dict(bounds=['sat','mon']),
    dict(bounds=[15.5, 9.25], pattern='hour')
])
fig.update_yaxes(title_text='Spot',   row=1, col=1)
fig.update_yaxes(title_text='IV %',   row=2, col=1)
fig.update_yaxes(title_text='Skew',   row=3, col=1)
fig.update_yaxes(title_text='P&L ₹', row=4, col=1)
fig.update_yaxes(title_text='OI',     row=5, col=1)

fig.write_html(HTML_FILE)
print(f"Dashboard updated → {HTML_FILE} ({len(df)} IV points, {len(trades_df) if not trades_df.empty else 0} trades)")
