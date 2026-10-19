package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Free My Projector: projector controls in the bottom drawer (keystone, picture, sound, sleep timer, test pattern).
 * Each one is only shown when its screen exists on this projector, so the same APK works on other brands.
 * Wanbo hides some Amlogic screens (not exported); those open through "Puente Wanbo" (pe.carlos.puente), a small
 * system-signed helper of the Wanbo ROM that only opens screens of com.droidlogic.tv.settings.
 */
final class Projector {
    private Projector() {}

    private static final String BRIDGE = "pe.carlos.puente";

    /** {label pt, label en, symbol, target}. target: "package/.Activity", or "bridge:package/.Activity". */
    private static final String[][] CONTROLS = {
        {"Keystone", "Keystone", "tv", "com.android.tv.settings/.device.keystone.KeyStoneActivity"},
        {"Imagem", "Picture", "sparkles", "bridge:com.droidlogic.tv.settings/.pqsettings.PictrueModeActivity"},
        {"Efeitos de som", "Sound effects", "speaker", "bridge:com.droidlogic.tv.settings/.tvoption.TvOptionActivity"},
        {"Timer para desligar", "Sleep timer", "bedtime", BRIDGE + "/.Temporizador"},
    };

    /**
     * Control Center switches (like tvOS's Match Content, Enhance Dialogue and Reduce Loud Sounds): the frame rate
     * follows the video (AFR, from the Wanbo ROM's wanbo-tweaks), dialog clarity and the volume leveler (Amlogic's
     * effects, applied live by Puente Wanbo). On: the icon lights up in the accent color.
     */
    private static final String[][] TOGGLES = {
        {"Hz automático", "Match frame rate", "sync", "toggle:afr"},
        {"Diálogos", "Enhance dialogue", "bell", "toggle:dialogo"},
        {"Volume uniforme", "Reduce loud sounds", "speaker", "toggle:agc"},
    };
    private static final String SOUND = BRIDGE + ".SONIDO";
    private static final String AFR_OFF = "/data/local/tmp/afr.off";
    private static volatile Boolean afrOn;   // read once through the TV's own ADB (the file isn't readable by apps)
    private static final java.util.Map<String, long[]> OPTIMISTIC = new java.util.HashMap<>();   // key → {on, until}

    static List<HomeApps.Item> items(Context c) {
        List<HomeApps.Item> out = new ArrayList<>();
        for (String[] d : CONTROLS) {
            Intent i = intent(d[3]);
            if (i.resolveActivity(c.getPackageManager()) == null) continue;
            if (d[3].startsWith("bridge:") && !exists(c.getPackageManager(), component(d[3].substring(7)))) continue;
            out.add(HomeApps.action(HomeApps.PROJECTOR, d[3], Ui.tr(d[0], d[1]), d[2]));
        }
        if (!hasStatus()) return out;   // the switches are the Wanbo ROM's (AFR) and its helper's (sound)
        boolean sound = !c.getPackageManager().queryBroadcastReceivers(new Intent(SOUND).setPackage(BRIDGE), 0).isEmpty();
        for (String[] d : TOGGLES) {
            if (!d[3].equals("toggle:afr") && !sound) continue;
            out.add(HomeApps.action(HomeApps.PROJECTOR, d[3], Ui.tr(d[0], d[1]), d[2]));
        }
        if (afrOn == null) checkAfr(c);
        return out;
    }

    // MARK: - Switches

    static boolean isToggle(String key) {
        return key.startsWith("toggle:");
    }

    static boolean isOn(Context c, String key) {
        synchronized (OPTIMISTIC) {   // just switched: show the new state until the system confirms it
            long[] o = OPTIMISTIC.get(key);
            if (o != null && System.currentTimeMillis() < o[1]) return o[0] == 1;
        }
        android.content.ContentResolver cr = c.getContentResolver();
        switch (key) {
            case "toggle:afr":
                return afrOn == null || afrOn;
            case "toggle:dialogo":
                return android.provider.Settings.Global.getInt(cr, "fmp_dialogo", 0) > 0;
            case "toggle:agc":
                return android.provider.Settings.Global.getInt(cr, "fmp_agc",
                        android.provider.Settings.Global.getInt(cr, "db_id_sound_effect_agc_on", 0)) > 0;
            default:
                return false;
        }
    }

    /** Flips a switch: the tile shows the new state right away; the change is applied in the background. */
    static void toggle(Context c, String key) {
        final Context app = c.getApplicationContext();
        final boolean on = !isOn(app, key);
        synchronized (OPTIMISTIC) {
            OPTIMISTIC.put(key, new long[] {on ? 1 : 0, System.currentTimeMillis() + 5000});
        }
        if (key.equals("toggle:afr")) {
            afrOn = on;
            new Thread(() -> {
                try (Adb adb = Adb.connect(app, 5000, null)) {
                    adb.shell(on ? "rm -f " + AFR_OFF : "touch " + AFR_OFF + "; echo set 6000 > /sys/class/lcd/frame_rate");
                } catch (Exception e) {
                    AppLog.w("AFR switch: " + e.getMessage());
                }
            }, "afr").start();
        } else {
            String effect = key.substring("toggle:".length());
            app.sendBroadcast(new Intent(SOUND).setClassName(BRIDGE, BRIDGE + ".Sonido")
                    .putExtra("efecto", effect).putExtra("valor", on ? (effect.equals("dialogo") ? 2 : 1) : 0));
        }
    }

    private static void checkAfr(Context c) {
        final Context app = c.getApplicationContext();
        afrOn = true;
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 5000, null)) {
                afrOn = !adb.shell("[ -f " + AFR_OFF + " ] && echo off").contains("off");
            } catch (Exception e) {
                AppLog.w("AFR state: " + e.getMessage());
            }
        }, "afr-state").start();
    }

    /** The sleep timer's tile shows how long is left ("42 min") while it runs (Puente publishes the time). */
    static String label(Context c, HomeApps.Item it) {
        if (!it.key.equals(CONTROLS[3][3])) return it.label;
        long at = android.provider.Settings.Global.getLong(c.getContentResolver(), "fmp_apagado", 0);
        long left = at - System.currentTimeMillis();
        return left > 0 ? ((left + 59_999) / 60_000) + " min" : it.label;
    }

    /** The sleep timer is on (its tile lights up like a switch). */
    static boolean timerOn(Context c, HomeApps.Item it) {
        return it.key.equals(CONTROLS[3][3])
                && android.provider.Settings.Global.getLong(c.getContentResolver(), "fmp_apagado", 0) > System.currentTimeMillis();
    }

    /** The keystone shortcut (first control), or null if this device has no keystone screen. */
    static HomeApps.Item keystone(Context c) {
        List<HomeApps.Item> all = items(c);
        return !all.isEmpty() && all.get(0).key.equals(CONTROLS[0][3]) ? all.get(0) : null;
    }

    static Intent intent(String target) {
        Intent i = new Intent(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (target.startsWith("bridge:")) {
            return i.setComponent(new ComponentName(BRIDGE, BRIDGE + ".Abrir")).putExtra("destino", target.substring(7));
        }
        return i.setComponent(component(target));
    }

    private static ComponentName component(String flat) {
        return ComponentName.unflattenFromString(flat);
    }

    // MARK: - Status card (drawer): processor temperature, fan, lamp and panel refresh rate

    private static final String SOC = "/sys/class/thermal/thermal_zone0/temp";
    private static final String FAN = "/sys/class/jixin-feng/PWM";          // Wanbo: duty in ns of a 40000 ns period
    private static final String LAMP = "/sys/class/jixin-pwm/PWM";          // Wanbo: LED drive, 1.26 M = full, 2.65 M = dimmest
    private static final String PANEL = "/sys/class/lcd/frame_rate";        // Amlogic LCD: "get frame_rate: 60.00Hz, ..."
    private static final float FAN_PERIOD = 40000, LAMP_FULL = 1_260_000, LAMP_DIM = 2_650_000;

    /** The rows that can be read on this device: {label pt, label en, value}. Empty off a projector. */
    static List<String[]> status() {
        List<String[]> rows = new ArrayList<>();
        long soc = number(read(SOC));
        if (soc > 0) rows.add(new String[] {"Processador", "Processor", Math.round(soc / 1000f) + " °C"});
        long fan = number(read(FAN));
        if (fan > 0) rows.add(new String[] {"Ventilador", "Fan", Math.round(100 * Math.min(1, fan / FAN_PERIOD)) + " %"});
        long lamp = number(read(LAMP));
        if (lamp >= LAMP_FULL * 0.9f && lamp <= LAMP_DIM * 1.1f) {
            float brightness = 1 - (lamp - LAMP_FULL) / (LAMP_DIM - LAMP_FULL) * 0.5f;   // the dimmest step is about half
            rows.add(new String[] {"Lâmpada", "Lamp", Math.round(100 * Math.max(0.5f, Math.min(1, brightness))) + " %"});
        }
        String panel = read(PANEL);
        java.util.regex.Matcher m = panel == null ? null : java.util.regex.Pattern.compile("([0-9.]+)\\s*Hz").matcher(panel);
        if (m != null && m.find()) {
            float hz = Float.parseFloat(m.group(1));
            rows.add(new String[] {"Tela", "Panel", (Math.abs(hz - Math.round(hz)) < 0.05f ? String.valueOf(Math.round(hz))
                    : String.format(java.util.Locale.ROOT, "%.2f", hz)) + " Hz"});
        }
        return rows;
    }

    /** The drawer shows the card only where these files exist (a projector), so a TV keeps the original layout. */
    static boolean hasStatus() {
        return new java.io.File(FAN).canRead() || new java.io.File(PANEL).canRead();
    }

    private static String read(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static long number(String s) {
        if (s == null) return -1;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The hidden screen exists (it isn't exported, so resolveActivity can't see it from here). */
    private static boolean exists(PackageManager pm, ComponentName cn) {
        try {
            pm.getActivityInfo(cn, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
