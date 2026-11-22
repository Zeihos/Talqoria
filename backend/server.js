'use strict';
// TALQORIA API v0.19 — no runtime dependencies. Node.js 20+.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PUBLIC_FILES = Object.freeze({
  '/': ['index.html', 'text/html; charset=utf-8'],
  '/index.html': ['index.html', 'text/html; charset=utf-8'],
  '/styles.css': ['styles.css', 'text/css; charset=utf-8'],
  '/app.js': ['app.js', 'text/javascript; charset=utf-8'],
});
const ALIVE_MS = 75_000;

function equalTokens(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || !b) return false;
  const left = Buffer.from(a), right = Buffer.from(b);
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}
function reply(res, code, data, methods = 'GET, HEAD, POST') {
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    'Allow': methods,
  });
  res.end(JSON.stringify(data));
}
async function readJson(req, maxBytes = 2048) {
  let total = 0;
  const parts = [];
  for await (const chunk of req) {
    total += chunk.length;
    if (total > maxBytes) {
      throw Object.assign(new Error('Слишком большой запрос'), { status: 413 });
    }
    parts.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(parts).toString('utf8')); }
  catch { throw Object.assign(new Error('Некорректный JSON'), { status: 400 }); }
}
function validHeartbeat(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const { online, max, version } = value;
  return Number.isSafeInteger(online) && Number.isSafeInteger(max)
    && online >= 0 && max >= online && max <= 100_000
    && typeof version === 'string' && /^[a-zA-Z0-9 ._+\-]{1,32}$/.test(version);
}
function createApp({ token = process.env.TALQORIA_BRIDGE_TOKEN || '',
    siteRoot = path.resolve(__dirname, '../site'),
    newsFile = path.resolve(__dirname, 'data/news.json'),
    marketFile = path.resolve(__dirname, 'data/market.json'),
    now = () => Date.now() } = {}) {
  let heartbeat = null;
  const server = http.createServer(async (req, res) => {
    try {
      // Disallow paths not on the allowlist. Do not expose backend files via static serving.
      const route = (req.url || '').split('?')[0];
      if (route === '/api/health' && req.method === 'GET') {
        return reply(res, 200, { ok: true, service: 'talqoria-api', version: '0.19.0' });
      }
      if (route === '/api/status' && req.method === 'GET') {
        const connected = heartbeat !== null && now() - heartbeat.at >= 0
          && now() - heartbeat.at <= ALIVE_MS;
        return reply(res, 200, {
          connected,
          online: connected ? heartbeat.online : null,
          max: connected ? heartbeat.max : null,
          version: connected ? heartbeat.version : null,
          lastSeen: connected ? new Date(heartbeat.at).toISOString() : null,
        });
      }
      if (route === '/api/news' && req.method === 'GET') {
        const items = JSON.parse(await fs.promises.readFile(newsFile, 'utf8'));
        return reply(res, 200, { items: items.slice(0, 20) });
      }
      if (route === '/api/market' && req.method === 'GET') {
        const offers = JSON.parse(await fs.promises.readFile(marketFile, 'utf8'));
        if (!Array.isArray(offers) || offers.length > 30 || offers.some(v =>
            !v || typeof v.id !== 'string' || !/^[a-z0-9_]{2,32}$/.test(v.id) ||
            typeof v.name !== 'string' || v.name.length > 80 ||
            !Number.isSafeInteger(v.buy) || !Number.isSafeInteger(v.sell) ||
            v.buy <= v.sell || v.sell < 0)) {
          throw new Error('Market catalog is invalid');
        }
        return reply(res, 200, { offers, currency: 'монеты', limitPerTrade: 64, status: 'alpha' });
      }
      if (route === '/api/internal/heartbeat' && req.method === 'POST') {
        if (!token) return reply(res, 503, { error: 'Bridge not configured' });
        const authorization = req.headers.authorization || '';
        if (!authorization.startsWith('Bearer ') ||
            !equalTokens(authorization.slice(7), token)) {
          return reply(res, 401, { error: 'Unauthorized' });
        }
        if (!String(req.headers['content-type'] || '').toLowerCase().startsWith('application/json')) {
          return reply(res, 415, { error: 'Content-Type must be application/json' });
        }
        const data = await readJson(req);
        if (!validHeartbeat(data)) return reply(res, 422, { error: 'Invalid heartbeat' });
        heartbeat = { online: data.online, max: data.max, version: data.version, at: now() };
        return reply(res, 200, { ok: true });
      }
      if (Object.hasOwn(PUBLIC_FILES, route)) {
        if (req.method !== 'GET' && req.method !== 'HEAD') {
          return reply(res, 405, { error: 'Method not allowed' });
        }
        const [file, mime] = PUBLIC_FILES[route];
        const buffer = await fs.promises.readFile(path.join(siteRoot, file));
        res.writeHead(200, {
          'Content-Type': mime,
          'Content-Length': buffer.length,
          'Content-Security-Policy': "default-src 'self'; style-src 'self' https://fonts.googleapis.com 'unsafe-inline'; font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
          'X-Content-Type-Options': 'nosniff',
          'X-Frame-Options': 'DENY',
          'Referrer-Policy': 'no-referrer',
        });
        if (req.method === 'HEAD') return res.end();
        return res.end(buffer);
      }
      if (['/api/status', '/api/health', '/api/news', '/api/market', '/api/internal/heartbeat'].includes(route)) {
        return reply(res, 405, { error: 'Method not allowed' });
      }
      return reply(res, 404, { error: 'Not found' });
    } catch (e) {
      if (e.status) return reply(res, e.status, { error: e.message });
      console.error('[TALQORIA] request failed:', e.message);
      return reply(res, 500, { error: 'Internal server error' });
    }
  });
  return server;
}

if (require.main === module) {
  const host = process.env.TALQORIA_HOST || '127.0.0.1';
  const port = Number(process.env.TALQORIA_PORT || 8080);
  if (!Number.isSafeInteger(port) || port < 1 || port > 65535) {
    console.error('TALQORIA_PORT must be 1..65535'); process.exit(1);
  }
  createApp().listen(port, host, () => {
    console.log(`[TALQORIA] site + API running at http://${host}:${port}`);
    if (!process.env.TALQORIA_BRIDGE_TOKEN) console.warn('[TALQORIA] status bridge disabled: set TALQORIA_BRIDGE_TOKEN');
  });
}
module.exports = { createApp, validHeartbeat, equalTokens, ALIVE_MS };
