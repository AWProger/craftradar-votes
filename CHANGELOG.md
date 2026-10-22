# Changelog

## 1.1.0 — кандидат (не опубликован)

Совместимость:
* Один jar для серверов 1.7.10 … 26.x на Java 8+ (было: только 1.20+ на Java 21). Статусы версий — `docs/compatibility/minecraft-matrix.md`.
* Bytecode Java 8 (`--release 8`), компиляция клея против spigot-api 1.8.8; `api-version: '1.13'` (было `'1.20'`).
* Исправлено: 1.0.0 не загружался на Paper 1.17–1.20.4 с Java 17 (`UnsupportedClassVersionError`).
* Исправлено: выдача денег на Spigot/старом Paper падала с `NoSuchMethodError` (`getOfflinePlayerIfCached` — только Paper 1.16+).
* Исправлено: с classic Votifier 1.9 каждый голос падал на `getAdditionalData()`.
* Vault < 1.4 (методы с `String`) — fallback.
* Material: `isLegacy`/`isAir`/`isItem` только если есть в API сервера.

Протокол и поведение (без изменений протокола сайта):
* HTTP-клиент: `java.net.http` → `HttpURLConnection` (HTTP/1.1, без редиректов, тайм-ауты 5/10 с, без тихого повтора POST).
* `key_id` проверяется как `^[A-Za-z0-9_-]{4,24}$` (было 4–64; сайт принимает не длиннее 24).
* Формат `rewards-state.json`, очереди `queue/`/`rejected/` и `config.yml` не изменились: обновление 1.0.0 → 1.1.0 проверено на реальном сервере (конфиг не тронут, очередь доставлена, дедуп сохранился).

Сборка:
* Модули `core` (без Bukkit) и `bukkit`; gson 2.10.1 затенён; Gradle Wrapper 8.10.2 с SHA-256; воспроизводимый jar; `verifyJar` (bytecode, plugin.yml, gson, секреты) и `SHA256SUMS`.
* `realserver/`: автоматические прогоны на реальных Paper + NuVotifier/Votifier, мок CraftRadar с проверкой HMAC, тестовый игрок.
* CI (`.github/workflows/ci.yml`): сборка, compile-матрица API 1.6.4 … 26.3, ручной real-server, детектор новых версий. Без публикации.
* Каноничные `docs/PROTOCOL.md`, `docs/REWARDS.md`, `docs/protocol-vectors.json` (+ тест векторов HMAC).

## 1.0.0

Первый выпуск: мост Votifier/NuVotifier → CraftRadar (HMAC, очередь на диске, ретраи, подтверждение доставки, диагностика), локальные награды.
