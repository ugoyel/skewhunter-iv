// Tests for the relay, with small stand-ins for Cloudflare's runtime. Run: node --test hearby/relay/test
import test from 'node:test';
import assert from 'node:assert/strict';

class FakeSocket {
  constructor() { this.sent = []; this.closed = null; this.att = null; this.tags = []; }
  send(m) { if (this.closed) throw new Error('closed'); this.sent.push(m); }
  close(code, reason) { this.closed = { code, reason }; }
  serializeAttachment(a) { this.att = structuredClone(a); }
  deserializeAttachment() { return structuredClone(this.att); }
  json() { return this.sent.map((s) => JSON.parse(s)); }
}
globalThis.WebSocketPair = class { constructor() { this[0] = new FakeSocket(); this[1] = new FakeSocket(); } };
globalThis.Response = class { constructor(body, init = {}) { this.body = body; this.status = init.status ?? 200; this.webSocket = init.webSocket; } };

const { Party } = await import('../src/worker.js');

function fakeCtx() {
  const store = new Map();
  const sockets = [];
  return {
    sockets,
    store,
    storage: {
      async get(k) { return store.get(k); },
      async put(k, v) { store.set(k, v); },
      async deleteAll() { store.clear(); },
      async setAlarm(t) { store.set('__alarm', t); },
    },
    acceptWebSocket(ws, tags) { ws.tags = tags; sockets.push(ws); },
    getWebSockets(tag) { return sockets.filter((s) => !s.closed && (!tag || s.tags.includes(tag))); },
  };
}

async function connect(party, ctx, query) {
  const before = ctx.sockets.length;
  const res = await party.fetch(new Request('https://relay.test/ws/abc123?' + query));
  return { res, ws: ctx.sockets[before] };
}

const KEY = 'k'.repeat(32);

test('everyone gets the relay clock: sync is answered with its time in µs', async () => {
  const ctx = fakeCtx();
  const party = new Party(ctx, {}, () => 1_700_000_000_123);
  const { ws } = await connect(party, ctx, 'role=guest');
  await party.webSocketMessage(ws, JSON.stringify({ t: 'sync', c: 42.5 }));
  assert.deepEqual(ws.json().at(-1), { t: 'sync', c: 42.5, r: 1_700_000_000_123_000, s: 1_700_000_000_123_000 });
});

test('a guest gets hello once the DJ is there, then the state after joining', async () => {
  const ctx = fakeCtx();
  const party = new Party(ctx, {});
  const { ws: guest } = await connect(party, ctx, 'role=guest');
  assert.equal(guest.sent.length, 0, 'no DJ yet');
  const { res, ws: host } = await connect(party, ctx, 'role=host&key=' + KEY);
  assert.equal(res.status, 101);
  await party.webSocketMessage(host, JSON.stringify({ t: 'info', stream: 7, host: 'Ana' }));
  const hello = guest.json().at(-1);
  assert.equal(hello.t, 'hello');
  assert.equal(hello.host, 'Ana');
  assert.equal(hello.remote, true);
  const state = JSON.stringify({ t: 'state', video: { id: 'dQw4w9WgXcQ', playing: true, anchor: 1, pos: 0, v: 1 } });
  await party.webSocketMessage(host, state);
  assert.equal(guest.sent.length, 1, 'state only after the guest says hello');
  await party.webSocketMessage(guest, JSON.stringify({ t: 'hello', name: 'iPhone' }));
  assert.equal(guest.sent.at(-1), state, 'the latest state on joining');
  await party.webSocketMessage(host, state.replace('"v":1', '"v":2'));
  assert.equal(guest.json().at(-1).video.v, 2, 'and every change after');
  // A late guest gets hello right away.
  const { ws: late } = await connect(party, ctx, 'role=guest');
  assert.equal(late.json()[0].t, 'hello');
});

test('only the DJ who started the party can drive it', async () => {
  const ctx = fakeCtx();
  const party = new Party(ctx, {});
  assert.equal((await connect(party, ctx, 'role=host&key=short')).res.status, 403);
  assert.equal((await connect(party, ctx, 'role=host&key=' + KEY)).res.status, 101);
  assert.equal((await connect(party, ctx, 'role=host&key=' + 'x'.repeat(32))).res.status, 403);
  // The same DJ reconnecting replaces its old connection.
  const first = ctx.getWebSockets('host')[0];
  assert.equal((await connect(party, ctx, 'role=host&key=' + KEY)).res.status, 101);
  assert.ok(first.closed);
  // A guest can't send state.
  const { ws: guest } = await connect(party, ctx, 'role=guest');
  await party.webSocketMessage(guest, JSON.stringify({ t: 'state', video: null }));
  assert.equal(ctx.store.get('state'), undefined);
});

test('the party ends for everyone, and is forgotten', async () => {
  const ctx = fakeCtx();
  const party = new Party(ctx, {});
  const { ws: host } = await connect(party, ctx, 'role=host&key=' + KEY);
  await party.webSocketMessage(host, JSON.stringify({ t: 'info', stream: 1, host: 'DJ' }));
  const { ws: guest } = await connect(party, ctx, 'role=guest');
  await party.webSocketMessage(host, JSON.stringify({ t: 'bye' }));
  assert.equal(guest.json().at(-1).t, 'bye');
  assert.ok(guest.closed);
  assert.equal(ctx.store.size, 0);
});

test('junk is ignored', async () => {
  const ctx = fakeCtx();
  const party = new Party(ctx, {});
  const { ws } = await connect(party, ctx, 'role=guest');
  for (const m of ['nope', '[]', 'null', JSON.stringify({ t: 'sync', c: 'x' }), 'x'.repeat(20000)]) await party.webSocketMessage(ws, m);
  await party.webSocketMessage(ws, new ArrayBuffer(8));
  assert.equal(ws.sent.length, 0);
});
