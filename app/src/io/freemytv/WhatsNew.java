package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Free My Projector: "What's new", like the sheet tvOS and iOS apps show after an update. The first time the home
 * screen opens on a new release, this lists that release's notes (from its GitHub release) over the sky of the
 * settings. OK or Back closes it; ↑ ↓ scroll when the notes don't fit.
 */
public final class WhatsNew extends Activity {
    private static final String REPO = "CarlosM0905/free-my-projector";

    /** Called when the home screen shows: opens the sheet once per new release (never on the first install or a dev build). */
    static void maybeShow(Activity home) {
        try {
            android.content.pm.PackageInfo p = home.getPackageManager().getPackageInfo(home.getPackageName(), 0);
            SharedPreferences prefs = home.getSharedPreferences("updates", Context.MODE_PRIVATE);
            int seen = prefs.getInt("seenVersion", 0);
            if (seen == p.versionCode) return;
            prefs.edit().putInt("seenVersion", p.versionCode).apply();
            // Only releases: a tag like "v1.2.0" (a local build is named "v1.2.0-3-gabc123").
            if (seen == 0 || p.versionCode < seen || p.versionName == null || !p.versionName.matches("v?\\d+(\\.\\d+)+")) return;
            home.startActivity(new Intent(home, WhatsNew.class).putExtra("tag", p.versionName));
        } catch (Exception e) {
            AppLog.w("what's new: " + e.getMessage());
        }
    }

    /** A section title ("New") or an item under it. */
    private static final class Line {
        final boolean header;
        final String text;

        Line(boolean header, String text) {
            this.header = header;
            this.text = text;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Sheet sheet;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        String tag = getIntent().getStringExtra("tag");
        if (tag == null) {
            finish();
            return;
        }
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        sheet = new Sheet(this, version);
        setContentView(sheet);
        String given = getIntent().getStringExtra("notes");   // from ADB, to see the sheet without a release
        new Thread(() -> {
            List<Line> lines = null;
            try {
                String body = given != null ? given : new JSONObject(Updates.get("https://api.github.com/repos/" + REPO + "/releases/tags/" + tag)).optString("body");
                lines = parse(body);
            } catch (Exception e) {
                AppLog.w("what's new " + tag + ": " + e.getMessage());
            }
            final List<Line> got = lines;
            main.post(() -> {
                if (got == null || got.isEmpty()) finish();   // no notes: nothing to show
                else sheet.show(got);
            });
        }, "whats-new").start();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (sheet != null) sheet.running = false;
        super.onDestroy();
    }

    /**
     * The release's Markdown, reduced to what a TV can show: "## Title" headers and "- item" lines, without bold marks,
     * links or the "feat:"/"fix:" prefixes of the commits they come from.
     */
    static List<Line> parse(String body) {
        List<Line> out = new ArrayList<>();
        for (String raw : body.split("\\r?\\n")) {
            String s = raw.trim();
            if (s.startsWith("#")) {
                s = s.replaceFirst("^#+\\s*", "");
                if (!s.isEmpty() && !s.toLowerCase(java.util.Locale.ROOT).contains("changelog")) out.add(new Line(true, s));
            } else if (s.startsWith("- ") || s.startsWith("* ")) {
                s = s.substring(2)
                        .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")   // [text](url) → text
                        .replaceAll("\\*\\*|__|`", "")
                        .replaceFirst("^(feat|fix|style|perf)(\\([^)]*\\))?:\\s*", "")
                        .replaceAll("\\s+by @\\S+ in \\S+$", "");
                if (s.isEmpty()) continue;
                out.add(new Line(false, Character.toUpperCase(s.charAt(0)) + s.substring(1)));
            }
        }
        // A header with nothing under it says nothing.
        for (int i = out.size() - 1; i >= 0; i--) {
            if (out.get(i).header && (i == out.size() - 1 || out.get(i + 1).header)) out.remove(i);
        }
        return out;
    }

    private final class Sheet extends View {
        private final Scene scene = new Scene();
        private final String version;
        private final long startedAt = SystemClock.uptimeMillis();
        private final List<StaticLayout> layouts = new ArrayList<>();
        private final List<Line> lines = new ArrayList<>();
        private final RectF tmp = new RectF();
        private final Spring scroll = new Spring(0.22f, 0.9f, 0);
        private float k = 1, contentHeight;
        private int w, h;
        private boolean running = true, loaded;
        private long lastNanos, lastSky;
        private Paint title, subtitle, header, button, buttonFill, dot, spinner;
        private TextPaint item;

        Sheet(Context c, String version) {
            super(c);
            this.version = version;
            HomeData.Weather wx = HomeData.get(c).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
        }

        void show(List<Line> got) {
            lines.clear();
            lines.addAll(got);
            loaded = true;
            layout();
            invalidate();
        }

        /** Each item wrapped to the column's width, once. */
        private void layout() {
            layouts.clear();
            contentHeight = 0;
            if (w == 0 || !loaded) return;
            int width = (int) (820 * k);
            for (Line l : lines) {
                StaticLayout s = new StaticLayout(l.header ? l.text.toUpperCase(Ui.PT) : l.text, l.header ? new TextPaint(header) : item, width, Layout.Alignment.ALIGN_NORMAL, 1.15f, 0, false);
                layouts.add(s);
                contentHeight += s.getHeight() + (l.header ? 22 : 26) * k;
            }
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            title = Ui.text(Ui.displaySemibold, 64 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 28 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            header = Ui.text(Ui.semibold, 22 * k, 0x80FFFFFF);
            header.setLetterSpacing(0.06f);
            item = new TextPaint(Ui.text(Ui.regular, 30 * k, 0xEBFFFFFF));
            button = Ui.text(Ui.semibold, 28 * k, 0xFF1C1C1E);
            button.setTextAlign(Paint.Align.CENTER);
            buttonFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            buttonFill.setColor(0xF2FFFFFF);
            dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0xFF64D2FF);
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(4 * k);
            spinner.setColor(0xCCFFFFFF);
            layout();
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            boolean moving = scroll.step(dt);
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            scene.drawBackground(c);

            // On the left, like the settings: the symbol, the title and the version.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, 0xEBFFFFFF);
            c.drawText(Ui.tr("Novidades", "What's New"), cx, 620 * k, title);
            c.drawText(Ui.tr("Versão ", "Version ") + version, cx, 670 * k, subtitle);

            float left = 1000 * k, top = 160 * k, bottom = h - 220 * k;
            if (!loaded) {
                Ui.spinner(c, left + 410 * k, h / 2f, 22 * k, seconds, spinner);
            } else {
                c.save();
                c.clipRect(0, top - 10 * k, w, bottom);
                float y = top - scroll.value;
                for (int i = 0; i < layouts.size(); i++) {
                    StaticLayout s = layouts.get(i);
                    boolean isHeader = lines.get(i).header;
                    if (i > 0 && isHeader) y += 24 * k;   // a little air before each section
                    if (!isHeader) c.drawCircle(left - 30 * k, y + s.getLineBaseline(0) - 10 * k, 6 * k, dot);
                    c.save();
                    c.translate(left, y);
                    s.draw(c);
                    c.restore();
                    y += s.getHeight() + (isHeader ? 22 : 26) * k;
                }
                c.restore();
            }
            // The one button, always focused: tvOS's "Continue".
            float bw = 360 * k, bh = 84 * k, bx = left + 410 * k, by = h - 130 * k;
            tmp.set(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2);
            c.drawRoundRect(tmp, bh / 2, bh / 2, buttonFill);
            c.drawText(Ui.tr("Continuar", "Continue"), bx, by + Ui.halfCap(button), button);

            if (running) postInvalidateDelayed(moving ? 0 : 100);   // the sky drifts slowly behind
        }

        private float maxScroll() {
            return Math.max(0, contentHeight - (h - 380 * k) + 40 * k);
        }

        @Override public boolean onKeyDown(int code, KeyEvent e) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_UP:
                    float step = 240 * k * (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    scroll.target = Math.max(0, Math.min(maxScroll(), scroll.target + step));
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
                default:
                    return super.onKeyDown(code, e);
            }
        }
    }
}
