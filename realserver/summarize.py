#!/usr/bin/env python3
"""Сводка real-server прогонов: python3 -I realserver/summarize.py <work-dir> — markdown-строки для minecraft-matrix.md."""
import glob, json, os, sys

rows = []
for p in sorted(glob.glob(os.path.join(sys.argv[1], "**", "result.json"), recursive=True)):
    r = json.load(open(p))
    fails = [c["id"] for c in r["checks"] if not c["ok"]]
    ok = sum(c["ok"] for c in r["checks"])
    bot = any(c["id"].startswith("bot.") for c in r["checks"])
    mode = "без Votifier" if r["no_votifier"] else ("upgrade с " + os.path.basename(r["upgrade_from"]) if r["upgrade_from"] else "")
    if any(c["id"] == "diag.recognized_v1" for c in r["checks"]):
        mode = (mode + " classic Votifier 1.9").strip()
    rows.append(f"| {r['version']} | {r['jar']} | {os.path.basename(os.path.dirname(os.path.dirname(r['java'])))} | {mode or 'NuVotifier 2.7.3'} | "
                f"{'да' if bot else 'нет'} | {ok}/{len(r['checks'])} {'PASS' if r['passed'] else 'FAIL: ' + ', '.join(fails)} | `{os.path.relpath(os.path.dirname(p), sys.argv[1])}` |")
print("| Версия | Jar | JDK | Votifier | Игрок | Проверки | Каталог |\n|---|---|---|---|---|---|---|")
print("\n".join(rows))
