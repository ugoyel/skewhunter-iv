/*
 * Hearby in the browser: the sync logic of the guest page (index.html), kept free of page code so
 * Node can test it (app/src/test/js). Mirrors the app's Kotlin and Swift code:
 *   ClockSync            core/ClockSync.kt
 *   VideoSyncController  core/VideoSync.kt (YouTube mode)
 *   Chirp                core/SoundCheck.kt (sound check)
 *   AudioScheduler       plays each 20 ms frame at its moment on the shared timeline, with Web Audio
 * All times are microseconds unless a name says otherwise.
 */
(function (root) {
  'use strict';

  // ---- clock sync: host clock minus this browser's clock ------------------------------------------
  // For one exchange: t0 browser send, t1 host receive, t2 host send, t3 browser receive. Trust the
  // exchange with the smallest round trip in a short window, then low-pass it (step on big jumps).
  class ClockSync {
    constructor(window = 12) {
      this.window = window;
      this.offsets = [];
      this.rtts = [];
      this.next = 0;
      this.smoothed = 0;
      this.offsetUs = 0;
      this.rttUs = 0;
      this.sampleCount = 0;
    }

    get synced() { return this.sampleCount >= ClockSync.MIN_SAMPLES; }

    addSample(t0, t1, t2, t3) {
      const rtt = (t3 - t0) - (t2 - t1);
      if (!(rtt >= 0) || rtt > ClockSync.MAX_RTT_US) return false;
      const offset = ((t1 - t0) + (t2 - t3)) / 2;
      this.offsets[this.next] = offset;
      this.rtts[this.next] = rtt;
      this.next = (this.next + 1) % this.window;
      let best = 0;
      for (let i = 1; i < this.rtts.length; i++) if (this.rtts[i] < this.rtts[best]) best = i;
      const candidate = this.offsets[best];
      if (this.sampleCount === 0 || Math.abs(candidate - this.smoothed) > ClockSync.STEP_US) {
        this.smoothed = candidate; // first fix, or the clocks really moved
      } else {
        this.smoothed += (candidate - this.smoothed) * ClockSync.SMOOTHING;
      }
      this.offsetUs = Math.round(this.smoothed);
      this.rttUs = this.rtts[best];
      this.sampleCount++;
      return true;
    }

    reset() {
      this.offsets = [];
      this.rtts = [];
      this.next = 0;
      this.smoothed = 0;
      this.offsetUs = 0;
      this.rttUs = 0;
      this.sampleCount = 0;
    }
  }
  ClockSync.MIN_SAMPLES = 3;
  ClockSync.MAX_RTT_US = 1000000;
  ClockSync.STEP_US = 20000;
  ClockSync.SMOOTHING = 0.25;

  // ---- YouTube mode -------------------------------------------------------------------------------
  // The DJ's timeline: video `id` was at `pos` at DJ time `anchor`, playing or paused.
  function positionAt(v, hostUs) { return v.playing ? v.pos + Math.max(0, hostUs - v.anchor) : v.pos; }
  function startsInUs(v, hostUs) { return v.playing && hostUs < v.anchor ? v.anchor - hostUs : 0; }

  const S = { UNSTARTED: -1, ENDED: 0, PLAYING: 1, PAUSED: 2, BUFFERING: 3, CUED: 5 };
  const K = {
    HARD_ERROR: 1000000, SEEK_THRESHOLD: 150000, COARSE_NUDGE_THRESHOLD: 15000, NUDGE_THRESHOLD: 8000,
    NUDGE_SETTLE: 150000, START_WINDOW: 400000, PAUSED_TOLERANCE: 300000, PARKED_TOLERANCE: 50000,
    PARK_COOLDOWN: 600000, INITIAL_SEEK_LEAD: 250000, INITIAL_PLAY_LEAD: 150000, MAX_LEAD: 2000000,
    LEARN_LIMIT: 300000, LOAD_COOLDOWN: 1000000, SEEK_COOLDOWN: 1500000, START_COOLDOWN: 1200000,
    MAX_FINE_NUDGE: 0.10, MAX_COARSE_NUDGE: 0.25,
  };

  /** The available speed closest to normal, within maxChange, on the requested side. */
  function nudgeRate(rates, slower, maxChange = K.MAX_FINE_NUDGE) {
    let best = null;
    for (const r of rates) {
      const ok = slower ? (r < 1 && r >= 1 - maxChange - 1e-9) : (r > 1 && r <= 1 + maxChange + 1e-9);
      if (ok && (best === null || Math.abs(r - 1) < Math.abs(best - 1))) best = r;
    }
    return best;
  }

  const clampLead = (v) => Math.min(Math.max(v, 0), K.MAX_LEAD);

  /**
   * Steers one YouTube player onto the shared timeline (same rules as the apps). Call step() a few
   * times a second; carry out the returned actions:
   *   {type:'load', id} {type:'play', afterUs} {type:'pause'} {type:'seek', seconds} {type:'rate', rate, forUs}
   * report: {ready, state, positionUs, measuredAtUs, rate, rates, error}
   */
  class VideoSyncController {
    constructor() {
      this.loadedId = null;
      this.cooldownUntil = -Infinity;
      this.nudgeUntil = -Infinity;
      this.nudging = false;
      this.seekLead = K.INITIAL_SEEK_LEAD;
      this.playLead = K.INITIAL_PLAY_LEAD;
      this.awaitingSeek = false;
      this.leadAtSeek = 0;
      this.awaitingStart = false;
      this.errors = [];
      this.lastErrorUs = null;
      this.seeks = 0;
      this.nudges = 0;
    }

    step(nowUs, target, targetPositionUs, startsIn, report, mayStart) {
      if (!target) {
        this.reset();
        return report.ready && (report.state === S.PLAYING || report.state === S.BUFFERING) ? [{ type: 'pause' }] : [];
      }
      if (!report.ready || report.error) return [];
      if (this.loadedId !== target.id) {
        this.reset();
        this.loadedId = target.id;
        this.cooldownUntil = nowUs + K.LOAD_COOLDOWN;
        return [{ type: 'load', id: target.id }];
      }
      if (nowUs < this.cooldownUntil) return [];

      const playing = report.state === S.PLAYING;
      const rate = report.rate || 1;
      const position = report.positionUs + (playing ? Math.trunc((nowUs - report.measuredAtUs) * rate) : 0);

      if (!target.playing) {
        const restore = this.restoreRate(report);
        if (restore) return [restore];
        if (playing || report.state === S.BUFFERING) return [{ type: 'pause' }];
        if (Math.abs(position - targetPositionUs) > K.PAUSED_TOLERANCE && targetPositionUs >= 0) {
          this.cooldownUntil = nowUs + K.SEEK_COOLDOWN;
          return [{ type: 'seek', seconds: targetPositionUs / 1e6 }];
        }
        return [];
      }

      // A start or resume is scheduled: wait at the right spot, then press play just early enough.
      if (startsIn > 0) {
        if (playing || report.state === S.BUFFERING) return [{ type: 'pause' }];
        if (Math.abs(position - targetPositionUs) > K.PARKED_TOLERANCE) {
          this.cooldownUntil = nowUs + K.PARK_COOLDOWN;
          return [{ type: 'seek', seconds: targetPositionUs / 1e6 }];
        }
        if (startsIn <= this.playLead + K.START_WINDOW && mayStart) {
          this.awaitingStart = true;
          this.errors = [];
          const after = Math.max(0, startsIn - this.playLead);
          this.cooldownUntil = nowUs + after + K.START_COOLDOWN;
          return [{ type: 'play', afterUs: after }];
        }
        return [];
      }

      if (report.state === S.BUFFERING) return [];
      if (!playing) {
        if (!mayStart) return [];
        return this.seekTo(nowUs, targetPositionUs, true); // joined late, resumed, or stopped
      }

      const error = position - targetPositionUs;
      if (this.nudging) {
        if (nowUs < this.nudgeUntil + K.NUDGE_SETTLE) return [];
        this.nudging = false;
        this.errors = [];
      }
      const restore = this.restoreRate(report);
      if (restore) return [restore];

      if (this.awaitingSeek) {
        this.awaitingSeek = false;
        if (Math.abs(error) < K.LEARN_LIMIT) this.seekLead = Math.trunc((this.seekLead + clampLead(this.leadAtSeek - error)) / 2);
      }
      if (this.awaitingStart) {
        this.awaitingStart = false;
        if (Math.abs(error) < K.LEARN_LIMIT) this.playLead = Math.trunc((this.playLead + clampLead(this.playLead - error)) / 2);
      }

      this.errors.push(error);
      while (this.errors.length > 3) this.errors.shift();
      const e = this.errors.slice().sort((a, b) => a - b)[Math.floor(this.errors.length / 2)];
      this.lastErrorUs = e;

      if (Math.abs(error) > K.HARD_ERROR || (this.errors.length >= 3 && Math.abs(e) > K.SEEK_THRESHOLD)) {
        return this.seekTo(nowUs, targetPositionUs, false);
      }
      if (this.errors.length < 3) return [];
      const rates = report.rates && report.rates.length ? report.rates : [1];
      let r = null;
      if (Math.abs(e) > K.COARSE_NUDGE_THRESHOLD) {
        r = nudgeRate(rates, e > 0, K.MAX_FINE_NUDGE);
        if (r === null) r = nudgeRate(rates, e > 0, K.MAX_COARSE_NUDGE);
      } else if (Math.abs(e) > K.NUDGE_THRESHOLD) {
        r = nudgeRate(rates, e > 0, K.MAX_FINE_NUDGE);
      }
      if (r === null) return [];
      const forUs = Math.trunc(Math.abs(e) / Math.abs(r - 1));
      this.nudging = true;
      this.nudges++;
      this.nudgeUntil = nowUs + forUs;
      return [{ type: 'rate', rate: r, forUs }];
    }

    seekTo(nowUs, targetPositionUs, play) {
      this.seeks++;
      this.leadAtSeek = this.seekLead;
      this.awaitingSeek = true;
      this.errors = [];
      this.cooldownUntil = nowUs + K.SEEK_COOLDOWN;
      const seek = { type: 'seek', seconds: (targetPositionUs + this.seekLead) / 1e6 };
      return play ? [seek, { type: 'play', afterUs: 0 }] : [seek];
    }

    restoreRate(report) {
      return !this.nudging && Math.abs((report.rate || 1) - 1) > 1e-3 ? { type: 'rate', rate: 1, forUs: 0 } : null;
    }

    reset() {
      this.loadedId = null;
      this.nudging = false;
      this.awaitingSeek = false;
      this.awaitingStart = false;
      this.errors = [];
      this.lastErrorUs = null;
    }
  }

  // ---- sound check chirp: 40 ms sweep 1.5 → 7 kHz with 2 ms fades (same samples as the apps) -------
  function chirpSamples() {
    const N = 1920, RATE = 48000, F0 = 1500, F1 = 7000, AMP = 0.5, TAPER = 96, D = N / RATE;
    const out = new Float32Array(N);
    for (let i = 0; i < N; i++) {
      const t = i / RATE;
      const phase = 2 * Math.PI * (F0 * t + (F1 - F0) * t * t / (2 * D));
      let w = 1;
      if (i < TAPER) w = 0.5 - 0.5 * Math.cos(Math.PI * i / TAPER);
      else if (i >= N - TAPER) w = 0.5 - 0.5 * Math.cos(Math.PI * (N - 1 - i) / TAPER);
      out[i] = AMP * w * Math.sin(phase);
    }
    return out;
  }

  // ---- audio -------------------------------------------------------------------------------------

  /**
   * Maps this page's clock (performance.now(), ms) to the audio context's clock (s) at the moment a
   * sample is heard, from AudioContext.getOutputTimestamp() (which includes the output latency).
   * Readings jitter by about a render quantum, so they are smoothed; the audio hardware's clock also
   * drifts against the system clock, so the drift is measured over 5 to 10 s (slowly, so its noise
   * stays out of the playback speed) and the smoothing follows it without lagging. A jump steps.
   */
  class OutputClock {
    constructor() {
      this.d = null;    // context time minus page time (s), as of page time this.t
      this.t = 0;
      this.drift = 0;   // how much faster the audio clock runs (s per s)
      this.marks = [];  // [page time, d] once a second, for the drift
      this.startedAt = 0;
    }

    /** Feed one getOutputTimestamp() reading (contextTime s, performanceTime ms). */
    update(contextTime, performanceTime) {
      if (!(contextTime > 0) || !(performanceTime > 0)) return;
      const t = performanceTime / 1000;
      const d = contextTime - t;
      if (this.d === null) return this.restart(t, d);
      if (t <= this.t) return;
      const predicted = this.d + this.drift * (t - this.t);
      const r = d - predicted;
      if (Math.abs(r) > OutputClock.STEP_S) return this.restart(t, d); // the audio device restarted or changed
      this.d = predicted + OutputClock.GAIN * r;
      this.t = t;
      // Measure drift only once the readings have settled: an audio device's first few seconds of
      // timestamps wander, and that is not drift.
      if (t - this.startedAt < OutputClock.SETTLE_S) return;
      if (this.marks.length === 0) {
        this.marks.push([t, this.d]);
        return;
      }
      const last = this.marks[this.marks.length - 1];
      if (t - last[0] >= 1) {
        this.marks.push([t, this.d]);
        if (this.marks.length > 11) this.marks.shift();
        const first = this.marks[0];
        if (t - first[0] >= 5) {
          const drift = (this.d - first[1]) / (t - first[0]);
          this.drift = Math.max(-OutputClock.MAX_DRIFT, Math.min(OutputClock.MAX_DRIFT, drift));
        }
      }
    }

    restart(t, d) {
      this.d = d;
      this.t = t;
      this.startedAt = t;
      this.drift = 0;
      this.marks = [];
    }

    /** Fallback when the browser has no getOutputTimestamp: currentTime plus its stated latency. */
    updateFallback(currentTime, performanceNow, latency) {
      this.update(currentTime - (latency || 0), performanceNow);
    }

    get ready() { return this.d !== null; }

    /** Context time at which a sample must be scheduled to be heard at performance time perfMs. */
    contextTimeFor(perfMs) {
      const t = perfMs / 1000;
      return t + this.d + this.drift * (t - this.t);
    }
  }
  OutputClock.STEP_S = 0.05;
  OutputClock.GAIN = 0.02;
  OutputClock.SETTLE_S = 3;
  OutputClock.MAX_DRIFT = 0.001;

  /**
   * Places consecutive 20 ms frames back to back (no gaps or overlaps, so no clicks) and pulls the
   * stream toward where the timeline says it should be heard with a tiny speed change (at most
   * 0.3 % plus the measured clock drift: a few cents of pitch, inaudible). A gap in the stream, or
   * an error too big to pull in, starts again exactly on time.
   */
  class AudioScheduler {
    constructor(frameS = 0.02) {
      this.frameS = frameS;
      this.nextWhen = null;
      this.lastSeq = null;
      this.lastErrorS = 0;
      this.realigns = 0;
      this.late = 0;
      this.sinceAlign = 0;
    }

    /**
     * want: context time this frame should be heard; now: the context's currentTime; drift: how
     * much faster the audio clock runs than the timeline (OutputClock.drift).
     * Returns {when, rate} to start the frame with, or {late: seconds} if it is already too late.
     */
    place(seq, want, now, drift = 0) {
      const earliest = now + AudioScheduler.LEAD_S;
      if (want < earliest) {
        this.late++;
        this.nextWhen = null;
        return { late: earliest - want };
      }
      let when, rate = 1 / (1 + drift); // a frame lasts 20 ms of timeline, however fast the audio clock runs
      const contiguous = this.nextWhen !== null && seq === this.lastSeq + 1 && this.nextWhen >= earliest;
      // Right after starting, line up again rather than drift in slowly: the first moments of a
      // new audio device are when its timing estimate moves most.
      const tolerance = this.sinceAlign < AudioScheduler.STARTUP_FRAMES ? AudioScheduler.STARTUP_REALIGN_S : AudioScheduler.REALIGN_S;
      if (contiguous && Math.abs(this.nextWhen - want) <= tolerance) {
        this.sinceAlign++;
        when = this.nextWhen;
        const err = when - want; // > 0: running late
        if (Math.abs(err) > AudioScheduler.DEADBAND_S) {
          rate *= 1 + Math.max(-AudioScheduler.MAX_RATE, Math.min(AudioScheduler.MAX_RATE, err / AudioScheduler.HORIZON_S));
        }
      } else {
        when = want;
        if (this.nextWhen !== null) this.realigns++;
        if (!contiguous) this.sinceAlign = 0; // a new stream (a gap, a reset): start up again
      }
      this.lastErrorS = when - want;
      this.nextWhen = when + this.frameS / rate;
      this.lastSeq = seq;
      return { when, rate };
    }

    /** Seconds of audio scheduled ahead of `now`. */
    bufferedS(now) { return this.nextWhen === null ? 0 : Math.max(0, this.nextWhen - now); }

    reset() {
      this.nextWhen = null;
      this.lastSeq = null;
      this.sinceAlign = 0;
    }
  }
  AudioScheduler.STARTUP_FRAMES = 250;     // the first 5 s after (re)starting ...
  AudioScheduler.STARTUP_REALIGN_S = 0.002; // ... line up again if more than 2 ms off
  AudioScheduler.LEAD_S = 0.01;       // never schedule closer than this to "now"
  AudioScheduler.REALIGN_S = 0.04;    // further off than this: start again on time
  AudioScheduler.DEADBAND_S = 0.00025; // closer than this: leave the speed alone
  AudioScheduler.HORIZON_S = 1.0;     // pull errors in over about a second
  AudioScheduler.MAX_RATE = 0.003;

  /** Parses one binary audio message: seq, timestamp, then 16-bit stereo PCM. */
  function parseAudio(buffer) {
    const view = new DataView(buffer);
    if (buffer.byteLength < 12) return null;
    const seq = view.getUint32(0);
    const ts = view.getUint32(4) * 4294967296 + view.getUint32(8);
    const samples = new Int16Array(buffer, 12, (buffer.byteLength - 12) >> 1);
    return { seq, ts, samples };
  }

  const api = {
    ClockSync, VideoSyncController, nudgeRate, positionAt, startsInUs, PlayerState: S, Tuning: K,
    chirpSamples, OutputClock, AudioScheduler, parseAudio,
  };
  root.Hearby = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
