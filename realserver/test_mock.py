#!/usr/bin/env python3
"""Самопроверка мока: python3 -m unittest realserver/test_mock.py (из корня репо) или python3 realserver/test_mock.py."""
import json, os, sys, tempfile, threading, time, unittest, urllib.error, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mock_craftradar as m

KEY, SECRET = 'crk_test00000000ab', 'test-secret-not-real'
VEC = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'docs', 'protocol-vectors.json')


class MockPull(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.down = os.path.join(self.dir.name, 'down')
        self.srv = m.main(['--port', '0', '--key', KEY, '--secret', SECRET, '--log', os.path.join(self.dir.name, 'log'),
                           '--down', self.down, '--pull-rate', '8'])
        self.url = 'http://127.0.0.1:%d' % self.srv.server_port
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown(); self.srv.server_close(); self.dir.cleanup()

    def http(self, path, body=None, headers=None):
        r = urllib.request.Request(self.url + path, data=body, headers=headers or {}, method='POST' if body is not None else 'GET')
        try:
            with urllib.request.urlopen(r) as f:
                return f.status, json.loads(f.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def pull(self, obj=None, raw=None, secret=SECRET):
        b = raw if raw is not None else json.dumps(obj or {}).encode()
        ts = str(int(time.time()))
        return self.http('/api/vote_pull.php', b, {'X-CR-Key': KEY, 'X-CR-Timestamp': ts, 'X-CR-Signature': m.sign(secret, ts, KEY, b)})

    def queue(self, **c):
        return self.http('/_mock/queue', json.dumps(c).encode())

    def test_signature_vector(self):
        v = next(x for x in json.load(open(VEC))['vectors'] if x['name'] == 'pull_request')
        self.assertEqual(m.sign(v['secret'], v['ts'], v['key_id'], v['body'].encode()), v['signature'])

    def test_flow(self):
        self.assertEqual(self.pull(secret='wrong')[0], 401)
        self.assertEqual(self.pull({'ack': ['XYZ']}), (400, {'status': 'invalid', 'field': 'ack'}))
        self.assertEqual(self.pull({'limit': 101})[1]['field'], 'limit')
        self.assertEqual(self.pull({'limit': True})[1]['field'], 'limit')
        self.queue(votes=[{'id': '%016x' % i, 'username': 'U%d' % i} for i in (1, 2, 3)], diagnostics=['00112233aabbccdd'])
        c, r = self.pull(raw=b'', obj=None)   # пустое тело = {}
        self.assertEqual((c, r['mode'], r['poll_after'], r['more'], len(r['votes'])), (200, 'pull', 5, False, 3))
        self.assertEqual(r['votes'][0]['service_name'], 'CraftRadar')
        self.assertEqual(r['diagnostics'], ['00112233aabbccdd'])
        c, r = self.pull({'ack': ['%016x' % 1, 'ffffffffffffffff'], 'limit': 1})
        self.assertEqual((r['acked'], [v['id'] for v in r['votes']], r['more']), (1, ['%016x' % 2], True))
        self.assertEqual(self.pull({'ack': ['%016x' % 1], 'limit': 0})[1]['acked'], 0)   # повторный ack не считается
        self.queue(mode='votifier')
        r = self.pull()[1]
        self.assertEqual((r['votes'], r['poll_after']), ([], 60))
        self.queue(mode='off')
        self.assertEqual(self.pull(), (503, {'status': 'pull_not_configured'}))
        open(self.down, 'w').close()
        self.assertEqual(self.pull()[1]['status'], 'ingest_disabled')
        os.remove(self.down)
        c, r = self.pull()   # 9-й подписанный запрос при --pull-rate 8
        self.assertEqual((c, r['status']), (429, 'rate_limited'))
        self.assertGreaterEqual(r['retry_after'], 1)


if __name__ == '__main__':
    unittest.main()
