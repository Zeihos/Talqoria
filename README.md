# TALQORIA

Minecraft **Survival+**: поселения, приваты, экономика, задания и сайт проекта.

**Статус:** TalqoriaCore **0.13.0 Developer Alpha**. Исходники собираются через Maven с Paper API. Стабильность на игровом сервере и безопасность торговли пока требуют испытаний; публичный релиз **1.0 не объявлен**.

## Структура

| Путь | Содержимое |
|---|---|
| `plugin/` | Java-исходники TalqoriaCore, Maven, тесты, `plugin.yml` |
| `backend/` | Node.js API для сайта и статуса Paper |
| `site/` | HTML, CSS и JavaScript официального сайта |
| `server/` | Шаблоны конфигурации и скрипты запуска (без мира и данных игроков) |
| `tools/` | Диагностика, проверка перед запуском, установка Maven |
| `assets/` | Логотип, оформление и изображения проекта |
| `.github/workflows/` | Проверка Java, API и сборка JAR |

## Запуск на Windows

1. Установить **JDK 25** для тестируемого Paper 26.3 и **Node.js 20+**. Установленный в `PATH` Maven 3.9+ подходит; `BUILD_PLUGIN_WINDOWS.bat` может установить Maven локально.
2. Запустить `BUILD_PLUGIN_WINDOWS.bat`. После успешной сборки JAR появится в `server/plugins/TalqoriaCore.jar`.
3. Загрузить **совместимую сборку Paper** с [PaperMC](https://papermc.io/downloads/paper) в `server/paper.jar`.
4. Запустить `server/start-windows.bat`. Прочитать EULA Minecraft и принять её вручную, только если согласны. Для приватного тестирования используйте whitelist и `online-mode=true`.
5. Остановить Paper перед обновлением или резервным копированием. Проверить установку через `/tq help`, `/tq profile`, `/tq balance`.
6. Сайт/API запускается отдельно: `START_SITE_WINDOWS.bat` → http://127.0.0.1:8080.

Для проверки окружения — `CHECK_ENV_WINDOWS.bat`, для диагностики готовности — `PREFLIGHT_WINDOWS.bat`, для резервного копирования остановленного сервера — `BACKUP_OFFLINE_WINDOWS.bat`.

## Проверка кода

- Плагин: `mvn -f plugin/pom.xml clean package`
- Backend: `node --test backend/test/*.test.js`
- Рабочий JAR после успешной автоматической сборки доступен в **GitHub Actions → Verify Talqoria → Artifacts**.
- Основной сервер **не запускается в GitHub Actions**; сборка и тесты не означают успешное испытание игрового процесса.

## Важно о данных

Игровые миры, `server.properties`, `eula.txt`, логи, сохранения и персональные данные **не должны находиться в Git**. `server/server.properties.example` — только пример; его нельзя считать готовым продакшен-конфигом.

**Не удаляйте** локальные `plugins/TalqoriaCore/players.yml`, `territories.xml`, `quests.xml`, `placed-blocks.xml` и `placed-blocks.journal` при обновлении. Перед обновлением останавливайте сервер и делайте отдельную резервную копию.

Рынок предметов в Alpha **отключён по умолчанию**: нужна полная интеграционная проверка атомарности операций.

Историческая версия репозитория до очистки зафиксирована в `backup/pre-cleanup-20261117`; исторические коммиты по-прежнему могут содержать файлы игрового мира. Удаление из основной ветки не стирает их из истории Git.
