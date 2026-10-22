#!/usr/bin/env python3
"""Real-server сценарий CraftRadar Votes: настоящий Paper + NuVotifier + плагин, голоса по Votifier v1/v2, мок CraftRadar.

  python3 -I realserver/run_scenario.py --version 1.20.4 --java <java> --jar <plugin.jar> --cache <dir> --work <dir> [--port-base N]
        [--no-votifier] [--upgrade-from <old.jar>]

Только localhost, тестовые ключи. Итог: <work>/<name>/result.json (+ console-*.log, mock.jsonl). Код выхода 0 = все проверки PASS.
"""
import argparse, json, os, re, shutil, signal, subprocess, sys, time, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
KEY, SECRET = "crk_test00000000ab", "test-secret-not-real"
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")

ap = argparse.ArgumentParser()
ap.add_argument("--version", required=True)
ap.add_argument("--java", required=True)
ap.add_argument("--jar", required=True)
ap.add_argument("--cache", required=True)
ap.add_argument("--work", required=True)
ap.add_argument("--port-base", type=int, default=31000)
ap.add_argument("--no-votifier", action="store_true")
ap.add_argument("--upgrade-from", default="")
ap.add_argument("--classic-votifier", action="store_true", help="classic Votifier 1.9 (только v1, без additionalData) вместо NuVotifier")
ap.add_argument("--bot", default=os.environ.get("CRV_BOT_NODE_PATH", ""), help="node_modules с mineflayer: проверка выдачи наград игроку")
ap.add_argument("--no-bot", action="store_true", help="версия не поддерживается mineflayer: награды игроку не проверяются (NOT_TESTED)")
ap.add_argument("--bot-version", default="", help="версия протокола для mineflayer, если не автоопределяется")
ap.add_argument("--javac", default=os.environ.get("CRV_TOOL_JAVA", "java"), help="java 11+ для VoteSend.java")
a = ap.parse_args()

name = a.version + ("-novotifier" if a.no_votifier else "") + ("-classic" if a.classic_votifier else "") + ("-upgrade" if a.upgrade_from else "")
wd = os.path.join(a.work, name)
if os.path.exists(wd):
    sys.exit(f"{wd} уже существует: укажите новый --work (старые прогоны не удаляются)")
os.makedirs(os.path.join(wd, "srv", "plugins"))
srv = os.path.join(wd, "srv")
MC, VOT, HTTP = a.port_base + 565, a.port_base + 192, a.port_base + 80
checks, mock, SERVERS = [], None, []


def check(cid, ok, detail=""):
    checks.append({"id": cid, "ok": bool(ok), "detail": str(detail)[:400]})
    print(("PASS " if ok else "FAIL ") + cid + ("  " + str(detail)[:200] if detail else ""), flush=True)


def write(p, s):
    with open(p, "w", encoding="utf-8") as f:
        f.write(s)


def read(p):
    try:
        with open(p, encoding="utf-8", errors="replace") as f:
            return ANSI.sub("", f.read())
    except FileNotFoundError:
        return ""


class Server:
    def __init__(self, tag):
        self.log = os.path.join(wd, f"console-{tag}.log")
        self.out = open(self.log, "w")
        cmd = [a.java, "-Xms512M", "-Xmx1G", "-DIReallyKnowWhatIAmDoingISwear=true", "-Dpaper.disableStartupVersionCheck=true",
               "-jar", "paper.jar", "nogui"]
        self.p = subprocess.Popen(cmd, cwd=srv, stdin=subprocess.PIPE, stdout=self.out, stderr=subprocess.STDOUT, text=True,
                                  start_new_session=True)
        SERVERS.append(self)

    def text(self):
        return read(self.log)

    def wait(self, pattern, timeout):
        end = time.time() + timeout
        while time.time() < end:
            if re.search(pattern, self.text()):
                return True
            if self.p.poll() is not None:
                return bool(re.search(pattern, self.text()))
            time.sleep(1)
        return False

    def cmd(self, c):
        self.p.stdin.write(c + "\n")
        self.p.stdin.flush()

    def stop(self, timeout=120):
        if self.p.poll() is None:
            try:
                self.cmd("stop")
            except BrokenPipeError:
                pass
            try:
                self.p.wait(timeout)
            except subprocess.TimeoutExpired:
                os.killpg(self.p.pid, signal.SIGKILL)
                self.p.wait(30)
        self.out.close()
        return self.p.returncode


def votifier_dir():
    for d in ("Votifier", "NuVotifier", "nuvotifier"):
        if os.path.isdir(os.path.join(srv, "plugins", d)):
            return os.path.join(srv, "plugins", d)
    return None


def send(proto, user, ts, service="TestList", extra=None):
    vd = votifier_dir()
    if a.classic_votifier:   # classic Votifier: только v1, additionalData не передаётся
        proto, extra = "v1", None
    if proto == "v1":
        args = ["v1", "127.0.0.1", str(VOT), os.path.join(vd, "rsa", "public.key"), service, user, "127.0.0.1", str(ts)]
    else:
        token = re.search(r"(?m)^\s+default:\s*'?([A-Za-z0-9]+)'?\s*$", read(os.path.join(vd, "config.yml"))).group(1)
        args = ["v2", "127.0.0.1", str(VOT), token, service, user, "127.0.0.1", str(ts)] + ([extra] if extra else [])
    r = subprocess.run([a.javac, os.path.join(HERE, "VoteSend.java")] + args, capture_output=True, text=True, timeout=60)
    return r.returncode == 0, (r.stdout + r.stderr).strip()


def mock_events():
    out = []
    for line in read(os.path.join(wd, "mock.jsonl")).splitlines():
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return out


def wait_mock(pred, timeout):
    end = time.time() + timeout
    while time.time() < end:
        if any(pred(e) for e in mock_events()):
            return True
        time.sleep(1)
    return False


def accepted(user=None, etype=None):
    def p(e):
        if e.get("verdict") != "accepted":
            return False
        ev = e.get("event", {})
        if etype:
            return ev.get("event_type") == etype
        return ev.get("username") == user
    return p


def no_linkage_errors(text, tag):
    bad = re.findall(r".*(NoSuchMethodError|NoClassDefFoundError|UnsupportedClassVersionError|AbstractMethodError|IncompatibleClassChangeError|ClassNotFoundException).*", text)
    ours = [l for l in text.splitlines() if re.search(r"at ru\.craftradar|Error occurred while enabling CraftRadarVotes|Could not load 'plugins/craftradar", l)]
    check(f"{tag}.no_linkage_or_plugin_errors", not bad and not ours, (bad + ours)[:3])


def plugin_config():
    p = os.path.join(srv, "plugins", "CraftRadarVotes", "config.yml")
    s = read(p)
    rep = [(r'(?m)^api_url: .*$', f'api_url: "http://127.0.0.1:{HTTP}"'), (r'(?m)^key_id: .*$', f'key_id: "{KEY}"'),
           (r'(?m)^secret: .*$', f'secret: "{SECRET}"'), (r'(?m)^allow_insecure_http: .*$', 'allow_insecure_http: true'),
           (r'(?m)^log_level: .*$', 'log_level: debug'), (r'(?m)^  enabled: false(\s+# по умолчанию выключено)', r'  enabled: true\1'),
           (r'(?m)^    commands: \[\](\s+# до 20)', r'    commands: ["say CRV_REWARD {player}"]\1'),
           (r'(?m)^    items: \[\](\s+# amount)', r'    items: [{material: DIAMOND, amount: 1}]\1')]
    for pat, r in rep:
        s2 = re.sub(pat, r, s, count=1)
        if s2 == s:
            raise SystemExit("не удалось изменить config.yml: " + pat)
        s = s2
    write(p, s)


def votifier_config():
    vd = votifier_dir()
    p = os.path.join(vd, "config.yml")
    s = re.sub(r"(?m)^port: \d+", f"port: {VOT}", read(p))
    s = re.sub(r"(?m)^host: .*$", "host: 127.0.0.1", s)
    write(p, s)


def bot_rewards(s, rl, now):
    """Игрок Alice входит: очередь наград (команда+предмет+сообщение) выдаётся при входе; затем голос онлайн — сразу."""
    env = dict(os.environ, NODE_PATH=a.bot)
    log = os.path.join(wd, "bot.jsonl")
    with open(log, "w") as f:
        bp = subprocess.Popen(["node", os.path.join(HERE, "bot.js"), str(MC), "Alice", "40"] + ([a.bot_version] if a.bot_version else []),
                              stdout=f, stderr=subprocess.STDOUT, env=env, start_new_session=True)
        spawned = False
        end = time.time() + 60
        while time.time() < end and not spawned:
            spawned = '"spawned"' in read(log)
            time.sleep(1)
        check("bot.joined", spawned, read(log)[-300:])
        if spawned:
            check("bot.queued_command_reward", s.wait(r"CRV_REWARD [Aa]lice", 30))
            check("bot.queued_message_reward", wait_text(log, "Спасибо за голос", 20))
            check("bot.queued_item_reward", wait_text(log, r'"diamonds": ?[1-9]', 20))
            ok, out = send("v2", "Alice", now + 10)
            check("bot.online_vote_item", ok and wait_text(log, r'"diamonds": ?[2-9]', 30), read(log)[-200:])
            check("bot.granted_logged", wait_text(rl, r"(?i)GRANTED\t[^\t]*\talice", 10))
        try:
            bp.wait(60)
        except subprocess.TimeoutExpired:
            os.killpg(bp.pid, signal.SIGKILL)


def wait_text(path, pattern, timeout):
    end = time.time() + timeout
    while time.time() < end:
        if re.search(pattern, read(path)):
            return True
        time.sleep(1)
    return False


def main():
    global mock
    shutil.copy(os.path.join(a.cache, f"paper-{a.version}.jar"), os.path.join(srv, "paper.jar"))
    write(os.path.join(srv, "eula.txt"), "eula=true\n")
    write(os.path.join(srv, "server.properties"),
          f"online-mode=false\nserver-port={MC}\nserver-ip=127.0.0.1\nspawn-protection=0\nmax-players=5\nview-distance=3\n"
          "simulation-distance=3\nspawn-monsters=false\nspawn-animals=false\nspawn-npcs=false\ngenerate-structures=false\nenable-query=false\nenable-rcon=false\n")
    if not a.no_votifier:
        vj = "votifier-1.9.jar" if a.classic_votifier else "nuvotifier-2.7.3.jar"
        shutil.copy(os.path.join(a.cache, vj), os.path.join(srv, "plugins", vj))
    first_jar = a.upgrade_from or a.jar
    shutil.copy(first_jar, os.path.join(srv, "plugins", "craftradar-votes.jar"))

    # 1. первый старт: конфиги по умолчанию, пустой ключ -> мост выключен с понятной ошибкой
    s = Server("1-first")
    up = s.wait(r"Done \(", 600)
    check("start1.server_done", up, a.version)
    t = s.text()
    check("start1.plugin_enabled", re.search(r"Enabling CraftRadarVotes v", t), re.search(r"Enabling CraftRadarVotes v\S+", t).group(0) if re.search(r"Enabling CraftRadarVotes v\S+", t) else "")
    if a.no_votifier:
        check("start1.no_votifier_warning", "NuVotifier/Votifier не найден" in t)
    else:
        check("start1.empty_key_error", "key_id пуст" in t)
    check("start1.config_created", os.path.exists(os.path.join(srv, "plugins", "CraftRadarVotes", "config.yml")))
    s.cmd("crvotes status")
    s.wait(r"мост выключен или не настроен", 30)
    check("start1.status_cmd", "мост выключен или не настроен" in s.text())
    rc = s.stop()
    no_linkage_errors(s.text(), "start1")
    check("start1.clean_stop", "Disabling CraftRadarVotes" in s.text(), f"rc={rc}")
    if a.no_votifier or not up:
        return

    # 2. настройка и второй старт с моком (первый запрос мока -> 503)
    plugin_config()
    votifier_config()
    mock = subprocess.Popen([sys.executable, "-I", os.path.join(HERE, "mock_craftradar.py"), "--port", str(HTTP), "--key", KEY,
                             "--secret", SECRET, "--log", os.path.join(wd, "mock.jsonl"), "--fail-first", "1",
                             "--down", os.path.join(wd, "down.flag")], start_new_session=True)
    s = Server("2-votes")
    check("start2.server_done", s.wait(r"Done \(", 300))
    t = s.text()
    check("start2.bridge_ready", re.search(r"Готово: http://127\.0\.0\.1:\d+/api/vote_ingest\.php", t))
    check("start2.rewards_enabled", "Награды включены" in t)
    now = int(time.time() * 1000)
    corr = "0123456789abcdef"
    ok, out = send("v2", "Alice", now, extra="crdv1:" + corr)
    check("vote.v2_sent", ok and ("ok" in out or (a.classic_votifier and "v1 sent" in out)), out)
    ok, out = send("v1", "Bob", now + 1)
    check("vote.v1_sent", ok, out)
    want_corr = None if a.classic_votifier else corr   # v1 не несёт correlation: на сайт уходит correlation_id=null
    check("vote.v2_delivered_with_correlation", wait_mock(lambda e: accepted("Alice")(e) and e["event"].get("correlation_id") == want_corr
                                                         and e["event"].get("service_name") == "TestList", 90))
    check("vote.v1_delivered", wait_mock(accepted("Bob"), 90))
    ev = mock_events()
    check("transport.retry_after_503", ev and ev[0].get("verdict") == "simulated_503" and any(e.get("verdict") == "accepted" for e in ev[1:]))
    check("transport.hmac_all_valid", not any(e.get("verdict", "").startswith("bad_") for e in ev), [e.get("verdict") for e in ev])
    ok, out = send("v2", "CrDiag", now + 2, service="CraftRadarDiagnostic", extra="crdg1:fedcba9876543210")
    if a.classic_votifier:   # без additionalData нет diagnostic_id: проверка распознаётся по service, подтверждение не отправляется
        check("diag.recognized_v1", ok and s.wait(r"Диагностика CraftRadar: получена проверка", 30))
    else:
      check("diag.ack_delivered", ok and wait_mock(lambda e: accepted(etype="diagnostic_ack")(e) and e["event"].get("diagnostic_id") == "fedcba9876543210", 60))
    rl = os.path.join(srv, "plugins", "CraftRadarVotes", "rewards.log")
    deadline = time.time() + 30
    while time.time() < deadline and "alice" not in read(rl).lower():
        time.sleep(1)
    log = read(rl)
    check("rewards.offline_queued", re.search(r"(?i)QUEUED\t[^\t]*\talice", log), log[-300:])
    check("rewards.diag_not_rewarded", "crdiag" not in log.lower())
    ok, out = send("v2", "Alice", now, extra="crdv1:" + corr)   # тот же голос повторно
    deadline = time.time() + 30
    while time.time() < deadline and "REJECTED_DUPLICATE" not in read(rl):
        time.sleep(1)
    check("rewards.duplicate_rejected", "REJECTED_DUPLICATE" in read(rl))
    s.cmd("crvotes status")
    check("cmd.status", s.wait(r"Очередь: 0, в rejected/: 0", 30))
    s.cmd("crvotes rewards status")
    check("cmd.rewards_status", s.wait(r"(?i)награды", 30))
    # 3. недоступность сайта: голос остаётся в очереди на диске и переживает рестарт
    write(os.path.join(wd, "down.flag"), "1")
    t0 = time.time()
    ok, out = send("v2", "Carol", now + 3)
    qd = os.path.join(srv, "plugins", "CraftRadarVotes", "queue")
    check("outage.vote_sent", ok, out)
    check("outage.simulated_503_seen", wait_mock(lambda e: e.get("verdict") == "simulated_503" and e["t"] >= t0, 60))
    deadline = time.time() + 30   # событие Votifier обрабатывается на главном потоке (следующий тик)
    while time.time() < deadline and len([f for f in os.listdir(qd) if f.endswith(".json")]) != 1:
        time.sleep(1)
    check("outage.queued_on_disk", len([f for f in os.listdir(qd) if f.endswith(".json")]) == 1, os.listdir(qd))
    rc = s.stop()
    no_linkage_errors(s.text(), "start2")
    check("start2.clean_stop", "Disabling CraftRadarVotes" in s.text(), f"rc={rc}")

    if a.upgrade_from:
        shutil.copy(a.jar, os.path.join(srv, "plugins", "craftradar-votes.jar"))
    cfg_before = read(os.path.join(srv, "plugins", "CraftRadarVotes", "config.yml"))
    os.remove(os.path.join(wd, "down.flag"))
    s = Server("3-restart")
    check("start3.server_done", s.wait(r"Done \(", 300))
    check("start3.queue_restored", re.search(r"в очереди с прошлого запуска: 1", s.text()))
    check("start3.queued_vote_delivered", wait_mock(accepted("Carol"), 120))
    check("start3.config_unchanged", read(os.path.join(srv, "plugins", "CraftRadarVotes", "config.yml")) == cfg_before)
    st = read(os.path.join(srv, "plugins", "CraftRadarVotes", "rewards-state.json"))
    check("start3.rewards_state_kept", '"alice"' in st)
    ok, out = send("v2", "Alice", now, extra="crdv1:" + corr)   # дедуп пережил рестарт (и смену jar при upgrade)
    deadline = time.time() + 30
    while time.time() < deadline and read(rl).count("REJECTED_DUPLICATE") < 2:
        time.sleep(1)
    check("start3.dedupe_survives_restart", read(rl).count("REJECTED_DUPLICATE") >= 2)
    if a.bot and not a.no_bot:
        bot_rewards(s, rl, now)
    rc = s.stop()
    no_linkage_errors(s.text(), "start3")
    check("start3.clean_stop", "Disabling CraftRadarVotes" in s.text(), f"rc={rc}")


try:
    main()
except Exception as e:  # сбой самого сценария — тоже FAIL, не «пропуск»
    check("scenario.exception", False, repr(e))
finally:
    if mock and mock.poll() is None:
        os.killpg(mock.pid, signal.SIGTERM)
    for sv in SERVERS:   # аварийный выход: не оставлять серверы
        if sv.p.poll() is None:
            os.killpg(sv.p.pid, signal.SIGKILL)
    res = {"version": a.version, "java": a.java, "jar": os.path.basename(a.jar), "upgrade_from": a.upgrade_from,
           "no_votifier": a.no_votifier, "passed": all(c["ok"] for c in checks) and bool(checks), "checks": checks}
    write(os.path.join(wd, "result.json"), json.dumps(res, ensure_ascii=False, indent=1))
    print("RESULT", name, "PASS" if res["passed"] else "FAIL", f"{sum(c['ok'] for c in checks)}/{len(checks)}")
    sys.exit(0 if res["passed"] else 1)
