#!/usr/bin/env python3
"""
Udawane urządzenie Tuya do testów rdzenia aplikacji.

Ramki pakuje i rozpakowuje biblioteką tinytuya (jasonacox/tinytuya) – czyli niezależną,
sprawdzoną na prawdziwych urządzeniach implementacją protokołu. Jeśli aplikacja dogada się
z tym urządzeniem w wersjach 3.1–3.5, to mówi tym samym językiem co tinytuya.

Użycie:
  fake_device.py --version 3.5 --port 16668 --key 0123456789abcdef --id bf1234567890abcdefgh
                 [--device22] [--bcast-port 16667] [--bcast-host 127.0.0.1]
"""
import argparse
import hmac
import json
import os
import socket
import struct
import sys
import threading
import time
from hashlib import md5, sha256

import tinytuya
from tinytuya.core import message_helper as MH
from tinytuya.core import header as H
from tinytuya.core import command_types as CT
from tinytuya.core.crypto_helper import AESCipher

ap = argparse.ArgumentParser()
ap.add_argument('--version', default='3.3')
ap.add_argument('--port', type=int, default=16668)
ap.add_argument('--key', default='0123456789abcdef')
ap.add_argument('--id', default='bf1234567890abcdefgh')
ap.add_argument('--device22', action='store_true')
ap.add_argument('--bcast-port', type=int, default=0)
ap.add_argument('--bcast-host', default='127.0.0.1')
args = ap.parse_args()

VER = args.version
V = float(VER)
REAL_KEY = args.key.encode()
DEV_ID = args.id
STATE = {'1': True, '9': 0, '18': 120, '19': 276, '20': 2315}
RET0 = struct.pack('>I', 0)


def log(*a):
    print('[fake %s]' % VER, *a, file=sys.stderr, flush=True)


def ecb_enc(key, raw):
    return AESCipher(key).encrypt(raw, use_base64=False, pad=True)


def ecb_dec(key, raw):
    return AESCipher(key).decrypt(raw, use_base64=False, decode_text=False)


def pack55(seq, cmd, payload, hmac_key=None):
    # urządzenie → aplikacja: z 4-bajtowym kodem odpowiedzi przed danymi
    msg = MH.TuyaMessage(seq, cmd, 0, RET0 + payload, 0, True, H.PREFIX_55AA_VALUE, None)
    return MH.pack_message(msg, hmac_key=hmac_key)


def pack66(seq, cmd, payload, key):
    msg = MH.TuyaMessage(seq, cmd, 0, payload, 0, True, H.PREFIX_6699_VALUE, True)
    return MH.pack_message(msg, hmac_key=key)


def vhdr():
    return VER.encode() + b'\x00' * 12


def src_hdr(seq):
    # nagłówek źródła: wersja | CRC | nr | id źródła (urządzenie = 1)
    return VER.encode() + struct.pack('>III', 0, seq, 1)


def recv_frame(conn, buf, key, hmac55):
    """Zwraca (wiadomość, reszta bufora) albo (None, bufor) gdy za mało danych."""
    while True:
        try:
            hdr = MH.parse_header(buf)
        except Exception:
            if len(buf) >= 4 and buf[:4] not in (H.PREFIX_55AA_BIN, H.PREFIX_6699_BIN):
                raise ValueError('nieznany prefiks')
            hdr = None
        if hdr and len(buf) >= hdr.total_length:
            data, rest = buf[:hdr.total_length], buf[hdr.total_length:]
            if hdr.prefix == H.PREFIX_6699_VALUE:
                m = MH.unpack_message(data, hmac_key=key, no_retcode=True)
            else:
                m = MH.unpack_message(data, hmac_key=key if hmac55 else None, no_retcode=True)
            return m, rest
        chunk = conn.recv(4096)
        if not chunk:
            return None, buf
        buf += chunk


def handle(conn):
    seq = 100
    key = REAL_KEY
    client_nonce = None
    dev_nonce = os.urandom(16)
    buf = b''
    while True:
        try:
            m, buf = recv_frame(conn, buf, key, V == 3.4)
        except Exception as e:
            log('zła ramka – rozłączam:', e)
            return
        if m is None:
            return
        if V >= 3.5 and m.prefix != H.PREFIX_6699_VALUE or V < 3.5 and m.prefix != H.PREFIX_55AA_VALUE:
            log('ramka w innej wersji – rozłączam')
            return
        if not m.crc_good:
            log('zły CRC/HMAC/GCM – rozłączam')
            return
        seq += 1
        out = []

        if m.cmd == CT.SESS_KEY_NEG_START and V >= 3.4:
            p = ecb_dec(REAL_KEY, m.payload) if V == 3.4 else m.payload
            client_nonce = p[:16]
            resp = dev_nonce + hmac.new(REAL_KEY, client_nonce, sha256).digest()
            if V == 3.4:
                out.append(pack55(seq, CT.SESS_KEY_NEG_RESP, ecb_enc(REAL_KEY, resp), REAL_KEY))
            else:
                out.append(pack66(seq, CT.SESS_KEY_NEG_RESP, resp, REAL_KEY))
        elif m.cmd == CT.SESS_KEY_NEG_FINISH and V >= 3.4:
            p = ecb_dec(REAL_KEY, m.payload) if V == 3.4 else m.payload
            if p[:32] != hmac.new(REAL_KEY, dev_nonce, sha256).digest():
                log('zły HMAC w FINISH – rozłączam')
                return
            x = bytes(a ^ b for a, b in zip(client_nonce, dev_nonce))
            c = AESCipher(REAL_KEY)
            if V == 3.4:
                key = c.encrypt(x, use_base64=False, pad=False)
            else:
                key = c.encrypt(x, use_base64=False, pad=False, iv=client_nonce[:12])[12:28]
            log('klucz sesji', key.hex())
        else:
            # treść komendy
            p = m.payload
            if V == 3.4:
                p = ecb_dec(key, p)
                if p[:3] == VER.encode():
                    p = p[15:]
            elif V >= 3.5:
                if p[:3] == VER.encode():
                    p = p[15:]
            elif V >= 3.2:
                if p[:3] == VER.encode():
                    p = p[15:]
                p = ecb_dec(key, p)
            else:
                if m.cmd == CT.CONTROL:
                    assert p[:3] == b'3.1'
                    b64 = p[19:]
                    want = md5(b'data=' + b64 + b'||lpv=3.1||' + REAL_KEY).hexdigest()[8:24].encode()
                    if p[3:19] != want:
                        log('zły skrót MD5 w 3.1 – ignoruję')
                        continue
                    p = AESCipher(REAL_KEY).decrypt(b64, use_base64=True, decode_text=False)
            try:
                req = json.loads(p.decode())
            except Exception as e:
                log('nie JSON:', p, e)
                return
            log('komenda 0x%02x' % m.cmd, req)

            if m.cmd in (CT.DP_QUERY, CT.DP_QUERY_NEW) or (m.cmd == CT.CONTROL_NEW and V < 3.4):
                if args.device22 and m.cmd == CT.DP_QUERY:
                    body = b'json obj data unvalid'
                    out.append(pack55(seq, m.cmd, ecb_enc(key, body)))
                else:
                    resp = {'devId': DEV_ID, 'dps': STATE, 't': int(time.time())}
                    if V >= 3.4:
                        resp = {'protocol': 4, 't': int(time.time()), 'data': {'dps': STATE}}
                    raw = json.dumps(resp).encode()
                    out.append(enc_out(seq, m.cmd, raw, key, header=(m.cmd == CT.CONTROL_NEW)))
            elif m.cmd in (CT.CONTROL, CT.CONTROL_NEW):
                dps = req.get('dps') or req.get('data', {}).get('dps') or {}
                STATE.update(dps)
                # potwierdzenie bez treści, potem zgłoszenie nowego stanu
                out.append(enc_out(seq, m.cmd, b'', key))
                seq += 1
                if V >= 3.4:
                    st = {'protocol': 4, 't': int(time.time()), 'data': {'dps': dps}}
                else:
                    st = {'devId': DEV_ID, 'dps': dps, 't': int(time.time())}
                out.append(enc_out(seq, CT.STATUS, json.dumps(st).encode(), key, header=True, source=True))
            elif m.cmd == CT.HEART_BEAT:
                out.append(enc_out(seq, CT.HEART_BEAT, b'', key))
            else:
                log('nieobsługiwana komenda', m.cmd)
        for o in out:
            conn.sendall(o)


def enc_out(seq, cmd, raw, key, header=False, source=False):
    """Szyfruje odpowiedź tak, jak robi to urządzenie w danej wersji."""
    if V >= 3.5:
        if raw and header:
            raw = (src_hdr(seq) if source else vhdr()) + raw
        return pack66(seq, cmd, raw, key)
    if V == 3.4:
        if not raw:
            return pack55(seq, cmd, b'', key)
        if header:
            raw = (src_hdr(seq) if source else vhdr()) + raw
        return pack55(seq, cmd, ecb_enc(key, raw), key)
    if V >= 3.2:
        if not raw:
            return pack55(seq, cmd, b'')
        enc = ecb_enc(key, raw)
        if header:
            enc = (src_hdr(seq) if source else vhdr()) + enc
        return pack55(seq, cmd, enc)
    # 3.1: zapytania jawnie, zgłoszenia stanu zaszyfrowane jak sterowanie
    if cmd == CT.STATUS and raw:
        b64 = AESCipher(REAL_KEY).encrypt(raw, use_base64=True, pad=True)
        sig = md5(b'data=' + b64 + b'||lpv=3.1||' + REAL_KEY).hexdigest()[8:24].encode()
        return pack55(seq, cmd, b'3.1' + sig + b64)
    return pack55(seq, cmd, raw)


def broadcaster():
    info = {'ip': '192.168.1.77', 'gwId': DEV_ID, 'active': 2, 'ability': 0, 'mode': 0,
            'encrypt': V >= 3.3, 'productKey': 'keyfake123', 'version': VER}
    raw = json.dumps(info).encode()
    udpkey = md5(b'yGAdlopoPVldABfn').digest()
    if V >= 3.5:
        pkt = MH.pack_message(MH.TuyaMessage(1, CT.UDP_NEW, 0, raw, 0, True, H.PREFIX_6699_VALUE, True), hmac_key=udpkey)
    elif V >= 3.2:
        pkt = MH.pack_message(MH.TuyaMessage(1, CT.UDP_NEW, 0, RET0 + ecb_enc(udpkey, raw), 0, True, H.PREFIX_55AA_VALUE, None))
    else:
        pkt = MH.pack_message(MH.TuyaMessage(1, 0, 0, RET0 + raw, 0, True, H.PREFIX_55AA_VALUE, None))
    # pakiet musi dać się odczytać tinytuyą
    assert tinytuya.decrypt_udp(pkt) if hasattr(tinytuya, 'decrypt_udp') else True
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    while True:
        try:
            s.sendto(pkt, (args.bcast_host, args.bcast_port))
        except OSError:
            pass
        time.sleep(0.3)


def main():
    if args.bcast_port:
        threading.Thread(target=broadcaster, daemon=True).start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('127.0.0.1', args.port))
    srv.listen(4)
    print('READY', flush=True)
    while True:
        conn, _ = srv.accept()
        conn.settimeout(10)
        try:
            handle(conn)
        except Exception as e:
            log('wyjątek:', repr(e))
        finally:
            conn.close()


if __name__ == '__main__':
    main()
