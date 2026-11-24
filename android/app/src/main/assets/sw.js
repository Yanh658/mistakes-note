/* 错题集 Service Worker
 * 目标：第二次打开秒开、断网也能开；但页面更新要立刻生效，不能被旧缓存套住。
 * 规则：
 *   /proxy          → 永不缓存（AI 请求）
 *   request.cache=no-store → 永不缓存（题库包这样请求）
 *   页面导航         → 网络优先，失败回落缓存（保证能开）
 *   静态资源         → 缓存优先，后台更新
 */
const VER = 'cuoti-v28';
const SHELL = [
  './',
  './index.html',
  './intro.html',
  './manifest.json',
  './vendor/katex/katex.min.js',
  './vendor/katex/katex.min.css',
  './icon-192.png',
  './icon-512.png',
  './apple-touch-icon.png'
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(VER)
      .then(c => c.addAll(SHELL).catch(() => null))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== VER).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  if (url.origin !== location.origin) return;            // 跨域（含 AI 接口）直接放过
  if (url.pathname.startsWith('/proxy')) return;         // 代理永不缓存
  if (req.cache === 'no-store') return;                  // 明确要求最新的，交给浏览器

  // 页面：网络优先，拿到就顺手更新缓存；失败用缓存兜底
  if (req.mode === 'navigate') {
    e.respondWith(
      fetch(req).then(res => {
        const copy = res.clone();
        caches.open(VER).then(c => c.put(req, copy)).catch(() => { });
        return res;
      }).catch(() => caches.match(req).then(hit => hit || caches.match('./index.html')))
    );
    return;
  }

  // 静态资源：缓存优先
  e.respondWith(
    caches.match(req).then(hit => {
      if (hit) return hit;
      return fetch(req).then(res => {
        if (res && res.ok) {
          const copy = res.clone();
          caches.open(VER).then(c => c.put(req, copy)).catch(() => { });
        }
        return res;
      });
    })
  );
});
