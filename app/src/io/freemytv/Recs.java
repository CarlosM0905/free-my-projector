package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String link;            // opens the title itself in its app (null: just the app)
        boolean detailed;
        long seenAt;            // Up Next: when it was opened
        String fresh;           // Up Next: "New episode" / "New on Friday" (shows only), or null
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 4;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"netflix", "netflix", "com.netflix.mediaclient"},             // projectors: the phone build
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "prime", "com.amazon.avod.thirdpartyclient"},  // projectors: the phone build
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp;
    private volatile boolean listEnglish;   // the saved list came in English (titles, genres and texts)
    /** Up Next: the titles opened from the home screen, latest first (its own file: survives new lists and versions). */
    private final SharedPreferences seen;
    private final List<Pick> history = new ArrayList<>();
    private static final int HISTORY_MAX = 12;

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listEnglish = cache.getBoolean("english", false);
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
        seen = c.getSharedPreferences("up_next", Context.MODE_PRIVATE);
        try {
            history.addAll(parse(new JSONArray(seen.getString("picks", "[]"))));
        } catch (Exception ignored) {
        }
        for (Pick h : history) if (h.seenAt == 0) h.seenAt = System.currentTimeMillis();   // saved before seenAt existed
    }

    // MARK: - Up Next (like tvOS): what was opened from the home screen, to pick it up again

    /** The latest 3 opened titles whose app is still on the TV. */
    List<Pick> upNext() {
        List<Pick> out = new ArrayList<>();
        synchronized (history) {
            for (Pick p : history) if (out.size() < 3 && p.pkg != null && installed(p.pkg)) out.add(p);
        }
        checkEpisodes(out);
        return out;
    }

    private volatile long episodesAt;

    /**
     * Up Next, like the Apple TV app: a show you watched gets "New episode" once one airs after you opened it, or
     * "New on Friday" when the next one is less than a week away. Asked to TMDB at most every 6 hours.
     */
    private void checkEpisodes(List<Pick> shown) {
        long now = System.currentTimeMillis();
        if (!hasKey(context) || (now - episodesAt >= 0 && now - episodesAt < 6 * 3600_000L)) return;
        episodesAt = now;
        final List<Pick> shows = new ArrayList<>();
        for (Pick p : shown) if ("tv".equals(p.kind)) shows.add(p);
        if (shows.isEmpty()) return;
        io.execute(() -> {
            boolean changed = false;
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
            for (Pick p : shows) {
                try {
                    JSONObject o = get("/tv/" + p.id + "?language=" + lang());
                    String fresh = null;
                    JSONObject last = o.optJSONObject("last_episode_to_air"), next = o.optJSONObject("next_episode_to_air");
                    long seenDay = day.parse(day.format(new java.util.Date(p.seenAt))).getTime();
                    if (last != null && !last.optString("air_date").isEmpty()
                            && day.parse(last.optString("air_date")).getTime() > seenDay) {
                        fresh = Ui.tr("Episódio novo", "New episode");
                    } else if (next != null && !next.optString("air_date").isEmpty()) {
                        long at = day.parse(next.optString("air_date")).getTime();
                        long today = day.parse(day.format(new java.util.Date())).getTime();
                        if (at == today) {
                            fresh = Ui.tr("Novo hoje", "New today");
                        } else if (at > today && at - today < 7 * 86_400_000L) {
                            String wd = new java.text.SimpleDateFormat("EEEE", Ui.en ? java.util.Locale.US : Ui.PT).format(new java.util.Date(at));
                            fresh = String.format(Ui.tr("Novo na %s", "New on %s"), wd);
                        }
                    }
                    if (!java.util.Objects.equals(fresh, p.fresh)) {
                        p.fresh = fresh;
                        changed = true;
                    }
                } catch (Exception e) {
                    AppLog.w("episodes " + p.title + ": " + e.getMessage());
                }
            }
            if (changed) notifyListeners();
        });
    }

    /**
     * Top Shelf (like tvOS): up to 3 titles of one service (HomeApps key: "disney", "netflix"…) with their image on
     * disk, this hour's first (already in memory). Sized so the tabs' 9, Up Next's 3 and these 3 fit the image cache.
     */
    List<Pick> forService(String app) {
        List<Pick> out = new ArrayList<>();
        if (app == null || !hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Pick> all = new ArrayList<>(wanted());
        all.addAll(picks);
        for (Pick p : all) {
            if (out.size() < 3 && app.equals(p.app) && p.pkg != null && hasImage(p) && seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /**
     * Search (like tvOS): movies and shows matching the text that are on a service installed on this TV, in this
     * country, with their deep link. Blocking: call it off the main thread.
     */
    List<Pick> search(String query) throws Exception {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context) || query.trim().isEmpty()) return out;
        JSONArray results = get("/search/multi?language=" + lang() + "&include_adult=false&query="
                + URLEncoder.encode(query.trim(), "UTF-8")).getJSONArray("results");
        for (int i = 0; i < results.length() && i < 10 && out.size() < 4; i++) {
            JSONObject o = results.getJSONObject(i);
            String kind = o.optString("media_type");
            if (!kind.equals("movie") && !kind.equals("tv")) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = kind;
            p.title = o.optString(kind.equals("movie") ? "title" : "name");
            p.backdrop = o.optString("backdrop_path", "");
            if (p.backdrop.isEmpty() || p.backdrop.equals("null")) p.backdrop = null;
            JSONObject br = get("/" + kind + "/" + p.id + "/watch/providers").getJSONObject("results").optJSONObject(REGION);
            JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
            for (int j = 0; flat != null && j < flat.length() && p.app == null; j++) {
                String[] sv = service(flat.getJSONObject(j).optString("provider_name"));
                if (sv != null && installed(sv[2])) {
                    p.app = sv[1];
                    p.pkg = sv[2];
                }
            }
            if (p.app == null) continue;   // not on this TV's apps
            String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date");
            List<String> parts = new ArrayList<>();
            if (kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
            if (date.length() >= 4) parts.add(date.substring(0, 4));
            parts.add(SERVICE_NAME.get(p.app));
            p.meta = String.join(" · ", parts);
            try {
                p.link = link(p);
            } catch (Exception e) {
                AppLog.w("link for " + p.title + ": " + e.getMessage());
            }
            p.detailed = true;
            out.add(p);
        }
        return out;
    }

    /** Frees the ready images except these (the home screen is hidden; the rest are rebuilt when it comes back). */
    void keepOnly(List<Pick> keep) {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Pick p : keep) if (p.backdrop != null) wanted.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(wanted);
        }
    }

    /** A title was opened: it goes first in Up Next. */
    void remember(Pick p) {
        if (p == null || p.pkg == null) return;
        synchronized (history) {
            for (int i = history.size() - 1; i >= 0; i--) if (key(history.get(i)).equals(key(p))) history.remove(i);
            p.seenAt = System.currentTimeMillis();
            p.fresh = null;   // opened again: caught up
            history.add(0, p);
            while (history.size() > HISTORY_MAX) history.remove(history.size() - 1);
            try {
                JSONArray a = new JSONArray();
                for (Pick h : history) a.put(json(h));
                seen.edit().putString("picks", a.toString()).apply();
            } catch (Exception ignored) {
            }
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        return (Ui.en ? PERIOD_LABEL_EN : Ui.es ? Es.PERIOD_LABEL : PERIOD_LABEL)[period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind)) && (!p.detailed || p.pkg != null)) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode();   // a new key or country refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || listEnglish != Ui.en;
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listEnglish = Ui.en;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        return Ui.en ? "en-US" : Ui.es ? "es-MX" : "pt-BR";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        list.add(p);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null,pt,en");
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0 && Ui.en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        if (p.app != null) {
            try {
                p.link = link(p);
            } catch (Exception e) {
                AppLog.w("link for " + p.title + ": " + e.getMessage());
            }
        }
        p.detailed = true;
    }

    // MARK: - Deep links: the title's page in its app, from the service IDs that Wikidata keeps for each TMDB title

    /** Service key → {Wikidata property, link with %s for the ID}. */
    private static final Map<String, String[]> LINKS = new HashMap<>();
    static {
        LINKS.put("disney", new String[] {"P13902", "https://www.disneyplus.com/browse/%s"});
        LINKS.put("netflix", new String[] {"P1874", "https://www.netflix.com/title/%s"});
        LINKS.put("prime", new String[] {"P8055", "https://watch.amazon.com/detail?asin=%s"});
        LINKS.put("crunchyroll", new String[] {"P11330", "crunchyroll://series/%s"});
    }

    /**
     * The link to the title in its app, or null. A title can have several IDs on one service; the ones marked with
     * qualifiers are variants ("Sing-Along" versions, other regions), so the plain one wins.
     */
    private static String link(Pick p) throws Exception {
        String[] l = LINKS.get(p.app);
        if (l == null) return null;
        String query = "SELECT ?v (COUNT(?q) AS ?n) WHERE { ?i wdt:" + (p.kind.equals("tv") ? "P4983" : "P4947") + " \"" + p.id
                + "\"; p:" + l[0] + " ?s. ?s ps:" + l[0] + " ?v; wikibase:rank ?r. FILTER(?r != wikibase:DeprecatedRank) "
                + "OPTIONAL { ?s ?q ?x. FILTER(STRSTARTS(STR(?q), \"http://www.wikidata.org/prop/qualifier/\")) } } "
                + "GROUP BY ?v ORDER BY ?n LIMIT 1";
        HttpURLConnection c = (HttpURLConnection) new URL("https://query.wikidata.org/sparql?query="
                + URLEncoder.encode(query, "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/sparql-results+json");
        c.setRequestProperty("User-Agent", "FreeMyProjector/1.0 (https://github.com/CarlosM0905/free-my-projector)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("Wikidata returned HTTP " + c.getResponseCode());
            JSONArray rows = new JSONObject(read(c.getInputStream())).getJSONObject("results").getJSONArray("bindings");
            if (rows.length() == 0) return null;
            String id = rows.getJSONObject(0).getJSONObject("v").getString("value");
            return String.format(Locale.ROOT, l[1], id);
        } finally {
            c.disconnect();
        }
    }

    /** TV genres that TMDB returns in English even in pt-BR. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };

    private static String genre(String name) {
        for (String[] g : Ui.es ? Es.GENRES : GENRES_PT) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    /** The same for lists saved earlier ("Série · Action & Adventure · …" in Portuguese). */
    private static String fixGenres(String meta) {
        for (String[] g : Ui.es ? Es.GENRES : GENRES_PT) {
            meta = meta.replace(" · " + g[0] + " ·", " · " + g[1] + " ·");
            if (meta.endsWith(" · " + g[0])) meta = meta.substring(0, meta.length() - g[0].length()) + g[1];
        }
        return meta;
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), at the highest resolution
     * (up to 3840, TMDB's 4K) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 3840) * 100 + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The TV app for a TMDB service; with several builds of one service (TV / phone), the one that is installed. */
    private String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        for (String[] s : SERVICES) {
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    private volatile Backdrop landscape;
    private volatile boolean landscapeLight, landscapeLoading;

    /**
     * Free My Projector: the landscape of the day (the screensaver's newest photo) as the background when there are no
     * cards (Search, or no TMDB key). Null while it loads; the listeners hear when it's ready.
     */
    Backdrop landscape() {
        Backdrop b = landscape;
        if (b != null && landscapeLight == light) return b;
        if (landscapeLoading) return null;
        landscapeLoading = true;
        io.execute(() -> {
            try {
                List<Landscapes.Photo> photos = Landscapes.ready(context);
                if (photos.isEmpty()) {
                    Landscapes.refresh(context, this::notifyListeners);
                    return;
                }
                Bitmap bm = BitmapFactory.decodeFile(photos.get(0).file.getPath());   // 1920×1080, Backdrop's size
                if (bm == null) return;
                boolean forLight = light;
                Backdrop made = Backdrop.make(bm, forLight);
                if (forLight != light) return;
                landscapeLight = forLight;
                landscape = made;
                notifyListeners();
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("landscape background: " + e.getMessage());
            } finally {
                landscapeLoading = false;
            }
        });
        return null;
    }

    /** Reads the original (≥ 1920 wide) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? 16 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 24) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 24; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) a.put(json(p));
            cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putBoolean("english", listEnglish)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject json(Pick p) throws org.json.JSONException {
        return new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                .put("link", p.link == null ? "" : p.link).put("detailed", p.detailed).put("seenAt", p.seenAt);
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.meta = fixGenres(o.optString("meta"));
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.detailed = o.optBoolean("detailed");
            p.seenAt = o.optLong("seenAt");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
