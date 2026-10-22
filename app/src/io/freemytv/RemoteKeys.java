package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Free My Projector: the remote's Home button on Wanbo projectors. Their system doesn't open "the home screen": it
 * opens Google's (com.google.android.tvlauncher) by name, and Free mode turns that one off, so Home would do nothing.
 * The key reaches RemoteKeyService before that happens, so in Free mode it opens ours (like Projectivy does with its
 * own accessibility service). Original mode turns the service off again: the remote is as it came from the factory.
 * Other brands open the default home screen themselves, so nothing here runs there.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    static final String SERVICE = HomeMode.PKG + "/" + HomeMode.PKG + ".RemoteKeyService";
    /** Wanbo (made by Jixin): their system opens Google's home screen by name. */
    static final boolean WANBO = "Wanbo".equalsIgnoreCase(Build.BRAND) || "jixin".equalsIgnoreCase(Build.MANUFACTURER);

    static boolean needed() {
        return WANBO;
    }

    // MARK: - In the key process (RemoteKeyService)

    /** True if the key is Home and ours to handle (it is then consumed, and Free My Projector opens when it's let go). */
    static boolean onKey(Context c, KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME || !flag(c).exists()) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
            AppLog.w("home key: opening Free My Projector");
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName.unflattenFromString(HomeMode.HOME_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                c.startActivity(home);
            } catch (RuntimeException ex) {
                AppLog.w("home key: " + ex.getMessage());
            }
        }
        return true;
    }

    /** Set while Free mode is on: the key process checks it on every Home press (it can't share the main one's state). */
    private static File flag(Context c) {
        return new File(c.getFilesDir(), "home-key");
    }

    // MARK: - Turning it on and off (HomeMode, through the TV's own ADB)

    /** On: the flag and the service in the enabled accessibility services. Off: both removed. */
    static void set(Context c, Adb adb, boolean on) throws IOException {
        File f = flag(c);
        if (on) {
            //noinspection ResultOfMethodCallIgnored
            f.createNewFile();
        } else {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        String list = adb.shell("settings get secure enabled_accessibility_services").trim();
        if (list.equals("null")) list = "";
        List<String> parts = new ArrayList<>();
        for (String s : list.split(":")) if (!s.isEmpty() && !s.equals(SERVICE)) parts.add(s);
        if (on) parts.add(SERVICE);
        String now = String.join(":", parts);
        if (now.equals(list)) return;
        if (parts.isEmpty()) {
            adb.shell("settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0");
        } else {
            adb.shell("settings put secure enabled_accessibility_services " + now + "; settings put secure accessibility_enabled 1");
        }
    }

    /** Free mode on a Wanbo without it (an update from before 1.3.2, or the TV turned it off): turns it on. */
    static boolean ensure(Context c, Adb adb) throws IOException {
        if (!needed()) return false;
        boolean had = flag(c).exists() && adb.shell("settings get secure enabled_accessibility_services").contains(SERVICE);
        if (had) return false;
        set(c, adb, true);
        return true;
    }
}
