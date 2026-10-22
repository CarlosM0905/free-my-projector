package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Free My Projector: the landscapes of the framed screensaver ("Paisajes", like The Frame's art mode): Bing's photos
 * of the day (16 days, 1920×1080, with the place's name in the TV's language), kept on disk and refreshed once a day.
 */
final class Landscapes {
    private Landscapes() {}

    static final class Photo {
        File file;
        String title, place;
    }

    private static volatile boolean fetching;

    /** With fewer of your own backgrounds than this, the landscapes of the day follow them, so the screensaver still changes. */
    private static final int OWN_ENOUGH = 5;

    /**
     * The photos to show, newest first: your own backgrounds (picked from a search) and, while you have fewer than
     * OWN_ENOUGH, the landscapes of the day after them. Empty the first time; `refresh` fills it.
     */
    static List<Photo> ready(Context c) {
        List<Photo> mine = Backgrounds.mine(c);
        if (mine.size() >= OWN_ENOUGH) return mine;
        List<Photo> out = new ArrayList<>(mine);
        out.addAll(ofTheDay(c));
        return out;
    }

    /** The landscapes of the day already on disk, newest first. */
    private static List<Photo> ofTheDay(Context c) {
        List<Photo> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs(c).getString("list", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                File f = new File(dir(c), o.getString("id") + ".jpg");
                if (!f.exists()) continue;
                Photo p = new Photo();
                p.file = f;
                p.title = o.optString("title");
                p.place = o.optString("place");
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Once a day, in the background: the list of the last 16 photos and the ones not downloaded yet. */
    static void refresh(Context c, Runnable done) {
        final Context app = c.getApplicationContext();
        long now = System.currentTimeMillis(), at = prefs(app).getLong("at", 0);
        if (fetching || (now - at >= 0 && now - at < 12 * 3600_000L && !ofTheDay(app).isEmpty())) return;
        fetching = true;
        new Thread(() -> {
            try {
                // Bing's photo of the day speaks a few markets; the rest get the English captions
                String market = Ui.es ? "es-ES" : Ui.pt ? "pt-BR" : Ui.LANG.equals("fr") ? "fr-FR" : Ui.LANG.equals("de") ? "de-DE"
                        : Ui.LANG.equals("ja") ? "ja-JP" : Ui.LANG.equals("zh-CN") ? "zh-CN" : "en-US";
                JSONArray all = new JSONArray();
                for (int idx : new int[] {0, 8}) {
                    JSONArray images = new JSONObject(get("https://www.bing.com/HPImageArchive.aspx?format=js&n=8&idx="
                            + idx + "&mkt=" + market)).getJSONArray("images");
                    for (int i = 0; i < images.length(); i++) {
                        JSONObject im = images.getJSONObject(i);
                        String base = im.getString("urlbase"), copyright = im.optString("copyright");
                        int cut = copyright.indexOf(" (©");
                        JSONObject o = new JSONObject();
                        o.put("id", base.replaceAll("[^A-Za-z0-9_-]", ""));
                        o.put("url", "https://www.bing.com" + base + "_1920x1080.jpg");
                        o.put("title", im.optString("title"));
                        o.put("place", cut > 0 ? copyright.substring(0, cut) : copyright);
                        all.put(o);
                    }
                }
                File d = dir(app);
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
                List<String> keep = new ArrayList<>();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject o = all.getJSONObject(i);
                    File f = new File(d, o.getString("id") + ".jpg");
                    keep.add(f.getName());
                    if (!f.exists()) download(o.getString("url"), f);
                }
                File[] old = d.listFiles();
                if (old != null) for (File f : old) if (!keep.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                prefs(app).edit().putString("list", all.toString()).putLong("at", System.currentTimeMillis()).apply();
                if (done != null) done.run();
            } catch (Exception e) {
                AppLog.w("landscapes: " + e.getMessage());
            } finally {
                fetching = false;
            }
        }, "landscapes").start();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("landscapes", Context.MODE_PRIVATE);
    }

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "landscapes");
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
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

    private static void download(String url, File to) throws IOException {
        File part = new File(to.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(to)) throw new IOException("couldn't save " + to.getName());
    }

    /** "domingo, 19 de octubre" in the TV's language. */
    static String date(long now) {
        return Ui.date(now);
    }
}
