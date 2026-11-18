#!/usr/bin/env python3
"""
Udawane API chmury Tuya do testu importu urządzeń.

Podpis każdego żądania jest sprawdzany tym samym algorytmem, którego używa tinytuya
(Cloud._tuyaplatform, „new_sign_algorithm”), więc test potwierdza zgodność podpisu aplikacji.

Użycie: cloud_mock.py PORT ACCESS_ID SECRET
"""
import hashlib
import hmac
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlsplit, parse_qsl

PORT, KEY, SECRET = int(sys.argv[1]), sys.argv[2], sys.argv[3]
TOKEN = 'tok-123'
state = {'expire_once': True, 'calls': []}

DEVICES_PAGE1 = [
    {'id': 'bf00000000000000sock', 'name': 'Gniazdo salon', 'local_key': '', 'category': 'cz',
     'product_id': 'prodsocket', 'product_name': 'Smart Plug', 'uid': 'eu1', 'online': True, 'sub': False},
]
DEVICES_PAGE2 = [
    {'id': 'bf0000000000000light', 'name': 'Lampa', 'local_key': 'k3y!light_000001', 'category': 'dj',
     'product_id': 'prodlight', 'product_name': 'Bulb', 'uid': 'eu1', 'online': True, 'sub': False},
    {'id': 'bf000000000000garage', 'name': 'Brama garażowa', 'local_key': 'garage+key/00002', 'category': 'ckmkzq',
     'product_id': 'prodgarage', 'product_name': 'Garage door', 'uid': 'eu1', 'online': True, 'sub': False},
]
BY_USER = [
    {'id': 'bf00000000000000sock', 'local_key': 'sock&key=0000003', 'model': 'SP-10'},
    {'id': 'bf0000000000000light', 'local_key': 'k3y!light_000001'},
    {'id': 'bf000000000000garage', 'local_key': 'garage+key/00002'},
]
SPECS = {
    'bf00000000000000sock': {
        'category': 'cz',
        'functions': [
            {'code': 'switch_1', 'dp_id': 1, 'type': 'Boolean', 'values': '{}'},
            {'code': 'countdown_1', 'dp_id': 9, 'type': 'Integer', 'values': '{"unit":"s","min":0,"max":86400,"scale":0,"step":1}'},
        ],
        'status': [
            {'code': 'switch_1', 'dp_id': 1, 'type': 'Boolean', 'values': '{}'},
            {'code': 'cur_power', 'dp_id': 19, 'type': 'Integer', 'values': '{"unit":"W","min":0,"max":99999,"scale":1,"step":1}'},
            {'code': 'cur_voltage', 'dp_id': 20, 'type': 'Integer', 'values': '{"unit":"V","min":0,"max":5000,"scale":1,"step":1}'},
        ],
    },
    'bf0000000000000light': {
        'category': 'dj',
        'functions': [
            {'code': 'switch_led', 'dp_id': 20, 'type': 'Boolean', 'values': '{}'},
            {'code': 'work_mode', 'dp_id': 21, 'type': 'Enum', 'values': '{"range":["white","colour","scene","music"]}'},
            {'code': 'bright_value_v2', 'dp_id': 22, 'type': 'Integer', 'values': '{"min":10,"max":1000,"scale":0,"step":1}'},
            {'code': 'colour_data_v2', 'dp_id': 24, 'type': 'Json', 'values': '{"h":{"min":0,"scale":0,"unit":"","max":360,"step":1},"s":{"min":0,"scale":0,"unit":"","max":1000,"step":1},"v":{"min":0,"scale":0,"unit":"","max":1000,"step":1}}'},
        ],
        'status': [],
    },
    'bf000000000000garage': {
        'category': 'ckmkzq',
        'functions': [
            {'code': 'switch_1', 'dp_id': 1, 'type': 'Boolean', 'values': '{}'},
        ],
        'status': [
            {'code': 'doorcontact_state', 'dp_id': 3, 'type': 'Boolean', 'values': '{}'},
        ],
    },
}


def tinytuya_sign(method, path_with_query, body, token, t):
    # dokładnie jak tinytuya/Cloud.py: payload = key [+ token] + t + METHOD\nSHA256(body)\n<nagłówki>\n/ścieżka
    payload = KEY + (token or '') + t
    payload += ('%s\n' % method + hashlib.sha256((body or '').encode('utf-8')).hexdigest() + '\n' + '' + '\n' + path_with_query)
    return hmac.new(SECRET.encode('utf-8'), msg=payload.encode('utf-8'), digestmod=hashlib.sha256).hexdigest().upper()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, obj):
        data = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urlsplit(self.path)
        q = dict(parse_qsl(u.query, keep_blank_values=True))
        # do podpisu: parametry alfabetycznie, bez kodowania
        signq = '&'.join('%s=%s' % (k, q[k]) for k in sorted(q))
        spath = u.path + ('?' + signq if signq else '')
        t = self.headers.get('t', '')
        token = self.headers.get('access_token')
        is_token = u.path == '/v1.0/token'
        want = tinytuya_sign('GET', spath, '', None if is_token else token, t)
        state['calls'].append(spath)
        if self.headers.get('client_id') != KEY or self.headers.get('sign') != want:
            return self.reply({'success': False, 'code': 1004, 'msg': 'sign invalid', 't': int(time.time() * 1000)})
        if is_token:
            return self.reply({'success': True, 'result': {'access_token': TOKEN, 'expire_time': 7200, 'uid': 'x'}, 't': int(time.time() * 1000)})
        if token != TOKEN:
            return self.reply({'success': False, 'code': 1010, 'msg': 'token invalid'})
        if state['expire_once']:
            state['expire_once'] = False
            return self.reply({'success': False, 'code': 1010, 'msg': 'token invalid'})
        if u.path == '/v1.0/iot-01/associated-users/devices':
            if q.get('last_row_key') == 'row 1/2':
                return self.reply({'success': True, 'result': {'devices': DEVICES_PAGE2, 'has_more': False, 'last_row_key': ''}})
            return self.reply({'success': True, 'result': {'devices': DEVICES_PAGE1, 'has_more': True, 'last_row_key': 'row 1/2'}})
        if u.path == '/v1.3/iot-03/devices':
            if q.get('source_id') != 'eu1' or q.get('source_type') != 'tuyaUser':
                return self.reply({'success': False, 'code': 1109, 'msg': 'param is illegal'})
            return self.reply({'success': True, 'result': {'list': BY_USER, 'has_more': False}})
        if u.path.startswith('/v1.1/devices/') and u.path.endswith('/specifications'):
            dev = u.path.split('/')[3]
            if dev in SPECS:
                return self.reply({'success': True, 'result': SPECS[dev]})
            return self.reply({'success': False, 'code': 2009, 'msg': 'not support this device'})
        return self.reply({'success': False, 'code': 1108, 'msg': 'uri path invalid'})


srv = HTTPServer(('127.0.0.1', PORT), H)
print('READY', flush=True)
srv.serve_forever()
