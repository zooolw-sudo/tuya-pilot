package pl.zooolw.tuyapilot.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Klient chmury Tuya (Tuya Developer Platform / IoT Core) – używany tylko do pobrania listy urządzeń
 * z kluczami lokalnymi i opisem punktów danych. Sterowanie odbywa się potem lokalnie.
 *
 * Podpis żądań zgodny z dokumentacją Tuya („sign request” z 2021 r.):
 * sign = HMAC-SHA256(secret, client_id + [access_token] + t + METODA\nSHA256(treść)\n\nścieżka?zapytanie).toUpperCase()
 */
public class TuyaCloud {

    /** Centrum danych → host API. Konta Smart Life z Polski są zwykle w „eu” (Central Europe). */
    public static String hostFor(String region) {
        if (region == null) region = "eu";
        switch (region.toLowerCase(Locale.ROOT)) {
            case "weaz": return "openapi-weaz.tuyaeu.com";
            case "us": return "openapi.tuyaus.com";
            case "ueaz": return "openapi-ueaz.tuyaus.com";
            case "cn": return "openapi.tuyacn.com";
            case "in": return "openapi.tuyain.com";
            case "sg": return "openapi-sg.iotbing.com";
            default: return "openapi.tuyaeu.com";
        }
    }

    private final String baseUrl;
    private final String accessId;
    private final String secret;
    private String token;
    /** Czas do podpisu – w testach można go podmienić. */
    public java.util.function.LongSupplier clock = System::currentTimeMillis;

    public TuyaCloud(String region, String accessId, String secret) {
        this("https://" + hostFor(region), accessId, secret, 0);
    }

    /** Klient z pełnym adresem API (np. serwer testowy). */
    public static TuyaCloud withBaseUrl(String baseUrl, String accessId, String secret) {
        return new TuyaCloud(baseUrl, accessId, secret, 0);
    }

    private TuyaCloud(String baseUrl, String accessId, String secret, int unused) {
        if (accessId == null || accessId.trim().isEmpty()) throw new IllegalArgumentException("Podaj Access ID");
        if (secret == null || secret.trim().isEmpty()) throw new IllegalArgumentException("Podaj Access Secret");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.accessId = accessId.trim();
        this.secret = secret.trim();
    }

    // ---------- podpis ----------

    /** Tekst do podpisu i podpis – osobno, żeby dało się to przetestować. */
    public static String sign(String accessId, String secret, String token, long t, String method, String body, String pathWithQuery) {
        String bodyHash = TuyaCrypto.hex(TuyaCrypto.sha256((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
        String stringToSign = method + "\n" + bodyHash + "\n" + "\n" + pathWithQuery;
        String str = accessId + (token == null ? "" : token) + t + stringToSign;
        return TuyaCrypto.hex(TuyaCrypto.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), str.getBytes(StandardCharsets.UTF_8)))
                .toUpperCase(Locale.ROOT);
    }

    // ---------- żądania ----------

    private Map<String, Object> get(String path, Map<String, String> query) throws TuyaException {
        return request("GET", path, query, null, false);
    }

    private Map<String, Object> request(String method, String path, Map<String, String> query, String body, boolean retried)
            throws TuyaException {
        if (token == null && !path.startsWith("/v1.0/token")) obtainToken();
        // do podpisu parametry alfabetycznie i bez kodowania URL; w adresie – zakodowane
        StringBuilder signQ = new StringBuilder();
        StringBuilder urlQ = new StringBuilder();
        if (query != null && !query.isEmpty()) {
            for (Map.Entry<String, String> e : new TreeMap<>(query).entrySet()) {
                if (signQ.length() > 0) { signQ.append('&'); urlQ.append('&'); }
                signQ.append(e.getKey()).append('=').append(e.getValue());
                urlQ.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            }
        }
        String signPath = signQ.length() > 0 ? path + "?" + signQ : path;
        String urlPath = urlQ.length() > 0 ? path + "?" + urlQ : path;
        long t = clock.getAsLong();
        boolean tokenCall = path.startsWith("/v1.0/token");
        String sig = sign(accessId, secret, tokenCall ? null : token, t, method, body, signPath);

        HttpURLConnection c = null;
        String text;
        int code;
        try {
            c = (HttpURLConnection) new URL(baseUrl + urlPath).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setRequestProperty("client_id", accessId);
            c.setRequestProperty("sign", sig);
            c.setRequestProperty("t", String.valueOf(t));
            c.setRequestProperty("sign_method", "HMAC-SHA256");
            c.setRequestProperty("lang", "pl");
            if (!tokenCall) c.setRequestProperty("access_token", token);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            text = is == null ? "" : readAll(is);
        } catch (IOException e) {
            throw new TuyaException(TuyaException.Kind.CLOUD,
                    "Brak połączenia z chmurą Tuya (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "). Sprawdź internet.", e);
        } finally {
            if (c != null) c.disconnect();
        }
        Map<String, Object> r = Json.tryParseObject(text);
        if (r == null) throw new TuyaException(TuyaException.Kind.CLOUD, "Chmura Tuya zwróciła niezrozumiałą odpowiedź (HTTP " + code + ")");
        if (!Json.bool(r, "success", false)) {
            long errCode = Json.num(r, "code", -1);
            String msg = Json.str(r, "msg", "");
            if (!retried && !tokenCall && (errCode == 1010 || errCode == 1011 || msg.toLowerCase(Locale.ROOT).contains("token invalid"))) {
                token = null;
                obtainToken();
                return request(method, path, query, body, true);
            }
            throw new TuyaException(TuyaException.Kind.CLOUD, explain(errCode, msg));
        }
        return r;
    }

    private void obtainToken() throws TuyaException {
        Map<String, Object> r = request("GET", "/v1.0/token", Json.mapS("grant_type", "1"), null, false);
        String tk = Json.str(Json.obj(r, "result"), "access_token");
        if (tk == null) throw new TuyaException(TuyaException.Kind.CLOUD, "Chmura Tuya nie zwróciła tokenu");
        token = tk;
    }

    /** Polskie wyjaśnienia najczęstszych błędów. */
    static String explain(long code, String msg) {
        String m = msg == null ? "" : msg;
        String lower = m.toLowerCase(Locale.ROOT);
        if (code == 1004 || lower.contains("sign invalid")) {
            return "Chmura odrzuciła podpis: sprawdź Access ID i Access Secret oraz czy telefon ma prawidłową godzinę.";
        }
        if (code == 1106 || lower.contains("permission deny")) {
            return "Brak uprawnień: wybierz właściwe centrum danych (dla Polski zwykle Central Europe) i sprawdź, czy konto Smart Life jest połączone z projektem.";
        }
        if (code == 28841002 || code == 28841101 || lower.contains("subscription") || lower.contains("expired")) {
            return "Wygasł darmowy okres próbny usługi IoT Core. Przedłuż go na iot.tuya.com: Cloud → Project Management → przy IoT Core „Extend trial period” (zatwierdzenie trwa kilka minut).";
        }
        if (code == 1100 || code == 1109) return "Chmura Tuya: błędne dane w żądaniu (" + m + ").";
        if (code == 2009) return "To urządzenie nie ma opisu punktów danych w chmurze.";
        return "Chmura Tuya: " + (m.isEmpty() ? "błąd" : m) + (code >= 0 ? " (kod " + code + ")" : "");
    }

    // ---------- urządzenia ----------

    /**
     * Wszystkie urządzenia z kont aplikacji połączonych z projektem – z kluczami lokalnymi.
     * Tak samo jak kreator tinytuya: najpierw lista ze wszystkich kont, potem dla każdego konta
     * jeszcze raz, żeby mieć pewność, że klucze lokalne są uzupełnione.
     */
    public List<Map<String, Object>> devices() throws TuyaException {
        List<Map<String, Object>> all = new ArrayList<>();
        Map<String, String> q = new LinkedHashMap<>();
        q.put("size", "50");
        for (int page = 0; page < 40; page++) {
            Map<String, Object> res = Json.obj(get("/v1.0/iot-01/associated-users/devices", q), "result");
            List<Object> list = Json.arr(res, "devices");
            if (list != null) for (Object o : list) if (o instanceof Map) merge(all, cast(o));
            String last = Json.str(res, "last_row_key");
            if (!Json.bool(res, "has_more", false) || last == null || last.isEmpty()) break;
            q.put("last_row_key", last);
        }
        List<String> uids = new ArrayList<>();
        for (Map<String, Object> d : all) {
            String uid = Json.str(d, "uid");
            if (uid != null && !uid.isEmpty() && !uids.contains(uid)) uids.add(uid);
        }
        for (String uid : uids) {
            Map<String, String> q2 = new LinkedHashMap<>();
            q2.put("page_size", "75");
            q2.put("source_type", "tuyaUser");
            q2.put("source_id", uid);
            for (int page = 0; page < 40; page++) {
                Map<String, Object> res = Json.obj(get("/v1.3/iot-03/devices", q2), "result");
                List<Object> list = Json.arr(res, "list");
                if (list != null) for (Object o : list) if (o instanceof Map) merge(all, cast(o));
                String last = Json.str(res, "last_row_key");
                if (!Json.bool(res, "has_more", false) || last == null || last.isEmpty()) break;
                q2.put("last_row_key", last);
            }
        }
        return all;
    }

    /**
     * Opis punktów danych urządzenia: numer DP → {code, type, values, writable}.
     * „functions” to DP do sterowania, „status” – do odczytu.
     */
    public Map<String, Object> mapping(String deviceId) throws TuyaException {
        Map<String, Object> res;
        try {
            res = Json.obj(get("/v1.1/devices/" + deviceId + "/specifications", null), "result");
        } catch (TuyaException e) {
            if (e.getMessage() != null && e.getMessage().contains("opisu punktów")) return new LinkedHashMap<>();
            throw e;
        }
        Map<String, Object> out = new TreeMap<>((a, b) -> {
            try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); } catch (NumberFormatException e) { return a.compareTo(b); }
        });
        addSpecs(out, Json.arr(res, "functions"), true);
        addSpecs(out, Json.arr(res, "status"), false);
        return new LinkedHashMap<>(out);
    }

    private static void addSpecs(Map<String, Object> out, List<Object> list, boolean writable) {
        if (list == null) return;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> s = cast(o);
            String code = Json.str(s, "code");
            if (code == null) continue;
            String dp = s.containsKey("dp_id") ? String.valueOf(Json.num(s, "dp_id", -1)) : code;
            Map<String, Object> existing = Json.obj(out, dp);
            if (existing != null) {
                if (writable) existing.put("writable", true);
                continue;
            }
            Object values = s.get("values");
            String type = Json.str(s, "type", "");
            if (values instanceof String && !"string".equalsIgnoreCase(type)) {
                Map<String, Object> parsed = Json.tryParseObject((String) values);
                if (parsed != null) values = parsed;
            }
            out.put(dp, Json.map("code", code, "type", type, "values", values, "writable", writable));
        }
    }

    /**
     * Pełny import: lista urządzeń w formacie aplikacji (z mapą DP, pobieraną raz na model).
     */
    public List<Map<String, Object>> importAll() throws TuyaException {
        List<Map<String, Object>> src = devices();
        Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : src) {
            String id = Json.str(d, "id");
            if (id == null) continue;
            String product = Json.str(d, "product_id", id);
            Map<String, Object> map = byProduct.get(product);
            if (map == null) {
                try {
                    map = mapping(id);
                } catch (TuyaException e) {
                    if (e.getMessage() != null && e.getMessage().contains("okres próbny")) throw e;
                    map = new LinkedHashMap<>();
                }
                byProduct.put(product, map);
            }
            Map<String, Object> dev = new LinkedHashMap<>();
            dev.put("id", id);
            dev.put("name", Json.str(d, "name", id));
            dev.put("key", Json.str(d, "local_key", ""));
            dev.put("category", Json.str(d, "category", ""));
            dev.put("productId", product);
            dev.put("productName", Json.str(d, "product_name", ""));
            dev.put("model", Json.str(d, "model", ""));
            dev.put("sub", Json.bool(d, "sub", false));
            dev.put("nodeId", Json.str(d, "node_id", ""));
            dev.put("gatewayId", Json.str(d, "gateway_id", ""));
            dev.put("online", Json.bool(d, "online", true));
            dev.put("mapping", map);
            out.add(dev);
        }
        return out;
    }

    // ---------- pomocnicze ----------

    private static void merge(List<Map<String, Object>> all, Map<String, Object> d) {
        String id = Json.str(d, "id");
        if (id == null || id.isEmpty()) return;
        for (Map<String, Object> e : all) {
            if (id.equals(Json.str(e, "id"))) {
                for (Map.Entry<String, Object> kv : d.entrySet()) {
                    Object old = e.get(kv.getKey());
                    if (old == null || "".equals(old)) e.put(kv.getKey(), kv.getValue());
                }
                return;
            }
        }
        all.add(new LinkedHashMap<>(d));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int r;
        while ((r = is.read(b)) > 0) o.write(b, 0, r);
        is.close();
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }
}
