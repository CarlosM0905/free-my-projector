package io.freemytv;

import android.content.Context;

/**
 * Free My Projector: the Wi-Fi the projector is on, for the network button (like Clean Your TV's network pill). Apps
 * only get the network name with location turned on, which projectors leave off; the TV's own ADB reads it instead,
 * at most once a minute.
 */
final class Network {
    private Network() {}

    static volatile String ssid;
    static volatile int rssi;
    private static volatile long readAt;
    private static volatile boolean reading;

    interface Done { void run(); }

    /** Reads the network in the background (when it's over a minute old), then calls `done`. */
    static void refresh(Context c, Done done) {
        long now = System.currentTimeMillis();
        if (reading || (now - readAt >= 0 && now - readAt < 60_000)) return;
        reading = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 5000, null)) {
                String line = adb.shell("dumpsys wifi | grep -m1 mWifiInfo");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("SSID: (.*?), BSSID.*?RSSI: (-?\\d+)").matcher(line);
                if (m.find() && !m.group(1).contains("unknown")) {
                    ssid = m.group(1).replace("\"", "").trim();
                    rssi = Integer.parseInt(m.group(2));
                } else {
                    ssid = null;
                }
                readAt = System.currentTimeMillis();
                done.run();
            } catch (Exception e) {
                AppLog.w("network: " + e.getMessage());
            } finally {
                reading = false;
            }
        }, "network").start();
    }

    /** "MOVISTAR-WIFI6 · Excellent signal", or the plain label when it's not known. */
    static String label() {
        String s = ssid;
        if (s == null || s.isEmpty()) return Ui.tr("Rede", "Network");
        int r = rssi;
        String q = r >= -55 ? Ui.tr("Sinal excelente", "Excellent signal") : r >= -67 ? Ui.tr("Sinal bom", "Good signal")
                : r >= -75 ? Ui.tr("Sinal regular", "Fair signal") : Ui.tr("Sinal fraco", "Weak signal");
        return s + "  ·  " + q;
    }
}
