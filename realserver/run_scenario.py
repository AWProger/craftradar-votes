#!/usr/bin/env python3
"""Real-server сценарий CraftRadar Votes: настоящий Paper + NuVotifier + плагин, голоса по Votifier v1/v2, мок CraftRadar.

  python3 -I realserver/run_scenario.py --version 1.20.4 --java <java> --jar <plugin.jar> --cache <dir> --work <dir> [--port-base N]
        [--no-votifier] [--upgrade-from <old.jar>] [--pull]

--pull: способ «Наш плагин» без NuVotifier — голос ставится в очередь мока vote_pull, плагин забирает и подтверждает его,
награда считается один раз; после рестарта (и после потери pull-state.json) повтор того же id не награждается.
--pull --network: два сервера на одном ключе (сеть). any — голос получает ровно один сервер; online — игрок (бот) на втором
сервере, награда только на втором; игрок нигде не в сети — через offline_fallback_min (1 мин) ровно один сервер ставит её в очередь.

Только localhost, тестовые ключи. Итог: <work>/<name>/result.json (+ console-*.log, mock.jsonl). Код выхода 0 = все проверки PASS.
"""
import argparse, json, os, re, shutil, signal, subprocess, sys, time, urllib.request, zipfile

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
ap.add_argument("--pull", action="store_true", help="сценарий pull (vote_pull.php) без NuVotifier")
ap.add_argument("--network", action="store_true", help="с --pull: два сервера на одном ключе, network.reward_where any и online")
ap.add_argument("--classic-votifier", action="store_true", help="classic Votifier 1.9 (только v1, без additionalData) вместо NuVotifier")
ap.add_argument("--bot", default=os.environ.get("CRV_BOT_NODE_PATH", ""), help="node_modules с mineflayer: проверка выдачи наград игроку")
ap.add_argument("--no-bot", action="store_true", help="версия не поддерживается mineflayer: награды игроку не проверяются (NOT_TESTED)")
ap.add_argument("--bot-version", default="", help="версия протокола для mineflayer, если не автоопределяется")
ap.add_argument("--javac", default=os.environ.get("CRV_TOOL_JAVA", "java"), help="java 11+ для VoteSend.java")
a = ap.parse_args()

name = a.version + ("-novotifier" if a.no_votifier else "") + ("-classic" if a.classic_votifier else "") + ("-upgrade" if a.upgrade_from else "") + ("-pull" if a.pull else "") + ("-network" if a.network else "")
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
    def __init__(self, tag, cwd=None):
        self.log = os.path.join(wd, f"console-{tag}.log")
        self.out = open(self.log, "w")
        cmd = [a.java, "-Xms512M", "-Xmx1G", "-DIReallyKnowWhatIAmDoingISwear=true", "-Dpaper.disableStartupVersionCheck=true",
               "-jar", "paper.jar", "nogui"]
        self.p = subprocess.Popen(cmd, cwd=cwd or srv, stdin=subprocess.PIPE, stdout=self.out, stderr=subprocess.STDOUT, text=True,
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


def start_mock(fail_first):
    global mock
    mock = subprocess.Popen([sys.executable, "-I", os.path.join(HERE, "mock_craftradar.py"), "--port", str(HTTP), "--key", KEY,
                             "--secret", SECRET, "--log", os.path.join(wd, "mock.jsonl"), "--fail-first", str(fail_first),
                             "--down", os.path.join(wd, "down.flag")], start_new_session=True)


def mock_ctl(obj=None):
    """Без obj — состояние мока (/_mock/state), с obj — поставить голоса/диагностику (/_mock/queue). Ждёт, пока мок поднимется."""
    end = time.time() + 15
    while True:
        try:
            req = urllib.request.Request(f"http://127.0.0.1:{HTTP}/_mock/" + ("state" if obj is None else "queue"),
                                         data=None if obj is None else json.dumps(obj).encode())
            with urllib.request.urlopen(req, timeout=5) as f:
                return json.loads(f.read())
        except OSError:
            if time.time() > end:
                raise
            time.sleep(0.5)


def pull_flow():
    """Способ «Наш плагин»: голос из очереди сайта -> награда один раз -> ack; повторная выдача id после рестарта не награждает."""
    rl = os.path.join(srv, "plugins", "CraftRadarVotes", "rewards.log")
    ps = os.path.join(srv, "plugins", "CraftRadarVotes", "pull-state.json")
    vid, vid2, did = "a1b2c3d4e5f60718", "0f1e2d3c4b5a6978", "00112233aabbccdd"
    computed = lambda v: len(re.findall(rf"(?m)\tCOMPUTED\tcrdv1:{v}\t", read(rl)))
    acked = lambda v, t0: wait_mock(lambda e: e.get("verdict") == "pull" and v in e.get("ack", []) and e["t"] >= t0, 60)
    plugin_config()
    start_mock(1)   # первый запрос (опрос) -> 503 ingest_disabled: обычный повтор
    vote = {"id": vid, "username": "Dave", "timestamp": str(int(time.time() * 1000))}
    mock_ctl({"votes": [vote], "diagnostics": [did]})   # голос ждёт на сайте, пока сервер выключен
    s = Server("2-pull")
    check("pull.server_done", s.wait(r"Done \(", 300))
    check("pull.bridge_ready", re.search(r"Готово: ключ \S+, сайт http://127\.0\.0\.1:\d+, голоса CraftRadar забираются", s.text()))
    check("pull.vote_fetched", wait_mock(lambda e: e.get("verdict") == "pull" and vid in e.get("votes", []), 90))
    check("pull.vote_acked", acked(vid, 0) and mock_ctl()["pending"] == [], mock_ctl())
    check("pull.reward_once", computed(vid) == 1 and re.search(rf"(?i)QUEUED\tcrdv1:{vid}\tdave", read(rl)), read(rl)[-300:])
    check("pull.diag_ack_delivered", wait_mock(lambda e: accepted(etype="diagnostic_ack")(e) and e["event"].get("diagnostic_id") == did, 60)
          and mock_ctl()["diagnostics"] == [])
    check("pull.diag_not_rewarded", computed(did) == 0)
    ev = [e for e in mock_events() if e.get("verdict") != "control"]
    check("pull.retry_after_503", ev and ev[0].get("verdict") == "simulated_503" and ev[0].get("pull")
          and any(e.get("verdict") == "pull" for e in ev[1:]), [e.get("verdict") for e in ev[:3]])
    check("pull.hmac_all_valid", not any(e.get("verdict", "").startswith(("bad_", "pull_4")) for e in ev), [e.get("verdict") for e in ev])
    s.cmd("crvotes status")
    check("pull.status_cmd", s.wait(r"Способ в кабинете: «Наш плагин»", 30)
          and s.wait(r"Голосов CraftRadar с запуска: 1, ждут подтверждения сайтом: 0", 10), s.text()[-400:])
    rc = s.stop()
    no_linkage_errors(s.text(), "pull")
    check("pull.clean_stop", "Disabling CraftRadarVotes" in s.text(), f"rc={rc}")

    # рестарт: сайт выдаёт тот же id снова (потерянный ack) + новый голос
    mock_ctl({"votes": [vote, {"id": vid2, "username": "Erin"}]})
    t0 = time.time()
    s = Server("3-pull-restart")
    check("pull_restart.server_done", s.wait(r"Done \(", 300))
    check("pull_restart.new_vote_acked", acked(vid2, t0))
    check("pull_restart.dup_acked_again", acked(vid, t0) and mock_ctl()["pending"] == [], mock_ctl())
    check("pull_restart.no_duplicate_reward", computed(vid) == 1 and computed(vid2) == 1, read(rl)[-400:])
    rc = s.stop()
    no_linkage_errors(s.text(), "pull_restart")
    check("pull_restart.clean_stop", "Disabling CraftRadarVotes" in s.text(), f"rc={rc}")

    # потеря pull-state.json: дубль отсекает дедуп наград по crdv1:<id>
    os.remove(ps)
    mock_ctl({"votes": [vote]})
    t0 = time.time()
    s = Server("4-pull-lost-state")
    check("pull_lost_state.server_done", s.wait(r"Done \(", 300))
    check("pull_lost_state.dup_acked", acked(vid, t0) and mock_ctl()["pending"] == [], mock_ctl())
    check("pull_lost_state.duplicate_rejected", computed(vid) == 1 and wait_text(rl, rf"REJECTED_DUPLICATE\tcrdv1:{vid}\t", 30), read(rl)[-400:])
    rc = s.stop()
    no_linkage_errors(s.text(), "pull_lost_state")
    check("pull_lost_state.clean_stop", "Disabling CraftRadarVotes" in s.text(), f"rc={rc}")


def network_flow():
    """Сеть: один ключ на двух серверах (A — srv, B — srv2 на соседнем порту)."""
    srv2 = os.path.join(wd, "srv2")
    os.makedirs(os.path.join(srv2, "plugins", "CraftRadarVotes"))
    plugin_config()
    cfg = os.path.join(srv, "plugins", "CraftRadarVotes", "config.yml")
    write(cfg, re.sub(r'(?m)^  offline_fallback_min: 30', '  offline_fallback_min: 1', read(cfg)))
    for d, n in ((srv, "crv-a"), (srv2, "crv-b")):
        if d == srv2:
            for f in ("paper.jar", "eula.txt", os.path.join("plugins", "craftradar-votes.jar")):
                shutil.copy(os.path.join(srv, f), os.path.join(srv2, f))
            write(os.path.join(srv2, "server.properties"), read(os.path.join(srv, "server.properties")).replace(f"server-port={MC}", f"server-port={MC + 1}"))
        write(os.path.join(d, "plugins", "CraftRadarVotes", "config.yml"), re.sub(r'(?m)^server_name: .*$', f'server_name: "{n}"', read(cfg)))
    rl = {d: os.path.join(d, "plugins", "CraftRadarVotes", "rewards.log") for d in (srv, srv2)}
    computed = lambda d, v: len(re.findall(rf"(?m)\tCOMPUTED\tcrdv1:{v}\t", read(rl[d])))
    total = lambda v: computed(srv, v) + computed(srv2, v)
    acked = lambda v: wait_mock(lambda e: e.get("verdict") == "pull" and v in e.get("ack", []), 90)
    start_mock(0)
    A, B = Server("2-net-a"), Server("2-net-b", srv2)
    check("net.servers_done", A.wait(r"Done \(", 300) and B.wait(r"Done \(", 300))
    pulls = lambda: [e for e in mock_events() if e.get("verdict") == "pull"]
    wait_mock(lambda e: e.get("verdict") == "pull" and "crv-b" in e.get("ua", ""), 60)
    insts = {e["inst"]: e["ua"] for e in pulls()}
    check("net.two_instances", len(insts) == 2 and all(re.match(r"^crv-[0-9a-f]{32}-\d+$", i) for i in insts), insts)
    check("net.user_agent", sorted(re.sub(r"\(\S+ [\d.]+;", "(CORE;", u) for u in insts.values()) == ["CraftRadarVotes/1.4.0 (CORE; crv-a)", "CraftRadarVotes/1.4.0 (CORE; crv-b)"], insts)
    # any: игрок нигде не в сети — голос получает ровно один сервер (аренда), награда в его офлайн-очереди
    v0 = "aa00000000000001"
    mock_ctl({"votes": [{"id": v0, "username": "Dave"}]})
    check("net_any.vote_acked", acked(v0))
    time.sleep(8)
    check("net_any.reward_once_on_one_server", total(v0) == 1, (computed(srv, v0), computed(srv2, v0)))
    # online: оба сервера — reward_where: online
    for d, s in ((srv, A), (srv2, B)):
        c = os.path.join(d, "plugins", "CraftRadarVotes", "config.yml")
        write(c, re.sub(r'(?m)^  reward_where: any', '  reward_where: online', read(c)))
        s.cmd("crvotes reload")
    check("net_online.reloaded", A.wait(r"сеть: награда там, где игрок онлайн", 60) and B.wait(r"сеть: награда там, где игрок онлайн", 60))
    env = dict(os.environ, NODE_PATH=a.bot)
    blog = os.path.join(wd, "bot-b.jsonl")
    bot = None
    if a.bot and not a.no_bot:
        bot = subprocess.Popen(["node", os.path.join(HERE, "bot.js"), str(MC + 1), "Bob", "120"] + ([a.bot_version] if a.bot_version else []),
                               stdout=open(blog, "w"), stderr=subprocess.STDOUT, env=env, start_new_session=True)
        check("net_online.bot_joined_b", wait_text(blog, '"spawned"', 90), read(blog)[-300:])
    else:
        B.cmd("say no bot")  # без бота сценарий online не проверить
        check("net_online.bot_available", False, "нужен --bot (mineflayer)")
    v1 = "bb00000000000001"
    t1 = time.time()
    mock_ctl({"votes": [{"id": v1, "username": "Bob"}]})
    check("net_online.vote_acked", acked(v1))
    time.sleep(5)
    check("net_online.reward_only_on_b", computed(srv2, v1) == 1 and computed(srv, v1) == 0, (computed(srv, v1), computed(srv2, v1)))
    check("net_online.granted_to_player_on_b", wait_text(rl[srv2], r"(?i)GRANTED\t[^\t]*\tbob", 20) and wait_text(blog, r'"diamonds": ?[1-9]', 20), read(rl[srv2])[-300:])
    a_inst = [i for i, u in insts.items() if u.endswith("crv-a)")]
    a_got = [e for e in pulls() if e["t"] >= t1 and e["inst"] in a_inst and v1 in e["votes"]]
    a_rel = [e for e in pulls() if e["t"] >= t1 and e["inst"] in a_inst and v1 in e.get("release", []) and e.get("released") == 1]
    check("net_online.a_released_if_offered", not a_got or a_rel, (len(a_got), len(a_rel)))
    # online, игрок нигде не в сети: оба возвращают; через offline_fallback_min (1 мин) голос берёт один сервер в офлайн-очередь
    v2 = "cc00000000000001"
    t2 = time.time()
    mock_ctl({"votes": [{"id": v2, "username": "Erin"}]})
    check("net_fallback.released_first", wait_mock(lambda e: e.get("verdict") == "pull" and e["t"] >= t2 and v2 in e.get("release", []), 60))
    check("net_fallback.not_before_timeout", total(v2) == 0)
    check("net_fallback.vote_acked", wait_mock(lambda e: e.get("verdict") == "pull" and v2 in e.get("ack", []), 180) and time.time() - t2 >= 55, round(time.time() - t2))
    time.sleep(5)
    check("net_fallback.queued_once", total(v2) == 1 and re.search(rf"QUEUED\tcrdv1:{v2}\terin", read(rl[srv]) + read(rl[srv2]), re.I), (computed(srv, v2), computed(srv2, v2)))
    for s in (A, B):
        s.cmd("crvotes status")
    check("net.status_released", B.wait(r"отдано другим серверам сети", 20) or A.wait(r"отдано другим серверам сети", 20))
    if bot:
        try:
            bot.wait(150)
        except subprocess.TimeoutExpired:
            os.killpg(bot.pid, signal.SIGKILL)
    for tag, s in (("net_a", A), ("net_b", B)):
        rc = s.stop()
        no_linkage_errors(s.text(), tag)
        check(f"{tag}.clean_stop", "Disabling CraftRadarVotes" in s.text(), f"rc={rc}")


def main():
    shutil.copy(os.path.join(a.cache, f"paper-{a.version}.jar"), os.path.join(srv, "paper.jar"))
    write(os.path.join(srv, "eula.txt"), "eula=true\n")
    write(os.path.join(srv, "server.properties"),
          f"online-mode=false\nserver-port={MC}\nserver-ip=127.0.0.1\nspawn-protection=0\nmax-players=5\nview-distance=3\n"
          "simulation-distance=3\nspawn-monsters=false\nspawn-animals=false\nspawn-npcs=false\ngenerate-structures=false\nenable-query=false\nenable-rcon=false\n")
    if not (a.no_votifier or a.pull):
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
    if a.no_votifier or a.pull:
        check("start1.no_votifier_info_once", t.count("NuVotifier не установлен — это нормально") == 1, t.count("NuVotifier не установлен"))
    else:
        check("start1.votifier_found_once", t.count("NuVotifier найден: голоса других мониторингов") == 1)
        check("start1.empty_key_error", "key_id пуст" in t)
    check("start1.config_created", os.path.exists(os.path.join(srv, "plugins", "CraftRadarVotes", "config.yml")))
    s.cmd("crvotes status")
    s.wait(r"Связь с CraftRadar: выключена или не настроена", 30)
    check("start1.status_cmd", "Связь с CraftRadar: выключена или не настроена" in s.text())
    rc = s.stop()
    no_linkage_errors(s.text(), "start1")
    check("start1.clean_stop", "Disabling CraftRadarVotes" in s.text(), f"rc={rc}")
    if a.no_votifier or not up:
        return
    if a.pull and a.network:
        return network_flow()
    if a.pull:
        return pull_flow()

    # 2. настройка и второй старт с моком (первый запрос мока -> 503)
    plugin_config()
    votifier_config()
    start_mock(1)
    s = Server("2-votes")
    check("start2.server_done", s.wait(r"Done \(", 300))
    t = s.text()
    check("start2.bridge_ready", re.search(r"Готово: ключ \S+, сайт http://127\.0\.0\.1:\d+", t))
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
    st_ok = False   # под нагрузкой последняя отправка может быть ещё в полёте: повторяем status до 30 с
    for _ in range(6):
        s.cmd("crvotes status")
        if s.wait(r"Очередь статистики: 0, в rejected/: 0", 5):
            st_ok = True
            break
    check("cmd.status", st_ok)
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
    check("start3.queue_restored", re.search(r"[Вв] очереди (статистики )?с прошлого запуска: 1", s.text()))
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
           "no_votifier": a.no_votifier, "pull": a.pull, "network": a.network, "passed": all(c["ok"] for c in checks) and bool(checks), "checks": checks}
    write(os.path.join(wd, "result.json"), json.dumps(res, ensure_ascii=False, indent=1))
    print("RESULT", name, "PASS" if res["passed"] else "FAIL", f"{sum(c['ok'] for c in checks)}/{len(checks)}")
    sys.exit(0 if res["passed"] else 1)
