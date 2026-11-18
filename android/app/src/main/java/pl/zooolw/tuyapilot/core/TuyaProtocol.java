package pl.zooolw.tuyapilot.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Ramki lokalnego protokołu Tuya (port TCP 6668) w wersjach 3.1–3.5.
 *
 * 55AA (3.1–3.4): prefiks | nr | komenda | długość | [kod odpowiedzi] | dane | CRC32 albo HMAC | sufiks
 * 6699 (3.5):     prefiks | 0000 | nr | komenda | długość | IV(12) | dane AES-GCM | znacznik(16) | sufiks
 *
 * Opis formatu: dokument PROTOCOL.md projektu tinytuya (jasonacox/tinytuya), zgodnie z którym jest to napisane
 * i z którym jest testowane (tests/fake_device.py używa funkcji pakujących tinytuya).
 */
public final class TuyaProtocol {

    public static final int PREFIX_55AA = 0x000055AA;
    public static final int SUFFIX_55AA = 0x0000AA55;
    public static final int PREFIX_6699 = 0x00006699;
    public static final int SUFFIX_6699 = 0x00009966;

    public static final int SESS_KEY_NEG_START = 0x03;
    public static final int SESS_KEY_NEG_RESP = 0x04;
    public static final int SESS_KEY_NEG_FINISH = 0x05;
    public static final int CONTROL = 0x07;
    public static final int STATUS = 0x08;
    public static final int HEART_BEAT = 0x09;
    public static final int DP_QUERY = 0x0a;
    public static final int CONTROL_NEW = 0x0d;
    public static final int DP_QUERY_NEW = 0x10;
    public static final int UPDATEDPS = 0x12;
    public static final int UDP_NEW = 0x13;
    public static final int BOARDCAST_LPV34 = 0x23;
    public static final int REQ_DEVINFO = 0x25;
    public static final int LAN_EXT_STREAM = 0x40;

    /** Największa sensowna ramka – większa oznacza rozjechany strumień. */
    private static final int MAX_FRAME = 16 * 1024;

    private TuyaProtocol() { }

    /** Komendy wysyłane bez 15-bajtowego nagłówka wersji. */
    public static boolean noVersionHeader(int cmd) {
        switch (cmd) {
            case DP_QUERY:
            case DP_QUERY_NEW:
            case UPDATEDPS:
            case HEART_BEAT:
            case SESS_KEY_NEG_START:
            case SESS_KEY_NEG_RESP:
            case SESS_KEY_NEG_FINISH:
            case LAN_EXT_STREAM:
                return true;
            default:
                return false;
        }
    }

    /** Numer wersji jako liczba ×10 (31, 33, 34, 35) – wygodniej porównywać niż teksty. */
    public static int ver(String version) {
        if (version == null) return 33;
        switch (version.trim()) {
            case "3.1": return 31;
            case "3.2": return 32;
            case "3.4": return 34;
            case "3.5": return 35;
            default: return 33;
        }
    }

    public static byte[] versionHeader(String version) {
        byte[] h = new byte[15];
        byte[] v = version.trim().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(v, 0, h, 0, Math.min(3, v.length));
        return h;
    }

    // ---------- ramka odebrana ----------

    public static final class Frame {
        public final int prefix;
        public final long seq;
        public final int cmd;
        /** Kod odpowiedzi urządzenia (0 = OK) albo null, gdy ramka go nie ma. */
        public final Integer retcode;
        /** Dane po zdjęciu ramki (dla 3.5 już odszyfrowane z GCM, bez kodu odpowiedzi). */
        public final byte[] payload;
        /** IV ramki 6699 (do testów i diagnostyki). */
        public final byte[] iv;

        Frame(int prefix, long seq, int cmd, Integer retcode, byte[] payload, byte[] iv) {
            this.prefix = prefix;
            this.seq = seq;
            this.cmd = cmd;
            this.retcode = retcode;
            this.payload = payload;
            this.iv = iv;
        }

        @Override
        public String toString() {
            return String.format("Frame{%s seq=%d cmd=0x%02x ret=%s len=%d}",
                    prefix == PREFIX_6699 ? "6699" : "55AA", seq, cmd, retcode, payload.length);
        }
    }

    // ---------- pakowanie ----------

    /** Ramka 55AA od aplikacji do urządzenia (bez kodu odpowiedzi). hmacKey == null → CRC32 (3.1–3.3), inaczej HMAC (3.4). */
    public static byte[] pack55aa(long seq, int cmd, byte[] payload, byte[] hmacKey) {
        int tail = (hmacKey != null ? 32 : 4) + 4;
        int len = payload.length + tail;
        ByteBuffer b = ByteBuffer.allocate(16 + len);
        b.putInt(PREFIX_55AA).putInt((int) seq).putInt(cmd).putInt(len).put(payload);
        byte[] head = Arrays.copyOf(b.array(), 16 + payload.length);
        if (hmacKey != null) {
            b.put(TuyaCrypto.hmacSha256(hmacKey, head));
        } else {
            b.putInt((int) TuyaCrypto.crc32(head, 0, head.length));
        }
        b.putInt(SUFFIX_55AA);
        return b.array();
    }

    /** Ramka 6699 (3.5): całość danych szyfrowana AES-GCM, nagłówek jako dane uwierzytelniane (AAD). */
    public static byte[] pack6699(long seq, int cmd, byte[] plain, byte[] key, Integer retcode, byte[] iv) {
        byte[] raw = plain;
        if (retcode != null) {
            raw = TuyaCrypto.concat(ByteBuffer.allocate(4).putInt(retcode).array(), plain);
        }
        if (iv == null) iv = TuyaCrypto.random(12);
        int len = 12 + raw.length + 16;
        ByteBuffer h = ByteBuffer.allocate(18);
        h.putInt(PREFIX_6699).putShort((short) 0).putInt((int) seq).putInt(cmd).putInt(len);
        byte[] header = h.array();
        byte[] aad = Arrays.copyOfRange(header, 4, 18);
        byte[] enc = TuyaCrypto.gcmEncrypt(key, iv, aad, raw);
        return TuyaCrypto.concat(header, iv, enc, ByteBuffer.allocate(4).putInt(SUFFIX_6699).array());
    }

    /**
     * Pełny pakiet komendy dla danej wersji protokołu.
     *
     * @param key klucz lokalny (3.1–3.3) albo klucz sesji (3.4/3.5 po negocjacji; w trakcie negocjacji – klucz lokalny)
     */
    public static byte[] buildPacket(long seq, int cmd, byte[] data, String version, byte[] key) {
        int v = ver(version);
        boolean header = !noVersionHeader(cmd);
        if (v >= 35) {
            byte[] plain = header ? TuyaCrypto.concat(versionHeader(version), data) : data;
            return pack6699(seq, cmd, plain, key, null, null);
        }
        if (v == 34) {
            byte[] plain = header ? TuyaCrypto.concat(versionHeader(version), data) : data;
            return pack55aa(seq, cmd, TuyaCrypto.ecbEncrypt(key, plain, true), key);
        }
        if (v >= 32) {
            byte[] enc = TuyaCrypto.ecbEncrypt(key, data, true);
            if (header) enc = TuyaCrypto.concat(versionHeader(version), enc);
            return pack55aa(seq, cmd, enc, null);
        }
        // 3.1: szyfrowane jest tylko sterowanie
        if (cmd == CONTROL) {
            byte[] b64 = Base64.getEncoder().encode(TuyaCrypto.ecbEncrypt(key, data, true));
            byte[] pre = TuyaCrypto.concat("data=".getBytes(StandardCharsets.US_ASCII), b64,
                    "||lpv=3.1||".getBytes(StandardCharsets.US_ASCII), key);
            String md5 = TuyaCrypto.hex(TuyaCrypto.md5(pre)).substring(8, 24);
            byte[] payload = TuyaCrypto.concat("3.1".getBytes(StandardCharsets.US_ASCII),
                    md5.getBytes(StandardCharsets.US_ASCII), b64);
            return pack55aa(seq, cmd, payload, null);
        }
        return pack55aa(seq, cmd, data, null);
    }

    // ---------- odczyt ----------

    /**
     * Czyta jedną ramkę od urządzenia.
     *
     * @param key     klucz do odszyfrowania GCM (6699) albo sprawdzenia HMAC (55AA w 3.4)
     * @param hmac    czy ramki 55AA mają HMAC zamiast CRC (wersja 3.4)
     * @param hasRetcode czy ramki 55AA mają 4-bajtowy kod odpowiedzi (urządzenie → aplikacja: tak)
     */
    public static Frame readFrame(InputStream in, byte[] key, boolean hmac, boolean hasRetcode) throws IOException, TuyaException {
        // synchronizacja na prefiksie – pomija ewentualne śmieci w strumieniu
        int word = 0;
        int skipped = 0;
        for (int i = 0; i < 4; i++) word = (word << 8) | readByte(in);
        while (word != PREFIX_55AA && word != PREFIX_6699) {
            if (++skipped > MAX_FRAME) throw new TuyaException(TuyaException.Kind.PROTOCOL, "brak prefiksu ramki");
            word = (word << 8) | readByte(in);
        }
        if (word == PREFIX_55AA) {
            byte[] h = readFully(in, 12);
            ByteBuffer hb = ByteBuffer.wrap(h);
            long seq = hb.getInt() & 0xffffffffL;
            int cmd = hb.getInt();
            int len = hb.getInt();
            if (len < 8 || len > MAX_FRAME) throw new TuyaException(TuyaException.Kind.PROTOCOL, "zła długość ramki 55AA: " + len);
            byte[] body = readFully(in, len);
            return parse55aaBody(seq, cmd, h, body, key, hmac, hasRetcode);
        }
        byte[] h = readFully(in, 14);
        ByteBuffer hb = ByteBuffer.wrap(h);
        hb.getShort();
        long seq = hb.getInt() & 0xffffffffL;
        int cmd = hb.getInt();
        int len = hb.getInt();
        if (len < 28 || len > MAX_FRAME) throw new TuyaException(TuyaException.Kind.PROTOCOL, "zła długość ramki 6699: " + len);
        byte[] body = readFully(in, len + 4);
        return parse6699Body(seq, cmd, h, body, key, true);
    }

    /** Rozbiór całej ramki z bufora (np. pakiet UDP). */
    public static Frame parse(byte[] data, byte[] key, boolean hmac, boolean hasRetcode) throws TuyaException {
        try {
            return readFrame(new java.io.ByteArrayInputStream(data), key, hmac, hasRetcode);
        } catch (IOException e) {
            throw new TuyaException(TuyaException.Kind.PROTOCOL, "niepełna ramka");
        }
    }

    private static Frame parse55aaBody(long seq, int cmd, byte[] h, byte[] body, byte[] key, boolean hmac, boolean hasRetcode)
            throws TuyaException {
        int tail = (hmac ? 32 : 4) + 4;
        if (body.length < tail) throw new TuyaException(TuyaException.Kind.PROTOCOL, "za krótka ramka 55AA");
        int dataEnd = body.length - tail;
        if (hmac) {
            byte[] signed = TuyaCrypto.concat(ByteBuffer.allocate(4).putInt(PREFIX_55AA).array(), h, Arrays.copyOf(body, dataEnd));
            byte[] want = TuyaCrypto.hmacSha256(key, signed);
            byte[] got = Arrays.copyOfRange(body, dataEnd, dataEnd + 32);
            if (!java.security.MessageDigest.isEqual(want, got)) {
                throw new TuyaException(TuyaException.Kind.BAD_KEY, "HMAC ramki się nie zgadza");
            }
        }
        Integer ret = null;
        int start = 0;
        if (hasRetcode && dataEnd >= 4) {
            ret = ByteBuffer.wrap(body, 0, 4).getInt();
            start = 4;
        }
        return new Frame(PREFIX_55AA, seq, cmd, ret, Arrays.copyOfRange(body, start, dataEnd), null);
    }

    /**
     * @param stripRetcode true dla ramek TCP (kod odpowiedzi zawsze jest), false = zgadywanie jak w tinytuya
     *                     (rozgłoszenia UDP bywają bez kodu)
     */
    private static Frame parse6699Body(long seq, int cmd, byte[] h, byte[] body, byte[] key, boolean stripRetcode)
            throws TuyaException {
        if (key == null) throw new TuyaException(TuyaException.Kind.PROTOCOL, "brak klucza do ramki 6699");
        int len = body.length - 4;
        byte[] iv = Arrays.copyOfRange(body, 0, 12);
        byte[] enc = Arrays.copyOfRange(body, 12, len);
        byte[] plain = TuyaCrypto.gcmDecrypt(key, iv, h, enc);
        Integer ret = null;
        if (stripRetcode) {
            if (plain.length >= 4) {
                ret = ByteBuffer.wrap(plain, 0, 4).getInt();
                plain = Arrays.copyOfRange(plain, 4, plain.length);
            }
        } else if (plain.length > 4 && plain[0] != '{' && plain[4] == '{') {
            ret = ByteBuffer.wrap(plain, 0, 4).getInt();
            plain = Arrays.copyOfRange(plain, 4, plain.length);
        }
        return new Frame(PREFIX_6699, seq, cmd, ret, plain, iv);
    }

    /** Ramka 6699 z rozgłoszenia UDP – kod odpowiedzi może nie występować. */
    public static Frame parseUdp6699(byte[] data, byte[] key) throws TuyaException {
        if (data.length < 18 + 28 + 4) throw new TuyaException(TuyaException.Kind.PROTOCOL, "za krótki pakiet 6699");
        ByteBuffer hb = ByteBuffer.wrap(data, 4, 14);
        hb.getShort();
        long seq = hb.getInt() & 0xffffffffL;
        int cmd = hb.getInt();
        int len = hb.getInt();
        if (18 + len + 4 > data.length) throw new TuyaException(TuyaException.Kind.PROTOCOL, "niepełny pakiet 6699");
        byte[] h = Arrays.copyOfRange(data, 4, 18);
        byte[] body = Arrays.copyOfRange(data, 18, 18 + len + 4);
        return parse6699Body(seq, cmd, h, body, key, false);
    }

    // ---------- treść ----------

    /**
     * Zamienia dane ramki na tekst JSON (albo "" dla pustego potwierdzenia).
     *
     * @param key klucz lokalny (3.1–3.3) albo klucz sesji (3.4)
     */
    public static String decodePayload(byte[] payload, String version, byte[] key) throws TuyaException {
        int v = ver(version);
        byte[] p = payload;
        if (p.length == 0) return "";
        if (v == 34) {
            if (p.length % 16 != 0) throw new TuyaException(TuyaException.Kind.BAD_VERSION, "dane nie pasują do wersji 3.4");
            p = TuyaCrypto.ecbDecrypt(key, p);
            p = stripVersionHeader(p);
        } else if (v >= 35) {
            p = stripVersionHeader(p);
        } else if (v >= 32) {
            if (isVersionHeader(p)) {
                p = Arrays.copyOfRange(p, 15, p.length);
            } else if (p.length % 16 == 15) {
                p = Arrays.copyOfRange(p, 15, p.length); // device22: nagłówek bez numeru wersji
            }
            if (p.length > 0 && p[0] != '{') {
                if (p.length % 16 != 0) throw new TuyaException(TuyaException.Kind.BAD_VERSION, "dane nie pasują do wersji " + version);
                p = TuyaCrypto.ecbDecrypt(key, p);
            }
        } else {
            if (TuyaCrypto.startsWith(p, "3.1".getBytes(StandardCharsets.US_ASCII))) {
                if (p.length <= 19) return "";
                byte[] b64 = Arrays.copyOfRange(p, 19, p.length);
                byte[] enc;
                try {
                    enc = Base64.getDecoder().decode(b64);
                } catch (IllegalArgumentException e) {
                    throw new TuyaException(TuyaException.Kind.PROTOCOL, "zły base64 w odpowiedzi 3.1");
                }
                p = TuyaCrypto.ecbDecrypt(key, enc);
            } else if (p[0] != '{') {
                throw new TuyaException(TuyaException.Kind.BAD_VERSION, "odpowiedź nie wygląda na wersję 3.1");
            }
        }
        // końcowe zera i białe znaki zdarzają się w odpowiedziach
        int end = p.length;
        while (end > 0 && (p[end - 1] == 0 || p[end - 1] == ' ' || p[end - 1] == '\n' || p[end - 1] == '\r')) end--;
        return new String(p, 0, end, StandardCharsets.UTF_8);
    }

    private static boolean isVersionHeader(byte[] p) {
        return p.length >= 15 && p[0] == '3' && p[1] == '.' && p[2] >= '0' && p[2] <= '9';
    }

    private static byte[] stripVersionHeader(byte[] p) {
        return isVersionHeader(p) ? Arrays.copyOfRange(p, 15, p.length) : p;
    }

    // ---------- pomocnicze ----------

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("urządzenie zamknęło połączenie");
        return b;
    }

    static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int o = 0;
        while (o < n) {
            int r = in.read(b, o, n - o);
            if (r < 0) throw new EOFException("urządzenie zamknęło połączenie");
            o += r;
        }
        return b;
    }

}
