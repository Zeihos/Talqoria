'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const script = path.resolve(__dirname, '../../tools/preflight.js');

test('preflight accepts both the project root and the server directory', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'talqoria-preflight-'));
  try {
    const server = path.join(root, 'server');
    fs.mkdirSync(server);
    for (const candidate of [root, server]) {
      const result = spawnSync(process.execPath, [script, candidate, '--json'], { encoding: 'utf8', timeout: 15000 });
      assert.equal(result.status, 2, result.stderr);
      const data = JSON.parse(result.stdout);
      assert.equal(data.version, '0.13.0');
      assert.equal(data.ready, false);
      assert.ok(data.checks.some(v => v.code === 'SERVER_PROPERTIES' && v.status === 'BLOCK'));
      assert.ok(data.checks.some(v => v.code === 'PAPER_JAR' && v.status === 'BLOCK'));
    }
  } finally {
    fs.rmSync(root, { force: true, recursive: true });
  }
});
