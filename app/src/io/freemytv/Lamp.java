package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Free My Projector: lamp brightness modes (Control Center). Wanbo drives the LED by temperature only (luxcine_3 writes
 * /sys/class/jixin-pwm/PWM when the LED's temperature changes band; a higher value is a dimmer lamp). Eco and Night only
 * ever dim it further: if Wanbo is already dimmer (it's hot), Wanbo's value stays. Normal gives the lamp back to Wanbo.
 * There's no "Max": brighter than Wanbo allows would overheat the LED.
 */
final class Lamp {
    private Lamp() {}

    static final String[] MODES = {"normal", "eco", "night"};
    private static final long[] FLOOR = {0, 1_750_000, 2_650_000};   // ≈ 100 %, 75 %, 50 %
    private static final String PWM = "/sys/class/jixin-pwm/PWM";

    static boolean supported() {
        return new java.io.File(PWM).exists();
    }

    static String mode(Context c) {
        return prefs(c).getString("lampMode", "normal");
    }

    static String label(Context c) {
        String m = mode(c);
        return m.equals("eco") ? "Eco" : m.equals("night") ? Ui.tr("Noite", "Night") : Ui.tr("Lâmpada", "Lamp");
    }

    /** Normal → Eco → Night → Normal, applied right away. */
    static void next(Context c) {
        int at = java.util.Arrays.asList(MODES).indexOf(mode(c));
        prefs(c).edit().putString("lampMode", MODES[(at + 1) % MODES.length]).apply();
        enforce(c);
    }

    /**
     * Applies the mode (also once a minute, from Heat: Wanbo rewrites the lamp when the temperature changes band).
     * Wanbo's own value is kept aside so Normal can put it back.
     */
    static void enforce(Context c) {
        final Context app = c.getApplicationContext();
        final SharedPreferences p = prefs(app);
        final int at = Math.max(0, java.util.Arrays.asList(MODES).indexOf(mode(app)));
        new Thread(() -> {
            long cur = read();
            if (cur <= 0) return;
            long wrote = p.getLong("lampWrote", 0);
            if (at == 0) {
                long wanbo = p.getLong("lampWanbo", 0);
                if (wrote > 0 && cur == wrote && wanbo > 0) write(app, wanbo);
                p.edit().remove("lampWrote").remove("lampWanbo").apply();
                return;
            }
            if (cur != wrote) p.edit().putLong("lampWanbo", cur).apply();   // Wanbo set a new value since our last write
            if (cur < FLOOR[at]) {
                write(app, FLOOR[at]);
                p.edit().putLong("lampWrote", FLOOR[at]).apply();
            }
        }, "lamp-mode").start();
    }

    private static long read() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(PWM))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void write(Context app, long value) {
        try (java.io.FileWriter w = new java.io.FileWriter(PWM)) {
            w.write(String.valueOf(value));
            return;
        } catch (Exception ignored) {
        }
        try (Adb adb = Adb.connect(app, 5000, null)) {
            adb.shell("echo " + value + " > " + PWM);
        } catch (Exception e) {
            AppLog.w("lamp mode: " + e.getMessage());
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("projector", Context.MODE_PRIVATE);
    }
}
