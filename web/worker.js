/**
 * 错题集 · 一体部署（Cloudflare Worker + 静态资源）
 *
 * 一个 Worker 同时干两件事：
 *   1. 把 dist/ 里的网页当静态资源发出去（Workers Static Assets，免费不限次）
 *   2. /proxy 转发 AI 请求，绕过 CORS
 *
 * 好处：页面和代理同一个域名，前端「代理地址」留空就能用，
 * 没有跨域、不需要额外的 Pages 项目、也不用记两个网址。
 *
 * 安全要点：
 *  - 只转发白名单里的域名，别做成开放代理
 *  - 不保存、不记录任何 Key（请求头原样透传）
 *  - 绑了 KV（变量名 RATE）就自动按 IP 限流
 */

const ALLOW_HOSTS = [
  'https://api.deepseek.com',
  'https://open.bigmodel.cn',
  'https://dashscope.aliyuncs.com',
  'https://api.siliconflow.cn',
  'https://api.openai.com',
  'https://api.moonshot.cn',
  'https://ark.cn-beijing.volces.com',
  'https://api.minimax.chat'
];

const DAILY_LIMIT = 300;

function cors() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization,content-type',
    'Access-Control-Allow-Methods': 'POST,OPTIONS',
    'Access-Control-Max-Age': '86400'
  };
}

function json(status, obj) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', ...cors() }
  });
}

async function overLimit(ip, env) {
  if (!env || !env.RATE) return false;              // 没绑 KV 就不限流
  const key = 'r:' + ip + ':' + new Date().toISOString().slice(0, 10);
  const n = parseInt((await env.RATE.get(key)) || '0', 10);
  if (n >= DAILY_LIMIT) return true;
  await env.RATE.put(key, String(n + 1), { expirationTtl: 86400 * 2 });
  return false;
}

async function handleProxy(request, env, url) {
  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: cors() });
  }
  if (request.method !== 'POST') {
    return json(405, { error: '只用 POST' });
  }

  const target = url.searchParams.get('u') || '';
  if (!/^https:\/\//.test(target) || !ALLOW_HOSTS.some(h => target.startsWith(h))) {
    return json(403, { error: '这个地址不在白名单里：' + target });
  }

  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  if (await overLimit(ip, env)) {
    return json(429, { error: '今天的代理额度用完了，明天再来' });
  }

  let upstream;
  try {
    upstream = await fetch(target, {
      method: 'POST',
      headers: {
        'content-type': request.headers.get('content-type') || 'application/json',
        'authorization': request.headers.get('authorization') || ''
      },
      body: request.body
    });
  } catch (e) {
    return json(502, { error: '转发失败：' + ((e && e.message) || e) });
  }

  const headers = new Headers(cors());
  headers.set('content-type', upstream.headers.get('content-type') || 'application/json');
  headers.set('cache-control', 'no-store');
  return new Response(upstream.body, { status: upstream.status, headers });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === '/proxy' || url.pathname.startsWith('/proxy/')) {
      return handleProxy(request, env, url);
    }

    // 其余请求全部交给静态资源（dist/ 里的网页、图标、题库包）
    if (env && env.ASSETS) return env.ASSETS.fetch(request);

    return json(200, {
      ok: true,
      service: '错题集',
      hint: '静态资源没绑定上：wrangler 配置里要写 [assets] directory = "./dist"'
    });
  }
};
