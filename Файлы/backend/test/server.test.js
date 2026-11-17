'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const { createApp, ALIVE_MS } = require('../server');

test('status bridge requires authentication, validates and expires heartbeats', async () => {
  let clock = 1_700_000_000_000;
  const server = createApp({ token: 'private-test-token', now: () => clock });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const url = 'http://127.0.0.1:' + server.address().port;
  try {
    assert.equal((await fetch(url + '/api/health')).status, 200);
    assert.equal((await (await fetch(url + '/api/status')).json()).connected, false);
    const send = (data, auth = 'Bearer private-test-token') => fetch(url + '/api/internal/heartbeat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: auth },
      body: JSON.stringify(data)
    });
    const valid = { online: 2, max: 50, version: '26.3' };
    assert.equal((await send(valid, 'Bearer wrong')).status, 401);
    assert.equal((await send({ ...valid, online: -1 })).status, 422);
    assert.equal((await send(valid)).status, 200);
    const live = await (await fetch(url + '/api/status')).json();
    assert.equal(live.connected, true);
    assert.equal(live.online, 2);
    assert.equal(live.max, 50);
    clock += ALIVE_MS + 1;
    assert.equal((await (await fetch(url + '/api/status')).json()).connected, false);
  } finally {
    await new Promise((resolve, reject) => server.close(err => err ? reject(err) : resolve()));
  }
});

test('public routes reject unsupported methods and unconfigured bridge writes', async () => {
  const server = createApp({ token: '' });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const url = 'http://127.0.0.1:' + server.address().port;
  try {
    assert.equal((await fetch(url + '/api/health', { method: 'POST' })).status, 405);
    assert.equal((await fetch(url + '/not-a-page')).status, 404);
    assert.equal((await fetch(url + '/api/internal/heartbeat', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ online: 1, max: 10, version: '26.3' })
    })).status, 503);
  } finally {
    await new Promise((resolve, reject) => server.close(err => err ? reject(err) : resolve()));
  }
});
