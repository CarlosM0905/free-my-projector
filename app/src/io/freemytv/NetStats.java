package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Free My Projector: the Wi-Fi card's numbers (an idea from the "Clean TV for TCL G10" home): the data used today, the
 * download speed right now (a few seconds of history, for its little chart) and a speed test against Cloudflare, run at
 * most once an hour when the Control Center opens.
 */
final class NetStats {
    private NetStats() {}

    private static final int HISTORY = 30;
    private static final float[] rates = new float[HISTORY];   // bytes per second, oldest first
    private static long lastBytes = -1, lastAt;
    private static volatile boolean testing;

    /** Called about once a second while the Control Center is open: one more point on the chart. */
    static synchronized void sample() {
        long now = android.os.SystemClock.elapsedRealtime(), bytes = TrafficStats.getTotalRxBytes();
        if (lastBytes >= 0 && now > lastAt && bytes >= lastBytes) {
            System.arraycopy(rates, 1, rates, 0, HISTORY - 1);
            rates[HISTORY - 1] = (bytes - lastBytes) * 1000f / (now - lastAt);
        }
        lastBytes = bytes;
        lastAt = now;
    }

    static synchronized float[] history() {
        return rates.clone();
    }

    /** Download speed right now, in bits per second. */
    static synchronized float now() {
        return rates[HISTORY - 1] * 8;
    }

    /** Called once a minute (Heat): what came and went since the last call is added to today's count. */
    static void count(Context c) {
        SharedPreferences p = prefs(c);
        long total = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
        if (total < 0) return;
        long last = p.getLong("total", -1);
        long delta = last < 0 ? 0 : total >= last ? total - last : total;   // smaller: the TV restarted, counters reset
        String day = today();
        long used = day.equals(p.getString("day", "")) ? p.getLong("used", 0) : 0;
        p.edit().putLong("total", total).putString("day", day).putLong("used", used + delta).apply();
    }

    /** Bytes used today (both ways). */
    static long today(Context c) {
        SharedPreferences p = prefs(c);
        return today().equals(p.getString("day", "")) ? p.getLong("used", 0) : 0;
    }

    /** The last speed test, in Mb/s (0 = none yet), and when it ran. */
    static float tested(Context c) {
        return prefs(c).getFloat("mbps", 0);
    }

    static long testedAt(Context c) {
        return prefs(c).getLong("testedAt", 0);
    }

    /** A download speed test (8 MB from Cloudflare), at most once an hour, in the background; `done` when it ends. */
    static void testIfOld(Context c, Runnable done) {
        long now = System.currentTimeMillis(), at = testedAt(c);
        if (testing || (now - at >= 0 && now - at < 3600_000L)) return;
        testing = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=8000000").openConnection();
                con.setConnectTimeout(6000);
                con.setReadTimeout(15000);
                long start = System.nanoTime(), got = 0;
                try (InputStream in = con.getInputStream()) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) got += n;
                }
                float seconds = (System.nanoTime() - start) / 1e9f;
                float mbps = got * 8 / 1e6f / Math.max(0.05f, seconds);
                prefs(app).edit().putFloat("mbps", mbps).putLong("testedAt", System.currentTimeMillis()).apply();
                if (done != null) done.run();
            } catch (Exception e) {
                AppLog.w("speed test: " + e.getMessage());
            } finally {
                if (con != null) con.disconnect();
                testing = false;
            }
        }, "speed-test").start();
    }

    /** "1,2 GB", "350 MB". */
    static String size(long bytes) {
        java.util.Locale l = Ui.en ? java.util.Locale.US : Ui.PT;
        if (bytes >= 1_000_000_000L) return String.format(l, "%.1f GB", bytes / 1e9);
        return String.format(l, "%d MB", Math.round(bytes / 1e6));
    }

    /** "85 Mb/s", "1,2 Mb/s", "640 kb/s". */
    static String speed(float bps) {
        java.util.Locale l = Ui.en ? java.util.Locale.US : Ui.PT;
        if (bps >= 10e6) return String.format(l, "%d Mb/s", Math.round(bps / 1e6));
        if (bps >= 1e6) return String.format(l, "%.1f Mb/s", bps / 1e6);
        return String.format(l, "%d kb/s", Math.round(bps / 1e3));
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(new java.util.Date());
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("net-stats", Context.MODE_PRIVATE);
    }
}
