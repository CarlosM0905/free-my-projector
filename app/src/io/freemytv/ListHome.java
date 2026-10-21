package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Free My Projector: the light home screen ("Ligero" in Home Settings). Type instead of glass: one column of names by
 * section (Apps, Continue, Inputs, Projector, Display, More apps), the focused one large with its state under it, its
 * logo or photo on the right, and the date and time in the corner. No blur, no layers, no cards: the cheapest screen
 * to draw on a projector's GPU, so it's always smooth.
 */
final class ListHome extends View implements Home, Choreographer.FrameCallback, Recs.Listener {
    private static final int APP = 0, PICK = 1, ITEM = 2, DREAM = 3, NEXT_BG = 4, BACKGROUND = 5;
    /** Background styles ("Fondo"): the landscape sharp, blurred, or plain black. */
    private static final String[] BG_STYLES = {"sharp", "soft", "black"};

    private static final class Row {
        int type;
        String section, label, symbol;
        HomeApps.Item item;
        Recs.Pick pick;
        Spring focus = new Spring(0.3f, 0.86f, 0);
        float y;   // top, before scrolling
    }

    private final Activity activity;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Spring scroll = new Spring(0.38f, 0.9f, 0);
    private List<Row> rows = new ArrayList<>();
    private int index;
    private boolean running, okDown, backDown;
    private long lastNanos;
    private Bitmap background;
    private String backgroundPlace;
    private float k = 1;

    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG), section = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint state = new TextPaint(Paint.ANTI_ALIAS_FLAG), corner = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidate();   // the clock and the switches' state
            handler.postDelayed(this, 15_000);
        }
    };

    ListHome(Activity a) {
        super(a);
        activity = a;
        recs = Recs.get(a);
        recs.setLight(true);
        click = new Click(a);
        setFocusable(true);
        name.setTypeface(Ui.displaySemibold);
        section.setTypeface(Ui.medium);
        section.setColor(0x80FFFFFF);
        state.setTypeface(Ui.medium);
        corner.setTypeface(Ui.medium);
        rebuild(a, false);
    }

    // MARK: - Rows

    /** The rows; the apps' logos are lifted in the background the first time (`extract`). */
    private void rebuild(Context c, boolean extract) {
        final Context app = c.getApplicationContext();
        Runnable build = () -> {
            final List<Row> list = new ArrayList<>();
            String apps = Ui.tr("Apps", "Apps");
            for (HomeApps.Item it : HomeApps.shelf(app, extract)) list.add(item(apps, it, APP));
            for (Recs.Pick p : recs.upNext()) {
                Row r = new Row();
                r.type = PICK;
                r.section = Ui.tr("Continuar", "Up Next");
                r.label = p.title;
                r.pick = p;
                list.add(r);
            }
            String inputs = Ui.tr("Entradas", "Inputs");
            for (HomeApps.Item it : HomeApps.allInputs(app)) if (it.kind != HomeApps.ALL_APPS) list.add(item(inputs, it, ITEM));
            String projector = Ui.tr("Projetor", "Projector");
            for (HomeApps.Item it : Projector.items(app)) list.add(item(projector, it, ITEM));
            String display = Ui.tr("Tela", "Display");
            list.add(action(display, DREAM, Ui.tr("Tela de descanso", "Screensaver"), "sparkles"));
            list.add(action(display, BACKGROUND, Ui.tr("Fundo", "Background"), "wallpaper"));
            list.add(action(display, NEXT_BG, Ui.tr("Próxima paisagem", "Next landscape"), "wallpaper"));
            Row settings = item(display, HomeApps.settings(), ITEM);
            settings.label = Ui.tr("Ajustes do Início", "Home Settings");
            list.add(settings);
            HomeApps.Item tv = new HomeApps.Item();
            tv.kind = HomeApps.ANDROID_SETTINGS;
            tv.key = "tv-settings";
            tv.label = Ui.tr("Ajustes da TV", "TV Settings");
            tv.symbol = "tv_settings";
            list.add(item(display, tv, ITEM));
            String more = Ui.tr("Mais apps", "More apps");
            for (HomeApps.Item it : HomeApps.others(app)) if (it.kind == HomeApps.APP) list.add(item(more, it, APP));
            handler.post(() -> setRows(list));
        };
        if (extract) new Thread(build, "list-rows").start();
        else build.run();
    }

    private static Row item(String sec, HomeApps.Item it, int type) {
        Row r = new Row();
        r.type = type;
        r.section = sec;
        r.item = it;
        r.label = it.label;
        r.symbol = it.symbol;
        return r;
    }

    private static Row action(String sec, int type, String label, String symbol) {
        Row r = new Row();
        r.type = type;
        r.section = sec;
        r.label = label;
        r.symbol = symbol;
        return r;
    }

    /** New rows keep the focus on the same thing (by label) when it's still there. */
    private void setRows(List<Row> list) {
        String focused = index < rows.size() ? rows.get(index).label : null;
        rows = list;
        index = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).label != null && list.get(i).label.equals(focused)) index = i;
        for (int i = 0; i < list.size(); i++) list.get(i).focus.snap(i == index ? 1 : 0);
        layoutRows();
        scroll.snap(scroll.target);
        startFrames();
    }

    // MARK: - Lifecycle

    @Override public void resume() {
        Spring.instant = HomeView.reducedMotion(getContext());
        if (running) return;
        running = true;
        recs.addListener(this);
        recs.refreshIfStale();
        rebuild(getContext(), true);
        loadBackground(false);
        handler.post(tick);
        Updates.check(getContext(), text -> { });
        startFrames();
    }

    @Override public void pause() {
        running = false;
        recs.removeListener(this);
        handler.removeCallbacks(tick);
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override public void destroy() {
        pause();
        click.release();
    }

    @Override public void reset(boolean animated) {
        moveTo(0);
    }

    @Override public void trimMemory() {
        recs.keepOnly(new ArrayList<>());
    }

    @Override public void onRecs() {
        rebuild(getContext(), false);   // Up Next and the apps' picks
    }

    // MARK: - Background: the screensaver's landscape, dimmed ("Next landscape" goes through them)

    private void loadBackground(boolean next) {
        final Context app = getContext().getApplicationContext();
        final android.content.SharedPreferences ui = app.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        new Thread(() -> {
            List<Landscapes.Photo> photos = Landscapes.ready(app);
            if (photos.isEmpty()) {
                Landscapes.refresh(app, () -> handler.post(() -> loadBackground(false)));
                return;
            }
            int at = ui.getInt("listBg", 0) + (next ? 1 : 0);
            at = ((at % photos.size()) + photos.size()) % photos.size();
            ui.edit().putInt("listBg", at).apply();
            BitmapFactory.Options o = new BitmapFactory.Options();
            String style = bgStyle(app);
            final Bitmap b;
            if (style.equals("black")) {
                b = null;
            } else if (style.equals("soft")) {
                o.inSampleSize = 8;   // 240×135
                Bitmap small = BitmapFactory.decodeFile(photos.get(at).file.getPath(), o);
                if (small == null) return;
                // A soft blur for almost nothing, like a macOS wallpaper behind a sidebar: shrink a lot, then enlarge.
                Bitmap tiny = Bitmap.createScaledBitmap(small, 48, 27, true);
                b = Bitmap.createScaledBitmap(tiny, 480, 270, true);
            } else {
                o.inSampleSize = 2;   // 960×540, the size of the composed background
                b = BitmapFactory.decodeFile(photos.get(at).file.getPath(), o);
            }
            final String place = photos.get(at).place;
            handler.post(() -> {
                background = b;
                backgroundPlace = place;
                composed = null;
                invalidate();
            });
        }, "list-bg").start();
    }

    private static String bgStyle(Context c) {
        return c.getSharedPreferences("home-ui", Context.MODE_PRIVATE).getString("listBgStyle", "sharp");
    }

    /** "Fondo ‹ Nítido ›": the next or previous style, applied right away. */
    private void changeBackground(int dir) {
        android.content.SharedPreferences ui = getContext().getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        int at = java.util.Arrays.asList(BG_STYLES).indexOf(bgStyle(getContext()));
        at = ((at + dir) % BG_STYLES.length + BG_STYLES.length) % BG_STYLES.length;
        ui.edit().putString("listBgStyle", BG_STYLES[at]).apply();
        click.move();
        loadBackground(false);
    }

    // MARK: - Keys

    @Override public boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) moveTo(index - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveTo(index + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    Row here = rows.isEmpty() ? null : rows.get(index);
                    if (here != null && here.type == BACKGROUND) changeBackground(dir);
                    else if (here != null && isLamp(here)) {
                        Lamp.next(getContext());   // three modes: either arrow goes round
                        click.move();
                        invalidate();
                    } else jumpSection(dir);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled()) activate();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                // The remote's settings button: straight to the projector's controls.
                if (down && e.getRepeatCount() == 0) {
                    for (int i = 0; i < rows.size(); i++) {
                        if (rows.get(i).section.equals(Ui.tr("Projetor", "Projector"))) {
                            moveTo(i);
                            break;
                        }
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        if (index > 0) moveTo(0); else HomeView.startScreensaver(getContext());
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void moveTo(int i) {
        if (rows.isEmpty()) return;
        i = Math.max(0, Math.min(rows.size() - 1, i));
        if (i == index) return;
        index = i;
        click.move();
        for (int j = 0; j < rows.size(); j++) rows.get(j).focus.target = j == index ? 1 : 0;
        layoutRows();
        startFrames();
    }

    /** ←/→: the first row of the previous or next section. */
    private void jumpSection(int dir) {
        if (rows.isEmpty()) return;
        String here = rows.get(index).section;
        int i = index;
        if (dir > 0) {
            while (i < rows.size() && rows.get(i).section.equals(here)) i++;
            if (i < rows.size()) moveTo(i);
        } else {
            while (i > 0 && rows.get(i - 1).section.equals(here)) i--;
            if (i == 0) return;
            String prev = rows.get(i - 1).section;
            while (i > 0 && rows.get(i - 1).section.equals(prev)) i--;
            moveTo(i);
        }
    }

    private void activate() {
        if (rows.isEmpty()) return;
        Row r = rows.get(index);
        click.open();
        int cx = Math.round(getWidth() * 0.73f), cy = Math.round(getHeight() * 0.45f);
        switch (r.type) {
            case DREAM:
                HomeView.startScreensaver(getContext());
                return;
            case NEXT_BG:
                loadBackground(true);
                return;
            case BACKGROUND:
                changeBackground(1);
                return;
            case PICK:
                TitleActivity.open(getContext(), r.pick);
                return;
            default:
                HomeApps.open(activity, r.item, this, cx, cy, 1, 1);
                invalidate();   // a switch flips in place
        }
    }

    // MARK: - Frames

    private void startFrames() {
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long now) {
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        boolean moving = scroll.step(dt);
        for (Row r : rows) moving |= r.focus.step(dt);
        invalidate();
        if (moving) Choreographer.getInstance().postFrameCallback(this);
    }

    // Row metrics (1080p): a resting name, the focused one and the gap before each section's title.
    private static final float REST = 32, FOCUSED = 84, REST_H = 48, FOCUSED_H = 132, SECTION_H = 60;

    /** Rows' resting positions and where the list scrolls so the focused one sits a little above the middle. */
    private void layoutRows() {
        float y = 0;
        String last = null;
        float focusY = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!r.section.equals(last)) {
                y += SECTION_H;
                last = r.section;
            }
            r.y = y;
            if (i == index) focusY = y;
            y += i == index ? FOCUSED_H : REST_H;
        }
        scroll.target = focusY - 400;   // the greeting stays clear above
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (composed == null || composedFrom != background) compose();   // also when the style changes (composed = null)
        rect.set(0, 0, w, h);
        c.drawBitmap(composed, null, rect, image);   // one pass: the GPU of a projector can't afford three per frame

        drawPreview(c, w, h);
        drawList(c, h);
        drawCorner(c, w, h);
    }

    private Bitmap composed, composedFrom;

    /**
     * The background made once (960×540, drawn enlarged): the landscape blurred and dimmed, dark on the left for the
     * list and a warm light at the top right.
     */
    private void compose() {
        int w = 960, h = 540;
        Bitmap old = composed;
        composed = old != null ? old : Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(composed);
        c.drawColor(0xFF0B0B0D);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (background != null) {
            p.setAlpha(bgStyle(getContext()).equals("sharp") ? 170 : 120);
            c.drawBitmap(background, null, new RectF(0, 0, w, h), p);
        }
        p.setAlpha(255);
        p.setShader(new LinearGradient(0, 0, w * 0.6f, 0, 0xF20B0B0D, 0x8C0B0B0D, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.82f, -h * 0.05f, w * 0.45f, 0x30E0A060, 0x00E0A060, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        composedFrom = background;
    }

    private void drawList(Canvas c, float h) {
        float x = 56 * k, top = -scroll.value * k;
        // The list scrolls under the greeting: it's cut below it and fades in over 110 px.
        float clipTop = 120 * k, fadeEnd = 230 * k;
        c.save();
        c.clipRect(0, clipTop, getWidth() * 0.5f, h);
        String last = null;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float f = Ui.clamp(r.focus.value);
            // Positions blend between resting and focused heights, so the list breathes as the focus moves.
            float y = top + r.y * k;
            if (!r.section.equals(last)) {
                last = r.section;
                if (y > -100 * k && y < h + 100 * k) {
                    section.setTextSize(20 * k);
                    section.setAlpha(Math.round(128 * fade(y - 20 * k, clipTop, fadeEnd)));
                    c.drawText(r.section, x, y - 20 * k, section);
                }
            }
            if (y < -200 * k || y > h + 100 * k) continue;
            float size = REST + (FOCUSED - REST) * f;
            // Always rasterized at the focused size and scaled down: a new text size every frame of the animation
            // meant new glyphs every frame.
            name.setTextSize(FOCUSED * k);
            name.setColor(Ui.alpha(0xFFFFFFFF, (0.5f + 0.5f * f) * fade(y + REST * k, clipTop, fadeEnd)));
            float base = y + (size * 0.9f) * k, scale = size / FOCUSED;
            String label = ellipsized(r.label == null ? "" : r.label, 900 * k / scale);
            c.save();
            c.scale(scale, scale, x, base);
            c.drawText(label, x, base, name);
            c.restore();
            if (f > 0.05f) {
                String s = stateOf(r);
                if (!s.isEmpty()) {
                    state.setTextSize(24 * k);
                    state.setColor(Ui.alpha(lit(r) ? 0xFF0A84FF : 0xCCFFFFFF, f));
                    float sy = base + 44 * k;
                    float tx = x;
                    if (r.type != BACKGROUND && !isLamp(r)) {   // a dot before a state; a value has its ‹ › instead
                        fill.setColor(state.getColor());
                        c.drawCircle(x + 6 * k, sy - 9 * k, 5 * k, fill);
                        tx += 22 * k;
                    }
                    c.drawText(TextUtils.ellipsize(s, state, 860 * k, TextUtils.TruncateAt.END).toString(), tx, sy, state);
                }
            }
        }
        c.restore();
    }

    private static float fade(float y, float from, float to) {
        return Ui.clamp((y - from) / (to - from));
    }

    private final java.util.Map<String, String> cut = new java.util.HashMap<>();

    private String ellipsized(String text, float width) {
        String key = text + '' + Math.round(width);
        String v = cut.get(key);
        if (v == null) {
            if (cut.size() > 400) cut.clear();
            v = TextUtils.ellipsize(text, name, width, TextUtils.TruncateAt.END).toString();
            cut.put(key, v);
        }
        return v;
    }

    /** The focused one's line: its pick, signal, switch state… */
    private String stateOf(Row r) {
        Context c = getContext();
        switch (r.type) {
            case APP: {
                if (r.item.kind != HomeApps.APP) return "";
                List<Recs.Pick> picks = recs.forService(r.item.key);
                return picks.isEmpty() ? "" : Ui.tr("Recomendado: ", "Recommended: ") + picks.get(0).title;
            }
            case PICK:
                return r.pick.fresh != null ? r.pick.fresh : r.pick.meta == null ? "" : r.pick.meta;
            case DREAM: {
                String st = Screensaver.style(c);
                return st.equals("frame") ? Ui.tr("Moldura", "Frame") : st.equals("recs") ? Ui.tr("Recomendações", "Recommendations")
                        : Ui.tr("Paisagens", "Landscapes");
            }
            case NEXT_BG:
                return backgroundPlace == null ? "" : backgroundPlace;
            case BACKGROUND: {
                String st = bgStyle(c);
                return "‹  " + (st.equals("soft") ? Ui.tr("Desfocado", "Blurred") : st.equals("black") ? Ui.tr("Preto", "Black")
                        : Ui.tr("Nítido", "Sharp")) + "  ›";
            }
            default:
                HomeApps.Item it = r.item;
                if (it.kind == HomeApps.INPUT) return it.connected ? Ui.tr("Com sinal", "Signal") : Ui.tr("Sem sinal", "No signal");
                if (it.kind == HomeApps.PROJECTOR) {
                    if (isLamp(r)) {
                        String m = Lamp.mode(c);
                        return "‹  " + (m.equals("eco") ? "Eco" : m.equals("night") ? Ui.tr("Noite", "Night") : "Normal") + "  ›";
                    }
                    if (Projector.isToggle(it.key)) return Projector.isOn(c, it.key) ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
                    if (Projector.timerOn(c, it)) return Projector.label(c, it);
                }
                return "";
        }
    }

    private static boolean isLamp(Row r) {
        return r.type == ITEM && r.item != null && "toggle:lamp".equals(r.item.key);
    }

    private boolean lit(Row r) {
        return r.type == ITEM && r.item.kind == HomeApps.PROJECTOR
                && (Projector.isToggle(r.item.key) ? Projector.isOn(getContext(), r.item.key) : Projector.timerOn(getContext(), r.item));
    }

    /** Right side: the app's logo, the title's photo, or the shortcut's symbol, large. */
    private void drawPreview(Canvas c, float w, float h) {
        if (rows.isEmpty()) return;
        Row r = rows.get(index);
        float f = Ui.clamp(r.focus.value), cx = w * 0.73f, cy = h * 0.45f;
        int alpha = Math.round(255 * f);
        Bitmap photo = null;
        if (r.type == PICK) {
            Backdrop b = recs.backdrop(r.pick);
            photo = b == null ? null : b.wide;
        } else if (r.type == APP && r.item.kind == HomeApps.APP) {
            List<Recs.Pick> picks = recs.forService(r.item.key);
            Backdrop b = picks.isEmpty() ? null : recs.backdrop(picks.get(0));
            photo = b == null ? null : b.wide;
        }
        if (photo != null) {   // the photo, rounded, fading into the dark at the bottom
            float pw = 860 * k, ph = pw * 9 / 16;
            rect.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2);
            clip.reset();
            clip.addRoundRect(rect, 22 * k, 22 * k, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            image.setAlpha(alpha);
            c.drawBitmap(photo, null, rect, image);
            image.setAlpha(255);
            c.restore();
        }
        HomeApps.Item it = r.item;
        if (r.type == APP && it != null && (it.tile != null || it.logo != null)) {
            // The app's card, rounded with a soft shadow like an icon in the Dock; over the photo's corner when there is one.
            Bitmap card = it.tile != null ? it.tile : it.logo;
            float tw = (photo != null ? 300 : 560) * k, th = tw * card.getHeight() / card.getWidth();
            float tx = photo != null ? cx - 860 * k / 2 + 36 * k : cx - tw / 2;
            float ty = photo != null ? cy + 860 * k * 9 / 32 - th / 2 : cy - th / 2;
            float radius = (photo != null ? 18 : 30) * k;
            for (int i = 3; i >= 1; i--) {   // a soft shadow from three wider, fainter shapes (no blur: it's drawn every frame)
                rect.set(tx - i * 6 * k, ty + (10 + i * 6) * k, tx + tw + i * 6 * k, ty + th + (10 + i * 8) * k);
                fill.setColor(Ui.alpha(0xFF000000, 0.14f * f));
                c.drawRoundRect(rect, radius + i * 6 * k, radius + i * 6 * k, fill);
            }
            rect.set(tx, ty, tx + tw, ty + th);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            image.setAlpha(alpha);
            c.drawBitmap(card, null, rect, image);
            image.setAlpha(255);
            c.restore();
        } else if (photo == null) {
            String sym = r.symbol != null ? r.symbol : it != null && it.kind == HomeApps.INPUT ? "hdmi" : "tv";
            Symbols.draw(getContext(), c, sym, cx, cy, 190 * k, Ui.alpha(0xFFEDE6DA, 0.9f * f));
        }
    }

    /** Top right: the device's name. Bottom right: the date and the time, large. */
    private void drawCorner(Canvas c, float w, float h) {
        float right = w - 80 * k;
        corner.setTextAlign(Paint.Align.RIGHT);
        corner.setTypeface(Ui.semibold);
        corner.setTextSize(28 * k);
        corner.setColor(0xB3FFFFFF);
        c.drawText(deviceName(), right, 80 * k, corner);
        // Top left, small, like the macOS menu bar: a greeting and the weather.
        corner.setTextAlign(Paint.Align.LEFT);
        corner.setTypeface(Ui.medium);
        corner.setColor(0xB3FFFFFF);
        HomeData.Weather wx = HomeData.get(getContext()).weather;
        String top = greeting();
        if (!Double.isNaN(wx.temp)) {
            top += "   ·   " + HomeData.degrees(wx.temp) + "  " + (Ui.es ? Es.condition(wx.code, wx.isDay) : HomeData.condition(wx.code, wx.isDay));
        }
        c.drawText(top, 56 * k, 80 * k, corner);
        corner.setTextAlign(Paint.Align.RIGHT);
        long now = System.currentTimeMillis();
        String d = Landscapes.date(now);
        corner.setTypeface(Ui.medium);
        corner.setTextSize(28 * k);
        corner.setColor(0xD9FFFFFF);
        c.drawText(Character.toUpperCase(d.charAt(0)) + d.substring(1), right, h - 170 * k, corner);
        corner.setTypeface(Ui.displayMedium);
        corner.setTextSize(110 * k);
        corner.setColor(0xFFFFFFFF);
        String time = new java.text.SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm",
                java.util.Locale.ROOT).format(new java.util.Date(now));
        c.drawText(time, right, h - 60 * k, corner);
        corner.setTextAlign(Paint.Align.LEFT);
    }

    private static String greeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 5 && h < 12 ? Ui.tr("Bom dia", "Good morning") : h >= 12 && h < 19 ? Ui.tr("Boa tarde", "Good afternoon")
                : Ui.tr("Boa noite", "Good evening");
    }

    private String deviceName;

    private String deviceName() {
        if (deviceName == null) {
            String n = android.provider.Settings.Global.getString(getContext().getContentResolver(), "device_name");
            deviceName = n == null || n.isEmpty() ? android.os.Build.MODEL : n;
        }
        return deviceName;
    }

    /** Opened from the Settings: the design changes when Home comes back. */
    static boolean chosen(Context c) {
        return "list".equals(c.getSharedPreferences("home-ui", Context.MODE_PRIVATE).getString("design", "classic"));
    }
}
