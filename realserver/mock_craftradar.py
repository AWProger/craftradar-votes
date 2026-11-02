#!/usr/bin/env python3
"""Локальный мок CraftRadar для real-server тестов. Только stdlib, только 127.0.0.1.

/api/vote_ingest.php (§3): X-CR-Key, окно X-CR-Timestamp ±300 с, X-CR-Signature = hex(HMAC-SHA256(secret, ts.key_id.body)),
JSON-тело; принятые события пишет строкой JSON в --log. --fail-first N: первые N запросов -> 503 (проверка ретраев).
Файл --down: пока он существует, отвечает 503 (имитация недоступности сайта без остановки процесса).

/api/vote_pull.php (§10): та же подпись; ack/limit с проверкой (400 field=...), votes старые первыми, more, poll_after
(5 в pull, 60 в votifier), diagnostics (ждут diagnostic_ack в vote_ingest). 429 + retry_after сверх --pull-rate запросов
за 60 с на ключ; 503 ingest_disabled при --down/--fail-first, 503 pull_not_configured в режиме off.

Управление (только для сценария, без подписи): POST /_mock/queue {"votes":[{id,username,...}], "diagnostics":[id],
"mode":"pull|votifier|off"} — повторно поставленный id снова становится неподтверждённым (имитация потерянного ack);
GET /_mock/state -> {"mode", "pending":[id], "acked":[id], "diagnostics":[id]}.
Без TTL 7 дней и модерации: в тестах не нужны.
"""
import argparse, hashlib, hmac, json, os, re, secrets, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ID = re.compile(r"^[0-9a-f]{16}$")


def sign(secret, ts, key, body):
    return hmac.new(secret.encode(), (str(ts) + '.' + key + '.').encode() + body, hashlib.sha256).hexdigest()


def make_handler(a):
    lock = threading.Lock()
    st = {'n': 0, 'mode': a.pull_mode, 'votes': {}, 'diag': [], 'hits': []}   # votes: id -> {'v': dict, 'acked': bool}

    class H(BaseHTTPRequestHandler):
        def reply(self, code, obj):
            b = json.dumps(obj).encode()
            self.send_response(code); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(b)))
            self.end_headers(); self.wfile.write(b)

        def record(self, verdict, **kw):
            with open(a.log, 'a') as f:
                f.write(json.dumps(dict(verdict=verdict, t=time.time(), **kw), ensure_ascii=False) + '\n')

        def state(self):
            v = st['votes']
            return {'mode': st['mode'], 'pending': [i for i in v if not v[i]['acked']], 'acked': [i for i in v if v[i]['acked']],
                    'diagnostics': list(st['diag'])}

        def do_GET(self):
            if self.path != '/_mock/state':
                return self.reply(404, {'status': 'not_found'})
            with lock:
                return self.reply(200, self.state())

        def auth(self, body):
            key, ts, sig = self.headers.get('X-CR-Key', ''), self.headers.get('X-CR-Timestamp', ''), self.headers.get('X-CR-Signature', '')
            if key != a.key:
                self.record('bad_key', key=key); return self.reply(401, {'status': 'unauthorized'})
            if not ts.isdigit() or abs(int(ts) - time.time()) > 300:
                self.record('bad_ts', ts=ts); return self.reply(401, {'status': 'unauthorized', 'reason': 'timestamp'})
            if not hmac.compare_digest(sign(a.secret, ts, key, body), sig):
                self.record('bad_sig'); return self.reply(401, {'status': 'unauthorized', 'reason': 'signature'})
            return True

        def do_POST(self):
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            with lock:
                if self.path == '/_mock/queue':
                    return self.control(body)
                st['n'] += 1
                if self.path not in ('/api/vote_ingest.php', '/api/vote_pull.php'):
                    self.record('bad_path', path=self.path); return self.reply(404, {'status': 'not_found'})
                pull = self.path == '/api/vote_pull.php'
                if st['n'] <= a.fail_first or (a.down and os.path.exists(a.down)):
                    self.record('simulated_503', pull=pull); return self.reply(503, {'status': 'ingest_disabled' if pull else 'unavailable'})
                if self.auth(body) is not True:
                    return
                return self.pull(body) if pull else self.ingest(body)

        def ingest(self, body):
            try:
                ev = json.loads(body)
            except Exception:
                self.record('bad_json'); return self.reply(400, {'status': 'invalid'})
            if isinstance(ev, dict) and ev.get('event_type') == 'diagnostic_ack' and ev.get('diagnostic_id') in st['diag']:
                st['diag'].remove(ev['diagnostic_id'])
            self.record('accepted', event=ev, ua=self.headers.get('User-Agent', ''))
            return self.reply(202, {'status': 'accepted'})

        def pull(self, body):
            now = time.time()
            st['hits'] = [t for t in st['hits'] if now - t < 60]
            if len(st['hits']) >= a.pull_rate:
                ra = max(1, int(60 - (now - st['hits'][0])) + 1)
                self.record('pull_429', retry_after=ra); return self.reply(429, {'status': 'rate_limited', 'retry_after': ra})
            st['hits'].append(now)
            if st['mode'] == 'off':
                self.record('pull_503'); return self.reply(503, {'status': 'pull_not_configured'})
            bad = lambda f: (self.record('pull_400', field=f), self.reply(400, {'status': 'invalid', 'field': f}))
            if len(body) > 8192:
                return bad('body')
            try:
                req = json.loads(body) if body.strip() else {}
            except Exception:
                return bad('body')
            if not isinstance(req, dict):
                return bad('body')
            ack, limit = req.get('ack', []), req.get('limit', 50)
            if not isinstance(ack, list) or len(ack) > 100 or not all(isinstance(i, str) and ID.match(i) for i in ack):
                return bad('ack')
            if type(limit) is not int or not 0 <= limit <= 100:
                return bad('limit')
            acked = 0
            for i in ack:
                v = st['votes'].get(i)
                if v and not v['acked']:
                    v['acked'] = True; acked += 1
            pending = [] if st['mode'] == 'votifier' else [x['v'] for x in st['votes'].values() if not x['acked']]
            votes = pending[:limit]
            resp = {'status': 'ok', 'request_id': secrets.token_hex(8), 'mode': st['mode'],
                    'poll_after': 60 if st['mode'] == 'votifier' else 5, 'acked': acked, 'more': len(pending) > limit,
                    'votes': votes, 'diagnostics': list(st['diag'])}
            self.record('pull', ack=ack, limit=limit, acked=acked, votes=[v['id'] for v in votes], more=resp['more'])
            return self.reply(200, resp)

        def control(self, body):
            c = json.loads(body or b'{}')
            if c.get('mode'):
                st['mode'] = c['mode']
            for v in c.get('votes', []):
                v = dict({'service_name': 'CraftRadar', 'address': '127.0.0.1', 'timestamp': str(int(time.time() * 1000))}, **v)
                st['votes'].pop(v['id'], None)   # повтор id -> в конец очереди, снова не подтверждён
                st['votes'][v['id']] = {'v': v, 'acked': False}
            st['diag'] += [d for d in c.get('diagnostics', []) if d not in st['diag']]
            self.record('control', control=c)
            return self.reply(200, self.state())

        def log_message(self, *x): pass

    return H


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, required=True)
    ap.add_argument('--key', required=True)
    ap.add_argument('--secret', required=True)
    ap.add_argument('--log', required=True)
    ap.add_argument('--fail-first', type=int, default=0)
    ap.add_argument('--down', default='')
    ap.add_argument('--pull-mode', default='pull', choices=['pull', 'votifier', 'off'])
    ap.add_argument('--pull-rate', type=int, default=30, help='запросов vote_pull за 60 с на ключ, дальше 429')
    a = ap.parse_args(argv)
    return ThreadingHTTPServer(('127.0.0.1', a.port), make_handler(a))


if __name__ == '__main__':
    main().serve_forever()
