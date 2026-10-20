package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

/**
 * Free My Projector: once a minute, in the home's process (alive for Now Playing even while other apps play):
 * - a notice when the processor runs hot (the projector throttles and video stutters; usually a blocked vent),
 * - the lamp's hours of use (picture on and lamp on), shown on the Control Center's projector card.
 */
final class Heat {
    private Heat() {}

    private static final String SOC = "/sys/class/thermal/thermal_zone0/temp";
    static final int HOT = 82;   // °C: the Amlogic throttle bites hard from 75, so 82 is "something's wrong"
    private static final long EVERY = 60_000, QUIET = 30 * 60_000L;
    private static Handler handler;
    private static long warnedAt;

    static synchronized void start(Context c) {
        if (handler != null || !new java.io.File(SOC).canRead()) return;
        final Context app = c.getApplicationContext();
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                tick(app);
                handler.postDelayed(this, EVERY);
            }
        }, EVERY);
    }

    private static void tick(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm == null || !pm.isInteractive()) return;
        if (!Lamp.mode(c).equals("normal")) Lamp.enforce(c);   // Wanbo rewrites the lamp when the temperature changes band
        if (lampOn()) {
            SharedPreferences p = prefs(c);
            p.edit().putLong("lampMs", p.getLong("lampMs", 0) + EVERY).apply();
        }
        int t = temperature();
        long now = System.currentTimeMillis();
        if (t >= HOT && (now - warnedAt > QUIET || now < warnedAt)) {
            warnedAt = now;
            String text = Ui.tr("O projetor está muito quente (%d °C). Verifique se as saídas de ar estão livres.",
                    "The projector is running hot (%d °C). Make sure its vents aren't blocked.");
            Toast.makeText(c, String.format(java.util.Locale.ROOT, text, t), Toast.LENGTH_LONG).show();
        }
    }

    /** The processor's temperature in °C, or -1. */
    static int temperature() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(SOC))) {
            return Math.round(Long.parseLong(r.readLine().trim()) / 1000f);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Hours the lamp has been on since Free My Projector started counting. */
    static long lampHours(Context c) {
        return prefs(c).getLong("lampMs", 0) / 3600_000L;
    }

    private static boolean lampOn() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(AudioOnly.LAMP_OFF))) {
            return !"1".equals(r.readLine().trim());
        } catch (Exception e) {
            return true;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("projector", Context.MODE_PRIVATE);
    }
}
