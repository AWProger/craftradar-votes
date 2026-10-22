#!/usr/bin/env python3
"""Локальный мок CraftRadar /api/vote_ingest.php для real-server тестов. Только stdlib, только 127.0.0.1.
Проверяет X-CR-Key, окно X-CR-Timestamp ±300 с и X-CR-Signature = hex(HMAC-SHA256(secret, ts.key_id.body)),
JSON-тело; принятые события пишет строкой JSON в --log. --fail-first N: первые N запросов -> 503 (проверка ретраев).
Файл --down: пока он существует, отвечает 503 (имитация недоступности сайта без остановки процесса).
"""
import argparse, hashlib, hmac, json, os, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ap = argparse.ArgumentParser()
ap.add_argument('--port', type=int, required=True)
ap.add_argument('--key', required=True)
ap.add_argument('--secret', required=True)
ap.add_argument('--log', required=True)
ap.add_argument('--fail-first', type=int, default=0)
ap.add_argument('--down', default='')
a = ap.parse_args()
state = {'n': 0}

class H(BaseHTTPRequestHandler):
    def reply(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(b)))
        self.end_headers(); self.wfile.write(b)

    def record(self, verdict, **kw):
        with open(a.log, 'a') as f:
            f.write(json.dumps(dict(verdict=verdict, t=time.time(), **kw), ensure_ascii=False) + '\n')

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        state['n'] += 1
        if self.path != '/api/vote_ingest.php':
            self.record('bad_path', path=self.path); return self.reply(404, {'status': 'not_found'})
        if state['n'] <= a.fail_first or (a.down and os.path.exists(a.down)):
            self.record('simulated_503'); return self.reply(503, {'status': 'unavailable'})
        key, ts, sig = self.headers.get('X-CR-Key', ''), self.headers.get('X-CR-Timestamp', ''), self.headers.get('X-CR-Signature', '')
        if key != a.key:
            self.record('bad_key', key=key); return self.reply(401, {'status': 'unauthorized'})
        if not ts.isdigit() or abs(int(ts) - time.time()) > 300:
            self.record('bad_ts', ts=ts); return self.reply(401, {'status': 'unauthorized', 'reason': 'timestamp'})
        exp = hmac.new(a.secret.encode(), (ts + '.' + key + '.').encode() + body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(exp, sig):
            self.record('bad_sig'); return self.reply(401, {'status': 'unauthorized', 'reason': 'signature'})
        try:
            ev = json.loads(body)
        except Exception:
            self.record('bad_json'); return self.reply(400, {'status': 'invalid'})
        self.record('accepted', event=ev, ua=self.headers.get('User-Agent', ''))
        return self.reply(202, {'status': 'accepted'})

    def log_message(self, *x): pass

ThreadingHTTPServer(('127.0.0.1', a.port), H).serve_forever()
