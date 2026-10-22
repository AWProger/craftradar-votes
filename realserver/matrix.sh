#!/usr/bin/env bash
# Прогон real-server матрицы: realserver/matrix.sh <plugin.jar> <work-dir> [фильтр-версии]
# JDK 21 для инструментов (VoteSend) — $CRV_TOOL_JAVA или java из PATH; jdk21 в matrix.txt = он же.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
jar="$1"; work="$2"; only="${3:-}"
cache="${CRV_CACHE:-$HOME/.cache/crv-realserver}"
tool="${CRV_TOOL_JAVA:-java}"
mkdir -p "$work"
fail=0
while read -r ver jdk flags; do
  [[ -z "$ver" || "$ver" == \#* ]] && continue
  [[ -n "$only" && "$ver" != "$only" ]] && continue
  if [[ "$jdk" == jdk21 ]]; then j="$tool"; else j="$cache/$jdk/bin/java"; fi
  echo "=== $ver $jdk $flags"
  timeout 1800 python3 -I "$here/run_scenario.py" --version "$ver" --java "$j" --jar "$jar" --cache "$cache" --work "$work/$jdk" $flags \
    > "$work/$ver-$jdk${flags// /}.out" 2>&1 || fail=1
  tail -n 1 "$work/$ver-$jdk${flags// /}.out"
done < "$here/matrix.txt"
exit $fail
