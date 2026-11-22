'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const { createApp } = require('../server');
test('project website is served from source tree', async () => {
  const server = createApp({ token: 'test-token' });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = 'http://127.0.0.1:' + server.address().port;
  try {
    for (const route of ['/', '/index.html', '/styles.css', '/app.js']) {
      const response = await fetch(base + route);
      assert.equal(response.status, 200, route);
      assert.ok((await response.text()).length > 100, route);
    }
    const health = await (await fetch(base + '/api/health')).json();
    assert.equal(health.version, '0.19.0');
  } finally {
    await new Promise((resolve, reject) =>
      server.close(error => error ? reject(error) : resolve()));
  }
});
