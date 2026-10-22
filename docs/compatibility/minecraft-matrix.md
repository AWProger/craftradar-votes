# Матрица совместимости CraftRadar Votes 1.1.0

Дата прогона: 2026-10-22. Один артефакт для всех строк: `craftradar-votes-1.1.0.jar` (полная матрица — сборка r1, контрольные строки — финальная r2; разница r1/r2 см. evidence/2026-10-22/README.md) (Java 8 bytecode, `api-version: '1.13'`).
Доказательства: `docs/compatibility/evidence/2026-10-22/*.json` (result.json каждого прогона: список проверок и итог); полные консольные логи остаются у того, кто запускал (`realserver/matrix.sh` → `<work>/<jdk>/<версия>/console-*.log`).

## Что значит «прогон real-server» (`realserver/run_scenario.py`)

Настоящий сервер Paper указанной версии на указанной Java, headless, offline-mode, только 127.0.0.1; настоящий NuVotifier 2.7.3 (или classic Votifier 1.9); мок CraftRadar проверяет `X-CR-Key`, окно ±300 с и HMAC каждого запроса. Проверки (35 без игрока, 41 с игроком):
первый старт с пустым конфигом (понятная ошибка, мост выключен, `/crvotes status`), чистая остановка; голос NuVotifier v2 с `crdv1`-корреляцией и голос v1 (RSA) доставлены; первый ответ 503 → повтор; все подписи валидны; диагностика `crdg1` → `diagnostic_ack`, награды не выдаются; голос офлайн-игрока → награда в очереди; повтор того же голоса → `REJECTED_DUPLICATE`; сайт недоступен → голос в `queue/` на диске → рестарт → очередь восстановлена и доставлена; конфиг не перезаписан; дедуп пережил рестарт; нет `NoSuchMethodError`/`NoClassDefFoundError`/`UnsupportedClassVersionError` и стеков `ru.craftradar`. С игроком (mineflayer 4.39): вход → выданы команда-награда (`say`), предмет (DIAMOND в инвентаре) и сообщение; голос онлайн → предмет сразу; `GRANTED` в журнале.

## Матрица

| MC | Сервер | Java | API для компиляции/связывания | Votifier | Сборка | Запуск | Функц. тесты | Ограничения | Статус |
|---|---|---|---|---|---|---|---|---|---|
| 1.6.4 | CraftBukkit (нет законного источника) | 8 (эксп.) | Bukkit 1.6.4-R2.0: компиляция OK, ApiLinkCheck 0 missing (1 guarded), загрузка 277 классов на Java 8 `-Xverify:all` OK | classic 1.9 (собран из исходников) | ✓ | — | — | real-server невозможен у нас, см. known-limitations #1 | **NOT_TESTED** (BUILD_VERIFIED по API) |
| 1.7.10 | Paper(Spigot) 1.7.10-2025 | 8 | — | NuVotifier 2.7.3 | ✓ | ✓ | 35/35 | игрок не проверен (#5) | **PARTIALLY_SUPPORTED** (мост/очередь/награды в очереди — да, выдача игроку — не проверена) |
| 1.7.10 | Paper 1.7.10-2025 | 8 | — | classic Votifier 1.9 (v1) | ✓ | ✓ | 35/35 | #3, #5 | **PARTIALLY_SUPPORTED** |
| 1.8.8 | Paper 1.8.8-445 | 8 | spigot-api 1.8.8 (основная) | NuVotifier 2.7.3 | ✓ | ✓ | 41/41 (r1 и финальный jar) | — | **SUPPORTED_AND_TESTED** |
| 1.8.8 | Paper 1.8.8-445 | 8 | — | classic Votifier 1.9 (v1) | ✓ | ✓ | 41/41 | #3 | **SUPPORTED_AND_TESTED** (v1) |
| 1.8.8 | Paper 1.8.8-445 | 8 | — | нет | ✓ | ✓ | 7/7 (предупреждение, плагин жив) | — | graceful degradation ✓ |
| 1.12.2 | Paper 1.12.2-1620 | 8 | spigot-api 1.12.2 ✓ | NuVotifier 2.7.3 | ✓ | ✓ | 41/41 | #8 | **SUPPORTED_AND_TESTED** |
| 1.12.2 | Paper 1.12.2-1620 | 8 | — | classic Votifier 1.9 (v1) | ✓ | ✓ | 41/41 (финальный jar) | #3, #8 | **SUPPORTED_AND_TESTED** (v1) |
| 1.13.2 | Paper 1.13.2-657 | 8 | spigot-api 1.13.2 ✓ | NuVotifier 2.7.3 | ✓ | ✓ | 41/41 | — (нет `isAir` → fallback по имени) | **SUPPORTED_AND_TESTED** |
| 1.16.5 | Paper 1.16.5-794 | 8 | spigot-api 1.16.5 ✓ | NuVotifier 2.7.3 | ✓ | ✓ | 41/41 | — | **SUPPORTED_AND_TESTED** |
| 1.17.1 | Paper 1.17.1-411 | 17 | — | NuVotifier 2.7.3 | ✓ | ✓ | 41/41 | — | **SUPPORTED_AND_TESTED** |
| 1.20.4 | Paper 1.20.4-499 | 17 | paper-api 1.20.4 ✓ | NuVotifier 2.7.3 | ✓ | ✓ | 41/41 | — | **SUPPORTED_AND_TESTED** |
| 1.20.4 | Paper 1.20.4-499 | 21 | — | NuVotifier 2.7.3 | ✓ | ✓ | 41/41: обновление 1.0.0 → 1.1.0 на живых данных | — | **SUPPORTED_AND_TESTED** (upgrade) |
| 1.20.6 | Paper 1.20.6-151 | 21 | — | NuVotifier 2.7.3 | ✓ | ✓ | 41/41 | — | **SUPPORTED_AND_TESTED** |
| 1.21.11 | Paper 1.21.11-132 | 21 | paper-api / spigot-api 1.21.11 ✓ | NuVotifier 2.7.3 | ✓ | ✓ | 41/41 (r1 и финальный jar), без Votifier 7/7, обновление 1.0.0 → 1.1.0 41/41 (финальный jar) | — | **SUPPORTED_AND_TESTED** |
| 26.1.2 | Paper 26.1.2-74 | 25 | — | NuVotifier 2.7.3 | ✓ | ✓ | 41/41 | — | **SUPPORTED_AND_TESTED** |
| 26.2 | Paper 26.2-134 | 25 | — | NuVotifier 2.7.3 | ✓ | ✓ | 35/35 | игрок не проверен (#5) | **PARTIALLY_SUPPORTED** |
| 26.3 | Paper 26.3-213 | 25 | paper-api 26.3.build.214, spigot-api 26.3 ✓ (JDK 25) | NuVotifier 2.7.3 | ✓ | ✓ | 35/35 (r1 и финальный jar) | игрок не проверен (#5) | **PARTIALLY_SUPPORTED** |
| 1.9–1.11, 1.14–1.15, 1.18–1.19 | Paper | 8/17 | — | — | — | — | — | промежуточные, между проверенными соседями без изменений используемого API | **NOT_TESTED** |
| любая | Spigot / CraftBukkit (не Paper) | — | spigot-api 1.8.8 … 26.3 ✓ | — | ✓ | — | — | #6 | **NOT_TESTED** (BUILD_VERIFIED) |

«API для компиляции/связывания» — `./gradlew :bukkit:compileJava -PbukkitApi=…` и `realserver/ApiLinkCheck.java` (все ссылки плагина на Bukkit/Votifier найдены в этом API). Это `BUILD_VERIFIED`, не замена запуску.

## Для сравнения: 1.0.0 (baseline, тот же сценарий)

| MC | Java | Итог | Причина |
|---|---|---|---|
| 1.20.4 | 21 | 41/41 PASS | — |
| 1.21.11 | 21 | 41/41 PASS | — |
| 1.20.4 | 17 | FAIL, плагин не загружен | `UnsupportedClassVersionError` (class 65) |
| 1.16.5 | 8 | FAIL, плагин не загружен | `Unsupported API version 1.20` |

Регрессий 1.1.0 относительно 1.0.0 на версиях, где 1.0.0 работал, нет (те же 41/41).

## Какой файл ставить

Один: `craftradar-votes-<версия>.jar` — для всех серверов из таблицы. Требование к хосту: Java 8 или новее (той, которую требует сам сервер).
