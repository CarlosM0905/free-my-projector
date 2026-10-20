package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.List;

/**
 * Free My Projector: the "Landscapes" screensaver, like a Samsung The Frame in art mode next to a macOS-style clock.
 * A landscape of the day framed with a mat on the left; on the right, the time, the date, the weather, what's playing
 * and where the photo was taken. A new photo every minute with a slow crossfade; nothing else moves, so it costs the
 * GPU almost nothing.
 */
final class FrameView extends View implements Screensaver.Dream {
    private static final long EVERY = 60_000, FADE = 1600;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HomeData data;
    private List<Landscapes.Photo> photos;
    private int index = -1;
    private Bitmap current, previous;
    private Landscapes.Photo shown;
    private long fadeAt;
    private int wall = 0xFF121214, wallFrom = 0xFF121214;
    private boolean running;
    private float k = 1;

    private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint time, date, big, small, title, place, playing;
    private final RectF pic = new RectF(), mat = new RectF(), tmp = new RectF();
    private final Rect src = new Rect();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            if (SystemClock.uptimeMillis() - fadeAt >= EVERY) next();
            invalidate();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);   // redrawn when the minute changes
        }
    };

    private final boolean framed;
    private final Paint scrim = new Paint();

    FrameView(Context c, boolean framed) {
        super(c);
        this.framed = framed;
        data = HomeData.get(c);
        setBackgroundColor(0xFF000000);
    }

    @Override public void start() {
        if (running) return;
        running = true;
        photos = Landscapes.ready(getContext());
        Landscapes.refresh(getContext(), () -> post(() -> {
            photos = Landscapes.ready(getContext());
            if (current == null) next();
        }));
        next();
        handler.post(tick);
    }

    @Override public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    /** Decodes the next photo off the main thread and starts the crossfade. */
    private void next() {
        if (photos == null || photos.isEmpty()) return;
        index = (index + 1) % photos.size();
        final Landscapes.Photo p = photos.get(index);
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;   // 1920×1080 drawn about 1300 wide: full size keeps it sharp
            Bitmap b = BitmapFactory.decodeFile(p.file.getPath(), o);
            if (b == null) return;
            int avg = average(b);
            post(() -> {
                if (!running) return;
                previous = current;
                current = b;
                shown = p;
                wallFrom = wall;
                wall = avg;
                fadeAt = SystemClock.uptimeMillis();
                animateFade();
            });
        }, "frame-photo").start();
    }

    private void animateFade() {
        invalidate();
        if (SystemClock.uptimeMillis() - fadeAt < FADE) postOnAnimation(this::animateFade);
        else previous = null;
    }

    /** The wall behind the frame: the photo's average colour, very dark (like the room light falling on it). */
    private static int average(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
        long r = 0, g = 0, bl = 0;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int p = s.getPixel(x, y);
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            bl += p & 255;
        }
        s.recycle();
        float f = 0.16f;
        return 0xFF000000 | (Math.round(r / 64f * f + 6) << 16) | (Math.round(g / 64f * f + 6) << 8) | Math.round(bl / 64f * f + 6);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        time = Ui.numbers(Ui.thin, 150 * k, 0xF2FFFFFF);
        date = Ui.text(Ui.medium, 32 * k, 0x99FFFFFF);
        big = Ui.numbers(Ui.light, 56 * k, 0xF2FFFFFF);
        small = Ui.text(Ui.medium, 26 * k, 0x99FFFFFF);
        title = Ui.text(Ui.semibold, 30 * k, 0xE6FFFFFF);
        place = new TextPaint(Ui.text(Ui.regular, 24 * k, 0x8CFFFFFF));
        playing = Ui.text(Ui.medium, 26 * k, 0xD9FFFFFF);
        // The frame: a 16:9 photo with a 34 px mat, on the left two thirds; full screen without it.
        if (framed) pic.set(150 * k, 168 * k, 1270 * k, 798 * k);
        else pic.set(0, 0, w, h);
        scrim.setShader(new android.graphics.LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0x99000000,
                android.graphics.Shader.TileMode.CLAMP));
        mat.set(pic.left - 34 * k, pic.top - 34 * k, pic.right + 34 * k, pic.bottom + 34 * k);
    }

    @Override protected void onDraw(Canvas c) {
        if (time == null) return;
        float e = Math.min(1, (SystemClock.uptimeMillis() - fadeAt) / (float) FADE);
        e = e * e * (3 - 2 * e);
        if (!framed) {   // edge to edge, like the Apple TV and Mac aerials, with the information in a glass box
            if (previous != null && e < 1) drawPhoto(c, previous, 255);
            if (current != null) drawPhoto(c, current, Math.round(255 * (previous != null ? e : 1)));
            c.drawRect(0, 0, getWidth(), getHeight(), scrim);
            drawBox(c);
            return;
        }
        c.drawColor(mix(wallFrom, wall, e));
        // Frame: soft shadow on the wall, a thin dark moulding, the off-white mat and the photo.
        fill.setColor(0x59000000);
        tmp.set(mat.left - 8 * k, mat.top - 2 * k, mat.right + 8 * k, mat.bottom + 22 * k);
        c.drawRoundRect(tmp, 6 * k, 6 * k, fill);
        fill.setColor(0xFF2A2724);
        tmp.set(mat.left - 10 * k, mat.top - 10 * k, mat.right + 10 * k, mat.bottom + 10 * k);
        c.drawRect(tmp, fill);
        fill.setColor(0xFFECE8E1);
        c.drawRect(mat, fill);
        fill.setColor(0x26000000);   // the mat's bevel around the photo
        tmp.set(pic.left - 3 * k, pic.top - 3 * k, pic.right + 3 * k, pic.bottom + 3 * k);
        c.drawRect(tmp, fill);
        if (previous != null && e < 1) drawPhoto(c, previous, 255);
        if (current != null) drawPhoto(c, current, Math.round(255 * (previous != null ? e : 1)));
        else {
            fill.setColor(0xFF2C2C2E);
            c.drawRect(pic, fill);
        }
        drawInfo(c);
    }

    private void drawPhoto(Canvas c, Bitmap b, int alpha) {
        float aspect = pic.width() / pic.height();
        int sw = Math.min(b.getWidth(), Math.round(b.getHeight() * aspect)), sh = Math.round(sw / aspect);
        src.set((b.getWidth() - sw) / 2, (b.getHeight() - sh) / 2, (b.getWidth() + sw) / 2, (b.getHeight() + sh) / 2);
        photo.setAlpha(alpha);
        c.drawBitmap(b, src, pic, photo);
    }

    /**
     * Full screen: a dark glass box at the bottom left with the time, date, weather and what's playing; where the
     * photo was taken at the bottom right.
     */
    private void drawBox(Canvas c) {
        long now = System.currentTimeMillis();
        HomeData.Weather w = data.weather;
        NowPlaying.Track tr = NowPlaying.current(getContext());
        float x = 96 * k, pad = 40 * k, boxW = 620 * k;
        float boxH = (Double.isNaN(w.temp) ? 236 : 372) * k + (tr != null ? 56 * k : 0);
        float bottom = getHeight() - 84 * k;
        tmp.set(x, bottom - boxH, x + boxW, bottom);
        fill.setColor(0x8C0E0E10);
        c.drawRoundRect(tmp, 30 * k, 30 * k, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(1.5f * k);
        fill.setColor(0x26FFFFFF);
        c.drawRoundRect(tmp, 30 * k, 30 * k, fill);
        fill.setStyle(Paint.Style.FILL);
        float ix = x + pad, y = tmp.top + 136 * k;
        String hhmm = clockText(now);
        c.drawText(hhmm, ix - Ui.leftBearing(time, hhmm), y, time);
        String d = Landscapes.date(now);
        c.drawText(Character.toUpperCase(d.charAt(0)) + d.substring(1), ix, y + 54 * k, date);
        float wy = y + 150 * k;
        if (!Double.isNaN(w.temp)) {
            Symbols.draw(getContext(), c, Symbols.weather(w.code, w.isDay), ix + 26 * k, wy - 18 * k, 46 * k, 0xF2FFFFFF);
            String t = Math.round(w.temp) + "°";
            c.drawText(t, ix + 66 * k, wy, big);
            String cond = Ui.es ? Es.condition(w.code, w.isDay) : HomeData.condition(w.code, w.isDay);
            c.drawText(TextUtils.ellipsize(cond, new TextPaint(small), boxW - 2 * pad - 66 * k - big.measureText(t) - 16 * k,
                    TextUtils.TruncateAt.END).toString(), ix + 66 * k + big.measureText(t) + 16 * k, wy - 6 * k, small);
            if (!Double.isNaN(w.max)) {
                c.drawText(Ui.tr("Máx. ", "H ") + Math.round(w.max) + "°   " + Ui.tr("Mín. ", "L ") + Math.round(w.min) + "°", ix, wy + 46 * k, small);
            }
            wy += 102 * k;
        }
        if (tr != null) {
            String line = tr.artist == null || tr.artist.isEmpty() ? tr.title : tr.title + "  ·  " + tr.artist;
            line = TextUtils.ellipsize(line, new TextPaint(playing), boxW - 2 * pad - 36 * k, TextUtils.TruncateAt.END).toString();
            Symbols.draw(getContext(), c, "play", ix + 12 * k, wy - 9 * k, 24 * k, 0xFF0A84FF);
            c.drawText(line, ix + 36 * k, wy, playing);
        }
        // The place, bottom right, small (like the location on the aerials).
        if (shown != null) {
            float r = getWidth() - 96 * k;
            String where = shown.place == null ? "" : shown.place;
            where = TextUtils.ellipsize(where, (TextPaint) place, 760 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(where, r - place.measureText(where), bottom, place);
            if (shown.title != null && !shown.title.isEmpty()) c.drawText(shown.title, r - title.measureText(shown.title), bottom - 40 * k, title);
        }
    }

    private String clockText(long now) {
        return new java.text.SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm",
                java.util.Locale.ROOT).format(new java.util.Date(now));
    }

    /** Right column: time, date, weather, now playing; at the bottom, where the photo was taken. */
    private void drawInfo(Canvas c) {
        float x = 1400 * k, right = 1800 * k, y = pic.top + 110 * k;
        long now = System.currentTimeMillis();
        String hhmm = new java.text.SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm",
                java.util.Locale.ROOT).format(new java.util.Date(now));
        c.drawText(hhmm, x - Ui.leftBearing(time, hhmm), y, time);
        String d = Landscapes.date(now);
        c.drawText(Character.toUpperCase(d.charAt(0)) + d.substring(1), x, y + 56 * k, date);
        // Weather now.
        HomeData.Weather w = data.weather;
        float wy = y + 170 * k;
        if (!Double.isNaN(w.temp)) {
            String sym = Symbols.weather(w.code, w.isDay);
            Symbols.draw(getContext(), c, sym, x + 30 * k, wy - 20 * k, 52 * k, 0xF2FFFFFF);
            String t = Math.round(w.temp) + "°";
            c.drawText(t, x + 76 * k, wy, big);
            String cond = Ui.es ? Es.condition(w.code, w.isDay) : HomeData.condition(w.code, w.isDay);
            c.drawText(cond, x + 76 * k + big.measureText(t) + 18 * k, wy - 6 * k, small);
            if (!Double.isNaN(w.max)) {
                c.drawText(Ui.tr("Máx. ", "H ") + Math.round(w.max) + "°   " + Ui.tr("Mín. ", "L ") + Math.round(w.min) + "°",
                        x, wy + 44 * k, small);
            }
        }
        // Now Playing, when something plays.
        NowPlaying.Track tr = NowPlaying.current(getContext());
        if (tr != null) {
            String line = tr.artist == null || tr.artist.isEmpty() ? tr.title : tr.title + "  ·  " + tr.artist;
            line = TextUtils.ellipsize(line, new TextPaint(playing), right - x - 40 * k, TextUtils.TruncateAt.END).toString();
            float py = wy + 130 * k;
            Symbols.draw(getContext(), c, "play", x + 12 * k, py - 9 * k, 24 * k, 0xFF0A84FF);
            c.drawText(line, x + 36 * k, py, playing);
        }
        // The photo: its title and place, lined up with the bottom of the frame.
        if (shown != null) {
            float by = mat.bottom;
            String placeText = shown.place == null ? "" : shown.place;
            android.text.StaticLayout lay = new android.text.StaticLayout(placeText, (TextPaint) place, Math.round(right - x),
                    android.text.Layout.Alignment.ALIGN_NORMAL, 1.15f, 0, false);
            int lines = Math.min(3, lay.getLineCount());
            float blockH = lay.getLineBottom(lines - 1), top = by - blockH;
            if (shown.title != null && !shown.title.isEmpty()) c.drawText(shown.title, x, top - 16 * k, title);
            c.save();
            c.translate(x, top);
            c.clipRect(0, 0, right - x, blockH);
            lay.draw(c);
            c.restore();
        }
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
