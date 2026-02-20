import pandas as pd
import plotly.graph_objects as go
from plotly.subplots import make_subplots
import os

CSV_FILE  = "iv_data.csv"
HTML_FILE = "index.html"   # GitHub Pages serves this automatically

if not os.path.exists(CSV_FILE):
    print("No data yet.")
    exit()

df = pd.read_csv(CSV_FILE)
df['timestamp'] = pd.to_datetime(df['timestamp'])
df = df.sort_values('timestamp').drop_duplicates('timestamp')

fig = make_subplots(
    rows=4, cols=1,
    shared_xaxes=True,
    row_heights=[0.35, 0.25, 0.25, 0.15],
    vertical_spacing=0.03,
    subplot_titles=(
        'NIFTY Spot Price',
        'Real IV — OTM CE vs OTM PE',
        'IV Skew (CE IV − PE IV)',
        'OI — CE vs PE'
    )
)

# NIFTY Spot
fig.add_trace(go.Scatter(
    x=df['timestamp'], y=df['spot'],
    name='NIFTY Spot', line=dict(color='#00bcd4', width=2)
), row=1, col=1)

# ATM IV lines
fig.add_trace(go.Scatter(
    x=df['timestamp'], y=df['atm_ce_iv'],
    name='ATM CE IV', line=dict(color='lime', width=1.5)
), row=2, col=1)
fig.add_trace(go.Scatter(
    x=df['timestamp'], y=df['atm_pe_iv'],
    name='ATM PE IV', line=dict(color='red', width=1.5, dash='dash')
), row=2, col=1)
fig.add_trace(go.Scatter(
    x=df['timestamp'], y=df['ce_iv'],
    name='OTM CE IV', line=dict(color='#76ff03', width=1, dash='dot')
), row=2, col=1)
fig.add_trace(go.Scatter(
    x=df['timestamp'], y=df['pe_iv'],
    name='OTM PE IV', line=dict(color='#ff5252', width=1, dash='dot')
), row=2, col=1)

# IV Skew
colors_skew = ['lime' if v >= 0 else 'red' for v in df['iv_skew']]
fig.add_trace(go.Bar(
    x=df['timestamp'], y=df['iv_skew'],
    name='IV Skew', marker_color=colors_skew, opacity=0.8
), row=3, col=1)
fig.add_hline(y=0, line_dash='dot', line_color='white', row=3, col=1)

# OI
fig.add_trace(go.Scatter(
    x=df['timestamp'], y=df['ce_oi'],
    name='CE OI', line=dict(color='lime', width=1), fill='tozeroy',
    fillcolor='rgba(0,255,0,0.05)'
), row=4, col=1)
fig.add_trace(go.Scatter(
    x=df['timestamp'], y=df['pe_oi'],
    name='PE OI', line=dict(color='red', width=1), fill='tozeroy',
    fillcolor='rgba(255,0,0,0.05)'
), row=4, col=1)

# Last update annotation
last_row  = df.iloc[-1]
last_time = last_row['timestamp'].strftime('%d %b %Y %H:%M IST')
fig.add_annotation(
    text=f"Last Update: {last_time} | Spot: {last_row['spot']} | "
         f"CE IV: {last_row['ce_iv']:.1f}% | PE IV: {last_row['pe_iv']:.1f}% | "
         f"Skew: {last_row['iv_skew']:.2f}",
    xref='paper', yref='paper', x=0, y=1.02,
    showarrow=False, font=dict(size=11, color='white'),
    bgcolor='rgba(0,0,0,0.5)'
)

fig.update_layout(
    template='plotly_dark',
    height=900,
    title=dict(
        text='Skew Hunter — NIFTY Real IV Dashboard (Auto-Updated Every 3 Min)',
        font=dict(size=14)
    ),
    legend=dict(orientation='h', y=1.06),
    hovermode='x unified',
    xaxis_rangeslider_visible=False,
    updatemenus=[dict(
        type='buttons', direction='right',
        x=0.0, y=1.14, showactive=True,
        buttons=[
            dict(label='All',    method='relayout', args=[{'xaxis.autorange': True}]),
            dict(label='Today',  method='relayout', args=[{'xaxis.range': [
                df['timestamp'].dt.date.max().strftime('%Y-%m-%d 09:15:00'),
                df['timestamp'].dt.date.max().strftime('%Y-%m-%d 15:30:00')
            ]}]),
            dict(label='1 Week', method='relayout', args=[{'xaxis.range': [
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
    dict(bounds=['sat', 'mon']),
    dict(bounds=[15.5, 9.25], pattern='hour')
])
fig.update_yaxes(title_text='NIFTY',   row=1, col=1)
fig.update_yaxes(title_text='IV %',    row=2, col=1)
fig.update_yaxes(title_text='Skew',    row=3, col=1)
fig.update_yaxes(title_text='OI',      row=4, col=1)

fig.write_html(HTML_FILE)
print(f"Chart saved → {HTML_FILE}  ({len(df)} data points)")
