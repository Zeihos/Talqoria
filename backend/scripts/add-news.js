'use strict';
// Local operator-only CLI. No public news-writing API is exposed.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const file = path.resolve(__dirname, '../data/news.json');
const title = (process.argv[2] || '').trim();
const body = (process.argv[3] || '').trim();
if (!title || title.length > 120 || !body || body.length > 800) {
  console.error('Пример: node scripts/add-news.js "Заголовок до 120" "Текст до 800"');
  process.exitCode = 1;
} else {
  try {
    const existing = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (!Array.isArray(existing)) throw new Error('news.json must be an array');
    const item = {
      id: crypto.randomUUID(),
      date: new Date().toISOString().slice(0, 10),
      tag: 'Новости',
      title,
      body,
    };
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify([item, ...existing].slice(0, 100), null, 2) + '\n', { flag: 'w', mode: 0o600 });
    fs.renameSync(tmp, file);
    console.log('Опубликовано локально:', title);
  } catch (error) {
    console.error('Не удалось сохранить новость:', error.message);
    process.exitCode = 1;
  }
}
