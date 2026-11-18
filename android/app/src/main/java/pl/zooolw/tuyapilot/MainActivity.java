package pl.zooolw.tuyapilot;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pl.zooolw.tuyapilot.core.Json;
import pl.zooolw.tuyapilot.core.TuyaCloud;
import pl.zooolw.tuyapilot.core.TuyaDevice;
import pl.zooolw.tuyapilot.core.TuyaDiscovery;
import pl.zooolw.tuyapilot.core.TuyaException;

/**
 * Ekran aplikacji: interfejs z assets/index.html w WebView. Strona woła most „Tuya”,
 * a ten wykonuje operacje na urządzeniach w tle (rdzeń w pakiecie core) i odsyła wynik
 * przez window.__tuya(id, wynik).
 */
public class MainActivity extends Activity {

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    /** Jedno połączenie naraz na urządzenie – Tuya źle znosi równoległe. */
    private final Map<String, Object> deviceLocks = new ConcurrentHashMap<>();
    private SharedPreferences prefs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tuya", MODE_PRIVATE);
        applyBars(isNightMode());

        web = new WebView(this);
        web.setBackgroundColor(isNightMode() ? 0xFF0D121B : 0xFFE9EDF2);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Tuya");
        web.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file:")) return false;
                openExternal(url);
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.__resume&&window.__resume()", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("window.__pause&&window.__pause()", null);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.__back&&window.__back())?'1':'0'", value -> {
            if (!"\"1\"".equals(value)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------- most dla strony ----------

    private class Bridge {
        @JavascriptInterface
        public String load(String key) { return prefs.getString("k_" + key, null); }

        @JavascriptInterface
        public void save(String key, String value) {
            if (value == null) prefs.edit().remove("k_" + key).apply();
            else prefs.edit().putString("k_" + key, value).apply();
        }

        @JavascriptInterface
        public boolean isNight() { return isNightMode(); }

        @JavascriptInterface
        public void setDark(final boolean dark) { main.post(() -> applyBars(dark)); }

        @JavascriptInterface
        public void openUrl(String url) { main.post(() -> openExternal(url)); }

        @JavascriptInterface
        public void openTailscale() {
            main.post(() -> {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
                if (i != null) {
                    startActivity(i);
                } else {
                    openExternal("https://play.google.com/store/apps/details?id=com.tailscale.ipn");
                }
            });
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                v.vibrate(VibrationEffect.createOneShot(Math.max(10, Math.min(ms, 500)), VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void share(String text) {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                try { startActivity(Intent.createChooser(i, "Udostępnij")); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "?";
            }
        }

        /** Stan sieci: Wi-Fi, VPN (Tailscale), adres telefonu i adres rozgłoszeniowy. */
        @JavascriptInterface
        public String netInfo() { return Json.write(networkInfo()); }

        @JavascriptInterface
        public void status(final String reqId, final String devJson) {
            onDevice(reqId, devJson, (dev, d) -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("dps", d.status());
                out.put("device22", d.isDevice22());
                return out;
            });
        }

        @JavascriptInterface
        public void set(final String reqId, final String devJson, final String dpsJson) {
            onDevice(reqId, devJson, (dev, d) -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("dps", d.set(Json.parseObject(dpsJson)));
                out.put("device22", d.isDevice22());
                return out;
            });
        }

        @JavascriptInterface
        public void detect(final String reqId, final String devJson) {
            pool.execute(() -> {
                Map<String, Object> dev = Json.parseObject(devJson);
                String id = Json.str(dev, "id");
                synchronized (lockFor(id)) {
                    try {
                        Object[] r = TuyaDevice.detect(id, Json.str(dev, "ip"), TuyaDevice.PORT, Json.str(dev, "key"), knownDps(dev));
                        reply(reqId, Json.map("ok", true, "data", Json.map("version", r[0], "dps", r[1], "device22", r[2])));
                    } catch (TuyaException e) {
                        reply(reqId, error(e));
                    } catch (Exception e) {
                        reply(reqId, Json.map("ok", false, "kind", "PROTOCOL", "error", String.valueOf(e.getMessage())));
                    }
                }
            });
        }

        @JavascriptInterface
        public void scan(final String reqId, final int millis) {
            pool.execute(() -> {
                WifiManager.MulticastLock lock = null;
                try {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        lock = wm.createMulticastLock("tuya-scan");
                        lock.setReferenceCounted(false);
                        lock.acquire();
                    }
                } catch (Exception ignored) { }
                try {
                    Map<String, Object> net = networkInfo();
                    String ip = Json.str(net, "ip");
                    String bc = Json.str(net, "broadcast");
                    Map<String, TuyaDiscovery.Found> found = new TuyaDiscovery()
                            .scan(Math.max(2000, Math.min(millis, 20000)), ip, bc, null);
                    List<Object> list = new ArrayList<>();
                    for (TuyaDiscovery.Found f : found.values()) list.add(f.toMap());
                    reply(reqId, Json.map("ok", true, "data", list));
                } catch (Exception e) {
                    reply(reqId, Json.map("ok", false, "kind", "PROTOCOL", "error", String.valueOf(e.getMessage())));
                } finally {
                    try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void cloudImport(final String reqId, final String region, final String accessId, final String secret) {
            pool.execute(() -> {
                try {
                    List<Map<String, Object>> devs = new TuyaCloud(region, accessId, secret).importAll();
                    reply(reqId, Json.map("ok", true, "data", devs));
                } catch (TuyaException e) {
                    reply(reqId, error(e));
                } catch (IllegalArgumentException e) {
                    reply(reqId, Json.map("ok", false, "kind", "CLOUD", "error", e.getMessage()));
                } catch (Exception e) {
                    reply(reqId, Json.map("ok", false, "kind", "CLOUD", "error", "Błąd: " + e));
                }
            });
        }
    }

    // ---------- operacje w tle ----------

    private interface DeviceOp {
        Map<String, Object> run(Map<String, Object> dev, TuyaDevice d) throws TuyaException;
    }

    private void onDevice(final String reqId, final String devJson, final DeviceOp op) {
        pool.execute(() -> {
            Map<String, Object> dev;
            try {
                dev = Json.parseObject(devJson);
            } catch (Exception e) {
                reply(reqId, Json.map("ok", false, "kind", "PROTOCOL", "error", "złe dane urządzenia"));
                return;
            }
            String id = Json.str(dev, "id");
            synchronized (lockFor(id)) {
                TuyaDevice d = null;
                try {
                    String ip = Json.str(dev, "ip");
                    if (ip == null || ip.isEmpty()) {
                        reply(reqId, Json.map("ok", false, "kind", "NO_IP",
                                "error", "Brak adresu IP. Połącz się z domowym Wi-Fi i użyj „Szukaj w sieci”, albo wpisz adres ręcznie."));
                        return;
                    }
                    d = new TuyaDevice(id, ip, TuyaDevice.PORT, Json.str(dev, "key"), Json.str(dev, "version", "3.3"))
                            .setDevice22(Json.bool(dev, "device22", false))
                            .setKnownDps(knownDps(dev));
                    reply(reqId, Json.map("ok", true, "data", op.run(dev, d)));
                } catch (TuyaException e) {
                    reply(reqId, error(e));
                } catch (IllegalArgumentException e) {
                    reply(reqId, Json.map("ok", false, "kind", "BAD_KEY", "error", e.getMessage()));
                } catch (Exception e) {
                    reply(reqId, Json.map("ok", false, "kind", "PROTOCOL", "error", "Błąd: " + e));
                } finally {
                    if (d != null) d.close();
                }
            }
        });
    }

    private Object lockFor(String id) {
        Object l = deviceLocks.get(id);
        if (l == null) {
            Object n = new Object();
            l = deviceLocks.putIfAbsent(id, n);
            if (l == null) l = n;
        }
        return l;
    }

    private static List<String> knownDps(Map<String, Object> dev) {
        Map<String, Object> m = Json.obj(dev, "mapping");
        List<String> out = new ArrayList<>();
        if (m != null) for (String k : m.keySet()) if (k.matches("\\d+")) out.add(k);
        return out;
    }

    private static Map<String, Object> error(TuyaException e) {
        return Json.map("ok", false, "kind", e.kind.name(), "error", e.userMessage(), "detail", String.valueOf(e.getMessage()));
    }

    private void reply(final String reqId, final Map<String, Object> result) {
        final String js = "window.__tuya&&window.__tuya(" + Json.write(reqId) + "," + Json.write(result) + ")";
        main.post(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    // ---------- sieć ----------

    private Map<String, Object> networkInfo() {
        boolean wifi = false;
        boolean vpn = false;
        boolean online = false;
        String ip = null;
        String broadcast = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    if (c == null) continue;
                    if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) online = true;
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        wifi = true;
                        LinkProperties lp = cm.getLinkProperties(n);
                        if (lp != null) {
                            for (LinkAddress la : lp.getLinkAddresses()) {
                                InetAddress a = la.getAddress();
                                if (a instanceof Inet4Address) {
                                    ip = a.getHostAddress();
                                    broadcast = broadcastOf(a.getAddress(), la.getPrefixLength());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return Json.map("wifi", wifi, "vpn", vpn, "online", online, "ip", ip, "broadcast", broadcast);
    }

    private static String broadcastOf(byte[] a, int prefix) {
        int v = ((a[0] & 0xff) << 24) | ((a[1] & 0xff) << 16) | ((a[2] & 0xff) << 8) | (a[3] & 0xff);
        int mask = prefix <= 0 ? 0 : (int) (0xffffffffL << (32 - prefix));
        int b = v | ~mask;
        return ((b >>> 24) & 0xff) + "." + ((b >>> 16) & 0xff) + "." + ((b >>> 8) & 0xff) + "." + (b & 0xff);
    }

    // ---------- wygląd ----------

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void applyBars(boolean dark) {
        int bg = dark ? 0xFF0D121B : 0xFFE9EDF2;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : flags, flags);
            }
        } else {
            int f = getWindow().getDecorView().getSystemUiVisibility();
            int light = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(dark ? (f & ~light) : (f | light));
        }
        if (web != null) web.setBackgroundColor(bg);
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) { }
    }
}
