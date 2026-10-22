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
    private static final String TIMER = BRIDGE + "/.Temporizador";

    /** {label pt, label en, symbol, target}. target: "package/.Activity", or "bridge:package/.Activity". */
    private static final String[][] CONTROLS = {
        {"Keystone", "Keystone", "tv", "com.android.tv.settings/.device.keystone.KeyStoneActivity"},
        {"Tela", "Screen setup", "focus", "io.freemytv/.FocusActivity"},
        {"Imagem", "Picture", "sparkles", "bridge:com.droidlogic.tv.settings/.pqsettings.PictrueModeActivity"},
        {"Efeitos de som", "Sound effects", "speaker", "bridge:com.droidlogic.tv.settings/.tvoption.TvOptionActivity"},
        {"Timer para desligar", "Sleep timer", "bedtime", TIMER},
        {"Só áudio", "Audio only", "music", "io.freemytv/.AudioOnly"},
    };

    /** Free memory: closes the apps waiting in the background (not a switch; the tile shows the free memory). */
    static final String RAM = "toggle:ram";

    /**
     * Control Center switches (like tvOS's Match Content, Enhance Dialogue and Reduce Loud Sounds): the frame rate
     * follows the video (AFR, from the Wanbo ROM's wanbo-tweaks), dialog clarity and the volume leveler (Amlogic's
     * effects, applied live by Puente Wanbo). On: the icon lights up in the accent color.
     */
    private static final String[][] TOGGLES = {
        {"Hz automático", "Match frame rate", "sync", "toggle:afr"},
        {"Diálogos", "Enhance dialogue", "voice", "toggle:dialogo"},
        {"Volume uniforme", "Reduce loud sounds", "leveler", "toggle:agc"},
        {"Cena", "Scene", "movie", "toggle:cine"},
        {"Lâmpada", "Lamp", "sunny", "toggle:lamp"},
        {"Ao terminar", "When it ends", "bedtime", "toggle:fin"},
        {"Memória", "Memory", "memory", RAM},
    };
    private static final String SOUND = BRIDGE + ".SONIDO";
    private static volatile long freeMb = -1;
    private static final String AFR_OFF = "/data/local/tmp/afr.off";
    private static volatile Boolean afrOn;   // read once through the TV's own ADB (the file isn't readable by apps)
    private static final java.util.Map<String, long[]> OPTIMISTIC = new java.util.HashMap<>();   // key → {on, until}

    static List<HomeApps.Item> items(Context c) {
        List<HomeApps.Item> out = new ArrayList<>();
        for (String[] d : CONTROLS) {
            Intent i = intent(d[3]);
            if (i.resolveActivity(c.getPackageManager()) == null) continue;
            if (d[3].startsWith("bridge:") && !exists(c.getPackageManager(), component(d[3].substring(7)))) continue;
            if (d[3].endsWith(".AudioOnly") && !AudioOnly.supported()) continue;
            if (d[3].endsWith(".FocusActivity") && !FocusActivity.supported()) continue;
            out.add(HomeApps.action(HomeApps.PROJECTOR, d[3], Ui.tr(d[0], d[1]), d[2]));
        }
        if (!hasStatus()) return ordered(c, out);   // the switches are the Wanbo ROM's (AFR) and its helper's (sound)
        boolean sound = !c.getPackageManager().queryBroadcastReceivers(new Intent(SOUND).setPackage(BRIDGE), 0).isEmpty();
        for (String[] d : TOGGLES) {
            if (d[3].equals("toggle:fin") || d[3].equals(RAM)) {   // need nothing from the ROM
                out.add(HomeApps.action(HomeApps.PROJECTOR, d[3], Ui.tr(d[0], d[1]), d[2]));
                continue;
            }
            if (d[3].equals("toggle:lamp")) {
                if (Lamp.supported()) out.add(HomeApps.action(HomeApps.PROJECTOR, d[3], Ui.tr(d[0], d[1]), d[2]));
                continue;
            }
            if (!d[3].equals("toggle:afr") && !sound) continue;   // cinema mode also sets the sound
            out.add(HomeApps.action(HomeApps.PROJECTOR, d[3], Ui.tr(d[0], d[1]), d[2]));
        }
        if (afrOn == null) checkAfr(c);
        return ordered(c, out);
    }

    /** In the order you left them (OK held on one in the Control Center); new ones after, in their usual place. */
    private static List<HomeApps.Item> ordered(Context c, List<HomeApps.Item> items) {
        final List<String> saved = java.util.Arrays.asList(prefs(c).getString("order", "").split(","));
        final List<HomeApps.Item> original = new ArrayList<>(items);
        java.util.Collections.sort(items, (a, b) -> Integer.compare(rank(saved, original, a), rank(saved, original, b)));
        return items;
    }

    private static int rank(List<String> saved, List<HomeApps.Item> original, HomeApps.Item it) {
        int at = saved.indexOf(it.key);
        return at >= 0 ? at : 1000 + original.indexOf(it);
    }

    static void saveOrder(Context c, List<HomeApps.Item> items) {
        StringBuilder keys = new StringBuilder();
        for (HomeApps.Item it : items) if (it.kind == HomeApps.PROJECTOR) keys.append(keys.length() > 0 ? "," : "").append(it.key);
        prefs(c).edit().putString("order", keys.toString()).apply();
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
            case "toggle:fin":
                return Heat.endSleepOn(c);
            case RAM:
                return false;
            case "toggle:lamp":
                return !Lamp.mode(c).equals("normal");
            case "toggle:cine":
                return !scene(c).equals("off");
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
        if (key.equals("toggle:lamp")) {   // three modes, not on/off: it reads the saved mode right away
            Lamp.next(app);
            return;
        }
        if (key.equals(RAM)) {
            freeMemory(app);
            return;
        }
        final boolean on = !isOn(app, key);
        synchronized (OPTIMISTIC) {
            OPTIMISTIC.put(key, new long[] {on ? 1 : 0, System.currentTimeMillis() + 5000});
        }
        if (key.equals("toggle:fin")) {
            Heat.setEndSleep(app, on);
        } else if (key.equals("toggle:cine")) {
            String next = SCENES[(java.util.Arrays.asList(SCENES).indexOf(scene(app)) + 1) % SCENES.length];
            synchronized (OPTIMISTIC) {
                OPTIMISTIC.put(key, new long[] {next.equals("off") ? 0 : 1, System.currentTimeMillis() + 5000});
            }
            scene(app, next);
        } else if (key.equals("toggle:afr")) {
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

    /**
     * Scenes, one button (like a tvOS Focus), pressed again for the next: Cinema (the frame rate follows the video,
     * clearer dialogue, the loudness leveler off: it flattens the soundtrack), Sports (the frame rate follows the video,
     * the leveler on for the crowd and the ads), Music (no dialogue boost, no leveler) and back to off, which puts back
     * what was on before the first scene. {afr, dialogue, leveler}: 1 on, 0 off, -1 as it was.
     */
    private static final String[] SCENES = {"off", "cine", "sport", "music"};
    private static final int[][] SCENE_SET = {{-1, -1, -1}, {1, 1, 0}, {1, 0, 1}, {-1, 0, 0}};
    private static final String[] SCENE_KEYS = {"toggle:afr", "toggle:dialogo", "toggle:agc"};

    static String scene(Context c) {
        android.content.SharedPreferences p = prefs(c);
        return p.getString("scene", p.getBoolean("cine", false) ? "cine" : "off");   // "cine": before scenes
    }

    private static void scene(Context app, String next) {
        android.content.SharedPreferences p = prefs(app);
        android.content.SharedPreferences.Editor e = p.edit().putString("scene", next).remove("cine");
        if (scene(app).equals("off")) for (String k : SCENE_KEYS) e.putBoolean("before:" + k, isOn(app, k));
        e.apply();
        int[] set = SCENE_SET[Math.max(0, java.util.Arrays.asList(SCENES).indexOf(next))];
        for (int i = 0; i < SCENE_KEYS.length; i++) {
            boolean want = next.equals("off") ? p.getBoolean("before:" + SCENE_KEYS[i], isOn(app, SCENE_KEYS[i]))
                    : set[i] < 0 ? isOn(app, SCENE_KEYS[i]) : set[i] == 1;
            if (isOn(app, SCENE_KEYS[i]) != want) toggle(app, SCENE_KEYS[i]);
        }
    }

    static String sceneLabel(Context c, String fallback) {
        switch (scene(c)) {
            case "cine": return Ui.tr("Cinema", "Cinema");
            case "sport": return Ui.tr("Esportes", "Sports");
            case "music": return Ui.tr("Música", "Music");
            default: return fallback;
        }
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("projector", Context.MODE_PRIVATE);
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
        if (it.key.equals("toggle:lamp")) return Lamp.label(c);   // "Eco" / "Noche" while dimmed
        if (it.key.equals("toggle:cine")) return sceneLabel(c, it.label);   // the scene that's on
        if (it.key.equals(RAM)) {
            long mb = freeMb;
            return mb > 0 ? Ui.tr(mb + " MB livres", mb + " MB free") : it.label;
        }
        if (!it.key.equals(TIMER)) return it.label;
        long at = android.provider.Settings.Global.getLong(c.getContentResolver(), "fmp_apagado", 0);
        long left = at - System.currentTimeMillis();
        return left > 0 ? ((left + 59_999) / 60_000) + " min" : it.label;
    }

    /** The sleep timer is on (its tile lights up like a switch). */
    static boolean timerOn(Context c, HomeApps.Item it) {
        return it.key.equals(TIMER)
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

    // MARK: - Memory

    /** MemAvailable in MB (what apps can still get without the system closing others), or -1. */
    static long availableMb() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("MemAvailable:")) return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Once a minute (Heat): the Memory tile's number. */
    static void updateMemory() {
        freeMb = availableMb();
    }

    /**
     * Closes the apps the system keeps cached in the background (am kill-all: only processes that are safe to close;
     * the app in front, the music playing and the system stay). Nothing is uninstalled or cleared: they open again
     * from scratch. A notice says how much was freed.
     */
    private static void freeMemory(Context app) {
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            long before = availableMb();
            String text;
            try (Adb adb = Adb.connect(app, 5000, null)) {
                adb.shell("am kill-all");
                Thread.sleep(1500);   // the memory comes back as the processes exit
                long after = availableMb();
                freeMb = after;
                long gained = Math.max(0, after - before);
                text = String.format(java.util.Locale.ROOT, Ui.tr("%d MB liberados · %d MB livres", "%d MB freed · %d MB free"),
                        gained, after);
            } catch (Exception e) {
                AppLog.w("free memory: " + e.getMessage());
                text = Ui.tr("Não foi possível liberar a memória.", "Couldn't free the memory.");
            }
            final String shown = text;
            main.post(() -> android.widget.Toast.makeText(app, shown, android.widget.Toast.LENGTH_LONG).show());
        }, "free-memory").start();
    }

    // MARK: - Status card (drawer): processor temperature, fan, lamp and panel refresh rate

    private static final String SOC = "/sys/class/thermal/thermal_zone0/temp";
    private static final String FAN = "/sys/class/jixin-feng/PWM";          // Wanbo: duty in ns of a 40000 ns period
    private static final String LAMP = "/sys/class/jixin-pwm/PWM";          // Wanbo: LED drive, 1.26 M = full, 2.65 M = dimmest
    private static final String PANEL = "/sys/class/lcd/frame_rate";        // Amlogic LCD: "get frame_rate: 60.00Hz, ..."
    private static final float FAN_PERIOD = 40000, LAMP_FULL = 1_260_000, LAMP_DIM = 2_650_000;

    /** The rows that can be read on this device: {label pt, label en, value}. Empty off a projector. */
    static List<String[]> status(Context c) {
        List<String[]> rows = new ArrayList<>();
        long soc = number(read(SOC));
        if (soc > 0) rows.add(new String[] {"Processador", "Processor", Math.round(soc / 1000f) + " °C"});
        long fan = number(read(FAN));
        if (fan > 0) rows.add(new String[] {"Ventilador", "Fan", Math.round(100 * Math.min(1, fan / FAN_PERIOD)) + " %"});
        long lamp = number(read(LAMP));
        if (lamp >= LAMP_FULL * 0.9f && lamp <= LAMP_DIM * 1.1f) {
            float brightness = 1 - (lamp - LAMP_FULL) / (LAMP_DIM - LAMP_FULL) * 0.5f;   // the dimmest step is about half
            long hours = Heat.lampHours(c);   // counted since Free My Projector was installed
            rows.add(new String[] {"Lâmpada", "Lamp", Math.round(100 * Math.max(0.5f, Math.min(1, brightness))) + " %"
                    + (hours > 0 ? "  ·  " + hours + " h" : "")});
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
