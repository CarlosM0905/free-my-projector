package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free My Projector: the lyrics of the song playing (Spotify, AuraMusic…), in time with it, like Apple Music on tvOS:
 * big lines, the one being sung bright and the rest dim, scrolling as the song goes. From lrclib.net (free, no key),
 * kept in the cache so a song is only asked for once. Follows the song when it changes; Back leaves, the media buttons
 * go to the app.
 */
public final class Lyrics extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Page page;
    private Runnable stopWatching = () -> {};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        page = new Page(this);
        setContentView(page);
    }

    @Override protected void onResume() {
        super.onResume();
        stopWatching = NowPlaying.watch(this, main, () -> page.setTrack(NowPlaying.current(this)));
        page.setTrack(NowPlaying.current(this));
        page.start();
    }

    @Override protected void onPause() {
        stopWatching.run();
        page.stop();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
        boolean media = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_NEXT
                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_STOP;
        return media ? super.dispatchKeyEvent(e) : true;   // the arrows and OK have nothing to do here
    }

    /** The song's lines: `times` in ms (null: plain lyrics, no timing). */
    private static final class Words {
        final String[] lines;
        final long[] times;
        final boolean instrumental;

        Words(String[] lines, long[] times, boolean instrumental) {
            this.lines = lines;
            this.times = times;
            this.instrumental = instrumental;
        }
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");

    /** "[01:23.45] text" lines (a line may carry several times), sorted by time. */
    private static Words parseSynced(String lrc) {
        List<long[]> order = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher m = STAMP.matcher(raw);
            int end = 0;
            List<Long> at = new ArrayList<>();
            while (m.find() && m.start() == end) {
                at.add(Long.parseLong(m.group(1)) * 60_000 + Math.round(Double.parseDouble(m.group(2)) * 1000));
                end = m.end();
            }
            if (at.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : at) {
                order.add(new long[] {t, texts.size()});
                texts.add(text.isEmpty() ? "♪" : text);
            }
        }
        java.util.Collections.sort(order, (a, b) -> Long.compare(a[0], b[0]));
        String[] lines = new String[order.size()];
        long[] times = new long[order.size()];
        for (int i = 0; i < order.size(); i++) {
            times[i] = order.get(i)[0];
            lines[i] = texts.get((int) order.get(i)[1]);
        }
        return new Words(lines, times, false);
    }

    /** Asks lrclib.net (the exact match first, then a search), or the cache. Null: no lyrics for this song. */
    private static Words load(Context c, NowPlaying.Track t) throws IOException, org.json.JSONException {
        String key = Integer.toHexString((t.artist + "\u0000" + t.title).hashCode());
        File dir = new File(c.getCacheDir(), "lyrics"), f = new File(dir, key + ".json");
        String body = null;
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                body = read(in);
            }
        } else {
            String q = "artist_name=" + URLEncoder.encode(t.artist == null ? "" : t.artist, "UTF-8")
                    + "&track_name=" + URLEncoder.encode(t.title, "UTF-8");
            body = get("https://lrclib.net/api/get?" + q + (t.duration > 0 ? "&duration=" + t.duration / 1000 : ""));
            if (body == null) {
                String found = get("https://lrclib.net/api/search?" + q);
                JSONArray list = found == null ? null : new JSONArray(found);
                body = list != null && list.length() > 0 ? list.getJSONObject(0).toString() : "{}";
            }
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(body.getBytes("UTF-8"));
            }
        }
        try {
            JSONObject o = new JSONObject(body);
            if (o.optBoolean("instrumental")) return new Words(new String[0], null, true);
            String synced = o.optString("syncedLyrics", ""), plain = o.optString("plainLyrics", "");
            if (!synced.isEmpty() && !synced.equals("null")) {
                Words w = parseSynced(synced);
                if (w.lines.length > 0) return w;
            }
            if (!plain.isEmpty() && !plain.equals("null")) return new Words(plain.split("\n"), null, false);
        } catch (org.json.JSONException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        return null;
    }

    /** The body, or null for 404 (lrclib's "not found"). */
    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "FreeMyProjector (https://github.com/CarlosM0905/free-my-projector)");
        try {
            if (c.getResponseCode() == 404) return null;
            try (InputStream in = c.getInputStream()) {
                return read(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toString("UTF-8");
    }

    /** The screen: the song at the top, the lines below; redrawn only while the lines move. */
    private static final class Page extends View implements Choreographer.FrameCallback {
        private static final int LOADING = 0, READY = 1, NONE = 2, NOTHING = 3, FAILED = 4;
        private final Spring scroll = new Spring(0.6f, 1f, 0);
        private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
        private NowPlaying.Track track;
        private String songKey = "";
        private Words words;
        private StaticLayout[] layouts = new StaticLayout[0];
        private float[] tops = new float[0];
        private int state = NOTHING, current = -1, serial;
        private boolean running;
        private long lastNanos;
        private float k = 1;
        private final TextPaint line = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private Paint song, artist, note;

        Page(Context c) {
            super(c);
        }

        void start() {
            running = true;
            lastNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }

        void stop() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }

        /** A new report from the player: the same song moves its position; another one loads its lyrics. */
        void setTrack(NowPlaying.Track t) {
            if (t == null) {   // paused or stopped: the lines stay where they are
                if (track != null && track.position >= 0) {
                    track.position = track.positionNow();
                    track.updatedAt = android.os.SystemClock.elapsedRealtime();
                    track.speed = 0;
                }
                if (track == null) state = NOTHING;
                invalidate();
                return;
            }
            String key = t.artist + "\u0000" + t.title;
            track = t;
            if (key.equals(songKey)) return;
            songKey = key;
            words = null;
            layouts = new StaticLayout[0];
            current = -1;
            state = LOADING;
            final int mine = ++serial;
            final Context app = getContext().getApplicationContext();
            io.execute(() -> {
                Words w;
                int result;
                try {
                    w = load(app, t);
                    result = w == null ? NONE : READY;
                } catch (Exception e) {
                    AppLog.w("lyrics: " + e.getMessage());
                    w = null;
                    result = FAILED;
                }
                final Words found = w;
                final int done = result;
                post(() -> {
                    if (mine != serial) return;
                    words = found;
                    state = done;
                    layoutLines();
                    invalidate();
                });
            });
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            line.setTypeface(Ui.displaySemibold);
            line.setTextSize(64 * k);
            line.setColor(0xFFFFFFFF);
            song = Ui.text(Ui.semibold, 34 * k, 0xF2FFFFFF);
            artist = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            note = Ui.text(Ui.displaySemibold, 52 * k, 0x8CFFFFFF);
            layoutLines();
        }

        /** Each line wrapped once (here, not while drawing), and where it sits in the column. */
        private void layoutLines() {
            if (getWidth() == 0 || words == null || words.lines.length == 0) {
                layouts = new StaticLayout[0];
                tops = new float[0];
                return;
            }
            int width = Math.round(1500 * k);
            layouts = new StaticLayout[words.lines.length];
            tops = new float[words.lines.length];
            float y = 0, gap = 34 * k;
            for (int i = 0; i < layouts.length; i++) {
                String s = words.lines[i];
                layouts[i] = StaticLayout.Builder.obtain(s, 0, s.length(), line, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
                tops[i] = y;
                y += layouts[i].getHeight() + gap;
            }
            current = -1;
        }

        /** The line being sung now (-1 before the first). */
        private int lineAt(long ms) {
            int at = -1;
            for (int i = 0; i < words.times.length && words.times[i] <= ms; i++) at = i;
            return at;
        }

        @Override public void doFrame(long nanos) {
            if (!running) return;
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (nanos - lastNanos) / 1e9f);
            lastNanos = nanos;
            boolean moved = false;
            if (state == READY && layouts.length > 0 && track != null) {
                long ms = track.positionNow();
                float target;
                if (words.times != null) {
                    int at = ms < 0 ? -1 : lineAt(ms + 250);   // a moment early, as the singer breathes in
                    if (at != current) {
                        current = at;
                        moved = true;
                    }
                    target = at < 0 ? 0 : tops[at];
                } else {   // plain lyrics: they scroll with the song's progress
                    float total = tops[tops.length - 1] + layouts[layouts.length - 1].getHeight();
                    float progress = track.duration > 0 && ms >= 0 ? Ui.clamp((float) ms / track.duration) : 0;
                    target = Math.max(0, total - getHeight() * 0.4f) * progress;
                }
                scroll.target = target;
                moved |= scroll.step(dt);
            }
            if (moved) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onDraw(Canvas c) {
            if (song == null) return;
            float x = 210 * k;
            NowPlaying.Track t = track;
            if (t != null) {   // the song, small, at the top left
                Symbols.draw(getContext(), c, "lyrics", x - 46 * k, 96 * k - Ui.halfCap(song), 30 * k, 0xFFFF375F);
                c.drawText(t.title, x, 96 * k, song);
                if (t.artist != null) c.drawText(t.artist, x, 136 * k, artist);
            }
            String message = state == NOTHING ? Ui.tr("Nada tocando agora.", "Nothing playing right now.")
                    : state == LOADING ? Ui.tr("Buscando a letra…", "Looking for the lyrics…")
                    : state == NONE ? Ui.tr("Sem letra para esta música.", "No lyrics for this song.")
                    : state == FAILED ? Ui.tr("Não foi possível buscar a letra.", "Couldn't get the lyrics.")
                    : words != null && words.instrumental ? Ui.tr("Instrumental", "Instrumental") + "  ♪" : null;
            if (message != null) {
                c.drawText(message, x, getHeight() * 0.45f, note);
                return;
            }
            float base = getHeight() * 0.36f - scroll.value;
            boolean timed = words.times != null;
            c.save();
            c.clipRect(0, 170 * k, getWidth(), getHeight());   // the lines go under the song's name, not over it
            for (int i = 0; i < layouts.length; i++) {
                float top = base + tops[i];
                if (top > getHeight() || top + layouts[i].getHeight() < 170 * k) continue;
                int alpha = !timed ? 0xD9 : i == current ? 0xFF : i < current ? 0x40 : 0x59;
                line.setAlpha(alpha);
                c.save();
                c.translate(x, top);
                layouts[i].draw(c);
                c.restore();
            }
            c.restore();
            line.setAlpha(0xFF);
        }

        @Override protected void onDetachedFromWindow() {
            io.shutdownNow();
            super.onDetachedFromWindow();
        }
    }
}
