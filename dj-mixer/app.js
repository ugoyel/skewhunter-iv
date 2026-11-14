'use strict';
/* CrossDeck — a DJ-style mixer with two kinds of tracks.

   YouTube tracks play through the official YouTube IFrame Player. Rules that keep that side
   inside YouTube's API policies:
   - every YouTube deck is a normal, visible embedded player (>= 200x200) with its own controls, nothing drawn over it
   - YouTube audio is never extracted, recorded, analysed or processed; it is mixed only with the player's own volume
   - YouTube playback is paused when the app is not visible (no background play)
   - a second YouTube deck only starts over a playing one when the user taps something; Auto DJ never overlaps them

   Local files (the user's own music) play through Web Audio, so they get real beat analysis,
   EQ and beat-matched automatic mixing. */

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = t => { t = Math.max(0, Math.floor(t || 0)); return Math.floor(t / 60) + ':' + String(t % 60).padStart(2, '0'); };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const store = {
  get(k, d) { try { const v = localStorage.getItem('cd.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('cd.' + k, JSON.stringify(v)); } catch { /* storage full or unavailable */ } }
};

// Put your own YouTube Data API key here before publishing so users can search without any setup.
// Restrict the key to your site's address in Google Cloud Console.
const BUILT_IN_API_KEY = 'AIzaSyBCO3y2qvWEi6FBo87C_Fx-cpVtMRy9C1A';
const apiKey = () => state.apiKey || BUILT_IN_API_KEY;

const state = {
  x: 0, master: 1,
  curve: store.get('curve2', 'linear'),
  fadeSec: store.get('fadeSec', 8),
  stopOut: store.get('stopOut', true),
  four: store.get('four', false),
  apiKey: store.get('apiKey', ''),
  library: store.get('library', []),
  queue: [],
  files: [],
  results: [], resultsTitle: '', tab: 'library',
  autoDJ: false, transitioning: false, apiReady: false,
  live: 'A' // the player currently on the speaker; the other one is the next track being prepared
};
const NUM = { A: '1', B: '2', C: '3', D: '4' }; // what the players are called on screen
// queue entries point at the library's own track objects so saved mix points stay in one place
state.queue = store.get('queue', []).map(q => state.library.find(l => l.id === q.id) || q);

function toast(msg) {
  const t = $('#toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(toast.h); toast.h = setTimeout(() => t.classList.remove('show'), 3500);
}

function parseVideoId(s) {
  s = String(s).trim();
  if (/^[\w-]{11}$/.test(s)) return s;
  try {
    const u = new URL(s);
    if (!/(^|\.)youtube\.com$|(^|\.)youtu\.be$|(^|\.)youtube-nocookie\.com$/.test(u.hostname)) return null;
    if (u.hostname.endsWith('youtu.be')) return u.pathname.slice(1, 12) || null;
    const v = u.searchParams.get('v'); if (v && /^[\w-]{11}$/.test(v)) return v;
    const m = u.pathname.match(/\/(embed|shorts|live)\/([\w-]{11})/); if (m) return m[2];
  } catch { /* not a URL */ }
  return null;
}

/* ---------- timed ramps ---------- */
const anims = new Map();
function cancelAnim(key) { const a = anims.get(key); if (a) { clearInterval(a.h); anims.delete(key); a.res(false); } }
function animate(key, from, to, ms, set) {
  cancelAnim(key);
  return new Promise(res => {
    const t0 = performance.now();
    const h = setInterval(() => {
      const k = ms <= 0 ? 1 : Math.min(1, (performance.now() - t0) / ms);
      set(from + (to - from) * k);
      if (k >= 1) { clearInterval(h); anims.delete(key); res(true); }
    }, 40);
    anims.set(key, { h, res });
  });
}

/* ---------- mixer maths ---------- */
function xfGain(side) {
  const p = side === 'L' ? 1 - state.x : state.x; // 1 = fully on this deck's side
  if (state.curve === 'linear') return p;
  if (state.curve === 'cut') return p > 0.03 ? 1 : 0;
  return Math.sin(p * Math.PI / 2);
}
function setX(x) {
  state.x = clamp(x, 0, 1); $('#xfader').value = Math.round(state.x * 1000);
  $('#mix').value = Math.round(mixPos() * 1000); applyVolumes();
}
// 0 = only the current (live) player is heard on the speaker, 1 = only the next one
const mixPos = () => state.live === 'A' ? state.x : 1 - state.x;
const setMix = m => setX(state.live === 'A' ? m : 1 - m);
// Once the crossfader rests fully on one side, that player is the live one and the other becomes "next".
function syncLive() {
  if (state.x <= 0.001) state.live = 'A'; else if (state.x >= 0.999) state.live = 'B';
  const live = deck[state.live], next = otherDeck(live);
  $('#mix').value = Math.round(mixPos() * 1000);
  const lab = (id, d) => { const e = $(id); e.textContent = NUM[d.id]; e.style.setProperty('--c', COLORS[d.id]); };
  lab('#mix-from', live); lab('#mix-to', next);
  $('#btn-bring').textContent = `Play Player ${NUM[next.id]} on speaker too`;
  $('#btn-bring').style.setProperty('--c', COLORS[next.id]);
  $('#hp-row').hidden = !out.ready;
  $('#mix-hint').textContent = out.ready && next.track && next.kind === 'yt'
    ? 'YouTube tracks cannot be sent to headphones. Only your own music files can be previewed there.'
    : `Slide right to bring Player ${NUM[next.id]} in and fade Player ${NUM[live.id]} out.`;
  decks.forEach(d => d.renderRole()); applyVolumes();
}
// The button: start the next player on the speaker quietly (10%), ready to be mixed in with the slider.
function bringIn() {
  const live = deck[state.live], next = otherDeck(live);
  if (!next.track) return toast(`Load a track on Player ${NUM[next.id]} first`);
  cancelAnim('xf');
  if (!live.isPlaying()) { setX(next.side === 'L' ? 0 : 1); next.play(); return syncLive(); } // nothing to mix with: just switch over
  if (!next.isPlaying()) next.play();
  if (mixPos() < 0.1) setMix(0.1);
}
function applyVolumes() { for (const d of decks) d.applyVolume(); }

/* ---------- Web Audio (local files and FX pads only) ---------- */
let actx;
function audio() {
  actx = actx || new (window.AudioContext || window.webkitAudioContext)();
  if (actx.state === 'suspended') actx.resume();
  return actx;
}

// Headphone preview. The speaker gets the normal mix; a second "cue" mix is sent to a second sound device.
// Browsers only allow this for audio the app itself plays, so it cannot include YouTube.
const canSink = 'setSinkId' in HTMLMediaElement.prototype;
const out = { main: store.get('outMain', ''), hp: store.get('outHp', ''), hpVol: store.get('hpVol', 0.8), ready: false };
let cueBus, cueAudio;
function cueNode() {
  if (!cueBus) {
    const c = audio(), dest = c.createMediaStreamDestination();
    cueBus = c.createGain(); cueBus.connect(dest);
    cueAudio = new Audio(); cueAudio.srcObject = dest.stream;
  }
  return cueBus;
}
async function applyOutputs() {
  const c = audio(); cueNode(); out.ready = false;
  try { if (c.setSinkId && (out.main || out.mainSet)) { await c.setSinkId(out.main && out.main !== 'default' ? out.main : ''); out.mainSet = true; } } catch { /* device gone: stay on the system default */ }
  if (out.hp && canSink) {
    try { await cueAudio.setSinkId(out.hp); await cueAudio.play(); out.ready = true; } catch { /* device gone or blocked */ }
  } else cueAudio.pause();
  syncLive();
}
async function findDevices() {
  const note = $('#out-note');
  if (!canSink || !navigator.mediaDevices) { note.textContent = 'This browser cannot send sound to two devices. Headphone preview needs Chrome or Edge on a computer.'; return; }
  try { (await navigator.mediaDevices.getUserMedia({ audio: true })).getTracks().forEach(t => t.stop()); } // browsers only list speakers after this permission; nothing is recorded
  catch { note.textContent = 'Permission was refused, so the browser will not list your sound devices.'; return; }
  const devs = (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === 'audiooutput');
  const opts = devs.map((d, i) => `<option value="${esc(d.deviceId)}">${esc(d.label || 'Sound device ' + (i + 1))}</option>`).join('');
  $('#out-main').innerHTML = '<option value="">System default</option>' + opts; $('#out-main').value = out.main;
  $('#out-hp').innerHTML = '<option value="">None (no headphone preview)</option>' + opts; $('#out-hp').value = out.hp;
  $('#out-pick').hidden = false;
  note.textContent = devs.length ? 'Pick the big speaker and the headphones, then Save.' : 'No sound devices were found.';
}

// Plays one local file. Mirrors the small part of the YouTube player's interface the decks use.
class LocalPlayer {
  constructor(onState) {
    const c = audio(), a = this.audio = new Audio();
    this.onState = onState; a.preload = 'auto'; a.preservesPitch = true; // tempo changes keep the key
    const f = (type, freq) => { const n = c.createBiquadFilter(); n.type = type; n.frequency.value = freq; n.gain.value = 0; return n; };
    this.low = f('lowshelf', 220); this.mid = f('peaking', 1000); this.mid.Q.value = 0.8; this.high = f('highshelf', 4500);
    this.gain = c.createGain();
    this.cue = c.createGain(); this.cue.gain.value = 0; this.high.connect(this.cue).connect(cueNode()); // headphone send, before the volume
    c.createMediaElementSource(a).connect(this.low).connect(this.mid).connect(this.high).connect(this.gain).connect(c.destination);
    a.addEventListener('playing', () => onState(1));
    a.addEventListener('waiting', () => onState(3));
    a.addEventListener('pause', () => { if (!a.ended) onState(2); });
    a.addEventListener('ended', () => onState(0));
    a.addEventListener('error', () => { if (a.src) onState(-2); });
  }
  load(file, start, autoplay) {
    const a = this.audio;
    if (this.url) URL.revokeObjectURL(this.url);
    this.url = URL.createObjectURL(file); a.src = this.url; a.playbackRate = 1;
    a.addEventListener('loadedmetadata', () => { if (start) a.currentTime = start; this.onState(5); if (autoplay) this.playVideo(); }, { once: true });
  }
  playVideo() { audio(); this.audio.play().catch(() => {}); }
  pauseVideo() { this.audio.pause(); }
  seekTo(t) { this.audio.currentTime = clamp(t, 0, this.getDuration() || t); }
  getCurrentTime() { return this.audio.currentTime || 0; }
  getDuration() { const d = this.audio.duration; return isFinite(d) ? d : 0; }
  setGain(g) { this.gain.gain.setTargetAtTime(g, actx.currentTime, 0.015); }
  setCue(g) { this.cue.gain.setTargetAtTime(g, actx.currentTime, 0.015); }
  setEq(band, db) { this[band].gain.setTargetAtTime(db, actx.currentTime, 0.06); }
  setPlaybackRate(r) { this.audio.playbackRate = r; }
  getPlaybackRate() { return this.audio.playbackRate; }
}

/* ---------- beat analysis for local files ---------- */
async function analyseFile(file) {
  const buf = await audio().decodeAudioData(await file.arrayBuffer());
  const sr = buf.sampleRate, c0 = buf.getChannelData(0), c1 = buf.numberOfChannels > 1 ? buf.getChannelData(1) : c0;
  const hop = Math.round(sr / 100), frames = Math.floor(buf.length / hop);
  const full = new Float32Array(frames), low = new Float32Array(frames);
  const a = 1 - Math.exp(-2 * Math.PI * 150 / sr); let lp = 0; // one-pole low-pass isolates the kick/bass
  for (let f = 0, i = 0; f < frames; f++) {
    let ef = 0, el = 0;
    for (let k = 0; k < hop; k++, i++) { const x = (c0[i] + c1[i]) * 0.5; lp += a * (x - lp); ef += x * x; el += lp * lp; }
    full[f] = Math.sqrt(ef / hop); low[f] = Math.sqrt(el / hop);
    if (f % 4000 === 3999) await sleep(0); // keep the page responsive
  }
  return analyseEnvelope(full, low, sr / hop, buf.length / sr);
}

// full/low: loudness per frame (whole signal / bass only). fps: frames per second.
function analyseEnvelope(full, low, fps, dur) {
  const frames = full.length;
  const on = new Float32Array(frames); // onset strength: how sharply the sound gets louder
  for (let f = 1; f < frames; f++) on[f] = 2 * Math.max(0, Math.sqrt(low[f]) - Math.sqrt(low[f - 1])) + Math.max(0, Math.sqrt(full[f]) - Math.sqrt(full[f - 1]));

  // loudness per second, to find where the music really starts and where it winds down
  const secs = Math.max(1, Math.floor(frames / fps)), blk = new Float32Array(secs);
  for (let s = 0; s < secs; s++) { let e = 0; const a0 = Math.floor(s * fps), a1 = Math.min(frames, Math.floor((s + 1) * fps)); for (let f = a0; f < a1; f++) e += full[f]; blk[s] = e / Math.max(1, a1 - a0); }
  const ref = [...blk].sort((x, y) => x - y)[Math.floor(secs * 0.7)] || 0;
  let firstLoud = 0, lastLoud = dur;
  for (let s = 0; s < secs; s++) if (blk[s] > 0.2 * ref) { firstLoud = s; break; }
  for (let s = secs - 1; s >= 0; s--) if (blk[s] > 0.5 * ref) { lastLoud = Math.min(dur, s + 1); break; }

  let peak = 0; for (let f = 0; f < frames; f++) if (full[f] > peak) peak = full[f];
  const N = 240, peaks = [];
  for (let i = 0; i < N; i++) { let m = 0; const a0 = Math.floor(i * frames / N), a1 = Math.floor((i + 1) * frames / N); for (let f = a0; f < a1; f++) if (full[f] > m) m = full[f]; peaks.push(peak ? +(m / peak).toFixed(2) : 0); }

  // coarse tempo: which beat spacing makes the onsets line up with themselves best
  let best = 0, bestL = 0;
  for (let L = Math.floor(fps * 60 / 180); L <= Math.ceil(fps * 60 / 70); L++) {
    let s = 0, s2 = 0;
    for (let f = L; f < frames; f++) s += on[f] * on[f - L];
    for (let f = 2 * L; f < frames; f++) s2 += on[f] * on[f - 2 * L];
    const w = Math.exp(-0.5 * Math.pow(Math.log2(60 * fps / L / 122) / 0.7, 2)); // prefer typical dance tempos over half/double
    if ((s + 0.5 * s2) * w > best) { best = (s + 0.5 * s2) * w; bestL = L; }
  }
  if (!bestL) return { bpm: 0, beat0: 0, start: firstLoud, out: Math.max(firstLoud, dur - 6), dur, peaks };

  // fine tempo and beat position: slide a comb of evenly spaced beats over the onsets
  const at = p => { const i = Math.floor(p); return i + 1 < frames ? on[i] + (on[i + 1] - on[i]) * (p - i) : 0; };
  const comb = (lo, hi, step, phStep) => {
    let r = { s: -1 };
    for (let bpm = lo; bpm <= hi; bpm += step) {
      const per = 60 * fps / bpm;
      for (let ph = 0; ph < per; ph += phStep) { let s = 0; for (let p = ph; p < frames - 1; p += per) s += at(p); if (s > r.s) r = { s, bpm, ph }; }
    }
    return r;
  };
  const c = 60 * fps / bestL;
  let r = comb(c * 0.97, c * 1.03, 0.1, 1);
  r = comb(r.bpm - 0.1, r.bpm + 0.1, 0.01, 0.5);
  const bpm = +r.bpm.toFixed(2), beat = 60 / bpm, per = 60 * fps / bpm;

  // which of every four beats is the "one": the one carrying the most bass
  const sums = [0, 0, 0, 0]; let k = 0;
  for (let p = r.ph; p < frames - 4; p += per, k++) sums[k % 4] += low[Math.round(p) + 3];
  const bar = 4 * beat, bar0 = r.ph / fps + sums.indexOf(Math.max(...sums)) * beat;

  const T = 16 * beat; // length of a beat-matched blend
  let start = bar0 + Math.ceil((firstLoud - bar0) / bar) * bar; if (start < 0) start += bar;
  let out = bar0 + Math.floor((Math.min(lastLoud - T / 2, dur - T - 1) - bar0) / bar) * bar;
  if (out < start + 2 * bar) out = Math.max(start, dur - Math.min(T, dur / 4) - 1);
  return { bpm, beat0: +(r.ph / fps).toFixed(3), start: +start.toFixed(3), out: +out.toFixed(3), dur: +dur.toFixed(2), peaks };
}

const analysisQ = []; let analysing = false;
function queueAnalysis(t, first) {
  if (t.kind !== 'file' || t.analysed || t.failed) return;
  const i = analysisQ.indexOf(t); if (i >= 0) analysisQ.splice(i, 1);
  first ? analysisQ.unshift(t) : analysisQ.push(t);
  pumpAnalysis();
}
async function pumpAnalysis() {
  if (analysing) return; analysing = true;
  while (analysisQ.length) {
    const t = analysisQ.shift();
    try { Object.assign(t, await analyseFile(t.file)); t.analysed = true; saveTrack(t); }
    catch { t.failed = true; }
    renderList(); decks.forEach(d => d.track === t && d.onAnalysed());
    await sleep(30);
  }
  analysing = false;
}
function saveTrack(t) {
  if (t.kind === 'file') store.set('an.' + t.id, { bpm: t.bpm, beat0: t.beat0, start: t.start, out: t.out, dur: t.dur, peaks: t.peaks });
  else saveLibrary();
}

/* ---------- decks ---------- */
const COLORS = { A: '#4cc9f0', B: '#f72585', C: '#ffb703', D: '#52e39a' };
const ERRORS = { 2: 'Invalid video link', 5: 'This video cannot be played here', 100: 'Video not found or private', 101: 'The owner has disabled playback in other apps', 150: 'The owner has disabled playback in other apps' };

class Deck {
  constructor(id) {
    this.id = id; this.side = (id === 'A' || id === 'C') ? 'L' : 'R';
    this.fader = 1; this.fade = 1; this.muted = false; this.xf = true;
    this.cue = 0; this.hot = [null, null, null, null]; this.loop = null;
    this.baseBpm = 0; this.taps = []; this.rate = 1;
    this.kind = 'yt'; this.yt = null; this.ytReady = false; this.local = null; this.pending = null; this.track = null;
    this.eq = { low: 0, mid: 0, high: 0 };
    this.seeking = false; this.lastVol = -1; this.lastCue = -1; this.pstate = -1;
    this.build();
  }
  get player() { return this.kind === 'file' ? this.local : this.yt; }
  get ready() { return this.kind === 'file' ? !!this.local : this.ytReady; }

  build() {
    const el = this.el = document.createElement('section');
    el.className = 'deck'; el.style.setProperty('--c', COLORS[this.id]);
    el.innerHTML = `
      <header><span class="badge">${NUM[this.id]}</span><div class="title">Empty</div><span class="role"></span></header>
      <div class="status"></div>
      <div class="deckmain">
      <div class="screen"><div class="ytwrap"><div id="player-${this.id}"></div></div><canvas class="wave" hidden></canvas>
        <div class="placeholder">Pick a track from the list below</div></div>
      <div class="ctl">
      <div class="row"><span class="time el">0:00</span><input type="range" class="seek" min="0" max="1000" value="0" aria-label="Seek"><span class="time r rem">-0:00</span></div>
      <div class="transport"><button data-act="back10" title="Back 10 seconds" aria-label="Back 10 seconds">« 10</button><button class="play big" data-act="play">▶ Play</button><button data-act="fwd10" title="Forward 10 seconds" aria-label="Forward 10 seconds">10 »</button></div>
      <details class="more"><summary>More controls</summary><div class="morebody">
      <form class="row" data-form="load"><input type="text" placeholder="Search YouTube or paste a link" aria-label="Load player ${NUM[this.id]}"><button>Load</button></form>
      <div class="grid">
        <button data-act="cue" title="Jump to the start point and pause">Cue</button><button data-act="back">−5s</button><button data-act="fwd">+5s</button>
      </div>
      <div class="row"><span class="lbl">Volume</span><input type="range" class="vol" min="0" max="100" value="100" aria-label="Player volume"></div>
      <div class="meter" title="Output level of this player"><i></i></div>
      <div class="row"><span class="lbl">Auto mix</span><span class="points">No mix points saved</span></div>
      <div class="grid">
        <button data-act="setcue" title="Auto DJ starts this track here">Set start</button>
        <button data-act="setout" title="Auto DJ starts leaving this track here">Set mix-out</button>
        <button data-act="clearpts">Clear</button>
      </div>
      <div class="row"><span class="lbl">Hot cue</span><div class="grid" style="flex:1">
        ${[0, 1, 2, 3].map(i => `<button data-hot="${i}" title="Tap to set or jump. Long-press to clear.">${i + 1}</button>`).join('')}</div></div>
      <div class="row"><span class="lbl">Loop</span><div class="grid" style="flex:1">
        <button data-act="loopin">In</button><button data-act="loopout">Out</button><button data-act="loopexit">Exit</button>
        <button data-beats="4" title="Loop 4 beats (needs BPM)">4</button><button data-beats="8">8</button><button data-beats="16">16</button></div></div>
      <div class="row"><span class="lbl">Tempo</span><input type="range" class="tempo" min="50" max="150" value="100" aria-label="Tempo"><span class="read rate">1.00×</span><button data-act="ratereset">1×</button></div>
      <div class="row"><span class="lbl">BPM</span><span class="read bpm" style="text-align:left">—</span><span style="flex:1"></span>
        <button data-act="tap" title="Tap along with the beat">Tap</button><button data-act="sync" title="Match the other deck's BPM">Sync</button></div>
      <div class="row eqrow"><span class="lbl">EQ</span>
        ${['low', 'mid', 'high'].map(b => `<label class="eqband">${b}<input type="range" class="eq" data-band="${b}" min="-26" max="6" value="0" aria-label="${b} EQ"></label>`).join('')}</div>
      <div class="grid">
        <button data-act="mute">Mute</button><button data-act="fadeout">Fade out</button>
        <button data-act="xf" class="on" title="Route this player through the mix slider">X-fader</button>
      </div></div></details>
      </div></div>`;
    $('#slot-' + this.id).append(el);

    el.addEventListener('click', e => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.act) this.act(b.dataset.act);
      else if (b.dataset.hot != null) this.hotCue(+b.dataset.hot);
      else if (b.dataset.beats) this.beatLoop(+b.dataset.beats);
    });
    el.addEventListener('submit', e => {
      e.preventDefault();
      const inp = $('input', e.target), id = parseVideoId(inp.value);
      const q = inp.value.trim(); if (!q) return;
      inp.value = '';
      if (id) return this.load(addToLibrary({ id }), false);
      search(q); $('.library').scrollIntoView({ behavior: 'smooth', block: 'start' }); // words, not a link: search instead
    });
    // long-press / right-click clears a hot cue
    for (const b of $$('[data-hot]', el)) {
      let h;
      b.addEventListener('pointerdown', () => { h = setTimeout(() => { this.clearHot(+b.dataset.hot); b.dataset.skip = '1'; }, 600); });
      for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) b.addEventListener(ev, () => clearTimeout(h));
      b.addEventListener('contextmenu', e => { e.preventDefault(); this.clearHot(+b.dataset.hot); });
    }
    const seek = $('.seek', el);
    seek.addEventListener('input', () => { this.seeking = true; $('.el', el).textContent = fmt(seek.value / 1000 * this.dur()); });
    seek.addEventListener('change', () => { this.seek(seek.value / 1000 * this.dur()); this.seeking = false; });
    $('.tempo', el).addEventListener('input', e => this.setRate(e.target.value / 100));
    $('.vol', el).addEventListener('input', e => { this.fader = e.target.value / 100; this.applyVolume(); });
    for (const s of $$('.eq', el)) s.addEventListener('input', () => { this.eq[s.dataset.band] = +s.value; if (this.local) this.local.setEq(s.dataset.band, +s.value); });
    $('.wave', el).addEventListener('click', e => { const r = e.target.getBoundingClientRect(); this.seek((e.clientX - r.left) / r.width * this.dur()); });
  }

  /* --- player plumbing --- */
  load(track, autoplay) {
    const isFile = track.kind === 'file';
    if (!isFile && !state.apiReady) return toast('The YouTube player has not loaded yet. Check your connection.');
    if (this.ready) this.pause(); // stop whatever this deck was playing, including the other kind of player
    cancelAnim('rate' + this.id);
    this.track = track; this.cue = track.start || 0; this.hot = [null, null, null, null]; this.loop = null;
    this.baseBpm = track.bpm || 0; this.taps = []; this.pstate = -1; this.lastVol = -1; this.lastCue = -1;
    this.kind = isFile ? 'file' : 'yt';
    this.setStatus(''); this.renderTitle(); this.renderPads(); this.renderPoints();
    if (isFile && out.hp && !out.ready && !out.tried) { out.tried = true; applyOutputs(); } // reconnect the saved headphones
    $('.placeholder', this.el)?.remove();
    $('.ytwrap', this.el).hidden = isFile; $('.wave', this.el).hidden = !isFile; this.el.classList.toggle('isfile', isFile);

    if (isFile) {
      if (!this.local) this.local = new LocalPlayer(s => { if (this.kind === 'file') this.onState(s); });
      for (const b of ['low', 'mid', 'high']) this.local.setEq(b, this.eq[b]);
      this.local.load(track.file, this.cue, autoplay);
      this.setRate(1); this.applyVolume(); queueAnalysis(track, true); syncLive();
      return;
    }
    const arg = { videoId: track.id, startSeconds: this.cue };
    if (!this.yt) {
      const vars = { playsinline: 1, rel: 0, fs: 0, autoplay: autoplay ? 1 : 0 };
      if (this.cue >= 1) vars.start = Math.floor(this.cue);
      if (location.protocol.startsWith('http')) vars.origin = location.origin;
      this.yt = new YT.Player('player-' + this.id, {
        width: '100%', height: '100%', videoId: track.id, playerVars: vars,
        events: {
          onReady: () => {
            this.ytReady = true; this.lastVol = -1; this.applyVolume();
            if (this.pending) { const p = this.pending; this.pending = null; this.cueOrLoad(p.arg, p.autoplay); }
          },
          onStateChange: e => { if (this.kind === 'yt') this.onState(e.data); },
          onError: e => { if (this.kind === 'yt') this.onError(e.data); }
        }
      });
    } else if (!this.ytReady) this.pending = { arg, autoplay };
    else this.cueOrLoad(arg, autoplay);
    syncLive();
  }
  cueOrLoad(arg, autoplay) {
    this.setRate(1);
    if (autoplay) this.yt.loadVideoById(arg); else this.yt.cueVideoById(arg);
  }
  onState(s) {
    if (s === -2) return this.onError('file');
    this.pstate = s;
    if (s === 1 && (this === deck.A || this === deck.B) && !state.transitioning && !anims.has('xf')) {
      if (!otherDeck(this).isPlaying()) { // the only thing playing: it gets the speaker
        if ((this.side === 'L' ? state.x : 1 - state.x) > 0.001) { setX(this.side === 'L' ? 0 : 1); syncLive(); }
      } else if (this.id !== state.live && !this.inHeadphones() && mixPos() < 0.1) setMix(0.1); // joins quietly at 10% unless it is in the headphones
    }
    $('.play', this.el).textContent = s === 1 || s === 3 ? '❚❚ Pause' : '▶ Play';
    $('.play', this.el).classList.toggle('on', s === 1);
    if (this.kind === 'yt' && (s === 1 || s === 5)) {
      const d = this.yt.getVideoData?.();
      if (d && d.title && this.track && d.video_id === this.track.id && this.track.title !== d.title) {
        this.track.title = d.title; this.track.channel = d.author || this.track.channel;
        saveLibrary(); this.renderTitle(); renderList();
      }
    }
    if (s === 0 && state.autoDJ && !state.transitioning && (this === deck.A || this === deck.B)) trackEnded(this);
  }
  onError(code) {
    this.setStatus(code === 'file' ? 'This file cannot be played' : (ERRORS[code] || 'Playback error'), true);
    this.pstate = -1;
    if (state.autoDJ && !state.transitioning && (this === deck.A || this === deck.B)) trackEnded(this); // skip unplayable tracks
  }
  onAnalysed() {
    const t = this.track;
    if (!this.baseBpm) this.baseBpm = t.bpm || 0;
    if (!this.cue && t.start && !this.isPlaying()) { this.cue = t.start; this.seek(t.start); }
    this.renderPoints();
  }
  isPlaying() { return this.ready && (this.pstate === 1 || this.pstate === 3); }
  time() { return this.ready ? (this.player.getCurrentTime() || 0) : 0; }
  dur() { return this.ready ? (this.player.getDuration() || 0) : 0; }
  play() { if (this.ready && this.track) this.player.playVideo(); }
  pause() { if (this.ready) this.player.pauseVideo(); }
  seek(t) { if (this.ready) this.player.seekTo(Math.max(0, t), true); }
  setRate(r) {
    this.rate = clamp(r, 0.5, 1.5);
    if (this.ready) this.player.setPlaybackRate(this.rate);
    $('.tempo', this.el).value = Math.round(this.rate * 100);
  }
  level() { return this.fader * this.fade * (this.muted ? 0 : 1) * (this.xf ? xfGain(this.side) : 1); }
  applyVolume() {
    const g = this.level() * state.master;
    $('.meter i', this.el).style.width = (this.isPlaying() ? g * 100 : 0) + '%';
    if (!this.ready) return;
    if (this.kind === 'file') {
      if (g !== this.lastVol) { this.lastVol = g; this.local.setGain(g); }
      const cg = this.inHeadphones() ? out.hpVol : 0; // the next track is heard in the headphones whatever the mix slider says
      if (cg !== this.lastCue) { this.lastCue = cg; this.local.setCue(cg); }
      return;
    }
    const v = Math.round(g * 100);
    if (v !== this.lastVol) { this.lastVol = v; this.yt.setVolume(v); if (v > 0 && this.yt.isMuted?.()) this.yt.unMute(); }
  }

  inHeadphones() { return out.ready && this.kind === 'file' && this.id !== state.live && (this === deck.A || this === deck.B); }
  renderRole() {
    const r = $('.role', this.el), main = this === deck.A || this === deck.B;
    r.textContent = !this.track || !main ? '' : this.id === state.live ? 'On speaker' : this.inHeadphones() ? 'In headphones' : 'Next';
    r.classList.toggle('live', this.id === state.live);
  }

  /* --- controls --- */
  act(a) {
    if (a !== 'tap' && a !== 'xf' && a !== 'mute' && !this.track) return toast('Load a track on Player ' + NUM[this.id] + ' first');
    const t = this.track;
    switch (a) {
      case 'play':
        if (this.isPlaying()) { this.pause(); break; }
        if ((this === deck.A || this === deck.B) && !otherDeck(this).isPlaying()) { cancelAnim('xf'); setX(this.side === 'L' ? 0 : 1); syncLive(); } // nothing else is playing: straight to the speaker
        this.play(); break;
      case 'cue': this.seek(this.cue); this.pause(); break;
      case 'setcue': this.cue = t.start = +this.time().toFixed(2); saveTrack(t); this.renderPoints(); toast(`Start point saved at ${fmt(this.cue)}`); break;
      case 'setout':
        if (this.time() <= (t.start || 0) + 5) return toast('The mix-out point must be later than the start point');
        t.out = +this.time().toFixed(2); saveTrack(t); this.renderPoints(); toast(`Mix-out point saved at ${fmt(t.out)}`); break;
      case 'clearpts': delete t.start; delete t.out; this.cue = 0; saveTrack(t); this.renderPoints(); break;
      case 'back': this.seek(this.time() - 5); break;
      case 'back10': this.seek(this.time() - 10); break;
      case 'fwd10': this.seek(this.time() + 10); break;
      case 'fwd': this.seek(this.time() + 5); break;
      case 'loopin': this.loop = { in: this.time(), out: null, on: false }; this.renderPads(); break;
      case 'loopout':
        if (!this.loop) return toast('Set the loop In point first');
        if (this.time() - this.loop.in < 0.3) return toast('Loop is too short');
        this.loop.out = this.time(); this.loop.on = true; this.seek(this.loop.in); this.renderPads(); break;
      case 'loopexit': this.loop = null; this.renderPads(); break;
      case 'ratereset': this.setRate(1); break;
      case 'tap': this.tap(); break;
      case 'sync': this.sync(); break;
      case 'mute': this.muted = !this.muted; $('[data-act=mute]', this.el).classList.toggle('on', this.muted); this.applyVolume(); break;
      case 'xf': this.xf = !this.xf; $('[data-act=xf]', this.el).classList.toggle('on', this.xf); this.applyVolume(); break;
      case 'fadeout': {
        const to = this.fade > 0.5 ? 0 : 1;
        $('[data-act=fadeout]', this.el).textContent = to ? 'Fade out' : 'Fade in';
        animate('fade' + this.id, this.fade, to, 3000, v => { this.fade = v; this.applyVolume(); }); break;
      }
    }
  }
  hotCue(i) {
    const b = $(`[data-hot="${i}"]`, this.el);
    if (b.dataset.skip) { delete b.dataset.skip; return; }
    if (!this.track) return toast('Load a track on Player ' + NUM[this.id] + ' first');
    if (this.hot[i] == null) this.hot[i] = this.time(); else { this.seek(this.hot[i]); this.play(); }
    this.renderPads();
  }
  clearHot(i) { this.hot[i] = null; this.renderPads(); }
  beatLoop(beats) {
    if (!this.track) return toast('Load a track on Player ' + NUM[this.id] + ' first');
    if (!this.baseBpm) return toast('Tap the BPM first so the loop length is known');
    const t = this.time();
    this.loop = { in: t, out: t + beats * 60 / this.baseBpm, on: true, beats }; this.renderPads();
  }
  tap() {
    const now = performance.now();
    if (this.taps.length && now - this.taps[this.taps.length - 1] > 2000) this.taps = [];
    this.taps.push(now); if (this.taps.length > 12) this.taps.shift();
    if (this.taps.length >= 3) {
      const avg = (this.taps[this.taps.length - 1] - this.taps[0]) / (this.taps.length - 1);
      this.baseBpm = 60000 / avg / (this.rate || 1); // BPM of the track at normal speed
      if (this.track) { this.track.bpm = +this.baseBpm.toFixed(2); saveTrack(this.track); this.renderPoints(); }
    }
  }
  effBpm() { return this.baseBpm * this.rate; }
  sync() {
    if (!this.baseBpm) return toast('Tap the BPM on Player ' + NUM[this.id] + ' first');
    const src = activeDecks().filter(d => d !== this && d.baseBpm).sort((a, b) => (b.isPlaying() - a.isPlaying()) || (b.level() - a.level()))[0];
    if (!src) return toast('The other deck has no BPM yet');
    const r = tempoRatio(src.effBpm(), this.baseBpm);
    if (r < 0.5 || r > 1.5) return toast('Tempos are too far apart to sync');
    this.setRate(r);
  }

  /* --- rendering --- */
  setStatus(msg, err) { const s = $('.status', this.el); s.textContent = msg; s.classList.toggle('err', !!err); }
  renderTitle() { const t = $('.title', this.el); t.textContent = this.track ? (this.track.title || this.track.id) : 'Empty'; t.title = t.textContent; }
  renderPoints() {
    const t = this.track || {}, parts = [];
    if (t.start) parts.push('Start ' + fmt(t.start));
    if (t.out) parts.push('Mix-out ' + fmt(t.out));
    if (t.bpm) parts.push(t.bpm.toFixed(1) + ' BPM');
    $('.points', this.el).textContent = parts.join(' · ') || (t.kind === 'file' && !t.analysed && !t.failed ? 'Analysing beats…' : 'No mix points saved');
  }
  renderPads() {
    this.hot.forEach((h, i) => { const b = $(`[data-hot="${i}"]`, this.el); b.classList.toggle('on', h != null); b.textContent = h != null ? fmt(h) : i + 1; });
    $('[data-act=loopin]', this.el).classList.toggle('on', !!this.loop);
    $('[data-act=loopout]', this.el).classList.toggle('on', !!(this.loop && this.loop.on));
    for (const b of $$('[data-beats]', this.el)) b.classList.toggle('on', !!(this.loop && this.loop.on && this.loop.beats === +b.dataset.beats));
  }
  drawWave(t, d) {
    const c = $('.wave', this.el), w = c.clientWidth, h = c.clientHeight; if (!w) return;
    const dpr = window.devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
    const g = c.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, w, h);
    const pk = this.track.peaks; d = d || this.track.dur || 0;
    if (!pk) { g.fillStyle = '#8d95a8'; g.font = '14px system-ui'; g.textAlign = 'center'; g.fillText(this.track.failed ? 'Could not analyse this file' : 'Analysing beats…', w / 2, h / 2); return; }
    const bw = w / pk.length, pos = d ? t / d : 0;
    for (let i = 0; i < pk.length; i++) {
      const hh = Math.max(2, pk[i] * h * 0.8);
      g.fillStyle = i / pk.length < pos ? COLORS[this.id] : '#3a4256';
      g.fillRect(i * bw, (h - hh) / 2, Math.max(1, bw - 1), hh);
    }
    const mark = (time, col) => { if (time && d) { g.fillStyle = col; g.fillRect(time / d * w - 1, 0, 2, h); } };
    mark(this.track.start, '#52e39a'); mark(this.track.out, '#ff7b7b');
    g.fillStyle = '#fff'; g.fillRect(pos * w - 1, 0, 2, h);
  }
  tick() {
    if (!this.ready) return;
    const t = this.time(), d = this.dur();
    if (this.loop && this.loop.on && t >= this.loop.out) this.seek(this.loop.in);
    if (!this.seeking) {
      $('.el', this.el).textContent = fmt(t); $('.rem', this.el).textContent = '-' + fmt(d - t);
      $('.seek', this.el).value = d ? Math.round(t / d * 1000) : 0;
    }
    const actual = this.player.getPlaybackRate?.() || this.rate; // show what the player really applied
    $('.rate', this.el).textContent = actual.toFixed(2) + '×';
    $('.bpm', this.el).textContent = this.baseBpm ? (this.baseBpm * actual).toFixed(1) : '—';
    if (this.kind === 'file' && this.track) this.drawWave(t, d);
    this.applyVolume();
  }
}

// speed change that brings `bpm` to `target`, allowing half/double time, whichever is the smallest change
function tempoRatio(target, bpm) { return [0.5, 1, 2].map(m => target * m / bpm).sort((a, b) => Math.abs(a - 1) - Math.abs(b - 1))[0]; }

const decks = ['A', 'B', 'C', 'D'].map(id => new Deck(id));
const deck = Object.fromEntries(decks.map(d => [d.id, d]));
const activeDecks = () => state.four ? decks : [deck.A, deck.B];

/* ---------- manual timed crossfade ---------- */
// Started by a tap, so bringing in the second deck here is user-initiated playback.
async function autoFade() {
  const to = state.x < 0.5 ? 1 : 0, inSide = to ? 'R' : 'L';
  const incoming = activeDecks().filter(d => d.side === inSide && d.xf && d.track);
  if (!incoming.length) return toast('Load a track on the other deck first');
  incoming.filter(d => !d.isPlaying()).forEach(d => d.play());
  const done = await animate('xf', state.x, to, state.fadeSec * 1000, setX);
  if (done && state.stopOut) activeDecks().filter(d => d.side !== inSide && d.xf).forEach(d => d.pause());
  syncLive();
}

/* ---------- Auto DJ: plays the queue on decks A/B ----------
   Two local files that have both been analysed get a beat-matched, overlapping blend.
   Anything involving a YouTube track gets a fade-out then fade-in, so two YouTube
   players never auto-play at the same time. Saved start / mix-out points are used for both. */
const otherDeck = d => d === deck.A ? deck.B : deck.A;
function liveDeck() { return [deck.A, deck.B].filter(d => d.isPlaying()).sort((a, b) => b.level() - a.level())[0] || null; }
function setAutoDJ(on, why) {
  state.autoDJ = on;
  const b = $('#btn-autodj'); b.textContent = 'Auto DJ: ' + (on ? 'On' : 'Off'); b.classList.toggle('on', on);
  if (why) toast(why);
}
function takeNext() { const n = state.queue.shift(); saveQueue(); renderList(); return n; }
function startNextOn(d) {
  const next = takeNext();
  if (!next) return setAutoDJ(false, 'Queue finished');
  d.fade = 1; setX(d.side === 'L' ? 0 : 1); syncLive(); d.load(next, true);
}
function toggleAutoDJ() {
  if (state.autoDJ) return setAutoDJ(false);
  if (!liveDeck() && !state.queue.length) return toast('Add tracks to the queue first');
  setAutoDJ(true);
  if (!liveDeck()) startNextOn(state.x < 0.5 ? deck.A : deck.B);
}
function trackEnded(d) { // a track ran out (or failed) with no transition in progress
  if (otherDeck(d).isPlaying()) return;
  if (!state.queue.length) return setAutoDJ(false, 'Queue finished');
  state.transitioning = true; seqMix(d, otherDeck(d));
}
const canBeatMix = (live, next) => live.kind === 'file' && next.kind === 'file' && live.track.analysed && next.analysed && live.track.bpm > 0 && next.bpm > 0;

function autoDJTick() {
  if (!state.autoDJ || state.transitioning) return;
  const live = liveDeck(); if (!live || !(live.dur() > 0)) return;
  const other = otherDeck(live), next = state.queue[0], lt = live.track, dur = live.dur();
  const end = lt.out && lt.out < dur ? lt.out : dur;
  const left = (end - live.time()) / (live.rate || 1); // real seconds until the mix point
  if (!next) return;
  if (left <= 30 && !other.isPlaying() && other.track !== next) other.load(next, false); // cue the next track early
  const beat = canBeatMix(live, next);
  const lead = beat || end < dur ? 0 : state.fadeSec / 2; // with no saved mix-out point, start fading before the very end
  if (left - lead <= 0.25) {
    state.transitioning = true;
    setTimeout(() => (beat && other.track === next ? beatMix : seqMix)(live, other), Math.max(0, left - lead) * 1000);
  }
}

// fade the old track out completely, then start the next one and fade it in
async function seqMix(live, other) {
  const half = state.fadeSec * 500;
  cancelAnim('xf');
  if (live.isPlaying()) { await animate('fade' + live.id, live.fade, 0, half, v => { live.fade = v; live.applyVolume(); }); live.pause(); }
  live.fade = 1;
  const next = takeNext();
  if (!next) { state.transitioning = false; return setAutoDJ(false, 'Queue finished'); }
  other.fade = 0; setX(other.side === 'L' ? 0 : 1); syncLive();
  if (other.track === next && other.ready && !other.isPlaying()) other.play(); else other.load(next, true);
  await animate('fade' + other.id, 0, 1, half, v => { other.fade = v; other.applyVolume(); });
  state.transitioning = false;
}

// local files only: start the next track on the beat, hold the two in time, swap the bass half way, blend across
async function beatMix(live, other) {
  const next = takeNext(), lt = live.track;
  const liveBpm = lt.bpm * live.rate;
  let r = tempoRatio(liveBpm, next.bpm);
  const match = r >= 0.92 && r <= 1.08; if (!match) r = 1; // too far apart: quick blend at normal speed instead
  let T = (match ? 16 : 4) * 60 / liveBpm;
  T = Math.max(1, Math.min(T, (live.dur() - live.time()) / live.rate - 0.2));
  const refIn = next.start || 0, now = live.time();
  const ref = lt.out && Math.abs(now - lt.out) < 0.5 ? lt.out : now; // the beat in the old track that the new one lines up with
  other.fade = 1; other.setRate(r); other.seek(refIn); other.local.setEq('low', -26); other.play();
  cancelAnim('xf');
  const t0 = performance.now(); let swapped = false;
  const hold = setInterval(() => {
    if (match && live.isPlaying() && other.isPlaying()) { // nudge the new track's speed to keep its beats on the old track's beats
      const err = other.time() - (refIn + (live.time() - ref) / live.rate * r);
      other.local.setPlaybackRate(r * (1 - clamp(err * 0.6, -0.04, 0.04)));
    }
    if (!swapped && performance.now() - t0 >= T * 500) { swapped = true; live.local.setEq('low', -26); other.local.setEq('low', other.eq.low); }
  }, 40);
  await animate('xf', state.x, other.side === 'L' ? 0 : 1, T * 1000, setX);
  clearInterval(hold);
  live.pause(); live.fade = 1; live.local.setEq('low', live.eq.low); other.local.setEq('low', other.eq.low);
  other.setRate(r);
  if (r !== 1) animate('rate' + other.id, r, 1, 10000, v => other.setRate(v)); // drift back to the track's own tempo
  syncLive();
  state.transitioning = false;
}

/* ---------- library / queue / search ---------- */
function saveLibrary() { store.set('library', state.library); }
function saveQueue() { store.set('queue', state.queue.filter(t => t.kind !== 'file')); } // files cannot be reopened after a reload
function addToLibrary(t) {
  let ex = state.library.find(x => x.id === t.id);
  if (ex) { if (t.title && !ex.title) Object.assign(ex, t); }
  else { ex = { id: t.id, title: t.title || '', channel: t.channel || '' }; state.library.unshift(ex); if (!ex.title) fetchTitle(ex); }
  saveLibrary(); renderList(); return ex;
}
async function fetchTitle(t) { // best effort; the deck fills the title in on load if this fails
  try {
    const r = await fetch('https://www.youtube.com/oembed?format=json&url=' + encodeURIComponent('https://www.youtube.com/watch?v=' + t.id));
    if (!r.ok) return; const j = await r.json();
    t.title = j.title || t.title; t.channel = j.author_name || t.channel; saveLibrary(); renderList();
    decks.forEach(d => d.track === t && d.renderTitle());
  } catch { /* offline or blocked */ }
}
async function search(q) {
  const id = parseVideoId(q);
  if (id) { addToLibrary({ id }); setTab('library'); return toast('Added to library'); }
  if (!apiKey()) { setTab('results'); state.pendingSearch = q; return openSettings(); } // no key yet: show the one-time setup
  try {
    state.results = await ytList('search?part=snippet&type=video&videoEmbeddable=true&maxResults=25&q=' + encodeURIComponent(q));
    state.resultsTitle = 'Results for “' + q + '”'; setTab('results');
  } catch (e) { toast('Search failed: ' + e.message); }
}
async function ytList(path) {
  const r = await fetch('https://www.googleapis.com/youtube/v3/' + path + '&key=' + encodeURIComponent(apiKey())), j = await r.json();
  if (!r.ok) throw new Error(j.error?.message || 'request failed');
  const dec = s => { const e = document.createElement('textarea'); e.innerHTML = s; return e.value; };
  return (j.items || []).map(i => ({ id: i.id?.videoId || i.id, title: dec(i.snippet.title), channel: dec(i.snippet.channelTitle) })).filter(t => typeof t.id === 'string');
}
// Popular music right now, shown on the YouTube tab so there is something to browse before searching.
async function trending() {
  if (!apiKey()) return;
  try {
    const region = (navigator.language || '').split('-')[1] || 'US';
    state.results = await ytList('videos?part=snippet&chart=mostPopular&videoCategoryId=10&maxResults=25&regionCode=' + encodeURIComponent(region));
    state.resultsTitle = 'Popular music right now'; if (state.tab === 'results') renderList();
  } catch (e) { if (state.tab === 'results') toast('Could not load popular music: ' + e.message); }
}

/* ---------- local music folder ---------- */
const isAudio = f => /\.(mp3|m4a|aac|wav|ogg|oga|opus|flac|weba)$/i.test(f.name) || (f.type || '').startsWith('audio/');
async function walk(dir, out, depth) {
  for await (const h of dir.values()) {
    if (h.kind === 'file') { if (isAudio(h)) out.push(await h.getFile()); }
    else if (depth < 6) await walk(h, out, depth + 1);
  }
}
async function pickFolder() {
  audio(); // this tap also unlocks sound
  if (!window.showDirectoryPicker) return $('#folder-input').click();
  try { const out = []; await walk(await window.showDirectoryPicker(), out, 0); addFiles(out); }
  catch (e) { if (e.name !== 'AbortError') $('#folder-input').click(); }
}
function addFiles(list) {
  const files = [...list].filter(isAudio).sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
  if (!files.length) return toast('No music files found there');
  let added = 0;
  for (const f of files) {
    const id = 'file:' + f.name + ':' + f.size;
    if (state.files.some(t => t.id === id)) continue;
    const t = { id, kind: 'file', file: f, title: f.name.replace(/\.[^.]+$/, ''), channel: '' };
    const saved = store.get('an.' + id, null);
    if (saved) { Object.assign(t, saved); t.analysed = true; } else queueAnalysis(t);
    state.files.push(t); added++;
  }
  setTab('files'); toast(added + ' track' + (added === 1 ? '' : 's') + ' added');
}
function mixAllFiles() {
  for (const t of state.files) if (!t.failed && !state.queue.includes(t) && !decks.some(d => d.track === t && d.isPlaying())) state.queue.push(t);
  saveQueue(); renderList();
  if (!state.autoDJ) toggleAutoDJ(); else toast('Folder added to the queue');
}

function setTab(tab) {
  state.tab = tab; $$('.tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === tab)); renderList();
  if (tab === 'results' && !state.results.length) trending();
}
const EMPTY = {
  library: 'Your library is empty. Search YouTube or paste a link above.',
  queue: 'The queue is empty. Use “+ Queue” on a track, then turn Auto DJ on.',
  files: 'Open a music folder (a pen drive works too). Every track is analysed for tempo and beats, then Auto DJ can mix them on the beat.'
};
function renderList() {
  const n = (id, a) => { $(id).textContent = a.length ? `(${a.length})` : ''; };
  n('#n-library', state.library); n('#n-queue', state.queue); n('#n-files', state.files);
  const items = state[state.tab], ul = $('#list');
  if (!items.length) {
    const msg = EMPTY[state.tab] || (apiKey() ? 'Type a song or artist above to search YouTube.' : 'To browse and search YouTube here, add a free YouTube Data API key in Settings. Until then, paste a link to add a track.');
    ul.innerHTML = `<li class="empty">${msg}</li>`; return;
  }
  let head = '';
  if (state.tab === 'results' && state.resultsTitle) head = `<li class="head">${esc(state.resultsTitle)}</li>`;
  if (state.tab === 'files') {
    const done = state.files.filter(t => t.analysed || t.failed).length;
    head = `<li class="head"><span>${done < state.files.length ? `Analysing beats: ${done} of ${state.files.length} done` : `${state.files.length} tracks ready`}</span><button data-do="mixall" class="big on">Auto-mix this folder</button></li>`;
  }
  const loadBtns = activeDecks().map(d => `<button data-do="load" data-deck="${d.id}" style="--c:${COLORS[d.id]}" title="Load on Player ${NUM[d.id]}">${NUM[d.id]}</button>`).join('');
  ul.innerHTML = head + items.map((t, i) => {
    const file = t.kind === 'file';
    const sub = file ? (t.analysed ? `${t.bpm ? t.bpm.toFixed(1) + ' BPM' : 'No steady beat found'} · ${fmt(t.dur)}` : t.failed ? 'Could not read this file' : 'Analysing…')
      : [t.channel, t.bpm ? t.bpm.toFixed(1) + ' BPM' : '', t.out ? 'mix points saved' : ''].filter(Boolean).join(' · ');
    return `<li data-i="${i}">
    ${file ? '<div class="note">♪</div>' : `<img loading="lazy" alt="" src="https://i.ytimg.com/vi/${esc(t.id)}/mqdefault.jpg">`}
    <div class="meta"><b>${esc(t.title || t.id)}</b><span>${esc(sub)}</span></div>
    <div class="acts">${loadBtns}
      ${state.tab === 'queue' ? '' : '<button data-do="queue" title="Add to queue" aria-label="Add to queue">＋</button>'}
      ${state.tab === 'results' ? '<button data-do="save" title="Save to library" aria-label="Save to library">☆</button>' : '<button data-do="del" title="Remove" aria-label="Remove">✕</button>'}
    </div></li>`;
  }).join('');
}
$('#list').addEventListener('click', e => {
  const b = e.target.closest('button[data-do]'); if (!b) return;
  if (b.dataset.do === 'mixall') return mixAllFiles();
  const i = +b.closest('li').dataset.i, t = state[state.tab][i]; if (!t) return;
  const own = () => state.tab === 'queue' || t.kind === 'file' ? t : addToLibrary(t);
  switch (b.dataset.do) {
    case 'load': {
      const d = deck[b.dataset.deck], main = d === deck.A || d === deck.B;
      if (main && !otherDeck(d).isPlaying()) { cancelAnim('xf'); setX(d.side === 'L' ? 0 : 1); syncLive(); } // nothing else playing: full volume from the start
      d.load(own(), true);
      d.el.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); break;
    }
    case 'queue': state.queue.push(own()); saveQueue(); renderList(); toast('Added to queue'); break;
    case 'save': addToLibrary(t); toast('Saved to library'); break;
    case 'del':
      state[state.tab].splice(i, 1);
      if (state.tab === 'queue') saveQueue(); else if (state.tab === 'library') saveLibrary();
      renderList(); break;
  }
});
$('#search-form').addEventListener('submit', e => { e.preventDefault(); const q = $('#search').value.trim(); if (q) { search(q); $('#search').value = ''; } });
$$('.tabs button').forEach(b => b.addEventListener('click', () => setTab(b.dataset.tab)));
$('#btn-folder').addEventListener('click', pickFolder);
$('#btn-files').addEventListener('click', () => { audio(); $('#files-input').click(); });
for (const id of ['#folder-input', '#files-input']) $(id).addEventListener('change', e => { addFiles(e.target.files); e.target.value = ''; });

/* ---------- FX pads: synthesised in the browser, not taken from any track ---------- */
function fx(name) {
  const c = audio(), t = c.currentTime, out = c.createGain(); out.connect(c.destination);
  const env = (g, peak, dur) => { g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(peak, t + 0.02); g.gain.setValueAtTime(peak, t + dur * 0.7); g.gain.exponentialRampToValueAtTime(0.0001, t + dur); };
  const peak = 0.25 * Math.max(0.05, state.master);
  const osc = (type, f, dur) => { const o = c.createOscillator(); o.type = type; o.frequency.setValueAtTime(f, t); o.connect(out); o.start(t); o.stop(t + dur); return o; };
  if (name === 'horn') { env(out, peak, 0.9); [440, 554, 659].forEach(f => osc('sawtooth', f, 0.9)); }
  if (name === 'siren') { env(out, peak, 1.6); const o = osc('sine', 600, 1.6); for (let i = 0; i < 4; i++) o.frequency.linearRampToValueAtTime(i % 2 ? 600 : 1200, t + 0.4 * (i + 1)); }
  if (name === 'laser') { env(out, peak, 0.4); osc('square', 2400, 0.4).frequency.exponentialRampToValueAtTime(120, t + 0.4); }
  if (name === 'riser') {
    const dur = 2.5, buf = c.createBuffer(1, c.sampleRate * dur, c.sampleRate), d = buf.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
    const n = c.createBufferSource(), f = c.createBiquadFilter(); n.buffer = buf; f.type = 'bandpass'; f.Q.value = 2;
    f.frequency.setValueAtTime(200, t); f.frequency.exponentialRampToValueAtTime(8000, t + dur);
    out.gain.setValueAtTime(0.0001, t); out.gain.exponentialRampToValueAtTime(peak * 2, t + dur * 0.95); out.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    n.connect(f); f.connect(out); n.start(t);
  }
}
$$('[data-fx]').forEach(b => b.addEventListener('click', () => fx(b.dataset.fx)));

/* ---------- mixer wiring ---------- */
$('#xfader').addEventListener('input', e => { cancelAnim('xf'); setX(e.target.value / 1000); });
$('#xfader').addEventListener('change', syncLive);
$('#btn-bring').addEventListener('click', bringIn);
$('#mix').addEventListener('input', e => {
  cancelAnim('xf');
  const next = otherDeck(deck[state.live]), m = e.target.value / 1000;
  if (m > 0 && next.track && !next.isPlaying()) next.play(); // moving the slider is enough to start the next player
  setMix(m);
});
$('#mix').addEventListener('change', e => { // released at the far end: the next player takes over
  if (e.target.value < 995) return;
  const old = deck[state.live]; setMix(1);
  if (state.stopOut) old.pause();
  syncLive();
});
$('#hp-vol').value = Math.round(out.hpVol * 100);
$('#hp-vol').addEventListener('input', e => { out.hpVol = e.target.value / 100; store.set('hpVol', out.hpVol); applyVolumes(); });
$('#btn-outs').addEventListener('click', findDevices);
$('#master').addEventListener('input', e => { state.master = e.target.value / 100; $('#master-out').textContent = e.target.value; applyVolumes(); });
$('#btn-fade').addEventListener('click', autoFade);
$('#btn-cutL').addEventListener('click', () => { cancelAnim('xf'); setX(0); syncLive(); });
$('#btn-cutR').addEventListener('click', () => { cancelAnim('xf'); setX(1); syncLive(); });
$('#btn-center').addEventListener('click', () => { cancelAnim('xf'); setX(0.5); });
$('#fade-sec').value = state.fadeSec;
$('#fade-sec').addEventListener('change', e => { state.fadeSec = +e.target.value; store.set('fadeSec', state.fadeSec); });
$('#curve').value = state.curve;
$('#curve').addEventListener('change', e => { state.curve = e.target.value; store.set('curve2', state.curve); applyVolumes(); });
$('#stop-out').checked = state.stopOut;
$('#stop-out').addEventListener('change', e => { state.stopOut = e.target.checked; store.set('stopOut', state.stopOut); });
$('#btn-autodj').addEventListener('click', toggleAutoDJ);
function setFour(on) {
  state.four = on; store.set('four', on);
  $('#stage').classList.toggle('four', on); $('#btn-4deck').classList.toggle('on', on);
  if (!on) [deck.C, deck.D].forEach(d => d.pause()); // a hidden player must not keep playing
  renderList();
}
$('#btn-4deck').addEventListener('click', () => setFour(!state.four));
function openSettings() { $('#api-key').value = state.apiKey; $('#settings').showModal(); }
$('#btn-settings').addEventListener('click', openSettings);
$('#settings').addEventListener('close', () => {
  const k = $('#api-key').value.trim(), changed = k !== state.apiKey;
  state.apiKey = k; store.set('apiKey', k);
  if (!$('#out-pick').hidden) { // sound devices were listed: remember the choice and connect them
    out.main = $('#out-main').value; out.hp = $('#out-hp').value; out.mainSet = out.mainSet || !!out.main;
    store.set('outMain', out.main); store.set('outHp', out.hp); applyOutputs();
  }
  const q = state.pendingSearch; state.pendingSearch = null;
  if (k && q) search(q); // run the search that was waiting for the key
  else if (changed && k) { state.results = []; setTab('results'); }
});

document.addEventListener('keydown', e => {
  if (e.target.closest('input,select,textarea,dialog') || e.ctrlKey || e.metaKey || e.altKey) return;
  const k = e.key.toLowerCase();
  if (k === 'q') deck.A.act('play');
  else if (k === 'p') deck.B.act('play');
  else if (k === 'f') autoFade();
  else if (k === 'arrowleft') { cancelAnim('xf'); setX(state.x - 0.05); }
  else if (k === 'arrowright') { cancelAnim('xf'); setX(state.x + 0.05); }
  else return;
  e.preventDefault();
});

// No background playback for YouTube: stop its players when the app is not on screen. Local files keep playing.
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) return;
  const yt = decks.filter(d => d.kind === 'yt' && d.isPlaying());
  if (!yt.length) return;
  yt.forEach(d => d.pause());
  if (state.autoDJ) setAutoDJ(false);
});

setInterval(() => { decks.forEach(d => d.tick()); autoDJTick(); }, 100);

/* ---------- boot ---------- */
window.onYouTubeIframeAPIReady = () => { state.apiReady = true; };
(function loadApi() {
  const s = document.createElement('script'); s.src = 'https://www.youtube.com/iframe_api';
  s.onerror = () => toast('YouTube is unreachable right now. Your own music files still work.');
  document.head.append(s);
})();
setFour(state.four); setX(0); syncLive(); setTab(apiKey() ? 'results' : 'library');
if ('serviceWorker' in navigator && location.protocol.startsWith('http')) navigator.serviceWorker.register('sw.js').catch(() => {});
