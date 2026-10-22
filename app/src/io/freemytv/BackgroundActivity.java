package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Free My Projector: pick your own backgrounds from a search (Wikimedia Commons), like choosing an aerial on the Apple
 * TV. Suggestions at the top (or type your own with the screen's keyboard), photos below: OK keeps one in your
 * backgrounds or lets it go. Yours replace the landscapes of the day in the light design and the screensaver.
 */
public final class BackgroundActivity extends Activity {
    private static final String[] SUGGESTIONS_ES = {"Montañas", "Playa", "Aurora boreal", "Bosque", "Ciudad de noche", "Espacio", "Lago", "Desierto"};
    private static final String[] SUGGESTIONS_EN = {"Mountains", "Beach", "Aurora", "Forest", "City at night", "Space", "Lake", "Desert"};
    /** What each suggestion searches for (Commons' descriptions are mostly in English). */
    private static final String[] QUERIES = {"mountains", "beach", "aurora borealis", "forest", "city night skyline", "galaxy nebula", "lake", "desert dunes"};
    private static final String[] KEY_ROWS = {"abcdefghijklmnñopqrst", "uvwxyz0123456789 ⌫✓"};
    private static final int CHIPS = 0, KEYS = 1, GRID = 2;

    private int zone = CHIPS, chip = 0, keyRow, keyCol, cell;
    private boolean typing;
    private String typed = "", status = "";
    private List<Backgrounds.Photo> photos = new ArrayList<>();
    private Bitmap[] thumbs = new Bitmap[0];
    private boolean[] kept = new boolean[0];
    private Screen screen;
    private Click click;
    private int searchId;

    static void open(Context c) {
        c.startActivity(new Intent(c, BackgroundActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private String[] suggestions() {
        return Ui.en ? SUGGESTIONS_EN : Ui.es ? SUGGESTIONS_ES : Ui.trAll(SUGGESTIONS_EN);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        click = new Click(this);
        screen = new Screen(this);
        setContentView(screen);
        search(QUERIES[0]);   // the first suggestion's photos, so the screen never opens empty
        chip = 1;
    }

    @Override
    protected void onDestroy() {
        click.release();
        super.onDestroy();
    }

    private void search(String query) {
        final int id = ++searchId;
        status = Ui.tr("Procurando…", "Searching…");
        photos = new ArrayList<>();
        thumbs = new Bitmap[0];
        screen.invalidate();
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                List<Backgrounds.Photo> found = Backgrounds.search(query);
                boolean[] k = new boolean[found.size()];
                for (int i = 0; i < k.length; i++) k[i] = Backgrounds.has(app, found.get(i));
                runOnUiThread(() -> {
                    if (id != searchId) return;
                    photos = found;
                    kept = k;
                    thumbs = new Bitmap[found.size()];
                    status = found.isEmpty() ? Ui.tr("Nada encontrado", "Nothing found") : "";
                    cell = Math.min(cell, Math.max(0, found.size() - 1));
                    screen.invalidate();
                });
                for (int i = 0; i < found.size(); i++) {
                    Bitmap b = Backgrounds.thumbnail(found.get(i));
                    final int at = i;
                    runOnUiThread(() -> {
                        if (id != searchId || at >= thumbs.length) return;
                        thumbs[at] = b;
                        screen.invalidate();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (id != searchId) return;
                    status = Ui.tr("Não deu para buscar agora", "Couldn't search now");
                    screen.invalidate();
                });
            }
        }, "backgrounds").start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) {
                if (typing) {
                    typing = false;
                    zone = CHIPS;
                } else {
                    finish();
                }
                screen.invalidate();
            }
            return true;
        }
        if (!down) return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || super.dispatchKeyEvent(e);
        int chips = suggestions().length + 1;   // "Search" first
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int d = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (zone == CHIPS) chip = Math.max(0, Math.min(chips - 1, chip + d));
                else if (zone == KEYS) keyCol = Math.max(0, Math.min(KEY_ROWS[keyRow].length() - 1, keyCol + d));
                else cell = Math.max(0, Math.min(photos.size() - 1, cell + d));
                break;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (zone == CHIPS) zone = typing ? KEYS : GRID;
                else if (zone == KEYS && keyRow == 0) keyRow = 1;
                else if (zone == KEYS) zone = GRID;
                else if (cell + 4 < photos.size()) cell += 4;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (zone == GRID && cell >= 4) cell -= 4;
                else if (zone == GRID) zone = typing ? KEYS : CHIPS;
                else if (zone == KEYS && keyRow == 1) keyRow = 0;
                else zone = CHIPS;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() > 0) break;
                click.open();
                activate();
                break;
            default:
                return super.dispatchKeyEvent(e);
        }
        if (zone == GRID && photos.isEmpty()) zone = typing ? KEYS : CHIPS;
        if (zone == GRID && !status.equals(Ui.tr("Salvando…", "Saving…"))) status = "";
        click.move();
        screen.invalidate();
        return true;
    }

    private void activate() {
        if (zone == CHIPS) {
            if (chip == 0) {   // "Search": the keyboard opens under the chips
                typing = true;
                zone = KEYS;
                keyRow = 0;
                keyCol = 0;
            } else {
                typing = false;
                search(QUERIES[chip - 1]);
            }
        } else if (zone == KEYS) {
            char k = KEY_ROWS[keyRow].charAt(Math.min(keyCol, KEY_ROWS[keyRow].length() - 1));
            if (k == '⌫') {
                if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1);
            } else if (k == '✓') {
                if (!typed.trim().isEmpty()) {
                    search(typed);
                    zone = GRID;
                    cell = 0;
                }
            } else if (typed.length() < 40) {
                typed += k;
            }
        } else if (cell < photos.size()) {   // keep it in your backgrounds, or let it go
            final int at = cell;
            final Backgrounds.Photo p = photos.get(at);
            final Context app = getApplicationContext();
            status = Ui.tr("Salvando…", "Saving…");
            new Thread(() -> {
                try {
                    Backgrounds.toggle(app, p);
                    boolean now = Backgrounds.has(app, p);
                    runOnUiThread(() -> {
                        if (at < kept.length) kept[at] = now;
                        status = now ? Ui.tr("Nos seus fundos", "In your backgrounds") : Ui.tr("Removido", "Removed");
                        screen.invalidate();
                    });
                } catch (Exception ex) {
                    runOnUiThread(() -> {
                        status = Ui.tr("Não deu para salvar", "Couldn't save it");
                        screen.invalidate();
                    });
                }
            }, "background-save").start();
        }
    }

    private final class Screen extends View {
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), chipText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG), key = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private final Path clip = new Path();

        Screen(Context c) {
            super(c);
            title.setTypeface(Ui.displaySemibold);
            title.setColor(0xFFFFFFFF);
            chipText.setTypeface(Ui.semibold);
            small.setTypeface(Ui.regular);
            key.setTypeface(Ui.medium);
            key.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = getWidth() / 1920f, w = getWidth(), h = getHeight(), x = 96 * k;
            c.drawColor(0xFF0B0B0D);
            title.setTextSize(56 * k);
            c.drawText(Ui.tr("Fundos", "Backgrounds"), x, 120 * k, title);
            small.setTextSize(24 * k);
            small.setColor(0x99FFFFFF);
            c.drawText(Ui.tr("Os que você guardar substituem as paisagens do dia no fundo e na tela de descanso.",
                    "The ones you keep replace the landscapes of the day in the background and the screensaver."), x, 166 * k, small);

            // Chips: Search, then the suggestions
            chipText.setTextSize(26 * k);
            float cx = x, cy = 210 * k, ch = 60 * k;
            String[] s = suggestions();
            for (int i = 0; i <= s.length; i++) {
                String label = i == 0 ? "⌕  " + (typing && !typed.isEmpty() ? typed : Ui.tr("Buscar", "Search")) : s[i - 1];
                float cw = chipText.measureText(label) + 52 * k;
                boolean on = zone == CHIPS && chip == i;
                r.set(cx, cy, cx + cw, cy + ch);
                fill.setColor(on ? 0xFFFFFFFF : 0x24FFFFFF);
                c.drawRoundRect(r, ch / 2, ch / 2, fill);
                chipText.setColor(on ? 0xFF1C1C1E : 0xE6FFFFFF);
                c.drawText(label, cx + 26 * k, r.centerY() + Ui.halfCap(chipText), chipText);
                cx += cw + 16 * k;
            }

            float gridTop = 310 * k;
            if (typing) {   // two rows of keys under the chips
                key.setTextSize(28 * k);
                float kw = (w - 2 * x) / KEY_ROWS[0].length(), kh = 56 * k;
                for (int row = 0; row < 2; row++) {
                    for (int i = 0; i < KEY_ROWS[row].length(); i++) {
                        float kx = x + i * kw, ky = 300 * k + row * (kh + 8 * k);
                        boolean on = zone == KEYS && keyRow == row && keyCol == i;
                        if (on) {
                            r.set(kx + 2 * k, ky, kx + kw - 2 * k, ky + kh);
                            fill.setColor(0xFFFFFFFF);
                            c.drawRoundRect(r, 12 * k, 12 * k, fill);
                        }
                        char ch2 = KEY_ROWS[row].charAt(i);
                        key.setColor(on ? 0xFF1C1C1E : ch2 == '✓' ? 0xFF0A84FF : 0xE6FFFFFF);
                        c.drawText(ch2 == ' ' ? "␣" : String.valueOf(ch2), kx + kw / 2, ky + kh / 2 + Ui.halfCap(key), key);
                    }
                }
                gridTop = 450 * k;
            }

            // Over the grid: what's happening, or the focused photo's name
            String line = !status.isEmpty() ? status : zone == GRID && cell < photos.size() ? photos.get(cell).title : "";
            if (!line.isEmpty()) {
                small.setColor(0xCCFFFFFF);
                c.drawText(TextUtils.ellipsize(line, small, w - 2 * x, TextUtils.TruncateAt.END).toString(), x, gridTop + 20 * k, small);
            }
            // The photos: 4 per row, 16:9
            float gap = 24 * k, tw = (w - 2 * x - 3 * gap) / 4, th = tw * 9 / 16, gy = gridTop + 48 * k;
            for (int i = 0; i < photos.size(); i++) {
                int col = i % 4, row = i / 4;
                float px = x + col * (tw + gap), py = gy + row * (th + gap) - Math.max(0, (cell / 4 - 1)) * (th + gap) * (zone == GRID ? 1 : 0);
                if (py > h || py + th < gridTop) continue;
                boolean on = zone == GRID && cell == i;
                float grow = on ? 10 * k : 0;
                r.set(px - grow, py - grow, px + tw + grow, py + th + grow);
                clip.reset();
                clip.addRoundRect(r, 16 * k, 16 * k, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                if (i < thumbs.length && thumbs[i] != null) {
                    Bitmap b = thumbs[i];
                    float sc = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
                    float bw = b.getWidth() * sc, bh = b.getHeight() * sc;
                    c.drawBitmap(b, null, new RectF(r.centerX() - bw / 2, r.centerY() - bh / 2, r.centerX() + bw / 2, r.centerY() + bh / 2), image);
                } else {
                    fill.setColor(0x1FFFFFFF);
                    c.drawRect(r, fill);
                }
                c.restore();
                if (on) {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(4 * k);
                    fill.setColor(0xFFFFFFFF);
                    c.drawRoundRect(r, 16 * k, 16 * k, fill);
                    fill.setStyle(Paint.Style.FILL);
                }
                if (i < kept.length && kept[i]) {   // a check: it's in your backgrounds
                    float bx = r.right - 30 * k, by = r.top + 30 * k;
                    fill.setColor(0xFF0A84FF);
                    c.drawCircle(bx, by, 18 * k, fill);
                    Symbols.draw(getContext(), c, "check", bx, by, 20 * k, 0xFFFFFFFF);
                }
            }

        }
    }
}
