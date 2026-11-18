import pl.zooolw.tuyapilot.core.Json;
import pl.zooolw.tuyapilot.core.TuyaCloud;
import pl.zooolw.tuyapilot.core.TuyaCrypto;
import pl.zooolw.tuyapilot.core.TuyaDevice;
import pl.zooolw.tuyapilot.core.TuyaDiscovery;
import pl.zooolw.tuyapilot.core.TuyaException;
import pl.zooolw.tuyapilot.core.TuyaProtocol;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Testy rdzenia bez Androida: zgodność pakietów z tinytuya, rozmowa z udawanym urządzeniem
 * w każdej wersji protokołu, wykrywanie wersji, rozgłoszenia UDP i import z (udawanej) chmury.
 *
 * Uruchomienie: java -cp <klasy> CoreTests <katalog tests>
 */
public class CoreTests {

    static String testsDir;
    static String python = System.getenv().getOrDefault("PYTHON", "python3");
    static int passed = 0;
    static final List<String> failures = new ArrayList<>();
    static final List<Process> procs = new ArrayList<>();

    static final String DEV = "bf1234567890abcdefgh";
    static final String KEY = "0123456789abcdef";

    public static void main(String[] args) throws Exception {
        testsDir = args.length > 0 ? args[0] : "tests";
        try {
            run("JSON: zapis i odczyt", CoreTests::json);
            run("Klucz UDP", CoreTests::udpKey);
            run("Pakiety zgodne z tinytuya (3.1–3.5)", CoreTests::vectors);
            for (String v : new String[]{"3.1", "3.3", "3.4", "3.5"}) {
                run("Urządzenie " + v + ": stan i sterowanie", () -> deviceRoundTrip(v, false));
            }
            run("Urządzenie 3.3 device22", () -> deviceRoundTrip("3.3", true));
            run("Zły klucz 3.3", () -> wrongKey("3.3"));
            run("Zły klucz 3.4", () -> wrongKey("3.4"));
            run("Zły klucz 3.5", () -> wrongKey("3.5"));
            run("Zła wersja (urządzenie 3.5, ustawione 3.3)", CoreTests::wrongVersion);
            for (String v : new String[]{"3.1", "3.3", "3.4", "3.5"}) {
                run("Rozpoznanie wersji " + v, () -> detect(v));
            }
            run("Brak urządzenia pod adresem", CoreTests::unreachable);
            for (String v : new String[]{"3.1", "3.3", "3.5"}) {
                run("Rozgłoszenie UDP " + v, () -> discovery(v));
            }
            run("Import z chmury (podpis jak w tinytuya)", CoreTests::cloud);
            run("Chmura: zły sekret", CoreTests::cloudBadSecret);
        } finally {
            for (Process p : procs) p.destroyForcibly();
        }
        System.out.println();
        System.out.println("Zaliczone: " + passed + ", niezaliczone: " + failures.size());
        for (String f : failures) System.out.println("  ✗ " + f);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    interface Body { void run() throws Exception; }

    static void run(String name, Body b) {
        long t0 = System.currentTimeMillis();
        try {
            b.run();
            passed++;
            System.out.println("✓ " + name + " (" + (System.currentTimeMillis() - t0) + " ms)");
        } catch (Throwable e) {
            failures.add(name + ": " + e);
            System.out.println("✗ " + name + ": " + e);
            e.printStackTrace(System.out);
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": oczekiwano " + want + ", jest " + got);
    }

    // ---------- testy ----------

    static void json() {
        String s = "{\"a\":1,\"b\":[true,false,null,\"x\\\"y\\u0105\"],\"c\":{\"d\":-2.5,\"e\":\"\"}}";
        Map<String, Object> m = Json.parseObject(s);
        eq(1L, m.get("a"), "liczba całkowita");
        eq(-2.5, Json.obj(m, "c").get("d"), "ułamek");
        eq("x\"yą", ((List<?>) m.get("b")).get(3), "tekst z ucieczkami");
        eq(s.replace("\\u0105", "ą"), Json.write(m), "zapis bez spacji");
        eq("{\"1\":true,\"22\":500}", Json.write(Json.map("1", true, "22", 500L)), "DP");
    }

    static void udpKey() {
        eq("6c1ec8e2bb9bb59ab50b0daf649b410a", TuyaCrypto.hex(TuyaCrypto.UDP_KEY), "MD5 klucza UDP");
    }

    @SuppressWarnings("unchecked")
    static void vectors() throws Exception {
        String out = runPython("vectors.py");
        List<Object> list = (List<Object>) Json.parse(out.trim());
        check(list.size() >= 9, "za mało wektorów");
        for (Object o : list) {
            Map<String, Object> v = (Map<String, Object>) o;
            String ver = (String) v.get("version");
            int cmd = (int) Json.num(v, "cmd", 0);
            long seq = Json.num(v, "seq", 0);
            byte[] key = TuyaCrypto.unhex((String) v.get("key"));
            byte[] payload = TuyaCrypto.unhex((String) v.get("payload"));
            byte[] got;
            if (ver.equals("3.5")) {
                byte[] plain = TuyaProtocol.noVersionHeader(cmd) ? payload
                        : TuyaCrypto.concat(TuyaProtocol.versionHeader(ver), payload);
                got = TuyaProtocol.pack6699(seq, cmd, plain, key, null, TuyaCrypto.unhex((String) v.get("iv")));
            } else {
                got = TuyaProtocol.buildPacket(seq, cmd, payload, ver, key);
            }
            eq(v.get("expected"), TuyaCrypto.hex(got), "pakiet " + ver + " cmd=0x" + Integer.toHexString(cmd));
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    static int startFake(String version, String key, boolean device22, int bcastPort) throws Exception {
        int port = freePort();
        List<String> cmd = new ArrayList<>(Arrays.asList(python, "-I", new File(testsDir, "fake_device.py").getPath(),
                "--version", version, "--port", String.valueOf(port), "--key", key, "--id", DEV));
        if (device22) cmd.add("--device22");
        if (bcastPort > 0) { cmd.add("--bcast-port"); cmd.add(String.valueOf(bcastPort)); }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(new File(System.getProperty("java.io.tmpdir"), "fake-" + version + "-" + port + ".log"));
        Process p = pb.start();
        procs.add(p);
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line = r.readLine();
        check("READY".equals(line), "udawane urządzenie nie wystartowało: " + line);
        return port;
    }

    static void deviceRoundTrip(String version, boolean device22) throws Exception {
        int port = startFake(version, KEY, device22, 0);
        TuyaDevice d = new TuyaDevice(DEV, "127.0.0.1", port, KEY, version);
        if (device22) d.setKnownDps(Arrays.asList("1", "9", "18", "19", "20"));
        try {
            Map<String, Object> st = d.status();
            eq(true, st.get("1"), "stan DP1");
            eq(2315L, st.get("20"), "stan DP20");
            if (device22) check(d.isDevice22(), "powinien przełączyć się na device22");
        } finally {
            d.close();
        }
        // osobne połączenie, jak w aplikacji
        TuyaDevice d2 = new TuyaDevice(DEV, "127.0.0.1", port, KEY, version).setDevice22(device22);
        try {
            Map<String, Object> st = d2.set(Json.map("1", false, "9", 60L));
            eq(false, st.get("1"), "DP1 po wyłączeniu");
            eq(60L, st.get("9"), "DP9 po ustawieniu");
        } finally {
            d2.close();
        }
        TuyaDevice d3 = new TuyaDevice(DEV, "127.0.0.1", port, KEY, version).setDevice22(device22);
        try {
            eq(false, d3.status().get("1"), "stan zapamiętany przez urządzenie");
        } finally {
            d3.close();
        }
    }

    static void wrongKey(String version) throws Exception {
        int port = startFake(version, KEY, false, 0);
        TuyaDevice d = new TuyaDevice(DEV, "127.0.0.1", port, "fedcba9876543210", version).setTimeouts(2000, 2000);
        try {
            d.status();
            throw new AssertionError("powinien być błąd klucza");
        } catch (TuyaException e) {
            check(e.kind == TuyaException.Kind.BAD_KEY || e.kind == TuyaException.Kind.KEY_OR_VERSION,
                    "rodzaj błędu " + e.kind + " (" + e.getMessage() + ")");
        } finally {
            d.close();
        }
        // przy rozpoznawaniu wersji zły klucz musi wyjść jako zły klucz
        try {
            TuyaDevice.detect(DEV, "127.0.0.1", port, "fedcba9876543210", null);
            throw new AssertionError("rozpoznanie powinno się nie udać");
        } catch (TuyaException e) {
            eq(TuyaException.Kind.BAD_KEY, e.kind, "rodzaj błędu przy rozpoznaniu");
        }
    }

    static void wrongVersion() throws Exception {
        int port = startFake("3.5", KEY, false, 0);
        TuyaDevice d = new TuyaDevice(DEV, "127.0.0.1", port, KEY, "3.3").setTimeouts(2000, 2000);
        try {
            d.status();
            throw new AssertionError("powinien być błąd");
        } catch (TuyaException e) {
            check(e.kind == TuyaException.Kind.BAD_VERSION || e.kind == TuyaException.Kind.KEY_OR_VERSION
                    || e.kind == TuyaException.Kind.TIMEOUT, "rodzaj błędu: " + e.kind);
        } finally {
            d.close();
        }
    }

    static void detect(String version) throws Exception {
        int port = startFake(version, KEY, false, 0);
        Object[] r = TuyaDevice.detect(DEV, "127.0.0.1", port, KEY, null);
        eq(version, r[0], "rozpoznana wersja");
        @SuppressWarnings("unchecked") Map<String, Object> st = (Map<String, Object>) r[1];
        eq(true, st.get("1"), "stan po rozpoznaniu");
    }

    static void unreachable() throws Exception {
        int port = freePort(); // nikt nie słucha
        TuyaDevice d = new TuyaDevice(DEV, "127.0.0.1", port, KEY, "3.3").setTimeouts(1000, 1000);
        try {
            d.status();
            throw new AssertionError("powinien być błąd połączenia");
        } catch (TuyaException e) {
            eq(TuyaException.Kind.UNREACHABLE, e.kind, "rodzaj błędu");
        }
    }

    static void discovery(String version) throws Exception {
        int bport = freePort();
        startFake(version, KEY, false, bport);
        TuyaDiscovery disc = new TuyaDiscovery();
        disc.ports = new int[]{bport};
        Map<String, TuyaDiscovery.Found> found = disc.scan(2500, null, null, null);
        TuyaDiscovery.Found f = found.get(DEV);
        check(f != null, "nie znaleziono urządzenia, znaleziono: " + found.keySet());
        eq("192.168.1.77", f.ip, "IP z rozgłoszenia");
        eq(version, f.version, "wersja z rozgłoszenia");
        // nasze własne zapytanie 3.5 nie może być wzięte za urządzenie
        check(TuyaDiscovery.decode(TuyaDiscovery.solicitation("192.168.1.10"), "192.168.1.10") == null, "zapytanie aplikacji uznane za urządzenie");
    }

    @SuppressWarnings("unchecked")
    static void cloud() throws Exception {
        int port = freePort();
        startCloud(port, "acc1d", "s3cr3t");
        TuyaCloud c = TuyaCloud.withBaseUrl("http://127.0.0.1:" + port, "acc1d", "s3cr3t");
        List<Map<String, Object>> devs = c.importAll();
        eq(3, devs.size(), "liczba urządzeń");
        Map<String, Object> sock = find(devs, "bf00000000000000sock");
        eq("sock&key=0000003", sock.get("key"), "klucz uzupełniony z listy konta");
        eq("cz", sock.get("category"), "kategoria");
        eq("SP-10", sock.get("model"), "model z drugiej listy");
        Map<String, Object> map = Json.obj(sock, "mapping");
        eq("cur_power", Json.str(Json.obj(map, "19"), "code"), "DP19");
        eq(false, Json.obj(map, "19").get("writable"), "DP19 tylko do odczytu");
        eq(true, Json.obj(map, "1").get("writable"), "DP1 do sterowania");
        eq("W", Json.str(Json.obj(Json.obj(map, "19"), "values"), "unit"), "jednostka");
        eq(Arrays.asList("1", "9", "19", "20"), new ArrayList<>(map.keySet()), "kolejność DP");
        Map<String, Object> light = find(devs, "bf0000000000000light");
        Map<String, Object> wm = Json.obj(Json.obj(light, "mapping"), "21");
        eq(Arrays.asList("white", "colour", "scene", "music"), Json.obj(wm, "values").get("range"), "zakres enum");
        Map<String, Object> garage = find(devs, "bf000000000000garage");
        eq("doorcontact_state", Json.str(Json.obj(Json.obj(garage, "mapping"), "3"), "code"), "czujnik bramy");
        eq("Brama garażowa", garage.get("name"), "polskie znaki w nazwie");
    }

    static void cloudBadSecret() throws Exception {
        int port = freePort();
        startCloud(port, "acc1d", "s3cr3t");
        TuyaCloud c = TuyaCloud.withBaseUrl("http://127.0.0.1:" + port, "acc1d", "zly-sekret");
        try {
            c.importAll();
            throw new AssertionError("powinien być błąd podpisu");
        } catch (TuyaException e) {
            check(e.getMessage().contains("podpis"), "komunikat: " + e.getMessage());
        }
    }

    static Map<String, Object> find(List<Map<String, Object>> devs, String id) {
        for (Map<String, Object> d : devs) if (id.equals(d.get("id"))) return d;
        throw new AssertionError("brak urządzenia " + id);
    }

    static void startCloud(int port, String id, String secret) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(python, "-I", new File(testsDir, "cloud_mock.py").getPath(),
                String.valueOf(port), id, secret);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        procs.add(p);
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        check("READY".equals(r.readLine()), "serwer chmury nie wystartował");
    }

    static String runPython(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(python, "-I", new File(testsDir, script).getPath());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(p.waitFor() == 0, script + " zakończył się błędem");
        return out;
    }
}
