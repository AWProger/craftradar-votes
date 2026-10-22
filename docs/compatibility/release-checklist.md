# Чеклист выпуска

- [ ] `git status` чистый, версия в `build.gradle.kts` поднята, запись в `CHANGELOG.md`.
- [ ] `./gradlew clean build` — все тесты зелёные, `verifyJar OK`.
- [ ] Вторая сборка с нуля даёт тот же SHA-256 (`bukkit/build/libs/SHA256SUMS`).
- [ ] CI `compile-matrix` зелёный по всем API.
- [ ] Real-server матрица (`realserver/matrix.sh`) по всем строкам `matrix.txt` — PASS; результаты перенесены в `docs/compatibility/minecraft-matrix.md`.
- [ ] Апгрейд с прошлой версии: `run_scenario.py --upgrade-from <старый.jar>` — PASS (конфиг не изменён, очередь и дедуп пережили смену jar).
- [ ] В jar нет секретов: `config.yml` с пустыми `key_id`/`secret`, `api_url` = `https://craftradar.ru` (проверяет `verifyJar`).
- [ ] `docs/PROTOCOL.md`, `docs/REWARDS.md`, `protocol-vectors.json` сверены с сайтом (support-policy.md → «Синхронизация»).
- [ ] Владелец дал разрешение на публикацию. Публикуются jar + SHA-256 + changelog + ссылка на матрицу.
