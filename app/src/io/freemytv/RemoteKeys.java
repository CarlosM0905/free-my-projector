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
 * Free My Projector: two buttons of the remote, seen by RemoteKeyService before any app.
 *
 * Home, on Wanbo projectors: their system doesn't open "the home screen", it opens Google's
 * (com.google.android.tvlauncher) by name, and Free mode turns that one off, so Home would do nothing. In Free mode the
 * service opens ours instead (like Projectivy does with its own accessibility service); Original mode turns it off.
 * Other brands open the default home screen themselves, so this part never runs there.
 *
 * The microphone (SEARCH or VOICE_ASSIST), if Preferences › Voice search is Free My Projector: hold it and talk (Voice),
 * like Siri on the Apple TV. With Google (the default) the key goes on to the system, as it came.
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

    /** True if the key is ours to handle (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME) return home(c, e);
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) return voice(c, e);
        return false;
    }

    private static boolean home(Context c, KeyEvent e) {
        if (!flag(c, HOME).exists()) return false;
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

    /** Pressed: the voice panel opens and listens; let go: it stops listening (a quick tap listens until you pause). */
    private static boolean voice(Context c, KeyEvent e) {
        if (!flag(c, VOICE).exists() || !Voice.available(c)) return false;   // Google's, as it came
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            c.startActivity(new Intent(c, Voice.class).putExtra("held", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            Voice.release();
        }
        return true;
    }

    /** The key process can't share the main one's state: each button's choice is a small file, checked per press. */
    private static final String HOME = "home-key", VOICE = "voice-key";

    private static File flag(Context c, String name) {
        return new File(c.getFilesDir(), name);
    }

    static boolean voiceOn(Context c) {
        return flag(c, VOICE).exists();
    }

    // MARK: - Turning them on and off (through the TV's own ADB)

    /** Free / Original mode (HomeMode): the Home button, on Wanbo only. */
    static void setHome(Context c, Adb adb, boolean on) throws IOException {
        mark(c, HOME, on && needed());
        apply(c, adb);
    }

    /** Preferences › Voice search: Free My Projector (on) or Google (off). Runs in the background. */
    static void setVoice(Context c, boolean on) {
        mark(c, VOICE, on);
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 5000, null)) {
                if (on) adb.shell("pm grant " + app.getPackageName() + " android.permission.RECORD_AUDIO");
                apply(app, adb);
            } catch (Exception e) {
                AppLog.w("voice key: " + e.getMessage());
            }
        }, "voice-key").start();
    }

    /** Free mode on a Wanbo without the Home button (an update from before 1.3.2, or the TV turned it off): turns it on. */
    static boolean ensure(Context c, Adb adb) throws IOException {
        if (!needed()) return false;
        boolean had = flag(c, HOME).exists() && adb.shell("settings get secure enabled_accessibility_services").contains(SERVICE);
        if (had) return false;
        setHome(c, adb, true);
        return true;
    }

    private static void mark(Context c, String name, boolean on) {
        File f = flag(c, name);
        try {
            if (on) {
                //noinspection ResultOfMethodCallIgnored
                f.createNewFile();
            } else {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (IOException e) {
            AppLog.w("remote keys: " + e.getMessage());
        }
    }

    /** The service is in the enabled accessibility services while any button needs it, and only then. */
    private static void apply(Context c, Adb adb) throws IOException {
        boolean on = flag(c, HOME).exists() || flag(c, VOICE).exists();
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
}
