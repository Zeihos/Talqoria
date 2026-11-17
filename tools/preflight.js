'use strict';
/** Read-only local readiness check for TALQORIA closed alpha on a DIRECT Paper server.
 * No network calls, no writes, no automatic EULA acceptance. Node.js >=20.
 */
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const VERSION = '0.13.0';
const LEVEL = { PASS: 0, WARN: 1, BLOCK: 2 };
function readProperties(data) {
  const result = Object.create(null);
  for (const line of data.split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith('#') || t.startsWith('!')) continue;
    const index = t.indexOf('=');
    if (index === -1) continue;
    result[t.slice(0, index).trim().toLowerCase()] = t.slice(index + 1).trim();
  }
  return result;
}
function getEntryFromJar(bytes, name) {
  if (bytes.length < 22) throw Error('архив слишком короткий');
  // Find the last valid central directory marker (ZIP comment: <=65535 bytes).
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (bytes.readUInt32LE(i) === 0x06054b50 && i + 22 + bytes.readUInt16LE(i + 20) === bytes.length) { eocd = i; break; }
  }
  if (eocd < 0) throw Error('нет корректной центральной директории ZIP');
  if (bytes.readUInt16LE(eocd + 4) || bytes.readUInt16LE(eocd + 6)) throw Error('многодисковые ZIP не поддерживаются');
  const count = bytes.readUInt16LE(eocd + 10);
  const length = bytes.readUInt32LE(eocd + 12);
  let cursor = bytes.readUInt32LE(eocd + 16);
  if (cursor + length > eocd || count > 20000) throw Error('ошибка границ ZIP');
  for (let n = 0; n < count; n++) {
    if (cursor + 46 > eocd || bytes.readUInt32LE(cursor) !== 0x02014b50) throw Error('неверная запись ZIP');
    const compression = bytes.readUInt16LE(cursor + 10);
    const compressed = bytes.readUInt32LE(cursor + 20);
    const uncompressed = bytes.readUInt32LE(cursor + 24);
    const filenameLen = bytes.readUInt16LE(cursor + 28);
    const extraLen = bytes.readUInt16LE(cursor + 30);
    const commentLen = bytes.readUInt16LE(cursor + 32);
    const localOffset = bytes.readUInt32LE(cursor + 42);
    const recordEnd = cursor + 46 + filenameLen + extraLen + commentLen;
    if (recordEnd > eocd) throw Error('ошибка имени файла ZIP');
    const filename = bytes.subarray(cursor + 46, cursor + 46 + filenameLen).toString('utf8');
    cursor = recordEnd;
    if (filename !== name) continue;
    if (uncompressed > 64 * 1024 || compressed > 64 * 1024) throw Error('слишком большой plugin.yml');
    if (localOffset + 30 > bytes.length || bytes.readUInt32LE(localOffset) !== 0x04034b50) throw Error('нет локального заголовка');
    const begin = localOffset + 30 + bytes.readUInt16LE(localOffset + 26) + bytes.readUInt16LE(localOffset + 28);
    if (begin + compressed > bytes.length) throw Error('неверный размер содержимого');
    const raw = bytes.subarray(begin, begin + compressed);
    const payload = compression === 0 ? raw : compression === 8 ? zlib.inflateRawSync(raw, { maxOutputLength: 64 * 1024 }) : null;
    if (!payload || payload.length !== uncompressed) throw Error('неподдерживаемое сжатие или неверный размер');
    return payload.toString('utf8');
  }
  return null;
}
function check(root) {
  const checks = [];
  const record = (status, code, message) => checks.push({ status, code, message });
  const server = path.resolve(root, 'server');
  function exists(relative) { return fs.existsSync(path.join(server, relative)); }
  function read(relative) { return fs.readFileSync(path.join(server, relative), 'utf8'); }
  const settingsPath = 'server.properties';
  if (!exists(settingsPath)) {
    record('BLOCK', 'SERVER_PROPERTIES', 'server/server.properties отсутствует: сначала запустите Paper один раз, затем настройте файл');
  } else {
    const config = readProperties(read(settingsPath));
    for (const [key, value] of [['online-mode', 'true'], ['white-list', 'true'], ['enforce-whitelist', 'true'], ['enable-rcon', 'false']]) {
      record(config[key] === value ? 'PASS' : 'BLOCK', `PROPERTY_${key.toUpperCase().replaceAll('-', '_')}`,
        `${key} должен быть ${value}; сейчас ${config[key] === undefined ? '(не указан)' : config[key]}`);
    }
    if (config['server-ip'] && !['127.0.0.1', 'localhost'].includes(config['server-ip'])) {
      record('WARN', 'SERVER_IP', 'Указан IP для привязки; убедитесь, что сервер закрыт для посторонних');
    }
    if (config['enable-command-block'] === 'true') record('WARN', 'COMMAND_BLOCK', 'Для первого теста рекомендуется отключить командные блоки');
    if (config['pvp'] !== 'false') record('WARN', 'PVP', 'Для закрытого теста рекомендуется pvp=false');
  }
  if (!exists('eula.txt')) record('BLOCK', 'EULA', 'eula.txt отсутствует: изучите EULA после первого запуска Paper');
  else record(readProperties(read('eula.txt')).eula === 'true' ? 'PASS' : 'BLOCK', 'EULA', 'Только владелец сервера может вручную принять EULA (eula=true)');
  if (!exists('paper.jar')) record('BLOCK', 'PAPER_JAR', 'Положите совместимый Paper в server/paper.jar');
  else {
    try {
      const paperFile = path.join(server, 'paper.jar');
      const info = fs.statSync(paperFile);
      if (!info.isFile() || info.size < 50_000 || info.size > 1500 * 1024 * 1024)
        throw Error('файл отсутствует, слишком мал или слишком велик');
      const manifest = getEntryFromJar(fs.readFileSync(paperFile), 'META-INF/MANIFEST.MF');
      if (!manifest || !/^Main-Class:\s*[^\r\n]+/mi.test(manifest))
        throw Error('в JAR нет исполняемого META-INF/MANIFEST.MF');
      record('PASS', 'PAPER_JAR', 'Исполняемый JAR читается; версию и подлинность Paper нужно проверить вручную');
    } catch (ex) { record('BLOCK', 'PAPER_JAR', `Paper JAR повреждён или не исполняемый: ${ex.message}`); }
  }
  if (!exists('plugins/TalqoriaCore.jar')) record('BLOCK', 'PLUGIN_JAR', 'Соберите TalqoriaCore через Maven и разместите JAR в server/plugins');
  else {
    try {
      const pluginFile = path.join(server, 'plugins/TalqoriaCore.jar');
      if (fs.statSync(pluginFile).size > 128 * 1024 * 1024) throw Error('JAR слишком большой для проверки');
      const descriptor = getEntryFromJar(fs.readFileSync(pluginFile), 'plugin.yml');
      if (descriptor === null) throw Error('plugin.yml отсутствует');
      const name = descriptor.match(/^name:\s*['"]?([^'"\r\n]+)/m)?.[1]?.trim();
      const version = descriptor.match(/^version:\s*['"]?([^'"\r\n]+)/m)?.[1]?.trim();
      const main = descriptor.match(/^main:\s*['"]?([^'"\r\n]+)/m)?.[1]?.trim();
      record(name === 'TalqoriaCore' && version === VERSION && main === 'ru.talqoria.core.TalqoriaCorePlugin' ? 'PASS' : 'BLOCK',
        'PLUGIN_JAR', `Ожидается TalqoriaCore ${VERSION}, класс ru.talqoria.core.TalqoriaCorePlugin. Найдено ${name ?? '?'} ${version ?? '?'} ${main ?? '?'}`);
    } catch (ex) { record('BLOCK', 'PLUGIN_JAR', `Не удаётся проверить JAR: ${ex.message}`); }
  }
  // Missing primary with a surviving backup must never be interpreted as a new economy.
  const walletPrimary = 'plugins/TalqoriaCore/players.yml';
  const walletBackup = 'plugins/TalqoriaCore/players.yml.bak';
  if (!exists(walletPrimary) && exists(walletBackup))
    record('BLOCK', 'WALLET_RECOVERY', 'Основной players.yml отсутствует, но найден .bak: экономику нельзя обнулять; сверить файлы вручную');
  else if (exists(walletPrimary) && exists(walletBackup)) {
    const primary = fs.statSync(path.join(server, walletPrimary));
    record(primary.size === 0 ? 'BLOCK' : 'WARN', 'WALLET_RECOVERY',
      primary.size === 0 ? 'players.yml пуст, несмотря на наличие .bak — проверьте кошельки вручную' :
      'Найдена резервная копия кошелька; содержимое YAML и правильность балансов этим скриптом не проверяются');
  } else if (exists(walletPrimary))
    record('WARN', 'WALLET_BACKUP', 'Основной кошелёк найден, но .bak отсутствует; сделайте резервную копию на остановленном сервере');
  else record('PASS', 'WALLET_RECOVERY', 'Кошельков и резервной копии пока нет (допустимо для нового сервера)');
  // A missing primary file WITH a surviving backup is recovery, never an empty new server.
  for (const [label, file] of [['TERRITORIES', 'territories.xml'], ['QUESTS', 'quests.xml']]) {
    const main = `plugins/TalqoriaCore/${file}`;
    const backup = `${main}.bak`;
    if (!exists(main) && exists(backup))
      record('BLOCK', `${label}_RECOVERY`, `${file} отсутствует, но .bak существует; только ручная сверка, не запускать изменения`);
    else if (exists(main) && fs.statSync(path.join(server, main)).size === 0)
      record('BLOCK', `${label}_RECOVERY`, `${file} пустой; проверьте сохранение и резервную копию`);
    else if (exists(main) && exists(backup))
      record('PASS', `${label}_RECOVERY`, `${file} и .bak найдены; содержимое проверит сам плагин`);
    else if (exists(main))
      record('WARN', `${label}_BACKUP`, `${file} без .bak — сохраните офлайн-копию после остановки`);
    else record('PASS', `${label}_RECOVERY`, `${file} ещё не создан (допустимо для нового сервера)`);
  }
  if (exists('plugins/TalqoriaCore/market-pending.xml')) record('BLOCK', 'MARKET_PENDING', 'Найдена незавершённая сделка — необходима ручная сверка, не удаляйте файл');
  else record('PASS', 'MARKET_PENDING', 'Незавершённая сделка не обнаружена (это не проверка целостности кошельков)');
  // For the conservative alpha, trades are blocked even when the operator deliberately enables them.
  const pluginConfig = 'plugins/TalqoriaCore/config.yml';
  if (exists(pluginConfig)) {
    const cfg = read(pluginConfig);
    const market = cfg.match(/(?:^|\n)market:\s*\n((?:[ \t]+[^\n]*\n?)*)/);
    if (market && /(?:^|\n)\s+enabled:\s*true(?:\s*(?:#.*)?)?$/m.test(market[1]))
      record('BLOCK', 'ALPHA_MARKET', 'market.enabled=true: торговля предметами в закрытой альфе пока не разрешена');
    else record('PASS', 'ALPHA_MARKET', 'Торговля не включена');
  } else record('WARN', 'ALPHA_MARKET', 'config.yml плагина ещё не создан — после первого запуска проверьте market.enabled=false');
  const worldEntries = exists('world/level.dat') || exists('world/level.dat_old');
  record(worldEntries ? 'PASS' : 'WARN', 'WORLD', worldEntries ? 'Файлы основного мира найдены' : 'Мир ещё не создан; Paper создаст его при первом запуске');
  // This check does not establish staff log integrity, backup freshness or firewall safety.
  const blocked = checks.filter(x => x.status === 'BLOCK').length;
  const warned = checks.filter(x => x.status === 'WARN').length;
  return { version: VERSION, ready: blocked === 0, blocked, warned, checks };
}
function runCli(argv) {
  const rootArg = argv.find(x => !x.startsWith('--')) || path.resolve(__dirname, '..');
  const root = path.resolve(rootArg);
  const report = check(root);
  if (argv.includes('--json')) console.log(JSON.stringify(report, null, 2));
  else {
    console.log(`TALQORIA v${VERSION} / closed-alpha preflight (read-only)\nProject: ${root}`);
    for (const c of report.checks) console.log(`[${c.status.padEnd(5)}] ${c.code}: ${c.message}`);
    console.log(`\nBlocks: ${report.blocked}; warnings: ${report.warned}; ${report.ready ? 'READY FOR MANUAL TESTS' : 'NOT READY'}`);
    console.log('Preflight is NOT an integration test, network security audit, or Paper compatibility proof.');
  }
  return report.ready ? 0 : 2;
}
if (require.main === module) {
  try { process.exitCode = runCli(process.argv.slice(2)); }
  catch (e) { console.error('[BLOCK] PREFLIGHT_ERROR:', e.message); process.exitCode = 3; }
}
module.exports = { check, readProperties, getEntryFromJar, runCli, VERSION };
