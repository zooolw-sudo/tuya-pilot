package pl.zooolw.tuyapilot;

import android.app.Application;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Zapisuje opis każdej awarii do pliku, żeby przy następnym uruchomieniu aplikacja mogła go pokazać
 * i dało się go wysłać (zamiast samego „aplikacja się zamyka”).
 */
public class App extends Application {

    static final String CRASH_FILE = "awaria.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                saveCrash(this, "Wątek: " + thread.getName(), error);
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static void saveCrash(android.content.Context c, String where, Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(describeDevice(c));
        pw.println(where);
        pw.println();
        error.printStackTrace(pw);
        pw.flush();
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), CRASH_FILE))) {
            out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    static String describeDevice(android.content.Context c) {
        String app = "?";
        try { app = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        String webview = "?";
        try {
            android.content.pm.PackageInfo p = android.webkit.WebView.getCurrentWebViewPackage();
            if (p != null) webview = p.packageName + " " + p.versionName;
        } catch (Throwable ignored) { }
        return "Tuya Pilot " + app + "\n"
                + "Telefon: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "WebView: " + webview;
    }
}
