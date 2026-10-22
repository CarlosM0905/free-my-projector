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
 * - the lamp's hours of use (picture on and lamp on), shown on the Control Center's projector card, and a reminder to
 *   clean the vents and the filter every 500 of them (dust is what makes these projectors run hot),
 * - the free memory on the Control Center's Memory tile,
 * - sleep when unused (Preferences): nothing playing and the remote untouched for 2, 3 or 4 hours (like Projectivy's
 *   idle detection; a projector left on for nothing wears its lamp).
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
        Projector.updateMemory();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                tick(app);
                handler.postDelayed(this, EVERY);
            }
        }, EVERY);
    }

    private static void tick(Context c) {
        NetStats.count(c);   // today's data, even while the screen is off
        Projector.updateMemory();
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm == null || !pm.isInteractive()) return;
        if (!Lamp.mode(c).equals("normal")) Lamp.enforce(c);
        endSleep(c);   // Wanbo rewrites the lamp when the temperature changes band
        idleSleep(c);
        if (lampOn()) {
            SharedPreferences p = prefs(c);
            long lamp = p.getLong("lampMs", 0) + EVERY;
            p.edit().putLong("lampMs", lamp).apply();
            if (lamp - p.getLong("cleanedAt", 0) >= CLEAN_EVERY) {
                p.edit().putLong("cleanedAt", lamp).apply();   // once: the next one in another 500 hours
                Toast.makeText(c, String.format(java.util.Locale.ROOT, Ui.tr(
                        "%d horas de lâmpada: limpe as saídas de ar e o filtro de pó (projetor desligado e frio).",
                        "%d lamp hours: clean the vents and the dust filter (with the projector off and cool)."),
                        lamp / 3600_000L), Toast.LENGTH_LONG).show();
            }
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

    static final long END_IDLE = 3 * 60_000L;
    private static final long CLEAN_EVERY = 500 * 3600_000L;

    /**
     * "When it ends" (Control Center): once something has played, three minutes with nothing playing put the projector
     * to sleep, and the switch turns itself off. An app that goes on to the next episode keeps it waiting.
     */
    private static void endSleep(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("endSleep", false)) return;
        long now = System.currentTimeMillis();
        if (NowPlaying.playing(c)) {
            p.edit().putBoolean("endSeen", true).putLong("endIdle", 0).apply();
            return;
        }
        if (!p.getBoolean("endSeen", false)) return;
        long idle = p.getLong("endIdle", 0);
        if (idle == 0) {
            p.edit().putLong("endIdle", now).apply();
        } else if (now - idle >= END_IDLE) {
            p.edit().putBoolean("endSleep", false).remove("endSeen").remove("endIdle").apply();
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                try (Adb adb = Adb.connect(app, 5000, null)) {
                    adb.shell("input keyevent KEYCODE_SLEEP");
                } catch (Exception e) {
                    AppLog.w("end sleep: " + e.getMessage());
                }
            }, "end-sleep").start();
        }
    }

    static final int[] IDLE_HOURS = {0, 2, 3, 4};   // 0: never
    private static volatile boolean checkingIdle;

    static int idleHours(Context c) {
        return prefs(c).getInt("idleHours", 0);
    }

    static void nextIdleHours(Context c, int dir) {
        int at = 0;
        for (int i = 0; i < IDLE_HOURS.length; i++) if (IDLE_HOURS[i] == idleHours(c)) at = i;
        int n = IDLE_HOURS.length;
        prefs(c).edit().putInt("idleHours", IDLE_HOURS[((at + dir) % n + n) % n]).remove("quietSince").apply();
    }

    /**
     * Counts the time with nothing playing; once it reaches the limit, asks the system (one ADB call) when the remote
     * was last used. Untouched for that long: sleep. Otherwise the count restarts from that last key press.
     */
    private static void idleSleep(Context c) {
        int hours = idleHours(c);
        if (hours <= 0 || checkingIdle) return;
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis(), limit = hours * 3600_000L;
        if (NowPlaying.playing(c)) {
            p.edit().remove("quietSince").apply();
            return;
        }
        long quiet = p.getLong("quietSince", 0);
        if (quiet == 0 || quiet > now) {
            p.edit().putLong("quietSince", now).apply();
            return;
        }
        if (now - quiet < limit) return;
        checkingIdle = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 5000, null)) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("mLastUserActivityTime=(\\d+)")
                        .matcher(adb.shell("dumpsys power"));
                long idle = m.find() ? android.os.SystemClock.uptimeMillis() - Long.parseLong(m.group(1)) : 0;
                if (idle >= limit) {
                    p.edit().remove("quietSince").apply();
                    AppLog.w("idle " + idle / 60_000 + " min: sleep");
                    adb.shell("input keyevent KEYCODE_SLEEP");
                } else {
                    p.edit().putLong("quietSince", System.currentTimeMillis() - Math.max(0, idle)).apply();
                }
            } catch (Exception e) {
                AppLog.w("idle sleep: " + e.getMessage());
                p.edit().putLong("quietSince", System.currentTimeMillis()).apply();
            } finally {
                checkingIdle = false;
            }
        }, "idle-sleep").start();
    }

    /** "When it ends" on or off (starts waiting for something to play). */
    static void setEndSleep(Context c, boolean on) {
        prefs(c).edit().putBoolean("endSleep", on).remove("endSeen").remove("endIdle").apply();
    }

    static boolean endSleepOn(Context c) {
        return prefs(c).getBoolean("endSleep", false);
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
