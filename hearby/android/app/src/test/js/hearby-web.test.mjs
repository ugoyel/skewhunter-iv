// Tests for the guest page's sync logic (app/src/main/assets/web/hearby-web.js).
// Run: node --test app/src/test/js/
// Mirrors the Kotlin tests in core/: the same clock filter, YouTube controller simulations and chirp.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const H = require('../../main/assets/web/hearby-web.js');

/** Small deterministic generator (mulberry32) so runs repeat exactly. */
function rng(seed) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return {
    unit: next,
    int: (n) => Math.floor(next() * n),
    gauss: () => Math.sqrt(-2 * Math.log(Math.max(next(), 1e-12))) * Math.cos(2 * Math.PI * next()),
  };
}

// ---- clock sync ------------------------------------------------------------------------------------

test('clock sync trusts the fastest exchange and ignores slow, lopsided ones', () => {
  const r = rng(1);
  const c = new H.ClockSync();
  const trueOffset = 123456789; // host minus browser
  let t = 1e6;
  for (let i = 0; i < 40; i++) {
    t += 100000;
    const up = 1500 + r.unit() * (i % 5 === 0 ? 40000 : 3000); // Wi-Fi: mostly quick, sometimes not
    const down = 1500 + r.unit() * (i % 7 === 0 ? 60000 : 3000);
    const t0 = t, t1 = t0 + up + trueOffset, t2 = t1 + 200, t3 = t2 - trueOffset + down;
    c.addSample(t0, t1, t2, t3);
  }
  assert.ok(c.synced);
  assert.ok(Math.abs(c.offsetUs - trueOffset) < 1500, `offset error ${c.offsetUs - trueOffset} µs`);
  assert.ok(c.rttUs < 5000);
  assert.equal(c.addSample(0, 10, 5, 2), true);
  assert.equal(c.addSample(0, 0, 0, 2e6), false); // a 2 s round trip is not believable
});

test('clock sync steps when the clocks really jump', () => {
  const c = new H.ClockSync();
  for (let i = 0; i < 5; i++) c.addSample(i * 1000, i * 1000 + 500, i * 1000 + 500, i * 1000 + 1000);
  assert.equal(c.offsetUs, 0);
  for (let i = 5; i < 20; i++) c.addSample(i * 1000, i * 1000 + 500 + 1e6, i * 1000 + 500 + 1e6, i * 1000 + 1000);
  assert.equal(c.offsetUs, 1e6);
});

// ---- YouTube mode: the same simulated players as core/VideoSyncTest.kt ------------------------------

const S = H.PlayerState;
const KEYFRAME = 2e6;

class SimPlayer {
  constructor(seed, driftPpm, rates, adUs = 0) {
    this.r = rng(seed);
    this.driftPpm = driftPpm;
    this.rates = rates;
    this.adUs = adUs;
    this.state = S.UNSTARTED;
    this.position = 0;
    this.rate = 1;
    this.pendingUntil = null;
    this.pendingTo = 0;
    this.pendingPlay = false;
    this.wantPlay = false;
    this.bufFrom = 0;
    this.bufTo = 0;
    this.adLeft = 0;
    this.last = 0;
    this.playAt = null;
    this.restoreAt = null;
  }

  apply(a, now) {
    switch (a.type) {
      case 'load':
        this.state = S.CUED; this.position = 0; this.bufFrom = 0; this.bufTo = 0; this.adLeft = this.adUs;
        break;
      case 'play':
        if (a.afterUs > 0) this.playAt = now + a.afterUs; else this.play(now);
        break;
      case 'pause':
        this.wantPlay = false;
        if (this.state === S.PLAYING || this.state === S.BUFFERING) this.state = S.PAUSED;
        this.pendingUntil = null;
        break;
      case 'seek': {
        if (this.adLeft > 0) return;
        const to = a.seconds * 1e6;
        const loaded = to >= this.bufFrom && to <= this.bufTo;
        const lands = loaded ? to : Math.floor(to / KEYFRAME) * KEYFRAME;
        this.pendingUntil = now + (loaded ? 80000 + this.r.int(120000) : 300000 + this.r.int(400000));
        this.pendingTo = lands;
        this.pendingPlay = this.wantPlay || this.state === S.PLAYING;
        if (!loaded) { this.bufFrom = lands; this.bufTo = lands; }
        this.state = this.pendingPlay ? S.BUFFERING : S.PAUSED;
        this.position = lands;
        break;
      }
      case 'rate': {
        let best = this.rates[0];
        for (const x of this.rates) if (Math.abs(x - a.rate) < Math.abs(best - a.rate)) best = x;
        this.rate = best;
        this.restoreAt = a.forUs > 0 ? now + a.forUs : null;
        break;
      }
    }
  }

  play(now) {
    this.wantPlay = true;
    if (this.pendingUntil !== null) { this.pendingPlay = true; return; }
    if (this.state !== S.PLAYING) {
      this.pendingUntil = now + 150000 + this.r.int(250000) + (this.bufTo <= this.bufFrom ? 250000 : 0);
      this.pendingTo = this.position;
      this.pendingPlay = true;
      this.state = S.BUFFERING;
    }
  }

  advance(to) {
    while (this.last < to) {
      let next = Math.min(to, this.last + 1000);
      if (this.playAt !== null && this.playAt > this.last && this.playAt <= next) next = this.playAt;
      if (this.restoreAt !== null && this.restoreAt > this.last && this.restoreAt <= next) next = this.restoreAt;
      this.step(next);
      if (next === this.playAt) { this.playAt = null; this.play(next); }
      if (next === this.restoreAt) { this.restoreAt = null; this.rate = 1; }
    }
  }

  step(to) {
    const dt = to - this.last;
    this.last = to;
    if (this.pendingUntil !== null && to >= this.pendingUntil) {
      this.position = this.pendingTo + (this.pendingPlay ? to - this.pendingUntil : 0);
      this.state = this.pendingPlay ? S.PLAYING : S.PAUSED;
      this.pendingUntil = null;
      return;
    }
    if (this.state === S.PLAYING) {
      if (this.adLeft > 0) { this.adLeft -= dt; return; }
      this.position += dt * this.rate * (1 + this.driftPpm * 1e-6);
      this.bufTo = Math.max(this.bufTo, this.position + 30e6);
    }
  }

  report(now) {
    return {
      ready: true, state: this.state, positionUs: Math.trunc(this.position + this.r.gauss() * 1500),
      measuredAtUs: now, rate: this.rate, rates: this.rates, error: 0,
    };
  }
}

const fineRates = Array.from({ length: 36 }, (_, i) => 0.25 + 0.05 * i);
const coarseRates = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
const start = { id: 'vid', title: 'Song', playing: true, anchor: 2e6, pos: 0, v: 1 };

function phones(rates, ad = 0, seed = 0) {
  return [
    { p: new SimPlayer(seed * 3 + 1, 40, rates), c: new H.VideoSyncController() },
    { p: new SimPlayer(seed * 3 + 2, -55, rates, ad), c: new H.VideoSyncController() },
    { p: new SimPlayer(seed * 3 + 3, 10, rates), c: new H.VideoSyncController() },
  ];
}

function run(list, timeline, from, seconds, settle, worst) {
  let now = from;
  const end = from + seconds * 1e6;
  while (now < end) {
    now += 200000;
    const t = timeline(now);
    list.forEach((ph, i) => {
      ph.p.advance(now);
      const target = t ? H.positionAt(t, now) : 0;
      const startsIn = t ? H.startsInUs(t, now) : 0;
      for (const a of ph.c.step(now, t, target, startsIn, ph.p.report(now), true)) ph.p.apply(a, now);
      if (t && t.playing && now > settle && target > 0) worst[i] = Math.max(worst[i], Math.abs(Math.trunc(ph.p.position) - target));
    });
  }
  return now;
}

test('videos start together and stay within a few ms with fine speeds', () => {
  const list = phones(fineRates);
  const worst = [0, 0, 0];
  run(list, () => start, 0, 180, 10e6, worst);
  worst.forEach((w, i) => assert.ok(w < 15000, `phone ${i}: ${w / 1000} ms`));
  list.forEach((ph) => assert.ok(ph.c.seeks <= 6));
});

test('videos stay close with only YouTube\'s standard speed steps, across many random runs', () => {
  let worstAll = 0;
  for (let seed = 1; seed <= 20; seed++) {
    const worst = [0, 0, 0];
    run(phones(coarseRates, 0, seed), () => start, 0, 90, 10e6, worst);
    worstAll = Math.max(worstAll, ...worst);
  }
  assert.ok(worstAll < 20000, `worst ${worstAll / 1000} ms`);
});

test('a pause and resume brings everyone back together', () => {
  const list = phones(fineRates);
  const paused = { ...start, playing: false, anchor: 20e6, pos: H.positionAt(start, 20e6), v: 2 };
  const resumed = { ...paused, playing: true, anchor: 26e6, v: 3 };
  const timeline = (now) => (now < 20e6 ? start : now < 25e6 ? paused : resumed);
  const early = [0, 0, 0];
  const now = run(list, timeline, 0, 27.5, 26.5e6, early);
  early.forEach((w) => assert.ok(w < 250000));
  const later = [0, 0, 0];
  run(list, timeline, now, 30, 33e6, later);
  later.forEach((w, i) => assert.ok(w < 15000, `phone ${i}: ${w / 1000} ms`));
});

test('a phone that gets an ad catches up after it, and a late joiner jumps straight in', () => {
  const worst = [0, 0, 0];
  run(phones(fineRates, 6e6), () => start, 0, 60, 20e6, worst);
  assert.ok(worst[1] < 15000);
  const late = [{ p: new SimPlayer(9, 20, fineRates), c: new H.VideoSyncController() }];
  const w = [0];
  run(late, () => ({ ...start, anchor: 0 }), 95e6, 30, 105e6, w);
  assert.ok(w[0] < 15000);
});

test('nudges pick the gentlest speed on the right side', () => {
  assert.ok(Math.abs(H.nudgeRate(fineRates, true) - 0.95) < 1e-9);
  assert.ok(Math.abs(H.nudgeRate(fineRates, false) - 1.05) < 1e-9);
  assert.equal(H.nudgeRate(coarseRates, true), null);
  assert.equal(H.nudgeRate(coarseRates, true, H.Tuning.MAX_COARSE_NUDGE), 0.75);
});

// ---- sound check -----------------------------------------------------------------------------------

test('the chirp is sample for sample the one the apps play', () => {
  const c = H.chirpSamples();
  assert.equal(c.length, 1920);
  const expected = { 50: -0.2020977, 100: 0.2314788, 500: 0.2541216, 1500: 0.0428987, 1900: 0.0263643 };
  for (const [i, v] of Object.entries(expected)) assert.ok(Math.abs(c[i] - v) < 1e-6, `sample ${i}: ${c[i]}`);
});

// ---- audio: frames heard on time despite clock drift and jittery output timestamps -----------------

/**
 * A simulated browser: its audio hardware clock runs `ppm` fast against the page clock, and each
 * getOutputTimestamp() reading jitters by up to one render quantum. Frames arrive with network jitter.
 * Returns the error of every frame as actually heard, in ms.
 */
function simulateAudio({ ppm, seconds, dropEvery = 0, seed = 5, startupWanderMs = 0 }) {
  const r = rng(seed);
  const c0 = 3.2; // context time when the page clock read 0
  const ctxAt = (perfMs) => c0 + (perfMs / 1000) * (1 + ppm * 1e-6);
  const perfAt = (ctx) => ((ctx - c0) / (1 + ppm * 1e-6)) * 1000;
  const clock = new H.OutputClock();
  const sched = new H.AudioScheduler();
  const delayMs = 350;
  const errors = [];
  let lastHeardEnd = null;
  let gaps = 0;
  for (let k = 0; k < seconds * 50; k++) {
    if (dropEvery && k > 0 && k % dropEvery === 0) continue; // a lost frame
    const capturedMs = 1000 + k * 20;
    const arrivesMs = capturedMs + 5 + r.unit() * 30;
    // The two numbers of a reading describe the same moment (Web Audio spec), give or take about
    // a render quantum of jitter.
    const q = (r.unit() - 0.5) * 0.003;
    // A new audio device's first readings can be off, settling over a couple of seconds.
    const wander = startupWanderMs * Math.max(0, 1 - k / 100) / 1000;
    clock.update(ctxAt(arrivesMs) + q + wander, arrivesMs);
    const wantMs = capturedMs + delayMs;
    const placed = sched.place(k, clock.contextTimeFor(wantMs), ctxAt(arrivesMs), clock.drift);
    if (placed.late !== undefined) continue;
    if (lastHeardEnd !== null && Math.abs(placed.when - lastHeardEnd) > 1e-9) gaps++;
    lastHeardEnd = placed.when + 0.02 / placed.rate;
    errors.push(perfAt(placed.when) - wantMs);
  }
  return { errors, gaps, sched };
}

/**
 * What must hold is that the timing is steady: every frame within 1 ms of the average. A small
 * constant (a browser that reports its output latency a little off) is what the sound check and the
 * slider take care of.
 */
function spread(errors) {
  const mean = errors.reduce((a, b) => a + b, 0) / errors.length;
  return { mean, worst: Math.max(...errors.map((e) => Math.abs(e - mean))) };
}

test('frames play back to back and stay on time while the audio clock drifts', () => {
  for (const ppm of [-300, 0, 150, 300]) {
    const { errors, gaps, sched } = simulateAudio({ ppm, seconds: 60 });
    const { mean, worst } = spread(errors.slice(500)); // after the first 10 s
    assert.ok(worst < 1, `${ppm} ppm: ${worst.toFixed(2)} ms from the average`);
    assert.ok(Math.abs(mean) < 0.5, `${ppm} ppm: constant ${mean.toFixed(2)} ms`);
    assert.equal(gaps, 0, `${ppm} ppm: ${gaps} gaps`);
    assert.equal(sched.realigns, 0);
  }
});

test('it locks on within seconds even when the first timestamps are off', () => {
  const { errors, sched } = simulateAudio({ ppm: 40, seconds: 20, startupWanderMs: 12 });
  const { mean, worst } = spread(errors.slice(300)); // from 6 s on
  assert.ok(worst < 1, `${worst.toFixed(2)} ms from the average`);
  assert.ok(Math.abs(mean) < 0.5, `constant ${mean.toFixed(2)} ms`);
  assert.ok(sched.realigns < 20, `${sched.realigns} realigns`);
});

test('a lost frame restarts the stream exactly on time', () => {
  const { errors, sched } = simulateAudio({ ppm: 100, seconds: 30, dropEvery: 250 });
  assert.ok(sched.realigns >= 5);
  const { mean, worst } = spread(errors.slice(500));
  assert.ok(worst < 1, `${worst.toFixed(2)} ms from the average`);
  assert.ok(Math.abs(mean) < 0.5);
});

test('a frame that arrives too late is reported, not played', () => {
  const sched = new H.AudioScheduler();
  assert.deepEqual(sched.place(0, 10.5, 10), { when: 10.5, rate: 1 });
  const late = sched.place(1, 10.0, 10.2);
  assert.ok(late.late > 0.2);
  assert.equal(sched.late, 1);
});

test('audio messages: big-endian header, little-endian samples', () => {
  const buf = new ArrayBuffer(12 + 8);
  const v = new DataView(buf);
  v.setUint32(0, 7);
  v.setUint32(4, 0x01);
  v.setUint32(8, 0x02030405);
  v.setInt16(12, -2, true);
  v.setInt16(14, 300, true);
  const m = H.parseAudio(buf);
  assert.equal(m.seq, 7);
  assert.equal(m.ts, 0x0102030405);
  assert.deepEqual(Array.from(m.samples), [-2, 300, 0, 0]);
});
