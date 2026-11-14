'use strict';
/* CrossDeck — a DJ-style mixer built on the official YouTube IFrame Player API.
   Design rules that keep it inside YouTube's API policies:
   - every deck is a normal, visible embedded player (>= 200x200) with its own controls, nothing drawn over it
   - audio is never extracted, recorded or processed; mixing is done only with the player's own volume
   - playback is paused when the app is not visible (no background play)
   - a second deck only starts playing over the first when the user taps something; Auto DJ never overlaps */

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = t => { t = Math.max(0, Math.floor(t || 0)); return Math.floor(t / 60) + ':' + String(t % 60).padStart(2, '0'); };
const store = {
  get(k, d) { try { const v = localStorage.getItem('cd.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('cd.' + k, JSON.stringify(v)); } catch { /* storage unavailable */ } }
};

const state = {
  x: 0, master: 1,
  curve: store.get('curve', 'blend'),
  fadeSec: store.get('fadeSec', 8),
  stopOut: store.get('stopOut', true),
  four: store.get('four', false),
  apiKey: store.get('apiKey', ''),
  library: store.get('library', []),
  queue: store.get('queue', []),
  results: [], tab: 'library',
  autoDJ: false, transitioning: false, apiReady: false
};

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
function setX(x) { state.x = clamp(x, 0, 1); $('#xfader').value = Math.round(state.x * 1000); applyVolumes(); }
function applyVolumes() { for (const d of decks) d.applyVolume(); }

/* ---------- decks ---------- */
const COLORS = { A: '#4cc9f0', B: '#f72585', C: '#ffb703', D: '#52e39a' };
const ERRORS = { 2: 'Invalid video link', 5: 'This video cannot be played here', 100: 'Video not found or private', 101: 'The owner has disabled playback in other apps', 150: 'The owner has disabled playback in other apps' };

class Deck {
  constructor(id) {
    this.id = id; this.side = (id === 'A' || id === 'C') ? 'L' : 'R';
    this.fader = 1; this.fade = 1; this.muted = false; this.xf = true;
    this.cue = 0; this.hot = [null, null, null, null]; this.loop = null;
    this.baseBpm = 0; this.taps = []; this.rate = 1;
    this.player = null; this.ready = false; this.pending = null; this.track = null;
    this.seeking = false; this.lastVol = -1; this.pstate = -1;
    this.build();
  }
  build() {
    const el = this.el = document.createElement('section');
    el.className = 'deck'; el.style.setProperty('--c', COLORS[this.id]);
    el.innerHTML = `
      <header><span class="badge">${this.id}</span><div class="title">Empty</div><span class="status"></span></header>
      <div class="screen"><div id="player-${this.id}"></div><div class="placeholder">Paste a link below, or load a track from the library</div></div>
      <div class="meter" title="Output level of this deck"><i></i></div>
      <form class="row" data-form="load"><input type="text" placeholder="YouTube link or video ID" aria-label="Load deck ${this.id}"><button>Load</button></form>
      <div class="row"><span class="time el">0:00</span><input type="range" class="seek" min="0" max="1000" value="0" aria-label="Seek"><span class="time r rem">-0:00</span></div>
      <div class="grid">
        <button class="play" data-act="play">▶ Play</button><button data-act="cue" title="Jump to cue point and pause">Cue</button>
        <button data-act="setcue" title="Set cue point here">Set cue</button><button data-act="back">−5s</button><button data-act="fwd">+5s</button>
      </div>
      <div class="row"><span class="lbl">Hot cue</span><div class="grid" style="flex:1">
        ${[0, 1, 2, 3].map(i => `<button data-hot="${i}" title="Tap to set or jump. Long-press to clear.">${i + 1}</button>`).join('')}</div></div>
      <div class="row"><span class="lbl">Loop</span><div class="grid" style="flex:1">
        <button data-act="loopin">In</button><button data-act="loopout">Out</button><button data-act="loopexit">Exit</button>
        <button data-beats="4" title="Loop 4 beats (needs BPM)">4</button><button data-beats="8">8</button><button data-beats="16">16</button></div></div>
      <div class="row"><span class="lbl">Tempo</span><input type="range" class="tempo" min="50" max="150" value="100" aria-label="Tempo"><span class="read rate">1.00×</span><button data-act="ratereset">1×</button></div>
      <div class="row"><span class="lbl">BPM</span><span class="read bpm" style="text-align:left">—</span><span style="flex:1"></span>
        <button data-act="tap" title="Tap along with the beat">Tap</button><button data-act="sync" title="Match the other deck's BPM">Sync</button></div>
      <div class="row"><span class="lbl">Volume</span><input type="range" class="vol" min="0" max="100" value="100" aria-label="Deck volume"></div>
      <div class="grid">
        <button data-act="mute">Mute</button><button data-act="fadeout">Fade out</button>
        <button data-act="xf" class="on" title="Route this deck through the crossfader">X-fader</button>
      </div>`;
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
      if (!id) return toast('That does not look like a YouTube link or video ID');
      inp.value = ''; this.load(addToLibrary({ id }), false);
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
  }

  /* --- player plumbing --- */
  load(track, autoplay) {
    if (!state.apiReady) return toast('The YouTube player has not loaded yet. Check your connection.');
    this.track = track; this.cue = 0; this.hot = [null, null, null, null]; this.loop = null; this.baseBpm = 0; this.taps = [];
    this.setStatus(''); this.renderTitle(); this.renderPads();
    $('.placeholder', this.el)?.remove();
    if (!this.player) {
      const vars = { playsinline: 1, rel: 0, autoplay: autoplay ? 1 : 0 };
      if (location.protocol.startsWith('http')) vars.origin = location.origin;
      this.player = new YT.Player('player-' + this.id, {
        width: '100%', height: '100%', videoId: track.id, playerVars: vars,
        events: {
          onReady: () => {
            this.ready = true; this.lastVol = -1; this.applyVolume();
            if (this.pending) { const p = this.pending; this.pending = null; this.cueOrLoad(p.id, p.autoplay); }
          },
          onStateChange: e => this.onState(e.data),
          onError: e => this.onError(e.data)
        }
      });
    } else if (!this.ready) this.pending = { id: track.id, autoplay };
    else this.cueOrLoad(track.id, autoplay);
  }
  cueOrLoad(id, autoplay) {
    this.setRate(1);
    if (autoplay) this.player.loadVideoById(id); else this.player.cueVideoById(id);
  }
  onState(s) {
    this.pstate = s;
    const PS = YT.PlayerState;
    $('.play', this.el).textContent = s === PS.PLAYING || s === PS.BUFFERING ? '❚❚ Pause' : '▶ Play';
    $('.play', this.el).classList.toggle('on', s === PS.PLAYING);
    if (s === PS.PLAYING || s === PS.CUED) {
      const d = this.player.getVideoData?.();
      if (d && d.title && this.track && d.video_id === this.track.id && this.track.title !== d.title) {
        this.track.title = d.title; this.track.channel = d.author || this.track.channel;
        saveLibrary(); this.renderTitle(); renderList();
      }
    }
    if (s === PS.ENDED && state.autoDJ && !state.transitioning) autoTransition(this);
  }
  onError(code) {
    this.setStatus(ERRORS[code] || 'Playback error', true);
    if (state.autoDJ && !state.transitioning) { // skip unplayable tracks
      const other = this === deck.A ? deck.B : deck.A;
      if (!other.isPlaying()) startNextOn(this);
    }
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
    const lv = this.level();
    $('.meter i', this.el).style.width = (this.isPlaying() ? lv * state.master * 100 : 0) + '%';
    if (!this.ready) return;
    const v = Math.round(lv * state.master * 100);
    if (v !== this.lastVol) { this.lastVol = v; this.player.setVolume(v); }
  }

  /* --- controls --- */
  act(a) {
    if (a !== 'tap' && a !== 'xf' && a !== 'mute' && !this.track) return toast('Load a track on deck ' + this.id + ' first');
    switch (a) {
      case 'play': this.isPlaying() ? this.pause() : this.play(); break;
      case 'cue': this.seek(this.cue); this.pause(); break;
      case 'setcue': this.cue = this.time(); toast(`Deck ${this.id} cue set at ${fmt(this.cue)}`); break;
      case 'back': this.seek(this.time() - 5); break;
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
    if (!this.track) return toast('Load a track on deck ' + this.id + ' first');
    if (this.hot[i] == null) this.hot[i] = this.time(); else { this.seek(this.hot[i]); this.play(); }
    this.renderPads();
  }
  clearHot(i) { this.hot[i] = null; this.renderPads(); }
  beatLoop(beats) {
    if (!this.track) return toast('Load a track on deck ' + this.id + ' first');
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
    }
  }
  effBpm() { return this.baseBpm * this.rate; }
  sync() {
    if (!this.baseBpm) return toast('Tap the BPM on deck ' + this.id + ' first');
    const src = activeDecks().filter(d => d !== this && d.baseBpm).sort((a, b) => (b.isPlaying() - a.isPlaying()) || (b.level() - a.level()))[0];
    if (!src) return toast('Tap the BPM on the other deck too');
    // allow half/double time, pick whichever needs the smallest tempo change
    const r = [0.5, 1, 2].map(m => src.effBpm() * m / this.baseBpm).sort((a, b) => Math.abs(a - 1) - Math.abs(b - 1))[0];
    if (r < 0.5 || r > 1.5) return toast('Tempos are too far apart to sync');
    this.setRate(r);
  }

  /* --- rendering --- */
  setStatus(msg, err) { const s = $('.status', this.el); s.textContent = msg; s.classList.toggle('err', !!err); }
  renderTitle() { const t = $('.title', this.el); t.textContent = this.track ? (this.track.title || this.track.id) : 'Empty'; t.title = t.textContent; }
  renderPads() {
    this.hot.forEach((h, i) => { const b = $(`[data-hot="${i}"]`, this.el); b.classList.toggle('on', h != null); b.textContent = h != null ? fmt(h) : i + 1; });
    $('[data-act=loopin]', this.el).classList.toggle('on', !!this.loop);
    $('[data-act=loopout]', this.el).classList.toggle('on', !!(this.loop && this.loop.on));
    for (const b of $$('[data-beats]', this.el)) b.classList.toggle('on', !!(this.loop && this.loop.on && this.loop.beats === +b.dataset.beats));
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
    this.applyVolume();
  }
}

const decks = ['A', 'B', 'C', 'D'].map(id => new Deck(id));
const deck = Object.fromEntries(decks.map(d => [d.id, d]));
const activeDecks = () => state.four ? decks : [deck.A, deck.B];

/* ---------- crossfades ---------- */
// Started by a tap, so bringing in the second deck here is user-initiated playback.
async function autoFade() {
  const to = state.x < 0.5 ? 1 : 0, inSide = to ? 'R' : 'L';
  const incoming = activeDecks().filter(d => d.side === inSide && d.xf && d.track);
  if (!incoming.length) return toast('Load a track on the other deck first');
  incoming.filter(d => !d.isPlaying()).forEach(d => d.play());
  const done = await animate('xf', state.x, to, state.fadeSec * 1000, setX);
  if (done && state.stopOut) activeDecks().filter(d => d.side !== inSide && d.xf).forEach(d => d.pause());
}

/* ---------- Auto DJ: plays the queue on decks A/B. The outgoing track fades out fully
   before the next one starts, so two players never auto-play at the same time. ---------- */
function liveDeck() {
  return [deck.A, deck.B].filter(d => d.isPlaying()).sort((a, b) => b.level() - a.level())[0] || null;
}
function setAutoDJ(on, why) {
  state.autoDJ = on;
  const b = $('#btn-autodj'); b.textContent = 'Auto DJ: ' + (on ? 'On' : 'Off'); b.classList.toggle('on', on);
  if (why) toast(why);
}
function startNextOn(d) {
  const next = state.queue.shift(); saveQueue(); renderList();
  if (!next) return setAutoDJ(false, 'Queue finished');
  setX(d.side === 'L' ? 0 : 1);
  d.load(next, true);
  return next;
}
function toggleAutoDJ() {
  if (state.autoDJ) return setAutoDJ(false);
  if (!liveDeck() && !state.queue.length) return toast('Add tracks to the queue first (Library → Queue)');
  setAutoDJ(true);
  if (!liveDeck()) { const d = state.x < 0.5 ? deck.A : deck.B; d.fade = 1; startNextOn(d); }
}
async function autoTransition(live) {
  if (state.transitioning) return;
  if (!state.queue.length) return setAutoDJ(false, 'Queue finished');
  state.transitioning = true;
  const other = live === deck.A ? deck.B : deck.A, half = state.fadeSec * 500;
  cancelAnim('xf');
  await animate('fade' + live.id, live.fade, 0, half, v => { live.fade = v; live.applyVolume(); });
  live.pause(); live.fade = 1;
  other.fade = 0;
  const next = state.queue[0];
  if (other.track && other.track.id === next.id && other.ready && !other.isPlaying()) { // already cued
    state.queue.shift(); saveQueue(); renderList();
    setX(other.side === 'L' ? 0 : 1); other.play();
  } else startNextOn(other);
  await animate('fade' + other.id, 0, 1, half, v => { other.fade = v; other.applyVolume(); });
  state.transitioning = false;
}
function autoDJTick() {
  if (!state.autoDJ || state.transitioning) return;
  const live = liveDeck(); if (!live) return;
  const left = live.dur() - live.time();
  if (!(live.dur() > 0)) return;
  if (left <= state.fadeSec / 2 + 0.3) autoTransition(live);
  else if (left <= 30 && state.queue.length) { // cue the next track early so it starts instantly
    const other = live === deck.A ? deck.B : deck.A;
    if (!other.isPlaying() && (!other.track || other.track.id !== state.queue[0].id)) other.load(state.queue[0], false);
  }
}

/* ---------- library / queue / search ---------- */
function saveLibrary() { store.set('library', state.library); }
function saveQueue() { store.set('queue', state.queue); }
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
  if (!state.apiKey) return toast('Paste a YouTube link, or add a YouTube Data API key in Settings to search by name');
  try {
    const u = 'https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=20&q=' + encodeURIComponent(q) + '&key=' + encodeURIComponent(state.apiKey);
    const r = await fetch(u), j = await r.json();
    if (!r.ok) throw new Error(j.error?.message || 'Search failed');
    const dec = s => { const e = document.createElement('textarea'); e.innerHTML = s; return e.value; };
    state.results = (j.items || []).filter(i => i.id?.videoId).map(i => ({ id: i.id.videoId, title: dec(i.snippet.title), channel: dec(i.snippet.channelTitle) }));
    setTab('results');
  } catch (e) { toast('Search failed: ' + e.message); }
}
function setTab(tab) { state.tab = tab; $$('.tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === tab)); renderList(); }
function renderList() {
  $('#n-library').textContent = state.library.length ? `(${state.library.length})` : '';
  $('#n-queue').textContent = state.queue.length ? `(${state.queue.length})` : '';
  const items = state[state.tab], ul = $('#list');
  if (!items.length) {
    ul.innerHTML = `<li class="empty">${{ library: 'Your library is empty. Paste a YouTube link above to add a track.', queue: 'The queue is empty. Use “+ Queue” on a library track, then turn Auto DJ on.', results: 'No results yet.' }[state.tab]}</li>`;
    return;
  }
  const loadBtns = activeDecks().map(d => `<button data-do="load" data-deck="${d.id}" style="--c:${COLORS[d.id]}" title="Load on deck ${d.id}">${d.id}</button>`).join('');
  ul.innerHTML = items.map((t, i) => `<li data-i="${i}">
    <img loading="lazy" alt="" src="https://i.ytimg.com/vi/${esc(t.id)}/default.jpg">
    <div class="meta"><b>${esc(t.title || t.id)}</b><span>${esc(t.channel || '')}</span></div>
    <div class="acts">${loadBtns}
      ${state.tab === 'queue' ? '' : '<button data-do="queue">+ Queue</button>'}
      ${state.tab === 'results' ? '<button data-do="save">Save</button>' : '<button data-do="del" aria-label="Remove">✕</button>'}
    </div></li>`).join('');
}
$('#list').addEventListener('click', e => {
  const b = e.target.closest('button[data-do]'); if (!b) return;
  const i = +b.closest('li').dataset.i, t = state[state.tab][i]; if (!t) return;
  switch (b.dataset.do) {
    case 'load': deck[b.dataset.deck].load(state.tab === 'queue' ? t : addToLibrary(t), false); break;
    case 'queue': state.queue.push(addToLibrary(t)); saveQueue(); renderList(); toast('Added to queue'); break;
    case 'save': addToLibrary(t); toast('Saved to library'); break;
    case 'del':
      if (state.tab === 'queue') { state.queue.splice(i, 1); saveQueue(); } else { state.library.splice(i, 1); saveLibrary(); }
      renderList(); break;
  }
});
$('#search-form').addEventListener('submit', e => { e.preventDefault(); const q = $('#search').value.trim(); if (q) { search(q); $('#search').value = ''; } });
$$('.tabs button').forEach(b => b.addEventListener('click', () => setTab(b.dataset.tab)));

/* ---------- FX pads: synthesised in the browser, not taken from any video ---------- */
let actx;
function fx(name) {
  actx = actx || new (window.AudioContext || window.webkitAudioContext)();
  if (actx.state === 'suspended') actx.resume();
  const t = actx.currentTime, out = actx.createGain(); out.connect(actx.destination);
  const env = (g, peak, dur) => { g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(peak, t + 0.02); g.gain.setValueAtTime(peak, t + dur * 0.7); g.gain.exponentialRampToValueAtTime(0.0001, t + dur); };
  const peak = 0.25 * Math.max(0.05, state.master);
  const osc = (type, f, dur) => { const o = actx.createOscillator(); o.type = type; o.frequency.setValueAtTime(f, t); o.connect(out); o.start(t); o.stop(t + dur); return o; };
  if (name === 'horn') { env(out, peak, 0.9); [440, 554, 659].forEach(f => osc('sawtooth', f, 0.9)); }
  if (name === 'siren') { env(out, peak, 1.6); const o = osc('sine', 600, 1.6); for (let i = 0; i < 4; i++) o.frequency.linearRampToValueAtTime(i % 2 ? 600 : 1200, t + 0.4 * (i + 1)); }
  if (name === 'laser') { env(out, peak, 0.4); osc('square', 2400, 0.4).frequency.exponentialRampToValueAtTime(120, t + 0.4); }
  if (name === 'riser') {
    const dur = 2.5, buf = actx.createBuffer(1, actx.sampleRate * dur, actx.sampleRate), d = buf.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
    const n = actx.createBufferSource(), f = actx.createBiquadFilter(); n.buffer = buf; f.type = 'bandpass'; f.Q.value = 2;
    f.frequency.setValueAtTime(200, t); f.frequency.exponentialRampToValueAtTime(8000, t + dur);
    out.gain.setValueAtTime(0.0001, t); out.gain.exponentialRampToValueAtTime(peak * 2, t + dur * 0.95); out.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    n.connect(f); f.connect(out); n.start(t);
  }
}
$$('[data-fx]').forEach(b => b.addEventListener('click', () => fx(b.dataset.fx)));

/* ---------- mixer wiring ---------- */
$('#xfader').addEventListener('input', e => { cancelAnim('xf'); setX(e.target.value / 1000); });
$('#master').addEventListener('input', e => { state.master = e.target.value / 100; $('#master-out').textContent = e.target.value; applyVolumes(); });
$('#btn-fade').addEventListener('click', autoFade);
$('#btn-cutL').addEventListener('click', () => { cancelAnim('xf'); setX(0); });
$('#btn-cutR').addEventListener('click', () => { cancelAnim('xf'); setX(1); });
$('#btn-center').addEventListener('click', () => { cancelAnim('xf'); setX(0.5); });
$('#fade-sec').value = state.fadeSec;
$('#fade-sec').addEventListener('change', e => { state.fadeSec = +e.target.value; store.set('fadeSec', state.fadeSec); });
$('#curve').value = state.curve;
$('#curve').addEventListener('change', e => { state.curve = e.target.value; store.set('curve', state.curve); applyVolumes(); });
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
$('#btn-settings').addEventListener('click', () => { $('#api-key').value = state.apiKey; $('#settings').showModal(); });
$('#settings').addEventListener('close', () => { state.apiKey = $('#api-key').value.trim(); store.set('apiKey', state.apiKey); });

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

// No background playback: stop everything when the app is not on screen.
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) return;
  decks.forEach(d => d.pause());
  if (state.autoDJ) setAutoDJ(false);
});

setInterval(() => { decks.forEach(d => d.tick()); autoDJTick(); }, 100);

/* ---------- boot ---------- */
window.onYouTubeIframeAPIReady = () => { state.apiReady = true; };
(function loadApi() {
  const s = document.createElement('script'); s.src = 'https://www.youtube.com/iframe_api';
  s.onerror = () => toast('Could not reach YouTube. Check your connection and reload.');
  document.head.append(s);
})();
setFour(state.four); setX(0); renderList();
if ('serviceWorker' in navigator && location.protocol.startsWith('http')) navigator.serviceWorker.register('sw.js').catch(() => {});
