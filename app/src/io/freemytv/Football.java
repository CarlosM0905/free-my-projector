package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Free My Projector: your football team's next match and last result for the home screen's widget (config:
 * football_team, e.g. "Sport Boys"). TheSportsDB's free API, at most every 3 hours; kept on disk between runs.
 */
final class Football {
    private Football() {}

    private static final String API = "https://www.thesportsdb.com/api/v1/json/123/";
    private static final long EVERY = 3 * 3600_000L;

    static final class Match {
        String team, home, away, homeScore, awayScore;
        long at;   // kick-off, epoch ms (0 = unknown)
    }

    static volatile Match next, last;
    private static volatile boolean fetching, loaded;

    static boolean enabled() {
        return !Prefs.footballTeam().trim().isEmpty();
    }

    /** In the background when stale; `done` runs (on any thread) when there's something new. */
    static void refresh(Context c, Runnable done) {
        if (!enabled()) return;
        final Context app = c.getApplicationContext();
        final SharedPreferences p = app.getSharedPreferences("football", Context.MODE_PRIVATE);
        if (!loaded) {
            loaded = true;
            next = parse(p.getString("next", null));
            last = parse(p.getString("last", null));
        }
        long now = System.currentTimeMillis(), at = p.getLong("at", 0);
        boolean played = next != null && next.at > 0 && now > next.at + 2 * 3600_000L;   // the next one is over: ask again
        if (fetching || (!played && now - at >= 0 && now - at < EVERY && p.getString("team", "").equals(Prefs.footballTeam()))) return;
        fetching = true;
        new Thread(() -> {
            try {
                String id = p.getString("id", "");
                if (id.isEmpty() || !p.getString("team", "").equals(Prefs.footballTeam())) {
                    JSONArray teams = new JSONObject(get("searchteams.php?t=" + URLEncoder.encode(Prefs.footballTeam().trim(), "UTF-8")))
                            .optJSONArray("teams");
                    if (teams == null || teams.length() == 0) throw new Exception("team not found: " + Prefs.footballTeam());
                    id = teams.getJSONObject(0).optString("idTeam");
                }
                JSONArray n = new JSONObject(get("eventsnext.php?id=" + id)).optJSONArray("events");
                JSONArray l = new JSONObject(get("eventslast.php?id=" + id)).optJSONArray("results");
                JSONObject ne = n == null || n.length() == 0 ? null : n.getJSONObject(0);
                JSONObject le = l == null || l.length() == 0 ? null : l.getJSONObject(0);
                p.edit().putString("id", id).putString("team", Prefs.footballTeam()).putLong("at", System.currentTimeMillis())
                        .putString("next", ne == null ? null : ne.toString()).putString("last", le == null ? null : le.toString()).apply();
                next = parse(ne == null ? null : ne.toString());
                last = parse(le == null ? null : le.toString());
                if (done != null) done.run();
            } catch (Exception e) {
                AppLog.w("football: " + e.getMessage());
            } finally {
                fetching = false;
            }
        }, "football").start();
    }

    private static Match parse(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            Match m = new Match();
            m.home = o.optString("strHomeTeam");
            m.away = o.optString("strAwayTeam");
            m.homeScore = o.isNull("intHomeScore") ? "" : o.optString("intHomeScore");
            m.awayScore = o.isNull("intAwayScore") ? "" : o.optString("intAwayScore");
            String ts = o.optString("strTimestamp");   // UTC
            if (!ts.isEmpty()) {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                m.at = f.parse(ts.length() > 19 ? ts.substring(0, 19) : ts).getTime();
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** "vs Cajamarca" at home, "at Cajamarca" away. */
    static String rival(Match m) {
        boolean home = m.home != null && m.home.toLowerCase(java.util.Locale.ROOT).contains(Prefs.footballTeam().trim().toLowerCase(java.util.Locale.ROOT));
        return home ? Ui.tr("vs ", "vs ") + m.away : Ui.tr("em ", "at ") + m.home;
    }

    /** "dom 25 · 15:30" in the TV's language and time zone. */
    static String when(Match m) {
        if (m.at == 0) return "";
        java.util.Locale l = Ui.en ? java.util.Locale.US : Ui.PT;
        String day = new java.text.SimpleDateFormat("EEE d", l).format(new java.util.Date(m.at));
        String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(new java.util.Date(m.at));
        return day + "  ·  " + time;
    }

    /** "1–3 vs Alianza Atlético". */
    static String result(Match m) {
        boolean home = m.home != null && m.home.toLowerCase(java.util.Locale.ROOT).contains(Prefs.footballTeam().trim().toLowerCase(java.util.Locale.ROOT));
        String us = home ? m.homeScore : m.awayScore, them = home ? m.awayScore : m.homeScore;
        if (us.isEmpty() || them.isEmpty()) return "";
        return us + "–" + them + "  " + (home ? Ui.tr("vs ", "vs ") + m.away : Ui.tr("em ", "at ") + m.home);
    }

    private static String get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
