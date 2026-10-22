#!/usr/bin/env bash
# Собирает classic Votifier 1.9 из исходников тега v1.9 (JDK 8 javac, против Bukkit API 1.6.4) в $CRV_CACHE/votifier-1.9.jar.
# Только для real-server тестов старых серверов (1.7.10). Требует: realserver/fetch.sh jdk8.tar.gz votifier-1.9-src.zip bukkit-api-1.6.4-R2.0.jar
set -euo pipefail
cache="${CRV_CACHE:-$HOME/.cache/crv-realserver}"
b="$cache/votifier19-build"; [[ -e "$b" ]] && { echo "уже есть $b — используйте его или удалите вручную"; exit 0; }
mkdir -p "$b/classes"
python3 -I -c "import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$cache/votifier-1.9-src.zip" "$b"
src="$b/votifier-1.9"
"$cache/jdk8/bin/javac" -nowarn -encoding UTF-8 -source 1.6 -target 1.6 -cp "$cache/bukkit-api-1.6.4-R2.0.jar" -d "$b/classes" $(find "$src/src/main/java" -name '*.java')
sed 's/\${project.version}/1.9/' "$src/src/main/resources/plugin.yml" > "$b/classes/plugin.yml"
"$cache/jdk8/bin/jar" cf "$cache/votifier-1.9.jar" -C "$b/classes" .
sha256sum "$cache/votifier-1.9.jar"
