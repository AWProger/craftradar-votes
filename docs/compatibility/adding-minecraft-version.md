# Как добавить новую версию Minecraft / Paper

Сигнал: еженедельная задача CI `new-versions` (`realserver/check_new_versions.py`) пишет предупреждение, если новая стабильная Paper не входит в `realserver/matrix.txt`. Решение всегда за человеком.

1. **Обнаружить изменения.** Release notes Paper/Spigot: минимальная Java, удалённые/переименованные API (`Material`, `ItemStack`, `ChatColor`, `broadcastMessage`, `ServicesManager`, планировщик), изменения `api-version` и plugin loader.
2. **Артефакты.** Взять STABLE-сборку из `https://fill.papermc.io/v3/projects/paper/versions/<v>/builds/latest`, вписать имя, SHA-256 и URL в `realserver/artifacts.txt`. Если нужна новая Java — добавить Temurin (`api.adoptium.net`) с SHA-256.
3. **Компиляция и связывание.** Найти `paper-api`/`spigot-api` этой версии в `repo.papermc.io` / `hub.spigotmc.org`, добавить строку в `compile-matrix` в `.github/workflows/ci.yml` и проверить локально:
   ```
   ./gradlew :bukkit:compileJava --rerun-tasks -PbukkitApi=<group:artifact:version> [-PcompileJdk=25 -Porg.gradle.java.installations.paths=<jdk>]
   cp=$(./gradlew -q :bukkit:printApiClasspath -PbukkitApi=<...>); java realserver/ApiLinkCheck.java bukkit/build/libs/craftradar-votes-<ver>.jar "$cp"
   ```
   `missing=0` обязательно. После — обычный `./gradlew build` (вернуть компиляцию против 1.8.8).
4. **Real-server.** Добавить строку в `realserver/matrix.txt` (версия, JDK, флаги: `--no-bot`, если mineflayer ещё не знает протокол) и прогнать:
   ```
   realserver/fetch.sh && realserver/matrix.sh bukkit/build/libs/craftradar-votes-<ver>.jar /tmp/crv-runs <версия>
   ```
   Нужны: `python3`, JDK 21 (`CRV_TOOL_JAVA`), для наград игроку — `npm i mineflayer@4.39.0` (`CRV_BOT_NODE_PATH`).
5. **Регрессии.** Прогнать предыдущую поддерживаемую версию тем же jar: результат не должен ухудшиться. Сравнивать с прошлым `result.json`, а не «списывать на окружение».
6. **Исправление.** Сломалось API — точечный fallback через рефлексию в `bukkit/` + unit-тест на логику выбора. Сломалась бинарная совместимость целиком — новый модуль-адаптер и отдельный артефакт (см. architecture.md), с понятным именем.
7. **Матрица.** Записать в [minecraft-matrix.md](minecraft-matrix.md) статус и путь к логам/`result.json`. Без real-server — максимум `BUILD_VERIFIED`.
8. **Кандидат и выпуск** — [release-checklist.md](release-checklist.md). Публикация только после проверок и разрешения владельца.
