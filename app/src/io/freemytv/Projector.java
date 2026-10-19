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
        {"Padrão de teste", "Test pattern", "check", "pe.carlos.atajos/.Patron"},
    };

    static List<HomeApps.Item> items(Context c) {
        List<HomeApps.Item> out = new ArrayList<>();
        for (String[] d : CONTROLS) {
            Intent i = intent(d[3]);
            if (i.resolveActivity(c.getPackageManager()) == null) continue;
            if (d[3].startsWith("bridge:") && !exists(c.getPackageManager(), component(d[3].substring(7)))) continue;
            out.add(HomeApps.action(HomeApps.PROJECTOR, d[3], Ui.tr(d[0], d[1]), d[2]));
        }
        return out;
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
