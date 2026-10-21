package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Free My Projector: "Continue watching" from the apps themselves (an idea from SferaDev's Google TV style home). Android
 * TV keeps a Watch Next list that Disney+, Netflix and others fill with what you left halfway: the title, an image, how
 * far you got and a link straight back into it. Apps can't read other apps' rows, but the TV's own ADB can
 * (`content query`), so it's read through it at most every few minutes and kept for the next start.
 */
final class WatchNext {
    private WatchNext() {}

    private static final String[] KEYS = {"package_name", "title", "episode_title", "season_display_number",
            "episode_display_number", "watch_next_type", "poster_art_uri", "intent_uri", "last_playback_position_millis",
            "duration_millis", "last_engagement_time_utc_millis", "browsable"};
    private static final long EVERY = 5 * 60_000L;
    private static volatile List<Recs.Pick> items;
    private static volatile long readAt;
    private static volatile boolean reading;

    interface Done { void run(); }

    /** What's on the list now (newest first), possibly from the last run; empty when nothing was read yet. */
    static List<Recs.Pick> items(Context c) {
        if (items == null) items = load(c);
        return items;
    }

    /** Reads the list again in the background when it's a few minutes old; `done` runs when it changed. */
    static void refresh(Context c, Done done) {
        long now = System.currentTimeMillis();
        if (reading || (now - readAt >= 0 && now - readAt < EVERY)) return;
        reading = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 5000, null)) {
                String out = adb.shell("content query --uri content://android.media.tv/watch_next_program --projection "
                        + String.join(":", KEYS));
                List<Recs.Pick> list = parse(app, out);
                readAt = System.currentTimeMillis();
                boolean changed = !json(list).equals(json(items(app)));
                items = list;
                if (changed) {
                    prefs(app).edit().putString("items", json(list)).apply();
                    done.run();
                }
            } catch (Exception e) {
                AppLog.w("watch next: " + e.getMessage());
            } finally {
                reading = false;
            }
        }, "watch-next").start();
    }

    /** One "Row: n key=value, key=value…" line per program; values can hold commas, so each is cut at the next key. */
    private static List<Recs.Pick> parse(Context c, String out) {
        List<Recs.Pick> list = new ArrayList<>();
        List<long[]> order = new ArrayList<>();   // engagement time, index
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (String line : out.split("\n")) {
            if (!line.startsWith("Row:")) continue;
            String[] v = new String[KEYS.length];
            for (int i = 0; i < KEYS.length; i++) {
                int start = line.indexOf(KEYS[i] + "=");
                if (start < 0) continue;
                start += KEYS[i].length() + 1;
                int end = i + 1 < KEYS.length ? line.indexOf(", " + KEYS[i + 1] + "=", start) : line.length();
                v[i] = (end < 0 ? line.substring(start) : line.substring(start, end)).trim();
                if ("NULL".equals(v[i])) v[i] = null;
            }
            String pkg = v[0];
            if (pkg == null || v[1] == null || "0".equals(v[11]) || HomeApps.launchIntent(pm, pkg) == null) continue;
            Recs.Pick p = new Recs.Pick();
            p.kind = v[2] != null ? "tv" : "movie";
            p.title = v[1];
            p.pkg = pkg;
            p.app = HomeApps.shelfKey(pkg);
            p.link = v[7];
            p.backdrop = bigger(v[6]);
            p.detailed = true;
            p.watchNext = true;
            long pos = number(v[8]), dur = number(v[9]);
            p.progress = dur > 0 && pos > 0 ? Math.min(1f, pos / (float) dur) : 0;
            List<String> parts = new ArrayList<>();
            if (v[3] != null && v[4] != null) parts.add(Ui.tr("T", "S") + v[3] + " · E" + v[4]);
            if (v[2] != null) parts.add(v[2]);
            if (dur > 0 && pos > 0) parts.add(left(dur - pos));
            String name = p.app == null ? null : Recs.serviceName(p);
            if (name != null && !name.isEmpty()) parts.add(name);
            p.meta = String.join(" · ", parts);
            order.add(new long[] {number(v[10]), list.size()});
            list.add(p);
        }
        order.sort((a, b) -> Long.compare(b[0], a[0]));
        List<Recs.Pick> sorted = new ArrayList<>();
        for (long[] o : order) if (sorted.size() < 12) sorted.add(list.get((int) o[1]));
        return sorted;
    }

    /** "1 h 30 min left" in the TV's language. */
    private static String left(long ms) {
        long min = Math.max(1, ms / 60_000);
        String t = min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
        return String.format(Ui.tr("faltam %s", "%s left"), t);
    }

    /** Disney's image service sizes on request: the full 1920 width instead of the 510 px thumbnail. */
    private static String bigger(String url) {
        if (url == null) return null;
        if (url.contains("bamgrid.com")) return url.replaceAll("height=\\d+", "height=1080").replaceAll("width=\\d+", "width=1920");
        return url;
    }

    private static long number(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String json(List<Recs.Pick> list) {
        JSONArray a = new JSONArray();
        try {
            for (Recs.Pick p : list) {
                a.put(new JSONObject().put("title", p.title).put("kind", p.kind).put("pkg", p.pkg).put("link", p.link)
                        .put("backdrop", p.backdrop).put("progress", p.progress).put("meta", p.meta));
            }
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    private static List<Recs.Pick> load(Context c) {
        List<Recs.Pick> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("items", "[]"));
            android.content.pm.PackageManager pm = c.getPackageManager();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Recs.Pick p = new Recs.Pick();
                p.title = o.optString("title");
                p.kind = o.optString("kind");
                p.pkg = o.optString("pkg");
                if (HomeApps.launchIntent(pm, p.pkg) == null) continue;
                p.app = HomeApps.shelfKey(p.pkg);
                p.link = o.optString("link", null);
                p.backdrop = o.optString("backdrop", null);
                p.progress = (float) o.optDouble("progress", 0);
                p.meta = o.optString("meta");
                p.detailed = true;
                p.watchNext = true;
                list.add(p);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("watch-next", Context.MODE_PRIVATE);
    }
}
