'use strict';
// Read-only analysis of Paper latest.log.
const fs = require('node:fs');
const path = require('node:path');
const input = process.argv[2] || path.join(__dirname, '..', 'server', 'logs', 'latest.log');
let contents;
try { contents = fs.readFileSync(input, 'utf8'); }
catch (error) { console.error('[TALQORIA] Cannot read ' + input + ': ' + error.message); process.exit(2); }
const lines = contents.split(/\r?\n/);
const patterns = {
  ready: /Done \([\d.,]+s\)! For help/,
  core: /\[TalqoriaCore\] Enabling TalqoriaCore/,
  insecure: /SERVER IS RUNNING IN OFFLINE\/INSECURE MODE/,
  freeze: /The server has not responded for \d+ seconds/,
  keepalive: /sending keepalive response .* out-of-order/,
  slowCommand: /SLOW_COMMAND tq /,
  slowLedger: /SLOW_LEDGER /,
  command: /issued server command: \/tq /,
  issue: /\[TalqoriaCore\].*(ERROR|FAILED|LOCKED|INVALID)/i,
};
const found = {};
for (const key of Object.keys(patterns)) found[key] = [];
lines.forEach((line, i) => {
  for (const [key, regex] of Object.entries(patterns)) if (regex.test(line))
    found[key].push({line: i+1, text: line.trim()});
});
console.log('Talqoria read-only log analysis: ' + path.resolve(input));
for (const [key, matches] of Object.entries(found)) {
  console.log(key + ': ' + matches.length);
  if (['freeze','keepalive','slowCommand','slowLedger','issue'].includes(key))
    matches.slice(0,10).forEach(m=>console.log('  L'+m.line+': '+m.text));
}
console.log('Command requests do not prove that the command caused a stall; use the Paper spark profiler.');
process.exitCode = found.freeze.length || found.keepalive.length || found.issue.length ? 1 : 0;
