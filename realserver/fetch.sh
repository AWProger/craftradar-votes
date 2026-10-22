#!/usr/bin/env bash
# Скачивает серверы/JDK/NuVotifier из artifacts.txt в $CRV_CACHE и проверяет SHA-256. Ничего не кладёт в репо.
# Использование: realserver/fetch.sh [имя ...]   (без аргументов — всё)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cache="${CRV_CACHE:-$HOME/.cache/crv-realserver}"
mkdir -p "$cache"
want=" $* "
while read -r name sha url; do
  [[ -z "$name" || "$name" == \#* ]] && continue
  [[ $# -gt 0 && "$want" != *" $name "* ]] && continue
  f="$cache/$name"
  if [[ ! -f "$f" ]] || ! echo "$sha  $f" | sha256sum -c --quiet - 2>/dev/null; then
    echo "fetch $name"
    curl -fsSL --retry 3 --max-time 900 -o "$f.part" "$url"
    echo "$sha  $f.part" | sha256sum -c --quiet - || { echo "SHA-256 MISMATCH: $name" >&2; exit 1; }
    mv "$f.part" "$f"
  fi
  if [[ "$name" == jdk*.tar.gz && ! -x "$cache/${name%.tar.gz}/bin/java" ]]; then
    mkdir -p "$cache/${name%.tar.gz}"
    tar -xzf "$f" -C "$cache/${name%.tar.gz}" --strip-components=1
  fi
done < "$here/artifacts.txt"
echo "ok: $cache"
