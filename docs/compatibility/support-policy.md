# Политика поддержки

* **Поддерживается** только то, что в [minecraft-matrix.md](minecraft-matrix.md) имеет статус `SUPPORTED_AND_TESTED`: реальный сервер этой версии, реальный NuVotifier/Votifier, голос по протоколу, доставка на мок CraftRadar с проверкой HMAC, рестарт с очередью, награды игроку (где есть тестовый клиент).
* `BUILD_VERIFIED` — компилируется и связывается с API этой версии, реальный сервер не запускался. Это не обещание работы.
* `PARTIALLY_SUPPORTED` — работает с названными ограничениями ([known-limitations.md](known-limitations.md)).
* `NOT_TESTED` — нет доказательств ни в одну сторону. `UNSUPPORTED` — известно, что не работает.
* Минимальная Java сервера — 8. Плагин не обещает работу на Java 6/7 (1.6.x/1.7.x на старой Java).
* Реализации: Paper — основная (проверена). Spigot/CraftBukkit — тот же Bukkit API, но real-server прогона нет (BuildTools не автоматизирован), статус `NOT_TESTED`. Forks (Purpur и т.п.) — по аналогии с Paper, без гарантий.
* Новая версия Minecraft считается поддержанной только после прохождения [adding-minecraft-version.md](adding-minecraft-version.md) и записи в матрицу с путём к логам.
* Выпуск jar — только человек, по [release-checklist.md](release-checklist.md). CI ничего не публикует.

## Синхронизация с сайтом CraftRadar

Канонические копии живут в этом репозитории; на сайте — копии, которые надо обновлять вместе:

| Здесь | На сайте | Что сверять |
|---|---|---|
| `docs/PROTOCOL.md` | `docs/voting-architecture.md`, `includes/voting/ingest.php` | заголовки, HMAC, окно ±300 с, коды ответов, формат `key_id` |
| `docs/protocol-vectors.json` | побайтовая копия (тест сайта) | число и значения векторов (тест здесь: `ProtocolVectorsTest`) |
| `docs/REWARDS.md` | `docs/rewards-contract.md` | ключи `rewards:`, лимиты, статусы журнала (генератор YAML в кабинете) |
| `bukkit/src/main/resources/config.yml` | страница `/votifier.php`, `docs/voting.md` | ключи и значения по умолчанию |
| `docs/compatibility/minecraft-matrix.md` | страница плагина на сайте | список версий |
