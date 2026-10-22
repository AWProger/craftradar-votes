# Архитектура совместимости (решение 2026-10-22)

## Аудит baseline (этап A, коммит d2e4dc7, плагин 1.0.0)

| Что | Факт |
|---|---|
| Сборка | один Gradle-проект (Kotlin DSL), без Wrapper; toolchain и `--release 21` |
| Зависимости | `compileOnly` paper-api 1.20.4, NuVotifier 2.7.1 (JitPack), gson 2.10.1 из рантайма сервера |
| plugin.yml | `api-version: '1.20'` → серверы < 1.20 отказываются грузить плагин |
| Bytecode | class 65 (Java 21) → Paper 1.20.4 на Java 17 падает: `UnsupportedClassVersionError` (проверено, `runs1/baseline-1.0.0/1.20.4/console-1-first.log`) |
| Java API | records, `java.net.http`, `var`, pattern matching, `List.of`, `Random.nextLong(bound)` (Java 17) — ядро не загрузится на Java 8/11 |
| Paper-only API | `Server.getOfflinePlayerIfCached` в Vault-мосте: на Spigot/старом Paper — `NoSuchMethodError` при выдаче денег |
| Material | `isLegacy()` (1.13+), `isAir()` (1.14+), `isItem()` (1.12+) вызывались напрямую |
| Votifier | `Vote.getAdditionalData()` — только NuVotifier; classic Votifier 1.9 → `NoSuchMethodError` на каждом голосе |
| Тесты | 161 JUnit5, все зелёные; real-server прогонов не было |
| Jar | 119 552 байт, недетерминированный (повторная сборка даёт другой SHA-256) |
| Факт поддержки 1.0.0 | Paper 1.20.4 … 1.21.11 только на Java 21 (real-server: 35/35 на 1.20.4/Java 21) |

## Рассмотренные варианты

* **A. Один jar** — выбран, но не «ради красоты»: эксперимент показал, что он реально загружается и работает на всех проверенных серверах 1.7.10 … 26.3 (см. matrix).
* **B/C. Core + адаптеры поколений / несколько jar** — гипотеза координатора. Отклонена по результатам: весь серверный код плагина — ~15 вызовов Bukkit API, которые существуют без изменений сигнатуры от Bukkit 1.6.4 до Paper 26.3 (проверено `ApiLinkCheck` и компиляцией против каждого API). Различия поколений (Material 1.12/1.13/1.14, Paper-only метод, classic Votifier, Vault < 1.4) закрываются 4 точечными рефлексивными проверками с fallback. Отдельные jar дали бы владельцу выбор «какой файл качать» и удвоили матрицу без выигрыша.
* **Многомодульность сохранена** там, где она полезна: `core` (без серверных зависимостей, Java 8) и `bukkit` (клей). Если будущая версия сломает бинарную совместимость (например, Material перестанет быть enum без commodore-переписывания в Paper), добавляется модуль `bukkit-<поколение>` и второй артефакт — структура к этому готова, сейчас не нужен (YAGNI).

## Слои

| Слой | Где |
|---|---|
| Core: голоса, валидация, идемпотентность, очередь, награды, конфиг | `core/…/votes/*`, `core/…/rewards/*` |
| Transport: HMAC, timestamp, ретраи, ack | `VoteForwarder`, `Signer`, `Backoff`, `DiskQueue` (HttpURLConnection, Java 8) |
| Server API adapter + compatibility layer | `bukkit/…/CraftRadarVotesPlugin` (+ `additionalData()`), `ItemGranter.usable()`, `EconomyBridge.resolve()` |
| Reward providers | `CommandExecutor` (команды/сообщения), `ItemGranter`, `EconomyBridge` (Vault, рефлексия) |
| Diagnostics | `Correlation`, `/crvotes status`, `rewards.log` |
| Build & test matrix | `bukkit/build.gradle.kts` (`verifyJar`, `-PbukkitApi`), `realserver/`, `.github/workflows/ci.yml` |

## Правила для кода

* `core` и `bukkit` main компилируются `--release 8`: javac сам запрещает API Java 9+. Тесты — Java 21.
* `bukkit` компилируется против **spigot-api 1.8.8** (старейший API в стабильном репозитории), CI дополнительно компилирует против Bukkit 1.6.4 … Paper 26.3 и проверяет связывание (`realserver/ApiLinkCheck.java`).
* Новые методы API — только через рефлексию с fallback и комментарием «с какой версии». NMS/внутренние классы сервера не используются.
* gson затенён (`ru.craftradar.votes.lib.gson`): у 1.7/1.8 свой старый gson, у 26.x — новый; наш не конфликтует. `verifyJar` запрещает незатенённые ссылки.
* `plugin.yml api-version: '1.13'`: на 1.13+ — без legacy-материалов, на 1.7–1.12 ключ игнорируется. Сервер с `api-version` новее своей версии плагин бы отверг — поэтому не повышаем.
