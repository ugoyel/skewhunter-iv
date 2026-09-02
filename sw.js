/* Skew Hunter service worker.
 *
 * Two caching rules, because the app has two kinds of request:
 *   - the shell (app.html, manifest, icons) is cache-first, so the app opens
 *     instantly and works with no connection;
 *   - app_data.json is network-first with a cache fallback, so a phone that is
 *     online always sees the latest 3-minute push and a phone that is not still
 *     opens on the last data it saw.
 *
 * Bump CACHE whenever the shell changes; the old cache is dropped on activate.
 */
const CACHE = 'skewhunter-v1';
const DATA = 'app_data.json';

const SHELL = [
  './app.html',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/maskable-192.png',
  './icons/maskable-512.png',
  './icons/apple-touch-icon.png',
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE)
      // addAll is atomic — one 404 would leave the app with no offline copy at
      // all, so each entry is cached independently and failures are tolerated.
      .then(cache => Promise.all(SHELL.map(url => cache.add(url).catch(() => null))))
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('message', event => {
  if (event.data === 'skip-waiting') self.skipWaiting();
});

async function networkFirst(request) {
  try {
    const response = await fetch(request);
    if (response && response.ok) {
      const cache = await caches.open(CACHE);
      cache.put(request, response.clone());
    }
    return response;
  } catch (err) {
    const cached = await caches.match(request, {ignoreSearch: true});
    if (cached) return cached;
    throw err;
  }
}

async function cacheFirst(request) {
  const cached = await caches.match(request, {ignoreSearch: true});
  if (cached) return cached;
  const response = await fetch(request);
  if (response && response.ok && request.method === 'GET') {
    const cache = await caches.open(CACHE);
    cache.put(request, response.clone());
  }
  return response;
}

self.addEventListener('fetch', event => {
  const {request} = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // The data feed: always try the network first, fall back to what we have.
  if (url.pathname.endsWith('/' + DATA) || url.pathname.endsWith(DATA)) {
    event.respondWith(networkFirst(request));
    return;
  }

  // Navigations: network first so a redeploy is picked up, cache as the
  // offline fallback. Only app.html is substituted — index.html (the desktop
  // Plotly dashboard) is left to fail normally rather than being hijacked.
  if (request.mode === 'navigate') {
    event.respondWith(
      networkFirst(request).catch(() =>
        caches.match(request, {ignoreSearch: true})
          .then(hit => hit || (url.pathname.endsWith('app.html') || url.pathname.endsWith('/')
            ? caches.match('./app.html')
            : undefined))
      )
    );
    return;
  }

  // Everything else in scope: the shell.
  event.respondWith(cacheFirst(request).catch(() => caches.match(request, {ignoreSearch: true})));
});
