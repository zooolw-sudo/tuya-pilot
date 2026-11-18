#!/usr/bin/env python3
"""
Wzorcowe pakiety wygenerowane przez tinytuya (XenonDevice._encode_message) – test porównuje z nimi
pakiety budowane przez aplikację bajt w bajt. Wypisuje JSON na standardowe wyjście.
"""
import json
import logging

import tinytuya
from tinytuya.core import command_types as CT
from tinytuya.core.message_helper import MessagePayload

# w trybie DEBUG tinytuya używa stałego IV dla GCM ('0123456789ab') – potrzebne do porównania 3.5
logging.getLogger('tinytuya').setLevel(logging.DEBUG)
logging.getLogger('tinytuya.core.crypto_helper').setLevel(logging.DEBUG)
logging.basicConfig(level=logging.CRITICAL)

DEV = 'bf1234567890abcdefgh'
KEY = '0123456789abcdef'
SESSION = bytes(range(16))

out = []
cases = [
    ('3.1', CT.DP_QUERY, {'gwId': DEV, 'devId': DEV, 'uid': DEV, 't': '1700000000'}),
    ('3.1', CT.CONTROL, {'devId': DEV, 'uid': DEV, 't': '1700000000', 'dps': {'1': True}}),
    ('3.3', CT.DP_QUERY, {'gwId': DEV, 'devId': DEV, 'uid': DEV, 't': '1700000000'}),
    ('3.3', CT.CONTROL, {'devId': DEV, 'uid': DEV, 't': '1700000000', 'dps': {'1': False, '2': 50}}),
    ('3.3', CT.CONTROL_NEW, {'devId': DEV, 'uid': DEV, 't': '1700000000', 'dps': {'1': None}}),
    ('3.4', CT.DP_QUERY_NEW, {}),
    ('3.4', CT.CONTROL_NEW, {'protocol': 5, 't': 1700000000, 'data': {'dps': {'1': True}}}),
    ('3.5', CT.DP_QUERY_NEW, {}),
    ('3.5', CT.CONTROL_NEW, {'protocol': 5, 't': 1700000000, 'data': {'dps': {'1': True, '22': 500}}}),
]
for ver, cmd, data in cases:
    d = tinytuya.Device(DEV, '127.0.0.1', KEY, version=float(ver))
    d.seqno = 7
    if ver in ('3.4', '3.5'):
        d.local_key = SESSION
    payload = json.dumps(data).replace(' ', '').encode()
    pkt = d._encode_message(MessagePayload(cmd, payload))
    out.append({
        'version': ver, 'cmd': cmd, 'seq': 7,
        'key': (SESSION if ver in ('3.4', '3.5') else KEY.encode()).hex(),
        'payload': payload.hex(), 'iv': b'0123456789ab'.hex(), 'expected': pkt.hex(),
    })
print(json.dumps(out))
