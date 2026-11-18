'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const script = path.resolve(__dirname, '../../tools/preflight.js');
const { check } = require('../../tools/preflight');

test('preflight accepts both the project root and the server directory', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'talqoria-preflight-'));
  try {
    const server = path.join(root, 'server');
    fs.mkdirSync(server);
    for (const candidate of [root, server]) {
      const result = spawnSync(process.execPath, [script, candidate, '--json'], { encoding: 'utf8', timeout: 15000 });
      assert.equal(result.status, 2, result.stderr);
      const data = JSON.parse(result.stdout);
      assert.equal(data.version, '0.15.0');
      assert.equal(data.ready, false);
      assert.ok(data.checks.some(v => v.code === 'SERVER_PROPERTIES' && v.status === 'BLOCK'));
      assert.ok(data.checks.some(v => v.code === 'PAPER_JAR' && v.status === 'BLOCK'));
    }
  } finally {
    fs.rmSync(root, { force: true, recursive: true });
  }
});

test('preflight blocks missing provenance primary and truncated journal', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'talqoria-provenance-preflight-'));
  try {
    const folder = path.join(root, 'server', 'plugins', 'TalqoriaCore');
    fs.mkdirSync(folder, { recursive: true });
    fs.writeFileSync(path.join(folder, 'placed-blocks.xml.bak'), 'backup sentinel');
    const recovered = check(root);
    assert.equal(recovered.ready, false);
    assert.ok(recovered.checks.some(v => v.code === 'PLACED_LEDGER_RECOVERY' && v.status === 'BLOCK'));
    fs.writeFileSync(path.join(folder, 'placed-blocks.xml'), 'snapshot');
    fs.writeFileSync(path.join(folder, 'placed-blocks.journal'), 'cut-off');
    const damaged = check(root);
    assert.ok(damaged.checks.some(v => v.code === 'BLOCK_JOURNAL' && v.status === 'BLOCK'));
  } finally { fs.rmSync(root, { force: true, recursive: true }); }
});
