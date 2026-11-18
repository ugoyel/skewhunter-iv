/**
 * Hearby relay: a party's meeting point on the internet, for phones that aren't on the DJ phone's
 * network (an iPhone on mobile data, a friend elsewhere). Only for YouTube parties: every phone plays
 * the video from YouTube itself, so all that has to travel is tiny: what plays, and where it is on a
 * clock everyone shares.
 *
 *  - The shared clock is this relay's: every phone (the DJ's too) syncs to it with the same
 *    request/answer exchange the DJ phone answers on Wi-Fi ({"t":"sync"}).
 *  - The DJ phone connects as host (/ws/<code>?role=host&key=<secret>) and sends the party's state
 *    with times already on the relay's clock; the relay hands it to every guest.
 *  - Guests open https://<relay>/p/<code>: the same page the DJ phone serves on Wi-Fi.
 *
 * Nothing is stored beyond the party itself, and it's gone when the DJ ends it.
 */

const MAX_GUESTS = 64;
const MAX_MESSAGE = 16 * 1024;
const CODE = /^[a-z0-9]{6,16}$/;
const FORGET_AFTER_MS = 24 * 3600 * 1000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split('/').filter(Boolean);
    if (parts[0] === 'ws' && parts.length === 2 && CODE.test(parts[1])) {
      if (request.headers.get('Upgrade') !== 'websocket') return new Response('WebSocket only', { status: 426 });
      const party = env.PARTY.get(env.PARTY.idFromName(parts[1]));
      return party.fetch(request);
    }
    if (parts[0] === 'p' && parts.length === 2 && CODE.test(parts[1])) {
      return env.ASSETS.fetch(new Request(new URL('/', url), request)); // index.html
    }
    if (parts.length >= 1 && parts[parts.length - 1] === 'hearby-web.js') {
      return env.ASSETS.fetch(new Request(new URL('/hearby-web.js', url), request));
    }
    if (parts.length === 0) return new Response('Hearby relay is running.', { headers: { 'content-type': 'text/plain' } });
    return new Response('Not found', { status: 404 });
  },
};

/** One party: its DJ, its guests, and the latest state. Uses WebSocket hibernation (free plan). */
export class Party {
  constructor(ctx, env, now = () => Date.now()) {
    this.ctx = ctx;
    this.now = now;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const role = url.searchParams.get('role') === 'host' ? 'host' : 'guest';
    if (role === 'host') {
      const key = url.searchParams.get('key') || '';
      if (key.length < 16) return new Response('key required', { status: 403 });
      const owner = await this.ctx.storage.get('key');
      if (owner && owner !== key) return new Response('This party code is taken.', { status: 403 });
      if (!owner) await this.ctx.storage.put('key', key);
      await this.ctx.storage.setAlarm(this.now() + FORGET_AFTER_MS); // a party left behind is forgotten
      for (const old of this.ctx.getWebSockets('host')) old.close(1000, 'replaced');
    } else if (this.ctx.getWebSockets('guest').length >= MAX_GUESTS) {
      return new Response('This party is full.', { status: 503 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server, [role]);
    const id = Math.floor(Math.random() * 0x7fffffff);
    server.serializeAttachment({ role, id, joined: false });
    if (role === 'guest') {
      const hello = await this.hello(id);
      if (hello) server.send(hello);
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async hello(id) {
    const info = await this.ctx.storage.get('info');
    if (!info) return null; // the DJ isn't here yet: hello follows when it is
    return JSON.stringify({ t: 'hello', id, stream: info.stream, host: info.host, delay: 350, remote: true });
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== 'string' || message.length > MAX_MESSAGE) return;
    let m;
    try { m = JSON.parse(message); } catch (e) { return; }
    if (!m || typeof m !== 'object') return;
    if (m.t === 'sync') {
      // The shared clock: the relay's, in µs. Received and sent count as the same instant.
      const us = this.now() * 1000;
      if (typeof m.c === 'number' && isFinite(m.c)) ws.send(JSON.stringify({ t: 'sync', c: m.c, r: us, s: us }));
      return;
    }
    const me = ws.deserializeAttachment() || {};
    if (me.role === 'host') await this.fromHost(m, message);
    else await this.fromGuest(ws, me, m);
  }

  async fromHost(m, message) {
    switch (m.t) {
      case 'info': {
        const info = { stream: Number(m.stream) || 0, host: String(m.host || 'DJ').slice(0, 64) };
        await this.ctx.storage.put('info', info);
        for (const g of this.ctx.getWebSockets('guest')) {
          const a = g.deserializeAttachment() || {};
          const hello = await this.hello(a.id);
          if (hello) g.send(hello);
        }
        break;
      }
      case 'state': {
        if (message !== this.lastState) {
          this.lastState = message;
          await this.ctx.storage.put('state', message);
        }
        for (const g of this.ctx.getWebSockets('guest')) if ((g.deserializeAttachment() || {}).joined) g.send(message);
        break;
      }
      case 'bye': {
        const bye = JSON.stringify({ t: 'bye', msg: String(m.msg || 'The DJ ended the party.').slice(0, 200) });
        for (const g of this.ctx.getWebSockets('guest')) { g.send(bye); g.close(1000, 'party over'); }
        await this.ctx.storage.deleteAll();
        this.lastState = undefined;
        break;
      }
    }
  }

  async fromGuest(ws, me, m) {
    if (m.t === 'hello') {
      me.joined = true;
      ws.serializeAttachment(me);
      const state = this.lastState || (await this.ctx.storage.get('state'));
      if (state) ws.send(state);
    } else if (m.t === 'leave') {
      ws.close(1000, 'left');
    }
  }

  async alarm() {
    if (this.ctx.getWebSockets().length > 0) {
      await this.ctx.storage.setAlarm(this.now() + FORGET_AFTER_MS); // still going
      return;
    }
    await this.ctx.storage.deleteAll();
    this.lastState = undefined;
  }

  async webSocketClose(ws, code) {
    try { ws.close(code === 1005 ? 1000 : code, 'closed'); } catch (e) { /* already closed */ }
  }

  async webSocketError(ws) {
    try { ws.close(1011, 'error'); } catch (e) { /* already closed */ }
  }
}
