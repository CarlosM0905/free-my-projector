package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Free My Projector: keeps the sideloaded apps up to date (nobody else updates them on a projector), once a day and
 * only while the home screen is showing, so the app being updated is never in use. Only official GitHub releases, and
 * only when the new APK is newer and signed with the same certificate as the installed app; it installs through the
 * TV's own ADB (the same one the home screen switch uses) and leaves a short notice.
 */
final class Updates {
    private Updates() {}

    /** {package, GitHub repository, file of the latest stable release (regex)}. */
    private static final String[][] SOURCES = {
        {"org.smarttube.stable", "yuliskov/SmartTube", "SmartTube_stable_.*_" + abi() + "\\.apk"},
        {"com.fgl27.twitch", "fgl27/SmartTwitchTV", "SmartTV_twitch_.*\\.apk"},
        {"com.auramusic.app", "TeamAuraMusic/AuraMusic", "AuraMusic-Tv\\.apk"},
        // Free My Projector itself: this repository's releases (built and signed by .github/workflows/release.yml).
        {"io.freemytv", "CarlosM0905/free-my-projector", "FreeMyTV\\.apk"},
    };
    private static final long EVERY = 24 * 3600_000L, RETRY = 3600_000L;
    private static volatile boolean running;

    interface Notice { void show(String text); }

    /** Called when the home screen shows: checks at most once a day, in the background. */
    static void check(Context c, Notice notice) {
        final Context app = c.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), last = prefs.getLong("checkedAt", 0);
        if (running || (now - last >= 0 && now - last < EVERY) || now - prefs.getLong("failedAt", 0) < RETRY) return;
        running = true;
        new Thread(() -> {
            try {
                boolean failed = false;
                for (String[] s : SOURCES) {
                    try {
                        String name = update(app, prefs, s);
                        if (name != null) notice.show(Ui.es ? name + " se actualizó." : Ui.tr(name + " foi atualizado.", name + " was updated."));
                    } catch (Exception e) {
                        failed = true;
                        AppLog.w("update " + s[0] + ": " + e.getMessage());
                    }
                }
                prefs.edit().putLong(failed ? "failedAt" : "checkedAt", System.currentTimeMillis()).apply();
            } finally {
                running = false;
            }
        }, "updates").start();
    }

    /** Updates one app if its latest release is new; returns its name when it was installed. */
    private static String update(Context c, SharedPreferences prefs, String[] s) throws Exception {
        PackageManager pm = c.getPackageManager();
        PackageInfo installed;
        try {
            installed = pm.getPackageInfo(s[0], PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;   // not on this TV
        }
        JSONObject release = new JSONObject(get("https://api.github.com/repos/" + s[1] + "/releases/latest"));
        String tag = release.getString("tag_name");
        if (tag.equals(prefs.getString("tag:" + s[0], ""))) return null;   // this release was already looked at
        String url = null;
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length() && url == null; i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.getString("name").matches(s[2])) url = a.getString("browser_download_url");
        }
        if (url == null) throw new IOException("no APK in " + tag);
        // Saved where the TV's shell can read it (pm install runs as the shell user).
        File dir = c.getExternalFilesDir(null);
        if (dir == null) throw new IOException("no storage");
        File apk = new File(dir, s[0] + ".apk");
        try {
            download(url, apk);
            PackageInfo fresh = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
            if (fresh == null || !s[0].equals(fresh.packageName)) throw new IOException("not the right app");
            String name = String.valueOf(pm.getApplicationLabel(installed.applicationInfo));
            if (fresh.versionCode <= installed.versionCode) {
                prefs.edit().putString("tag:" + s[0], tag).apply();
                return null;
            }
            if (!Arrays.equals(certs(installed.signatures), certs(fresh.signatures))) {
                throw new IOException("signed by someone else: not installed");
            }
            try (Adb adb = Adb.connect(c, 5000, null)) {
                // Streamed through the shell: the system can't read app storage itself where SELinux is enforcing.
                String out = adb.shell("cat '" + apk.getPath() + "' | pm install -r -S " + apk.length());
                if (!out.contains("Success")) throw new IOException("pm install: " + out.trim());
            }
            prefs.edit().putString("tag:" + s[0], tag).apply();
            AppLog.w("updated " + s[0] + " to " + fresh.versionName);
            return name;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    private static String[] certs(Signature[] signatures) throws Exception {
        if (signatures == null || signatures.length == 0) throw new IOException("unsigned");
        String[] out = new String[signatures.length];
        for (int i = 0; i < out.length; i++) {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            out[i] = hex.toString();
        }
        Arrays.sort(out);
        return out;
    }

    private static String abi() {
        for (String a : android.os.Build.SUPPORTED_ABIS) if (a.equals("arm64-v8a")) return a;
        return "armeabi-v7a";
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();   // GitHub redirects to its CDN
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
    }
}
