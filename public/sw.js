// Minimal offline app shell: the app keeps loading with no network.
const CACHE = 'meetsnap-v2';
self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(['/', '/index.html', '/favicon.svg', '/icon-192.png', '/apple-touch-icon.png', '/manifest.webmanifest'])));
  self.skipWaiting();
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (e.request.mode === 'navigate') {
    // Network first for the page, fall back to the cached shell.
    e.respondWith(
      fetch(e.request)
        .then((r) => { caches.open(CACHE).then((c) => c.put('/index.html', r.clone())); return r; })
        .catch(() => caches.match('/index.html')),
    );
    return;
  }
  // Cache first for hashed static assets.
  e.respondWith(
    caches.match(e.request).then((hit) => hit || fetch(e.request).then((r) => {
      if (r.ok) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); }
      return r;
    })),
  );
});
