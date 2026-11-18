package pl.zooolw.tuyapilot.core;

import java.io.Closeable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wyszukiwanie urządzeń Tuya w sieci domowej. Urządzenia co kilka sekund rozgłaszają się po UDP:
 * 6666 (3.1, jawnie), 6667 (3.3+, szyfrowane stałym kluczem), a nowsze 3.5 odpowiadają na zapytanie na porcie 7000.
 * Z rozgłoszenia bierzemy adres IP i wersję protokołu.
 */
public class TuyaDiscovery implements Closeable {

    public static final int PORT_31 = 6666;
    public static final int PORT_33 = 6667;
    public static final int PORT_APP = 7000;

    /** Znalezione urządzenie. */
    public static final class Found {
        public final String id;
        public final String ip;
        public final String version;
        public final String productKey;
        public final Map<String, Object> raw;

        Found(String id, String ip, String version, String productKey, Map<String, Object> raw) {
            this.id = id;
            this.ip = ip;
            this.version = version;
            this.productKey = productKey;
            this.raw = raw;
        }

        public Map<String, Object> toMap() {
            return Json.map("id", id, "ip", ip, "version", version, "productKey", productKey);
        }
    }

    /**
     * Odszyfrowuje rozgłoszenie. Zwraca null dla pakietów, które nie są rozgłoszeniem urządzenia
     * (np. nasze własne zapytanie na porcie 7000).
     */
    public static Found decode(byte[] data, String fromIp) {
        String text = decodeText(data);
        if (text == null) return null;
        Map<String, Object> m = Json.tryParseObject(text.trim());
        if (m == null) return null;
        if ("app".equals(Json.str(m, "from"))) return null;
        String id = Json.str(m, "gwId");
        if (id == null) id = Json.str(m, "devId");
        if (id == null || id.isEmpty()) return null;
        String ip = Json.str(m, "ip", fromIp);
        String ver = Json.str(m, "version", "3.3");
        return new Found(id, ip, ver, Json.str(m, "productKey"), m);
    }

    static String decodeText(byte[] data) {
        try {
            if (data.length >= 4 && data[0] == 0 && data[1] == 0 && (data[2] & 0xff) == 0x66 && (data[3] & 0xff) == 0x99) {
                TuyaProtocol.Frame f = TuyaProtocol.parseUdp6699(data, TuyaCrypto.UDP_KEY);
                return new String(f.payload, StandardCharsets.UTF_8).replace("\u0000", "");
            }
            if (data.length >= 4 && data[0] == 0 && data[1] == 0 && (data[2] & 0xff) == 0x55 && (data[3] & 0xff) == 0xAA) {
                TuyaProtocol.Frame f = TuyaProtocol.parse(data, null, false, true);
                byte[] p = f.payload;
                if (p.length > 0 && p[0] == '{') return new String(p, StandardCharsets.UTF_8);
                return new String(TuyaCrypto.ecbDecrypt(TuyaCrypto.UDP_KEY, p), StandardCharsets.UTF_8);
            }
            if (data.length > 0 && data[0] == '{') return new String(data, StandardCharsets.UTF_8);
            if (data.length % 16 == 0) return new String(TuyaCrypto.ecbDecrypt(TuyaCrypto.UDP_KEY, data), StandardCharsets.UTF_8);
        } catch (TuyaException | RuntimeException ignored) {
            // nie nasz pakiet
        }
        return null;
    }

    /** Zapytanie, na które odpowiadają nowsze urządzenia 3.5 (wysyłane na port 7000). */
    public static byte[] solicitation(String myIp) {
        String json = Json.write(Json.map("from", "app", "ip", myIp));
        return TuyaProtocol.pack6699(0, TuyaProtocol.REQ_DEVINFO, json.getBytes(StandardCharsets.UTF_8), TuyaCrypto.UDP_KEY, null, null);
    }

    // ---------- nasłuch ----------

    public interface Listener {
        void onFound(Found f);
    }

    private final List<DatagramSocket> sockets = new ArrayList<>();
    private volatile boolean running;

    /** Porty nasłuchu (w testach można podmienić). */
    public int[] ports = {PORT_31, PORT_33, PORT_APP};
    /** Port, na który idzie zapytanie do urządzeń 3.5. */
    public int solicitPort = PORT_APP;

    /**
     * Nasłuchuje przez podany czas i zwraca znalezione urządzenia (każde raz, ostatni odczyt wygrywa).
     *
     * @param myIp      adres telefonu w sieci Wi-Fi (do zapytania 3.5); null = bez zapytania
     * @param broadcast adres rozgłoszeniowy sieci (np. 192.168.1.255); null = 255.255.255.255
     */
    public Map<String, Found> scan(int millis, String myIp, String broadcast, Listener listener) {
        final Map<String, Found> found = new LinkedHashMap<>();
        running = true;
        List<Thread> threads = new ArrayList<>();
        for (int port : ports) {
            final DatagramSocket s;
            try {
                s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(port));
                s.setSoTimeout(300);
            } catch (SocketException e) {
                continue;
            }
            synchronized (sockets) { sockets.add(s); }
            Thread t = new Thread(() -> {
                byte[] buf = new byte[2048];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (Exception e) {
                        break;
                    }
                    byte[] data = java.util.Arrays.copyOf(p.getData(), p.getLength());
                    String from = p.getAddress() == null ? null : p.getAddress().getHostAddress();
                    Found f = decode(data, from);
                    if (f == null) continue;
                    boolean isNew;
                    synchronized (found) {
                        isNew = !found.containsKey(f.id);
                        found.put(f.id, f);
                    }
                    if (isNew && listener != null) listener.onFound(f);
                }
            }, "tuya-udp-" + port);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        long end = System.currentTimeMillis() + millis;
        long nextAsk = 0;
        while (running && System.currentTimeMillis() < end) {
            if (myIp != null && System.currentTimeMillis() >= nextAsk) {
                sendSolicitation(myIp, broadcast);
                nextAsk = System.currentTimeMillis() + 2000;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        close();
        for (Thread t : threads) {
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
        synchronized (found) { return new LinkedHashMap<>(found); }
    }

    private void sendSolicitation(String myIp, String broadcast) {
        byte[] pkt = solicitation(myIp);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            String[] targets = broadcast == null ? new String[]{"255.255.255.255"} : new String[]{broadcast, "255.255.255.255"};
            for (String t : targets) {
                try {
                    s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(t), solicitPort));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void close() {
        running = false;
        synchronized (sockets) {
            for (DatagramSocket s : sockets) s.close();
            sockets.clear();
        }
    }
}
