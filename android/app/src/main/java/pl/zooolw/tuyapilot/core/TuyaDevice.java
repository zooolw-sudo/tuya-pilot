package pl.zooolw.tuyapilot.core;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Połączenie z jednym urządzeniem Tuya w sieci lokalnej (TCP 6668): odczyt stanu i ustawianie punktów danych (DP).
 * Jedno połączenie na jedną operację – urządzenia Tuya przyjmują mało równoległych połączeń,
 * więc aplikacja nie trzyma ich otwartych.
 */
public class TuyaDevice implements Closeable {

    public static final int PORT = 6668;

    public final String id;
    public final String host;
    public final int port;
    private final byte[] realKey;
    private String version;
    private boolean device22;
    private final List<String> knownDps = new ArrayList<>();

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private byte[] sessionKey;
    private long seq = 1;

    /** Ostatnie zdarzenie, przydatne w diagnostyce. */
    public final List<String> log = new ArrayList<>();

    public TuyaDevice(String id, String host, int port, String localKey, String version) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("brak ID urządzenia");
        if (localKey == null) localKey = "";
        byte[] k = localKey.getBytes(StandardCharsets.UTF_8);
        if (k.length != 16) throw new IllegalArgumentException("klucz lokalny musi mieć 16 znaków (ma " + k.length + ")");
        this.id = id;
        this.host = host;
        this.port = port <= 0 ? PORT : port;
        this.realKey = k;
        this.version = version == null || version.isEmpty() ? "3.3" : version;
    }

    public TuyaDevice setTimeouts(int connectMs, int readMs) {
        connectTimeoutMs = connectMs;
        readTimeoutMs = readMs;
        return this;
    }

    /** Urządzenia „device22” odpowiadają na zapytanie o stan tylko z listą DP. */
    public TuyaDevice setDevice22(boolean on) {
        device22 = on;
        return this;
    }

    public boolean isDevice22() { return device22; }

    public String version() { return version; }

    /** Znane numery DP (z mapy z chmury) – potrzebne w trybie device22. */
    public TuyaDevice setKnownDps(List<String> dps) {
        knownDps.clear();
        if (dps != null) knownDps.addAll(dps);
        return this;
    }

    // ---------- operacje ----------

    /** Pełny stan urządzenia: numer DP → wartość. */
    public Map<String, Object> status() throws TuyaException {
        ensureConnected();
        try {
            return queryOnce();
        } catch (Device22Exception e) {
            note("urządzenie wymaga trybu device22");
            device22 = true;
            try {
                return queryOnce();
            } catch (Device22Exception again) {
                throw new TuyaException(TuyaException.Kind.PROTOCOL, "urządzenie odrzuca zapytanie o stan (data unvalid)");
            }
        }
    }

    /**
     * Ustawia wartości DP i zwraca stan po zmianie (to, co urządzenie zgłosiło, uzupełnione o pełny odczyt).
     */
    public Map<String, Object> set(Map<String, Object> dps) throws TuyaException {
        ensureConnected();
        int v = TuyaProtocol.ver(version);
        Map<String, Object> json = new LinkedHashMap<>();
        int cmd;
        if (v >= 34) {
            cmd = TuyaProtocol.CONTROL_NEW;
            json.put("protocol", 5L);
            json.put("t", nowSec());
            json.put("data", Json.map("dps", dps));
        } else {
            cmd = TuyaProtocol.CONTROL;
            json.put("devId", id);
            json.put("uid", id);
            json.put("t", String.valueOf(nowSec()));
            json.put("dps", dps);
        }
        send(cmd, Json.write(json));
        Map<String, Object> result = new LinkedHashMap<>();
        // urządzenie potwierdza komendę i zwykle od razu wysyła nowy stan; czekamy chwilę
        long deadline = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> got;
            try {
                got = readDps((int) Math.max(200, deadline - System.currentTimeMillis()), false);
            } catch (TuyaException e) {
                if (e.kind == TuyaException.Kind.TIMEOUT) break;
                throw e;
            } catch (Device22Exception e) {
                break;
            }
            if (got == null) continue;
            result.putAll(got);
            if (containsAll(result, dps)) break;
        }
        // pełny odczyt, żeby aplikacja widziała też DP, które zmieniły się pośrednio
        try {
            Map<String, Object> full = status();
            full.putAll(result);
            return full;
        } catch (TuyaException e) {
            if (!result.isEmpty()) return result;
            throw e;
        }
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null;
        in = null;
        out = null;
        sessionKey = null;
    }

    // ---------- rozpoznanie wersji ----------

    /**
     * Próbuje kolejnych wersji protokołu i zwraca pierwszą, w której urządzenie odpowiedziało poprawnie.
     * Wynik: [wersja, stan].
     */
    public static Object[] detect(String id, String host, int port, String localKey, List<String> knownDps) throws TuyaException {
        String[] order = {"3.3", "3.5", "3.4", "3.1"};
        TuyaException last = null;
        TuyaException badKey = null;
        for (String v : order) {
            TuyaDevice d = new TuyaDevice(id, host, port, localKey, v).setTimeouts(3000, 2500).setKnownDps(knownDps);
            try {
                Map<String, Object> st = d.status();
                return new Object[]{v, st, d.isDevice22()};
            } catch (TuyaException e) {
                if (e.kind == TuyaException.Kind.UNREACHABLE) throw e; // nie ma sensu próbować kolejnych
                // w 3.4/3.5 zły klucz wychodzi już przy uzgadnianiu – wersja jest wtedy pewna
                if (e.kind == TuyaException.Kind.BAD_KEY && TuyaProtocol.ver(v) >= 34) throw e;
                if (e.kind == TuyaException.Kind.BAD_KEY) badKey = e;
                last = e;
            } finally {
                d.close();
            }
        }
        if (badKey != null) throw badKey;
        // urządzenie jest osiągalne, ale nie przyjęło żadnej wersji – więc nie pasuje klucz
        throw new TuyaException(TuyaException.Kind.BAD_KEY,
                "urządzenie nie przyjęło połączenia w żadnej wersji protokołu (3.1–3.5)", last);
    }

    // ---------- połączenie ----------

    private void ensureConnected() throws TuyaException {
        if (socket != null) return;
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setSoTimeout(readTimeoutMs);
            socket = s;
            in = s.getInputStream();
            out = s.getOutputStream();
        } catch (SocketTimeoutException | ConnectException | NoRouteToHostException | UnknownHostException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw new TuyaException(TuyaException.Kind.UNREACHABLE, host + ": " + e.getMessage(), e);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw new TuyaException(TuyaException.Kind.UNREACHABLE, host + ": " + e.getMessage(), e);
        }
        seq = 1;
        sessionKey = null;
        if (TuyaProtocol.ver(version) >= 34) {
            try {
                negotiateSessionKey();
            } catch (TuyaException e) {
                close();
                throw e;
            }
        }
    }

    /** Uzgodnienie klucza sesji (3.4 i 3.5): START → RESP → FINISH. */
    private void negotiateSessionKey() throws TuyaException {
        int v = TuyaProtocol.ver(version);
        byte[] localNonce = TuyaCrypto.random(16);
        writeRaw(TuyaProtocol.buildPacket(seq++, TuyaProtocol.SESS_KEY_NEG_START, localNonce, version, realKey));
        TuyaProtocol.Frame f;
        try {
            f = TuyaProtocol.readFrame(in, realKey, v == 34, true);
        } catch (SocketTimeoutException e) {
            throw new TuyaException(TuyaException.Kind.TIMEOUT, "brak odpowiedzi na uzgadnianie klucza sesji");
        } catch (EOFException e) {
            throw new TuyaException(TuyaException.Kind.KEY_OR_VERSION, "urządzenie rozłączyło się przy uzgadnianiu klucza");
        } catch (IOException e) {
            throw new TuyaException(TuyaException.Kind.UNREACHABLE, e.getMessage(), e);
        }
        if (v == 34 && f.prefix != TuyaProtocol.PREFIX_55AA || v >= 35 && f.prefix != TuyaProtocol.PREFIX_6699) {
            throw new TuyaException(TuyaException.Kind.BAD_VERSION, "urządzenie odpowiada innym formatem ramek");
        }
        if (f.cmd != TuyaProtocol.SESS_KEY_NEG_RESP) {
            throw new TuyaException(TuyaException.Kind.PROTOCOL, "zła odpowiedź na uzgadnianie: komenda " + f.cmd);
        }
        byte[] p = f.payload;
        if (v == 34) p = TuyaCrypto.ecbDecrypt(realKey, p);
        if (p.length < 48) throw new TuyaException(TuyaException.Kind.PROTOCOL, "za krótka odpowiedź uzgadniania");
        byte[] remoteNonce = Arrays.copyOfRange(p, 0, 16);
        byte[] check = TuyaCrypto.hmacSha256(realKey, localNonce);
        if (!MessageDigest.isEqual(check, Arrays.copyOfRange(p, 16, 48))) {
            throw new TuyaException(TuyaException.Kind.BAD_KEY, "urządzenie nie potwierdziło klucza lokalnego");
        }
        byte[] fin = TuyaCrypto.hmacSha256(realKey, remoteNonce);
        writeRaw(TuyaProtocol.buildPacket(seq++, TuyaProtocol.SESS_KEY_NEG_FINISH, fin, version, realKey));
        byte[] x = new byte[16];
        for (int i = 0; i < 16; i++) x[i] = (byte) (localNonce[i] ^ remoteNonce[i]);
        if (v == 34) {
            sessionKey = TuyaCrypto.ecbEncrypt(realKey, x, false);
        } else {
            byte[] enc = TuyaCrypto.gcmEncrypt(realKey, Arrays.copyOf(localNonce, 12), null, x);
            sessionKey = Arrays.copyOf(enc, 16);
        }
        note("klucz sesji uzgodniony (" + version + ")");
    }

    private byte[] key() { return sessionKey != null ? sessionKey : realKey; }

    private void send(int cmd, String json) throws TuyaException {
        writeRaw(TuyaProtocol.buildPacket(seq++, cmd, json.getBytes(StandardCharsets.UTF_8), version, key()));
    }

    private void writeRaw(byte[] packet) throws TuyaException {
        try {
            out.write(packet);
            out.flush();
        } catch (IOException e) {
            throw new TuyaException(TuyaException.Kind.UNREACHABLE, "błąd wysyłania: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> queryOnce() throws TuyaException, Device22Exception {
        int v = TuyaProtocol.ver(version);
        if (v >= 34) {
            send(TuyaProtocol.DP_QUERY_NEW, "{}");
        } else if (device22) {
            Map<String, Object> dpsReq = new LinkedHashMap<>();
            if (knownDps.isEmpty()) dpsReq.put("1", null);
            for (String d : knownDps) dpsReq.put(d, null);
            send(TuyaProtocol.CONTROL_NEW, Json.write(Json.map("devId", id, "uid", id, "t", String.valueOf(nowSec()), "dps", dpsReq)));
        } else {
            send(TuyaProtocol.DP_QUERY, Json.write(Json.map("gwId", id, "devId", id, "uid", id, "t", String.valueOf(nowSec()))));
        }
        long deadline = System.currentTimeMillis() + readTimeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new TuyaException(TuyaException.Kind.TIMEOUT, "brak odpowiedzi ze stanem");
            Map<String, Object> dps = readDps((int) left, true);
            if (dps != null) return dps;
        }
    }

    /**
     * Czyta jedną ramkę i zwraca DP z niej (null, gdy ramka ich nie miała, np. potwierdzenie).
     */
    private Map<String, Object> readDps(int timeoutMs, boolean strict) throws TuyaException, Device22Exception {
        TuyaProtocol.Frame f;
        int v = TuyaProtocol.ver(version);
        try {
            socket.setSoTimeout(Math.max(100, timeoutMs));
            f = TuyaProtocol.readFrame(in, key(), v == 34, true);
        } catch (SocketTimeoutException e) {
            throw new TuyaException(TuyaException.Kind.TIMEOUT, "brak odpowiedzi");
        } catch (EOFException e) {
            // 3.4/3.5 rozłączają się po komendzie w złej wersji; 3.3 po złym kluczu
            close();
            throw new TuyaException(strict ? TuyaException.Kind.KEY_OR_VERSION : TuyaException.Kind.TIMEOUT,
                    "urządzenie zamknęło połączenie");
        } catch (IOException e) {
            throw new TuyaException(TuyaException.Kind.UNREACHABLE, e.getMessage(), e);
        }
        if (v >= 35 && f.prefix != TuyaProtocol.PREFIX_6699 || v < 35 && f.prefix == TuyaProtocol.PREFIX_6699) {
            throw new TuyaException(TuyaException.Kind.BAD_VERSION, "urządzenie odpowiada formatem innej wersji");
        }
        if (f.cmd == TuyaProtocol.HEART_BEAT) return null;
        String text = TuyaProtocol.decodePayload(f.payload, version, key());
        note("odebrano " + f + ": " + (text.length() > 200 ? text.substring(0, 200) + "…" : text));
        if (text.isEmpty()) return null;
        if (text.contains("data unvalid")) {
            if (!device22 && v >= 32 && v <= 34) throw new Device22Exception();
            return null;
        }
        if (!text.startsWith("{")) {
            // np. „json obj data unvalid” albo inny komunikat tekstowy
            return null;
        }
        Map<String, Object> m = Json.tryParseObject(text);
        if (m == null) throw new TuyaException(TuyaException.Kind.PROTOCOL, "niepoprawny JSON od urządzenia");
        Map<String, Object> dps = Json.obj(m, "dps");
        if (dps == null) dps = Json.obj(Json.obj(m, "data"), "dps");
        return dps == null ? null : new LinkedHashMap<>(dps);
    }

    private static boolean containsAll(Map<String, Object> have, Map<String, Object> want) {
        for (String k : want.keySet()) if (!have.containsKey(k)) return false;
        return true;
    }

    private static long nowSec() { return System.currentTimeMillis() / 1000L; }

    private void note(String s) {
        if (log.size() > 50) log.remove(0);
        log.add(s);
    }

    /** Sygnał wewnętrzny: urządzenie chce zapytań w trybie device22. */
    private static final class Device22Exception extends Exception {
        Device22Exception() { super("device22", null, false, false); }
    }
}
