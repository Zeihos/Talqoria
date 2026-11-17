#!/usr/bin/env node
'use strict';
// Source-tree hygiene checks for the public TALQORIA repository. Node.js 20+.
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const problems = [];
const requireFile = (file) => {
  const full = path.join(root, file);
  if (!fs.existsSync(full) || !fs.statSync(full).isFile()) {
    problems.push('Отсутствует обязательный файл: ' + file);
    return '';
  }
  return fs.readFileSync(full, 'utf8');
};
const pom = requireFile('plugin/pom.xml');
const plugin = requireFile('plugin/src/main/resources/plugin.yml');
const packageJson = requireFile('backend/package.json');
const backend = requireFile('backend/server.js');
const preflight = requireFile('tools/preflight.js');
const build = requireFile('BUILD_PLUGIN_WINDOWS.bat');
const env = requireFile('CHECK_ENV_WINDOWS.bat');
const backup = requireFile('BACKUP_OFFLINE_WINDOWS.bat');
const start = requireFile('backend/start-windows.bat');
const readme = requireFile('README.md');
const check = (condition, issue) => { if (!condition) problems.push(issue); };
const version = pom.match(/<artifactId>talqoria-core<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1];
check(Boolean(version), 'В Maven POM отсутствует версия TalqoriaCore');
if (version) {
  const short = version.replace(/\.0$/, '');
  check(plugin.includes("version: '" + version + "'"), 'Версия plugin.yml не совпадает с ' + version);
  check(preflight.includes("const VERSION = '" + version + "'"), 'Версия предзапускной диагностики не совпадает с ' + version);
  check(backend.includes("version: '" + version + "'"), 'Версия API не совпадает с ' + version);
  try { check(JSON.parse(packageJson).version === version, 'Версия backend/package.json не совпадает с ' + version); }
  catch { problems.push('Некорректный backend/package.json'); }
  check(build.includes('talqoria-core-' + version + '.jar'), 'Сборочный BAT ссылается на устаревший JAR');
  for (const [name, content] of [['BACKUP_OFFLINE_WINDOWS.bat', backup], ['CHECK_ENV_WINDOWS.bat', env], ['backend/start-windows.bat', start]]) {
    check(content.includes('v' + short), 'В ' + name + ' указан неактуальный номер версии');
  }
  check(readme.includes(version), 'README не отражает текущую версию TalqoriaCore');
}
for (const filename of ['README.md', 'docs/INSTALL.md', 'docs/ROADMAP.md', 'CONTRIBUTING.md', 'SECURITY.md', 'CHANGELOG.md']) {
  const contents = requireFile(filename);
  const refs = /!?\[[^\]]*]\(([^)\s]+)(?:\s+"[^"]*")?\)|(?:src|href)="([^"]+)"/g;
  let match;
  while ((match = refs.exec(contents))) {
    const href = match[1] || match[2];
    if (/^(?:https?:|mailto:|#|data:)/.test(href)) continue;
    const target = decodeURIComponent(href.split('#')[0].split('?')[0]);
    if (!target) continue;
    const resolved = path.resolve(root, path.dirname(filename), target);
    if (resolved !== root && !resolved.startsWith(root + path.sep)) {
      problems.push('Ссылка выходит за пределы проекта: ' + filename + ' → ' + href);
    } else if (!fs.existsSync(resolved)) {
      problems.push('Найдена несуществующая ссылка: ' + filename + ' → ' + href);
    }
  }
}
for (const file of [
  'site/index.html', 'site/styles.css', 'site/app.js',
  'PREFLIGHT_WINDOWS.bat', 'PREFLIGHT_LINUX.sh',
  'plugin/src/test/java/ru/talqoria/core/PlacedBlockLedgerSelfTest.java',
  '.github/workflows/verify.yml'
]) requireFile(file);
try {
  const names = execFileSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
  for (const file of names) {
    if (/(?:^|\/)(?:target|node_modules|world|logs)\/|(?:^|\/)players\.yml|\.class$|\.log(?:\.gz)?$|\.mca$|\.jar$|\.zip$/.test(file))
      problems.push('Запрещённый игровой или сгенерированный файл в Git: ' + file);
  }
} catch (error) {
  problems.push('Не удалось проверить список Git-файлов: ' + error.message);
}
if (problems.length) {
  console.error('[TALQORIA] Проверка репозитория: ' + problems.length + ' проблем.');
  for (const issue of problems) console.error('  - ' + issue);
  process.exitCode = 1;
} else {
  console.log('[TALQORIA] Структура, ссылки, версии и список Git-файлов проверены.');
}
