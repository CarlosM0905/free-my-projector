package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * Free My Projector: a movie or show's page, like the Apple TV app's. The photo fills the screen and drifts slowly;
 * on the left, the title, year · genres · length · rating, the synopsis and the cast; two buttons: watch it (opens the
 * title itself in its app) and its trailer (SmartTube). Opened from the cards, Up Next, Search and the light design.
 */
public final class TitleActivity extends Activity {
    static final String EXTRA = "pick", SHUFFLE = "shuffle";

    private Recs recs;
    private Recs.Pick pick;
    private Recs.Details details;
    private Photo photo;
    private Page page;
    private int focus;   // index in buttons()
    private boolean shuffle;
    private String keepFocus;   // after "Another", the focus stays on it once the new title's buttons are known
    private final java.util.Random random = new java.util.Random();
    private Click click;

    /** "What should I watch?": the page of a title picked at random from your apps, with an "Another" button. */
    static void shuffle(Context c) {
        c.startActivity(new Intent(c, TitleActivity.class).putExtra(SHUFFLE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Opens the page for a pick (from the home screen's process). */
    static void open(Context c, Recs.Pick p) {
        c.startActivity(new Intent(c, TitleActivity.class).putExtra(EXTRA, Recs.toJson(p)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        recs = Recs.get(this);
        shuffle = getIntent().getBooleanExtra(SHUFFLE, false);
        pick = shuffle ? another() : Recs.fromJson(getIntent().getStringExtra(EXTRA));
        if (pick == null) {
            finish();
            return;
        }
        click = new Click(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0D);
        photo = new Photo(this);
        page = new Page(this);
        photo.setLayerType(View.LAYER_TYPE_HARDWARE, null);   // the drift only moves the layer: nothing is redrawn
        page.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(photo);
        root.addView(page);
        setContentView(root);
        load();
    }

    @Override
    protected void onDestroy() {
        if (click != null) click.release();
        super.onDestroy();
    }

    /** The photo (already on disk from the cards) and the details, in the background. */
    private void load() {
        final java.io.File f = recs.photo(pick);
        new Thread(() -> {
            Bitmap b = f != null && f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
            if (b != null) runOnUiThread(() -> photo.show(b));
            try {
                Recs.Details d = recs.details(pick);
                runOnUiThread(() -> {
                    details = d;
                    if (keepFocus != null && buttons().contains(keepFocus)) focus = buttons().indexOf(keepFocus);
                    keepFocus = null;
                    page.invalidate();
                });
            } catch (Exception e) {
                AppLog.w("details: " + e.getMessage());
            }
        }, "title").start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    int to = Math.max(0, Math.min(buttons().size() - 1, focus + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                    if (to != focus) {
                        focus = to;
                        click.move();
                        page.invalidate();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down) activate();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    click.back();
                    finish();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    /** A random title from the pool, never the one showing. */
    private Recs.Pick another() {
        java.util.List<Recs.Pick> pool = recs.pool();
        if (pool.isEmpty()) return null;
        if (pick != null && pool.size() > 1) {
            for (int i = pool.size() - 1; i >= 0; i--) if (pool.get(i).id == pick.id) pool.remove(i);
        }
        return pool.get(random.nextInt(pool.size()));
    }

    /** The buttons, left to right: watch, trailer (when there is one), another (in shuffle mode). */
    private java.util.List<String> buttons() {
        java.util.List<String> b = new java.util.ArrayList<>();
        b.add("watch");
        if (hasTrailer()) b.add("trailer");
        if (shuffle) b.add("another");
        return b;
    }

    private boolean hasTrailer() {
        return details != null && details.trailer != null;
    }

    private void activate() {
        click.open();
        String what = buttons().get(Math.min(focus, buttons().size() - 1));
        if (what.equals("another")) {
            Recs.Pick next = another();
            if (next == null) return;
            pick = next;
            details = null;
            keepFocus = "another";
            photo.animate().cancel();
            photo.setAlpha(0);
            focus = buttons().indexOf("another");
            page.invalidate();
            load();
            return;
        }
        if (what.equals("trailer")) {
            Uri u = Uri.parse("https://www.youtube.com/watch?v=" + details.trailer);
            Intent i = new Intent(Intent.ACTION_VIEW, u).setPackage("org.smarttube.stable");
            if (i.resolveActivity(getPackageManager()) == null) i.setPackage(null);
            try {
                startActivity(i);
            } catch (RuntimeException e) {
                AppLog.w("trailer: " + e.getMessage());
            }
            return;
        }
        if (pick.pkg == null) return;
        HomeApps.Item it = HomeApps.app(pick.pkg, Recs.serviceName(pick));
        it.uri = pick.link;
        recs.remember(pick);
        if (HomeApps.open(this, it, page, page.getWidth() / 4, page.getHeight() - 200, 1, 1)) finish();
    }

    /** The photo, cropped to fill the screen; it drifts in slowly and back (a Ken Burns move, on the GPU layer). */
    private static final class Photo extends View {
        private Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();

        Photo(Context c) {
            super(c);
        }

        void show(Bitmap b) {
            bitmap = b;
            setAlpha(0);
            invalidate();
            animate().alpha(1).setDuration(500).start();
            if (Spring.instant) return;   // Reduce Motion: a still photo
            setPivotX(getWidth() * 0.7f);
            setPivotY(getHeight() * 0.4f);
            drift(true);
        }

        private void drift(boolean in) {
            animate().scaleX(in ? 1.08f : 1f).scaleY(in ? 1.08f : 1f).setDuration(24_000)
                    .setInterpolator(new LinearInterpolator()).withEndAction(() -> drift(!in)).start();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bitmap == null) return;
            float w = getWidth(), h = getHeight();
            float s = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
            float bw = bitmap.getWidth() * s, bh = bitmap.getHeight() * s;
            dst.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
            c.drawBitmap(bitmap, null, dst, paint);
        }
    }

    /** The shade over the photo, the text and the buttons; redrawn only when the focus or the details change. */
    private final class Page extends View {
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), meta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint button = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Page(Context c) {
            super(c);
            title.setTypeface(Ui.displaySemibold);
            title.setColor(0xFFFFFFFF);
            meta.setTypeface(Ui.medium);
            meta.setColor(0xD9FFFFFF);
            body.setTypeface(Ui.regular);
            body.setColor(0xCCFFFFFF);
            small.setTypeface(Ui.regular);
            small.setColor(0x8CFFFFFF);
            button.setTypeface(Ui.semibold);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), k = w / 1920f;
            fill.setShader(new LinearGradient(0, 0, w * 0.72f, 0, new int[] {0xF20B0B0D, 0xD90B0B0D, 0x000B0B0D},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(new LinearGradient(0, h * 0.55f, 0, h, 0x000B0B0D, 0xE60B0B0D, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);

            float x = 120 * k, width = 880 * k, y = 250 * k;
            if (shuffle) {   // a small label over the title, in the accent color
                small.setTextSize(24 * k);
                small.setColor(0xFF0A84FF);
                small.setLetterSpacing(0.08f);
                c.drawText(Ui.tr("PARA HOJE", "FOR TONIGHT"), x, y - 30 * k, small);
                small.setLetterSpacing(0);
                small.setColor(0x8CFFFFFF);
            }
            title.setTextSize(84 * k);
            StaticLayout t = layout(pick.title == null ? "" : pick.title, title, width, 2, 0.95f);
            c.save();
            c.translate(x, y);
            t.draw(c);
            c.restore();
            y += t.getHeight() + 34 * k;

            meta.setTextSize(28 * k);
            c.drawText(TextUtils.ellipsize(metaLine(), meta, width, TextUtils.TruncateAt.END).toString(), x, y + 28 * k, meta);
            y += 70 * k;
            if (details != null && !details.overview.isEmpty()) {
                body.setTextSize(30 * k);
                StaticLayout o = layout(details.overview, body, width, 5, 1.15f);
                c.save();
                c.translate(x, y);
                o.draw(c);
                c.restore();
                y += o.getHeight() + 28 * k;
            }
            if (details != null && !details.cast.isEmpty()) {
                small.setTextSize(24 * k);
                c.drawText(TextUtils.ellipsize(Ui.tr("Elenco: ", "Cast: ") + details.cast, small, width, TextUtils.TruncateAt.END).toString(),
                        x, y + 24 * k, small);
            }

            float by = h - 200 * k, bh = 84 * k;
            String watch = "▶  " + (pick.pkg == null ? Ui.tr("Indisponível", "Not available")
                    : String.format(Ui.tr("Assistir no %s", "Watch on %s"), Recs.serviceName(pick)));
            java.util.List<String> b = buttons();
            float bx = x - 24 * k;
            for (int i = 0; i < b.size(); i++) {
                String label = b.get(i).equals("watch") ? watch : b.get(i).equals("trailer") ? Ui.tr("Trailer", "Trailer")
                        : "↻  " + Ui.tr("Outra", "Another");
                bx = drawButton(c, label, bx + 24 * k, by, bh, focus == i, k);
            }
        }

        /** A pill: white with dark text when focused, frosted otherwise (like tvOS). Returns its right edge. */
        private float drawButton(Canvas c, String text, float x, float y, float h, boolean on, float k) {
            button.setTextSize(30 * k);
            float w = button.measureText(text) + 80 * k;
            r.set(x, y, x + w, y + h);
            if (on) {   // a soft shadow from three wider, fainter pills
                for (int i = 3; i >= 1; i--) {
                    fill.setColor(0x1F000000);
                    c.drawRoundRect(r.left - i * 4 * k, r.top + (6 + i * 4) * k, r.right + i * 4 * k, r.bottom + (6 + i * 5) * k,
                            h / 2 + i * 4 * k, h / 2 + i * 4 * k, fill);
                }
            }
            fill.setColor(on ? 0xFFFFFFFF : 0x33FFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, fill);
            button.setColor(on ? 0xFF1C1C1E : 0xF2FFFFFF);
            c.drawText(text, x + 40 * k, r.centerY() + Ui.halfCap(button), button);
            return r.right;
        }

        private String metaLine() {
            if (details == null) return pick.meta == null ? "" : pick.meta;
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (!details.year.isEmpty()) parts.add(details.year);
            if (!details.genres.isEmpty()) parts.add(details.genres);
            if (!details.length.isEmpty()) parts.add(details.length);
            if (details.rating > 0) parts.add("★ " + String.format(Ui.en ? java.util.Locale.US : Ui.PT, "%.1f", details.rating));
            return String.join("  ·  ", parts);
        }

        private StaticLayout layout(String text, TextPaint p, float width, int lines, float spacing) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(1, Math.round(width)))
                    .setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(0, spacing)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build();
        }
    }
}
