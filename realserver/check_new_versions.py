#!/usr/bin/env python3
"""Сравнивает последние STABLE-версии Paper (fill.papermc.io v3) с realserver/matrix.txt.
Печатает новые версии, которых нет в матрице (::warning:: для GitHub Actions). Ничего не меняет и не публикует.
Код выхода всегда 0: решение о поддержке новой версии принимает человек (docs/compatibility/adding-minecraft-version.md)."""
import json, os, urllib.request

here = os.path.dirname(os.path.abspath(__file__))
tested = {l.split()[0] for l in open(os.path.join(here, "matrix.txt")) if l.strip() and not l.startswith("#")}
with urllib.request.urlopen("https://fill.papermc.io/v3/projects/paper", timeout=30) as r:
    versions = json.load(r)["versions"]
# группы по убыванию; берём последнюю стабильную (без -rc/-pre) в двух новейших группах
groups = list(versions.items())[:2]
new = []
for g, vs in groups:
    stable = [v for v in vs if "-" not in v]
    if stable and stable[0] not in tested:
        new.append(stable[0])
for v in new:
    print(f"::warning::Paper {v} не входит в realserver/matrix.txt — пройдите docs/compatibility/adding-minecraft-version.md")
print("в матрице:", " ".join(sorted(tested)), "| новые:", " ".join(new) or "нет")
