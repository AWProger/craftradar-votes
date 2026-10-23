# Сборка и выпуск

## Сборка

Нужен только JDK 21 (Gradle скачает Wrapper сам, версия и SHA-256 зафиксированы в `gradle/wrapper/gradle-wrapper.properties`).

```bash
./gradlew build          # компиляция, все тесты, проверка JAR (verifyJar)
ls bukkit/build/libs/    # craftradar-votes-<версия>.jar — устанавливаемый файл, SHA256SUMS
```

Байт-в-байт тот же JAR (и SHA-256, что в релизе) получается при `umask 002` и правах `g+w` на файлах репозитория — права попадают в записи jar (так собирается и CI):

```bash
umask 002 && chmod -R g+w . && ./gradlew clean build
```

Ничего из основного репозитория сайта CraftRadar для сборки не нужно: все тесты и фикстуры лежат здесь.
Фикстуры `core/src/test/resources/cabinet/*.yml` генерирует сайт (генератор YAML в кабинете) — их обновляют PR-ом с сайта, вручную не правят.

## Выпуск (владелец)

1. Пройти [docs/compatibility/release-checklist.md](docs/compatibility/release-checklist.md). Обновить `version` в `build.gradle.kts` и `CHANGELOG.md` (что изменилось, протокол затронут или нет, проверенные версии MC/Java).
2. Чистая сборка на теге:
   ```bash
   git switch main && git pull --ff-only
   git tag -s v<версия> -m "CraftRadar Votes <версия>"   # -s: подписанный тег (или -a без подписи)
   ./gradlew clean build
   cat bukkit/build/libs/SHA256SUMS                    # создаёт ./gradlew build
   ```
3. `git push origin v<версия>`.
4. GitHub → Releases → **Draft a new release** → тег `v<версия>` → приложить `craftradar-votes-<версия>.jar` и `SHA256SUMS` → в описание: changelog, таблица «проверено на MC/Java», SHA-256 → Publish.
   CI артефакты **не** публикует (только сборка и тесты) — выпуск делается руками, секретов в CI нет.
5. Выкладка на сайт — см. раздел ниже.

## Выкладка на сайт CraftRadar

На dev-машине с репо сайта, тот же файл, что в релизе:

```bash
sha256sum -c SHA256SUMS                                     # сумма совпала с релизом
php tools/publish_votes_plugin.php --jar=путь/craftradar-votes-<версия>.jar \
    --confirm-tests-passed --paper=<мин.версия> --java=<мин.java> --nuvotifier=2.7.x
```

Скрипт проверит `plugin.yml`, посчитает sha256 и запишет `downloads/craftradar-votes/<jar>` + `manifest.json`. Эти два файла заливаются на хостинг вместе с обычным релизом сайта; `/votifier_download.php` отдаёт JAR, только если размер и sha256 совпадают с манифестом. Сумма на `craftradar.ru/votifier.php` должна совпасть с `SHA256SUMS` релиза.
