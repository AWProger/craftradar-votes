# Доказательства прогона 2026-10-22

* `<jar>_<версия>[-classic|-novotifier|-upgrade]_<jdk>.json` — result.json сценария `realserver/run_scenario.py` (каждая проверка с ok/detail).
* `1.0.0` — опубликованный jar 1.0.0 (SHA-256 2539124e254238e768b16104e922b58ea9662ed57d6bcc4373e9946cffd73ee7), baseline.
* `1.1.0-r1` — сборка коммита ac36ad3 (SHA-256 8c7d1be54c85d7977a134dbf10f8384953fdf757f448ca69fc40ad7141602bae), полная матрица.
* `1.1.0-r2` — финальная сборка коммита 56d4911 (SHA-256 1879f3795a2467c94d8421701ab2ce4af5318b8003e40a56dcfabdff991d60dc); отличие от r1 — только проверка имён материалов в конфиге (как в 1.0.0, без isItem). Повторены 1.8.8, 1.12.2 classic, 1.21.11, 1.21.11 upgrade, 26.3.
* `linkcheck_*.txt` — `realserver/ApiLinkCheck.java` для r1 против API: Bukkit 1.6.4 + classic Votifier 1.9 (в т.ч. на Java 8 с загрузкой всех классов), spigot-api 1.8.8 / 1.21.11, paper-api 1.21.11 / 26.3.
