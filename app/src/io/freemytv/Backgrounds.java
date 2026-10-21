package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Free My Projector: backgrounds you pick from a search (Wikimedia Commons: free photos, no key). The ones you keep are
 * saved on the projector and replace the landscapes of the day in the light design's background and the screensaver.
 */
final class Backgrounds {
    private Backgrounds() {}

    static final class Photo {
        String title, thumb, full;
    }

    /** Wide photos (at least 1920 px, landscape) for a search, with a small thumbnail and a 1920 px version. Blocking. */
    static List<Photo> search(String query) throws Exception {
        // Commons' featured pictures first (the best photographs, no maps or flags); everything if that finds too few.
        List<Photo> out = search(query, " incategory:Featured_pictures_on_Wikimedia_Commons");
        return out.size() >= 4 ? out : search(query, "");
    }

    private static List<Photo> search(String query, String filter) throws Exception {
        String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6"
                + "&gsrlimit=50&prop=imageinfo&iiprop=url|size&iiurlwidth=1920&gsrsearch="
                + URLEncoder.encode(query.trim() + filter + " filetype:bitmap", "UTF-8");
        JSONObject pages = new JSONObject(get(url)).optJSONObject("query");
        List<Photo> out = new ArrayList<>();
        if (pages == null || pages.optJSONObject("pages") == null) return out;
        JSONObject all = pages.getJSONObject("pages");
        java.util.Iterator<String> keys = all.keys();
        List<JSONObject> sorted = new ArrayList<>();
        while (keys.hasNext()) sorted.add(all.getJSONObject(keys.next()));
        sorted.sort((a, b) -> Integer.compare(a.optInt("index"), b.optInt("index")));   // the search's own order
        for (JSONObject p : sorted) {
            JSONArray info = p.optJSONArray("imageinfo");
            if (info == null || info.length() == 0) continue;
            JSONObject i = info.getJSONObject(0);
            int w = i.optInt("width"), h = i.optInt("height");
            if (w < 1920 || h <= 0 || w / (float) h < 1.4f || w / (float) h > 2.2f) continue;   // wide photos only
            Photo ph = new Photo();
            ph.title = p.optString("title").replaceFirst("^File:", "").replaceAll("\\.[A-Za-z]+$", "").replace('_', ' ');
            ph.full = i.optString("thumburl");
            ph.thumb = ph.full.replace("/1920px-", "/500px-");   // Wikimedia only serves its standard thumbnail widths
            out.add(ph);
            if (out.size() >= 12) break;
        }
        return out;
    }

    static Bitmap thumbnail(Photo p) {
        try {
            byte[] b = getBytes(p.thumb);
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Exception e) {
            return null;
        }
    }

    // MARK: - Yours

    static boolean has(Context c, Photo p) {
        return file(c, p).exists();
    }

    /** Keeps a photo (downloads the 1920 px version) or lets it go. Blocking. */
    static void toggle(Context c, Photo p) throws Exception {
        File f = file(c, p);
        SharedPreferences prefs = prefs(c);
        JSONObject titles = new JSONObject(prefs.getString("titles", "{}"));
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            titles.remove(f.getName());
        } else {
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(getBytes(p.full));
            }
            titles.put(f.getName(), p.title);
        }
        prefs.edit().putString("titles", titles.toString()).apply();
    }

    /** Your backgrounds as landscapes (newest first); empty when you have none (then the photos of the day). */
    static List<Landscapes.Photo> mine(Context c) {
        List<Landscapes.Photo> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        JSONObject titles;
        try {
            titles = new JSONObject(prefs(c).getString("titles", "{}"));
        } catch (Exception e) {
            titles = new JSONObject();
        }
        for (File f : files) {
            Landscapes.Photo p = new Landscapes.Photo();
            p.file = f;
            p.title = "";
            p.place = titles.optString(f.getName());
            out.add(p);
        }
        return out;
    }

    static void clear(Context c) {
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "backgrounds");
    }

    private static File file(Context c, Photo p) {
        return new File(dir(c), Integer.toHexString(p.full.hashCode()) + ".jpg");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("backgrounds", Context.MODE_PRIVATE);
    }

    private static String get(String url) throws Exception {
        return new String(getBytes(url), "UTF-8");
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "FreeMyProjector/1.0 (Android TV home screen)");   // Wikimedia asks for one
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
