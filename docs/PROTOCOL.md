# Протокол CraftRadar Votes ↔ CraftRadar (v1)

Каноническая спецификация обмена между плагином **CraftRadar Votes** и сайтом **CraftRadar**.
Сверено с кодом: сайт — `api/vote_ingest.php`, `includes/voting/ingest.php`, `includes/voting.php`, `includes/voting/diagnostics.php`, `includes/voting/votifier_client.php`;
плагин — `Signer`, `Settings`, `Correlation`, `VoteForwarder`, `Backoff`.

**Где канон.** Этот файл (в репозитории плагина) — канон для всего, что видно по проводу. Внутреннее устройство сайта (БД, журналы, лимиты, прокси) здесь описано только настолько, насколько оно меняет ответы. Изменение протокола: сначала PR сюда (+ векторы в `docs/protocol-vectors.json`), затем сайт.

Ключевые слова «ДОЛЖЕН», «НЕ ДОЛЖЕН», «МОЖЕТ» — в смысле RFC 2119.

## 1. Два потока

```
A. CraftRadar → сервер игры: Votifier v1/v2 по TCP (порт NuVotifier, обычно 8192). Это стандартный Votifier, не наш протокол.
   В v2 сайт кладёт в additionalData маркер (см. §6). Плагин этот пакет видит как обычный VotifierEvent.

B. сервер игры → CraftRadar: плагин шлёт каждый VotifierEvent на HTTPS-эндпоинт (этот документ).
```

## 2. Транспорт

| Параметр | Значение |
|---|---|
| Метод, URL | `POST {api_url}/api/vote_ingest.php`, `api_url` по умолчанию `https://craftradar.ru` |
| Схема | только `https://`; `http://` — лишь при явном `allow_insecure_http: true` (тесты) |
| Редиректы | клиент НЕ ДОЛЖЕН следовать 3xx; 3xx = ошибка конфигурации |
| HTTP | 1.1; connect-timeout 5 с, запрос 10 с (плагин) |
| Тело | `Content-Type: application/json`, UTF-8, **≤ 8192 байт**; JSON-объект (не массив) |
| Другие методы | `405`, `Allow: POST`, тело `{"status":"invalid","field":"method","request_id":…}` |
| Ответ | всегда JSON, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`; при 429 ещё заголовок `Retry-After` |

## 3. Аутентификация (HMAC-SHA256)

Заголовки:

| Заголовок | Формат | Проверка на сайте |
|---|---|---|
| `X-CR-Key` | `key_id` моста. Выдаётся как `crk_` + 16 hex (20 символов) | `^[A-Za-z0-9_-]{1,24}$`, ключ существует, мост включён, IP в `bridge_allowed_ips` (если задан) |
| `X-CR-Timestamp` | Unix-время **в секундах**, десятичное, момент отправки (не время голоса) | `^\d{9,12}$` и `|now − ts| ≤ voting_ingest_window_sec` (по умолчанию **300 с**) |
| `X-CR-Signature` | 64 символа hex в нижнем регистре | `hash_equals(expected, strtolower(trim(sig)))` |

```
signature = lower_hex( HMAC_SHA256( key = secret (UTF-8 байты),
                                    msg = ts + "." + key_id + "." + raw_body ) )
```

* `raw_body` — **ровно те байты**, что уходят в теле. Сериализовать JSON один раз и подписывать этот же буфер; переформатирование после подписи ломает её.
* `ts` — та же строка, что в `X-CR-Timestamp`. Плагин ставит новую `ts` и новую подпись на **каждую** попытку (повторы не протухают по окну).
* `secret` — строка, выданная в кабинете (сейчас 64 hex), используется как есть, без декодирования hex.
* Все отказы аутентификации дают одинаковый `401 {"status":"unauthorized","request_id":…}` — причину (нет ключа, чужой IP, окно, подпись) сайт наружу не раскрывает.

Тестовые векторы — `docs/protocol-vectors.json` (посчитаны PHP `hash_hmac`, проверяются JUnit-тестом и тестом сайта).

## 4. Тело: голос

```json
{"service_name":"TopCraft","username":"Steve","address":"203.0.113.7",
 "timestamp":"1761000000123","external_id":null,"correlation_id":null}
```

| Поле | Тип | Обязательно | Правила сайта |
|---|---|---|---|
| `service_name` | string | да | непустое после trim, ≤ 100 символов. Сопоставляется с мониторингом без учёта регистра |
| `username` | string | `username` или `uuid` | ник `^\.?[A-Za-z0-9_]{1,16}$` (точка — Bedrock/Geyser); иначе 400 `field=username` |
| `uuid` | string | `username` или `uuid` | 32 hex с дефисами или без; иначе 400 `field=uuid`. Плагин сейчас не отправляет |
| `address` | string | нет | сохраняется в сыром payload, в логике не используется |
| `timestamp` | string (или int) | да | время голоса **как пришло от Votifier**: `^\d{9,14}$`; ≤ 11 цифр = секунды, 12+ = мс; в диапазоне 2010-01-01 … now+1 сутки; иначе 400 `field=timestamp` |
| `external_id` | string \| null | нет | ≤ 100; если задан — единственная основа идемпотентности |
| `correlation_id` | string \| null | нет | `^[0-9a-f]{16}$`, иначе 400; значим только для подтверждений доставки (§6) |

Неизвестные поля игнорируются. Типы, отличные от string/null, у `username/uuid/external_id/correlation_id` → 400.

Тип события определяет **сайт**, а не плагин: `service_name`, соответствующий мониторингу CraftRadar (adapter `internal`), = `delivery_ack`; любой другой зарегистрированный мониторинг = `external_vote`; незарегистрированный = 409 `unknown_monitoring` (событие сохраняется, его можно переиграть из админки после регистрации мониторинга).

## 5. Тело: diagnostic_ack

```json
{"event_type":"diagnostic_ack","diagnostic_id":"0123456789abcdef","timestamp":"1761000000123"}
```

Отправляется, когда плагин получил диагностический пакет (§6, `crdg1`). Это **не голос**: ни событие, ни статистика, ни награды.

| HTTP | `status` | `reason` | Плагин |
|---|---|---|---|
| 200 | `ok` | — | удалить из очереди |
| 400 | `invalid` (`field=diagnostic_id`) | — | в `rejected/` |
| 404 | `rejected` | `ack_unknown` | в `rejected/` (не повторять) |
| 409 | `rejected` | `ack_replay` | в `rejected/` |
| 410 | `rejected` | `ack_expired` (ожидание > 600 с) | в `rejected/` |
| 404 | `unknown_server` | — (сервер не active) | в `rejected/` (так плагин различает по префиксу тела diagnostic_ack) |
| 503 | `rejected` | `storage_unavailable` | повтор |

## 6. Маркеры в `additionalData` Votifier v2 (поток A)

| Маркер (ASCII, в пакете base64) | Смысл | Что делает плагин |
|---|---|---|
| `crdv1:<16 hex>` | доставка голоса CraftRadar, `<16 hex>` = `correlation_id` | шлёт обычное тело §4 с `correlation_id`; сайт отметит доставку «подтверждено». Награды сайт за это не начисляет |
| `crdg1:<16 hex>` | диагностика из кабинета; `service_name=CraftRadarDiagnostic`, `username=CRDiag` | шлёт §5; локальные награды НЕ выдаются (в т.ч. при битом маркере или только по имени сервиса) |

Votifier v1 дополнительных данных не несёт: подтверждение доставки тогда ищется по точному `(сервер, ник, timestamp)`; найдено 0 или >1 → 409.

## 7. Ответы на голос

| HTTP | `status` | Доп. поля | Плагин |
|---|---|---|---|
| 202 | `accepted` | `event_id`, `event_type`; для `external_vote` — `reward` (`ok`/`failed`/`none`); для `delivery_ack` — `delivery_id`, `match_method` (`correlation_id`/`exact_tuple`) | удалить |
| 200 | `duplicate` | `event_id` существующего | удалить |
| 400 | `invalid` | `field` | в `rejected/`, не повторять |
| 401 | `unauthorized` | — | долгий повтор |
| 404 | `unknown_server` | — | долгий повтор |
| 409 | `rejected` | `reason`: `unknown_monitoring`, `unmatched_ack`, `ack_wrong_server`, `ambiguous_ack`; `event_id`, `event_type` | в `rejected/` |
| 429 | `rate_limited` | `retry_after` (с) | пауза всей очереди на `retry_after` (≥ 1 с, ≤ 15 мин) |
| 503 | `ingest_disabled` / `ingest_not_configured` | — | обычный повтор |
| 500 | `error` | — | обычный повтор (идемпотентность делает его безопасным) |

Во всех ответах есть `request_id` (16 hex) — его стоит показывать в логах для поддержки.

Политика повторов плагина: обычный — 5 с × 2ⁿ⁻¹, ±20 %, потолок 15 мин; долгий (401/403/404, 3xx, `config_error`) — 5 мин × 2ⁿ⁻¹, ±20 %, потолок 1 ч. Событие старше 7 дней → `rejected/` с причиной `expired`. Прочие 4xx — не повторять.

Лимиты сайта (могут меняться, клиент ДОЛЖЕН уважать 429): до аутентификации 120 запросов/мин на IP; после — 120/мин на ключ и 300/мин на IP. Плагин держит не более 4 запросов одновременно.

## 8. Идемпотентность

Ключ считает сайт, в пределах сервера:

* есть `external_id` → `sha256("ext" ␟ server_id ␟ lower(monitoring_slug) ␟ trim(external_id))`;
* иначе отпечаток `sha256("fp" ␟ server_id ␟ lower(slug) ␟ player_key ␟ timestamp_ms ␟ lower(trim(service_name)))`, где `player_key` = `uuid:<uuid>` или `name:<lower(ник)>`, `timestamp_ms` — нормализованный (`1761000000` = `1761000000000`);
* для `delivery_ack` с `correlation_id` вместо `external_id` берётся `corr:<id>`.

(␟ = байт 0x1F.) Следствия для клиента: повтор того же голоса безопасен и даёт `200 duplicate`; клиент НЕ ДОЛЖЕН менять `timestamp`/`username`/`service_name` голоса между попытками; уникальный `external_id` от мониторинга, если он есть, предпочтительнее.

## 9. Совместимость и версии

* Версия протокола — этот файл; несовместимые изменения требуют нового пути эндпоинта или явного поля версии (сейчас не используется).
* Сайт ДОЛЖЕН игнорировать неизвестные поля; плагин ДОЛЖЕН игнорировать неизвестные поля ответа.
* Известные расхождения и решения (на 2026-10-22):
  1. `key_id`: плагин с 1.1.0 проверяет `^[A-Za-z0-9_-]{4,24}$` (в 1.0.0 было 4–64). Сайт принимает 1–24 и выдаёт `crk_` + 16 hex (20 символов), так что любой выданный ключ проходит; ключ длиннее 24 плагин отвергает локально с понятной ошибкой, а не бесконечным 401. Ключи 1–3 символа сайт не выдаёт.
  2. нечисловой `timestamp` от некоторых мониторингов → сайт отвечает 400, плагин сразу переносит событие в `rejected/` (повтор бессмыслен; вернуть — `/crvotes requeue`). Поведение оставлено намеренно: плагин пересылает `timestamp` как пришёл от Votifier и не подменяет его (иначе сломается дедуп и подтверждение доставки по `(сервер, ник, timestamp)`).
