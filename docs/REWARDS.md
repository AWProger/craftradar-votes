# Награды за голоса — контракт (binding для плагина, генератора кабинета и гайда)

> Каноническая копия — в репозитории плагина (`docs/REWARDS.md`); на сайте CraftRadar — копия `docs/rewards-contract.md`, синхронизировать вместе (см. `docs/compatibility/support-policy.md`).

Награды настраиваются и выполняются **только локально** в плагине CraftRadar Votes (`plugins/CraftRadarVotes/config.yml`).
CraftRadar (сайт) не отправляет команд на игровой сервер. Кабинет (`dashboard/voting.php`) лишь **генерирует** YAML. Новых таблиц БД и миграций нет.

## 1. Что НЕ смешиваем
| Сущность | Где | Даёт награду? |
|---|---|---|
| Реальный голос игрока (VotifierEvent на сервере) | плагин | да, если `rewards.enabled` |
| Исходящая доставка сайт → сервер | сайт (`vote_deliveries`) | это и есть источник VotifierEvent, сама по себе нет |
| `delivery_ack` (подтверждение сайта) | сайт ← мост | нет, плагин награды по нему не выдаёт |
| Тестовое событие (`/crvotes rewards test`) | плагин | исполняет действия для онлайн-игрока, **не** трогает дедуп, серии, лимиты |
| Выдача награды | плагин | отдельный этап после решения |

## 2. Схема `rewards:` (единая; ключи kebab-case; неизвестный ключ = ошибка)
```yaml
rewards:
  enabled: false              # по умолчанию выключено
  dry-run: false              # true: считает и пишет в журнал «выдал бы», ничего не исполняет и не меняет состояние
  services: []                # имена сервисов голоса (без регистра); пусто = любой сервис
  timezone: "UTC"             # IANA, для границы суток, серий и лимита
  daily-limit: 0              # макс. наград на игрока на сервис в сутки; 0 = без лимита
  duplicate-protection: { enabled: true, retention-days: 30 }
  offline-queue: { enabled: true, max-per-player: 50, expire-days: 7 }
  default:                    # выдаётся на каждый принятый голос
    commands: []              # консольные команды, только плейсхолдер {player}
    items: []                 # [{material: DIAMOND, amount: 3}]
    money: 0                  # через Vault; нет Vault — действие FAILED, не «успех»
    message: ""               # игроку (онлайн), цвет & коды
    broadcast: ""             # всем, {player}
  weighted-pool:
    enabled: false
    entries: []               # [{weight: 70, commands: [], items: [], money: 0, message: ""}] выбирается ровно одна
  streaks:
    enabled: false
    milestones: []            # [{days: 7, repeat: false, commands: [], items: [], money: 0, message: ""}]
  logging: { enabled: true }  # rewards.log в папке плагина
```
Ограничения: `weight` 1..1_000_000; `amount` 1..2304; `days` 1..3650; `daily-limit` 0..1000; `money` 0..1e9 (не NaN); команд на блок ≤ 20, длина команды ≤ 256, без `\n`, не с `/`; `entries` ≤ 50, `milestones` ≤ 50; `material` — имя Bukkit `Material` (не legacy, не AIR); `timezone` — валидный `ZoneId`; `message`/`broadcast` ≤ 256.
Плейсхолдеры: **только** `{player}` в командах/message/broadcast. Любой другой `{...}` = ошибка конфигурации.

## 3. Семантика
- Суммируется: `default` + ровно одна запись `weighted-pool` (если включён) + все сработавшие `milestones`.
- Серия: подряд идущие **календарные дни** в `timezone` с принятым голосом (по сервису-фильтру). Следующий день = +1; тот же день = без изменения; пропуск ≥ 2 дней = серия 1. Милестоун `days: N` срабатывает при `streak == N`; `repeat: true` — при `streak % N == 0`.
- `daily-limit`: считается принятые голоса с наградой за календарные сутки (timezone). Сверх лимита: голос игнорируется для наград (статус `LIMIT_REACHED`), серия при этом **обновляется** (голос был).
- Оффлайн: если игрока нет онлайн, а `offline-queue.enabled` — награда ставится в очередь и выдаётся при входе. Если очередь выключена — `SKIPPED_OFFLINE`. Очередь ограничена (`max-per-player`, `expire-days`); переполнение/истечение логируется, не молча.
- Невалидный конфиг: **весь** `rewards` не запускается (`CONFIG_INVALID`), ошибки с путём (`rewards.default.items[0].material: ...`) в консоль и `/crvotes rewards status`. Пересылка голосов на сайт продолжает работать. При `reload` невалидного конфига остаётся предыдущий валидный движок.
- Совместимость со сторонними плагинами наград (VotingPlugin, SuperbVote…): если они выдают награды за тот же VotifierEvent — оставьте `rewards.enabled: false` (мост продолжит слать статистику на сайт). Включённый встроенный движок + сторонний = двойные награды; плагин при `enabled: true` и обнаружении этих плагинов пишет предупреждение при старте.

## 4. Идентификатор события и состояние
- Ключ события: `crdv1:<correlation_id>` из additionalData, если есть; иначе `sha256(service|lower(username)|timestampMs|address)`. Одного ника+приблизительного времени мало.
- Состояние: `rewards-state.json` (дедуп, серии, суточные счётчики, очередь, статусы действий) в папке плагина, фиксированное имя (никаких путей из конфига/сети), запись атомарная (tmp + rename), права по умолчанию. Размер ограничен (retention).
- Действие награды = `{eventKey}#{n}` со статусом `PENDING → DONE | FAILED | UNCERTAIN`.
- Стратегия: **at-most-once для команд и денег**. Перед исполнением действие помечается `ATTEMPTED` (на диск); после возврата — `DONE`/`FAILED`. После рестарта действия `ATTEMPTED` без итога → `UNCERTAIN` (в журнал, **не повторяем**). Повторяем только действия, не начатые (игрок оффлайн, нет Vault, полный инвентарь, а также PENDING-действия, оставшиеся после падения посреди награды: они доставляются через офлайн-очередь при входе игрока). ITEM и MESSAGE не пишут ATTEMPTED и имеют семантику at-least-once: после падения между выдачей и записью DONE возможна повторная выдача. Предметы: не помещается — остаток остаётся в очереди (не роняем на землю молча). `dispatchCommand` возвращает true ≠ гарантия эффекта стороннего плагина — документируем.
- Статусы журнала: `VOTE_RECEIVED`, `REJECTED_DUPLICATE`, `REJECTED_SERVICE`, `REJECTED_PLAYER`, `LIMIT_REACHED`, `COMPUTED`, `QUEUED`, `ACTION_DONE`, `ACTION_FAILED`, `ACTION_UNCERTAIN`, `GRANTED`, `SKIPPED_OFFLINE`, `DRY_RUN`, `CONFIG_INVALID`. «GRANTED» только когда все действия DONE.
- Ник: `^[A-Za-z0-9_.]{1,16}$` до любой подстановки; иначе `REJECTED_PLAYER`, никаких команд. Команды выполняются консолью на главном потоке; логи без секретов.

## 5. Пакет и владельцы файлов (Java, `ru.craftradar.votes.rewards`)
Скелет и интерфейсы создаёт архитектор (фаза 1); каждый класс — один владелец. Движок без зависимости от Bukkit (интерфейсы `Clock`, `PlayerGateway`, `ActionExecutor`, `StateStore`, `Journal`); Bukkit-клей тонкий.

| Класс (`rewards.*`) | Владелец | Ответственность | Публичный API |
|---|---|---|---|
| `RewardsSettings`, `Action`, `ActionResult`, `ActionStatus`, `RewardStatus`, `VoteInput`, `RewardResult`, `RewardsState`, `ValidationResult` | A1 | неизменяемые модели и состояние | records/enum; `VoteInput.eventKey()`, `Action.command/item/money/message/broadcast`, `RewardsSettings.disabled()` |
| `Clock`, `PlayerGateway`, `ActionExecutor`, `StateStore`, `Journal` | A1 | границы движка (без Bukkit) | `nowMs()`; `isOnline/giveItems/hasEconomy/balance/deposit/sendMessage/broadcast`; `execute(Action)`; `load/save`; `log(status,key,player,detail)` |
| `ConfigValidator` | A3 | строгая проверка §2, ошибки с путями | `static ValidationResult validate(Map raw, Predicate<String> materialExists)` |
| `RewardsEngine` | A4 | решение по голосу, суммирование, статусы, at-most-once | `configure`, `handleVote`, `test`, `onJoin`, `recoverOnStart`, `status` |
| `CommandExecutor`, `PlaceholderGuard` | A5 | исполнение действий; ник и `{player}` | `execute(Action)`; `validPlayerName`, `unknownPlaceholders`, `substitute` |
| `ItemGranter` | A6 | выдача предметов с учётом места | `static int give(Player, String material, int amount)` -> остаток |
| `EconomyBridge` | A7 | Vault через ServicesManager и рефлексию (без compile-зависимости) | `available`, `balance`, `deposit` |
| `WeightedPicker` | A8 | выбор одной записи пула | `static PoolEntry pick(List, Random)` |
| `StreakTracker` | A9 | серии по календарным дням, милестоуны | `static int record(state, player, nowMs, zone)`, `static List<Milestone> triggered(list, streak)` |
| `OfflineQueue` | A10 | очередь, лимит, истечение | `enqueue`, `drain`, `putBack`, `size` |
| `DedupeStore`, `JsonStateStore` | A11 | дедуп; `rewards-state.json` атомарно | `markIfNew(eventKey)`; `load/save` |
| `DailyLimiter` | A12 | суточный лимит | `tryAcquire(service, player)` |
| `RewardJournal` | A13 | `rewards.log` | `log(...)` |
