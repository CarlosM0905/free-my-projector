package io.freemytv;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Free My Projector: Screen Time on the Control Center. The apps used most in the last 7 days and today's total, from
 * Android's usage stats (the permission is given once through the TV's own ADB). Read in the background when the
 * Control Center opens; the card draws what was read last.
 */
final class Usage {
    private Usage() {}

    static final class App {
        String label;
        long ms;
    }

    static volatile List<App> week = new ArrayList<>();
    static volatile long todayMs = -1;   // -1: not read yet (or no permission yet)
    private static volatile boolean reading;
    private static final long DAY = 86_400_000L;

    /** Reads again in the background (at most one at a time); `done` runs on that thread when there's something new. */
    static void refresh(Context c, Runnable done) {
        if (reading) return;
        reading = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (!allowed(app)) {
                    try (Adb adb = Adb.connect(app, 5000, null)) {
                        adb.shell("appops set " + app.getPackageName() + " GET_USAGE_STATS allow");
                    }
                    if (!allowed(app)) return;
                }
                UsageStatsManager usm = (UsageStatsManager) app.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm == null) return;
                long now = System.currentTimeMillis();
                Calendar midnight = Calendar.getInstance();
                midnight.set(Calendar.HOUR_OF_DAY, 0);
                midnight.set(Calendar.MINUTE, 0);
                midnight.set(Calendar.SECOND, 0);
                midnight.set(Calendar.MILLISECOND, 0);
                List<App> apps = tally(app, usm.queryAndAggregateUsageStats(now - 7 * DAY, now));
                long today = 0;
                for (App a : tally(app, usm.queryAndAggregateUsageStats(midnight.getTimeInMillis(), now))) today += a.ms;
                week = apps;
                todayMs = today;
                if (done != null) done.run();
            } catch (Exception e) {
                AppLog.w("usage: " + e.getMessage());
            } finally {
                reading = false;
            }
        }, "usage").start();
    }

    /** The TV apps (the ones with a launcher entry; not the home screen itself), most used first. */
    private static List<App> tally(Context c, Map<String, UsageStats> stats) {
        PackageManager pm = c.getPackageManager();
        List<App> out = new ArrayList<>();
        for (UsageStats s : stats.values()) {
            String pkg = s.getPackageName();
            long ms = s.getTotalTimeInForeground();
            if (ms < 60_000 || pkg.equals(c.getPackageName()) || pm.getLeanbackLaunchIntentForPackage(pkg) == null) continue;
            App a = new App();
            try {
                a.label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            a.ms = ms;
            out.add(a);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.ms, a.ms));
        return out;
    }

    private static boolean allowed(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
                c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** "6 h 20 min", "45 min". */
    static String duration(long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 60) return min + " min";
        return min % 60 == 0 ? (min / 60) + " h" : (min / 60) + " h " + (min % 60) + " min";
    }
}
