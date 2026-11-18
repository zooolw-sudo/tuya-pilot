package pl.zooolw.tuyapilot.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mały parser i zapis JSON bez zależności (rdzeń ma działać i na Androidzie, i w testach na komputerze).
 * Obiekty → LinkedHashMap (kolejność kluczy zachowana), tablice → ArrayList,
 * liczby całkowite → Long, ułamkowe → Double. Zapis bez spacji – urządzenia Tuya ich nie lubią.
 */
public final class Json {

    private Json() { }

    public static Object parse(String s) {
        if (s == null) throw new IllegalArgumentException("pusty JSON");
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("nadmiarowe znaki w JSON na pozycji " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) throw new IllegalArgumentException("oczekiwano obiektu JSON");
        return (Map<String, Object>) o;
    }

    /** Parsuje, a przy błędzie zwraca null (dla danych z urządzeń, które bywają niepełne). */
    public static Map<String, Object> tryParseObject(String s) {
        try {
            return parseObject(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Iterator<?> it = ((Iterable<?>) v).iterator(); it.hasNext(); ) {
                if (!first) sb.append(',');
                first = false;
                write(sb, it.next());
            }
            sb.append(']');
        } else if (v instanceof Object[]) {
            sb.append('[');
            Object[] a = (Object[]) v;
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                write(sb, a[i]);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---------- wygodne odczyty z map ----------

    public static String str(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String key, String def) {
        String s = str(m, key);
        return s == null || s.isEmpty() ? def : s;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        if (m == null) return def;
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v) || "1".equals(v);
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return def;
    }

    public static long num(Map<String, Object> m, String key, long def) {
        if (m == null) return def;
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : null;
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    public static Map<String, String> mapS(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ---------- parser ----------

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        IllegalArgumentException err(String what) {
            return new IllegalArgumentException("błąd JSON: " + what + " (pozycja " + i + ")");
        }

        Object value() {
            if (i >= s.length()) throw err("nieoczekiwany koniec");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw err("nieoczekiwany znak '" + c + "'");
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, i)) throw err("oczekiwano " + word);
            i += word.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw err("oczekiwano klucza");
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("oczekiwano ':'");
                i++;
                ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) throw err("niezamknięty obiekt");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("oczekiwano ',' lub '}'");
            }
        }

        List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                if (i >= s.length()) throw err("niezamknięta tablica");
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw err("oczekiwano ',' lub ']'");
            }
        }

        String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("niezamknięty tekst");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err("niepełna sekwencja");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err("niepełne \\u");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw err("nieznana sekwencja \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            boolean frac = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') i++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { frac = true; i++; }
                else break;
            }
            String t = s.substring(start, i);
            if (t.isEmpty() || t.equals("-")) throw err("zła liczba");
            if (!frac) {
                try { return Long.parseLong(t); } catch (NumberFormatException ignored) { }
            }
            try { return Double.parseDouble(t); } catch (NumberFormatException e) { throw err("zła liczba " + t); }
        }
    }
}
