package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private Screensaver.Dream preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        android.view.View v = Screensaver.make(this);
        preview = (Screensaver.Dream) v;
        setContentView(v);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, PREFS = 2, TYPE = 3;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 74, ROW_GAP = 8, CHOICE_H = 104;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[11];   // the longest list: Preferences
        private final RectF tmp = new RectF(), rowRect = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                if (!state.optString("busy").isEmpty()) pendingAt = 0;
                invalidate();
                handler.postDelayed(this, 500);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private boolean busy() {
            return !state.optString("busy").isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            return screen == MAIN ? 6 : screen == PREFS ? 11 : 2;
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            scene.drawBackground(c);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, 0xEBFFFFFF);
            c.drawText("Free My Projector", cx, 612 * k, title);
            c.drawText(Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 704 * k, version);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == PREFS) drawPrefs(c); else if (screen == TYPE) drawTyping(c); else drawChoice(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = state.optString("busy");
            String mode = freeActive() ? "Free My Projector" : Ui.tr("Original", "Original");
            String[] labels = {Ui.tr("Início", "Home Screen"), Ui.tr("Tela de Descanso", "Screensaver"),
                    Ui.tr("Estilo da tela de descanso", "Screensaver style"), Ui.tr("Preferências", "Preferences"), Ui.tr("Design do Início", "Home design"), Ui.tr("Ajustes da TV", "TV Settings")};
            String st = Screensaver.style(getContext());
            String style = st.equals("frame") ? Ui.tr("Moldura", "Frame") : st.equals("recs") ? Ui.tr("Recomendações", "Recommendations")
                    : Ui.tr("Paisagens", "Landscapes");
            String motion = HomeView.reducedMotion(getContext()) ? Ui.tr("Reduzido", "Reduced") : Ui.tr("Completo", "Full");
            String design = ListHome.chosen(getContext()) ? Ui.tr("Leve", "Light") : Ui.tr("Clássico", "Classic");
            String[] values = {working ? "" : mode, Ui.tr("Ver agora", "Preview"), style, "", design, ""};
            for (int i = 0; i < 6; i++) {
                float y = (LIST_TOP + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (i >= 5 || i == 3) {   // opens another screen
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                } else if (i == 0 && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(0xFFFFFFFF, INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(values[i], right, base, rowValue);
                }
            }

            float y = (LIST_TOP + 6 * (ROW_H + ROW_GAP) + 46) * k;
            String error = state.optString("error");
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                c.drawText(TextUtils.ellipsize(error, new TextPaint(warn), 760 * k, TextUtils.TruncateAt.END).toString(), LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            }
            y = drawComparison(c, y);
            y = drawRecs(c, y + 26 * k);
            drawNotices(c, y + 40 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("Sem chave do TMDB: veja o README (config.properties).", "No TMDB key: see the README (config.properties).");
            else if (!error.isEmpty()) line = Ui.tr("Erro: ", "Error: ") + error + ".";
            else if (count > 0 && Ui.es) line = "Activas · " + count + " títulos de las apps del proyector, 3 nuevos cada hora.";
            else if (count > 0) line = Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                    "On · " + count + " titles from the TV's apps, 3 new ones every hour.");
            else line = Ui.tr("Buscando os títulos…", "Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            return y + 78 * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 300 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARAÇÃO", "COMPARISON"), x, y, header);
            cellValue.setColor(0x73FFFFFF);
            c.drawText("Original", colA, y, cellValue);
            c.drawText("Free My Projector", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Botão Início", "Home button"), "homeMs"}, {Ui.tr("Memória livre", "Free memory"), "memAvailableMB"},
                    {Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("AVISOS", "NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("Nenhum ainda.", "None yet."), x, y + 42 * k, notice);
        }

        // MARK: - Preferences (upstream's "Settings on the TV"): what config.properties sets, changed on the projector

        private String cityStatus = "";

        private void drawPrefs(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Preferências", "Preferences"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            Context ctx = getContext();
            String lang = Prefs.language(), temp = Prefs.temperature(), theme = Prefs.theme(), team = Prefs.footballTeam();
            String[] labels = {Ui.tr("Idioma", "Language"), Ui.tr("Cidade", "City"), Ui.tr("Temperatura", "Temperature"),
                    Ui.tr("Tema", "Theme"), Ui.tr("Movimento", "Motion"), Ui.tr("Criptomoedas", "Crypto prices"), Ui.tr("Time de futebol", "Football team"), Ui.tr("Chave do TMDB", "TMDB key"), Ui.tr("Fundos", "Backgrounds"),
                    Ui.tr("Dormir sem uso", "Sleep when unused"), Ui.tr("Hora de dormir", "Bedtime")};
            int idle = Heat.idleHours(ctx);
            String[] values = {
                    "‹  " + (lang.equals("es") ? "Español" : lang.equals("en") ? "English" : lang.equals("pt") ? "Português" : Ui.tr("Automático", "Automatic")) + "  ›",
                    !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city(),
                    "‹  " + (temp.equals("C") ? "°C" : temp.equals("F") ? "°F" : Ui.tr("Automática", "Automatic")) + "  ›",
                    "‹  " + (theme.equals("dark") ? Ui.tr("Escuro", "Dark") : theme.equals("light") ? Ui.tr("Claro", "Light theme")
                            : Ui.tr("Automático", "Automatic")) + "  ›",
                    "‹  " + (HomeView.reducedMotion(ctx) ? Ui.tr("Reduzido", "Reduced") : Ui.tr("Completo", "Full")) + "  ›",
                    "‹  " + (Prefs.quotes() ? Ui.tr("Sim", "On") : Ui.tr("Não", "Off")) + "  ›",
                    team.isEmpty() ? Ui.tr("Nenhum", "None") : team,
                    masked(Prefs.tmdbKey()),
                    Backgrounds.mine(ctx).isEmpty() ? Ui.tr("Paisagens do dia", "Landscapes of the day")
                            : String.format(Ui.tr("%d seus", "%d of yours"), Backgrounds.mine(ctx).size()),
                    "‹  " + (idle <= 0 ? Ui.tr("Nunca", "Never") : String.format(Ui.tr("Após %d h", "After %d h"), idle)) + "  ›",
                    "‹  " + Bedtime.label(ctx) + "  ›"};
            // Eleven rows: a little tighter than the other lists so the hint still fits on the screen.
            final float rowH = 64, rowGap = 6;
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + i * (rowH + rowGap)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + rowH * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                String v = TextUtils.ellipsize(values[i], new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(v, rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("← → muda o valor  ·  OK em Cidade, Time e Chave abre o teclado",
                    "← → changes the value  ·  OK on City, Team and Key opens the keyboard"), LIST_X * k + 30 * k,
                    (LIST_TOP + labels.length * (rowH + rowGap) + 40) * k, hint);
        }

        /** "••••3f9a": enough to tell keys apart, never the whole secret on screen. */
        private static String masked(String key) {
            if (key.isEmpty()) return Ui.tr("Nenhuma", "None");
            return "••••" + key.substring(Math.max(0, key.length() - 4));
        }

        private static String next(String[] values, String now, int dir) {
            int at = Math.max(0, java.util.Arrays.asList(values).indexOf(now));
            return values[((at + dir) % values.length + values.length) % values.length];
        }

        /** ←/→ (or OK) on a Preferences row. */
        private void change(int row, int dir) {
            Context c = getContext();
            switch (row) {
                case 0: {   // the whole app reads the language once: start the screen again in the new one
                    Prefs.setLanguage(next(Prefs.LANGUAGES, Prefs.language(), dir));
                    postDelayed(() -> {
                        activity.startActivity(new Intent(activity, SettingsActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }, 600);
                    break;
                }
                case 2:
                    Prefs.setTemperature(next(Prefs.TEMPERATURES, Prefs.temperature(), dir));
                    break;
                case 3:
                    Prefs.setTheme(next(Prefs.THEMES, Prefs.theme(), dir));
                    break;
                case 4: {
                    android.content.SharedPreferences ui = c.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
                    ui.edit().putBoolean("reducedMotion", !ui.getBoolean("reducedMotion", false)).apply();
                    break;
                }
                case 5:
                    Prefs.setQuotes(!Prefs.quotes());
                    break;
                case 9:
                    Heat.nextIdleHours(c, dir);
                    break;
                case 10:
                    Bedtime.next(c, dir);
                    break;
                default:
                    return;
            }
            invalidate();
        }

        // MARK: - Typing (City and Football team): our own keyboard, like the Search tab's. The system keyboard keeps every
        // key of the remote while it's open, so on a projector you couldn't even reach its Search button.

        private static final String[] KEY_ROWS = {"abcdefghijklmnñopqrst", "uvwxyz0123456789 \u232B\u2713"};
        private int typeFor, keyRow, keyCol, resultIndex = -1;
        private String typed = "";
        private final java.util.List<String[]> places = new java.util.ArrayList<>();

        private void startTyping(int row) {
            screen = TYPE;
            typeFor = row;
            typed = row == 6 ? Prefs.footballTeam() : "";   // the key is never shown back: typed fresh
            keyRow = 0;
            keyCol = 0;
            resultIndex = -1;
            places.clear();
            cityStatus = "";
            invalidate();
        }

        private boolean onTypingKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) {
                    screen = PREFS;
                    focus = typeFor;
                    invalidate();
                }
                return true;
            }
            if (!down) return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (resultIndex < 0) {
                        int n = KEY_ROWS[keyRow].length();
                        keyCol = Math.max(0, Math.min(n - 1, keyCol + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (resultIndex > 0) resultIndex--;
                    else if (resultIndex == 0) resultIndex = -1;
                    else if (keyRow == 1) keyRow = 0;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (resultIndex >= 0) resultIndex = Math.min(places.size() - 1, resultIndex + 1);
                    else if (keyRow == 0) keyRow = 1;
                    else if (!places.isEmpty()) resultIndex = 0;
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() > 0) break;
                    if (resultIndex >= 0) {   // a place picked
                        String[] place = places.get(resultIndex);
                        Prefs.setCity(place[1], place[2], place[3]);
                        HomeData.get(getContext()).refreshWeather();
                        screen = PREFS;
                        focus = typeFor;
                        break;
                    }
                    keyCol = Math.min(keyCol, KEY_ROWS[keyRow].length() - 1);
                    char k = KEY_ROWS[keyRow].charAt(keyCol);
                    if (k == '\u232B') {
                        if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1);
                    } else if (k == '\u2713') {
                        confirmTyping();
                    } else if (typed.length() < 40) {
                        typed += typeFor != 7 && (typed.isEmpty() || typed.endsWith(" ")) ? Character.toUpperCase(k) : k;
                    }
                    break;
                default:
                    // A real keyboard (a USB or Bluetooth one) types straight in.
                    int ch = e.getUnicodeChar();
                    if (code == KeyEvent.KEYCODE_DEL) {
                        if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1);
                    } else if (ch > 31 && typed.length() < 40) {
                        typed += (char) ch;
                    } else {
                        return false;
                    }
            }
            invalidate();
            return true;
        }

        /** ✓: the team is saved; the city is searched and its places listed below the keyboard. */
        private void confirmTyping() {
            if (typeFor == 7) {   // a new key: the recommendations are fetched again with it
                Prefs.setTmdbKey(typed);
                screen = PREFS;
                focus = typeFor;
                return;
            }
            if (typeFor == 6) {
                Prefs.setFootballTeam(typed);
                Football.next = null;
                Football.last = null;
                Football.refresh(getContext(), null);
                screen = PREFS;
                focus = typeFor;
                return;
            }
            if (typed.trim().isEmpty()) return;
            cityStatus = Ui.tr("Procurando…", "Searching…");
            places.clear();
            resultIndex = -1;
            final String name = typed;
            new Thread(() -> {
                java.util.List<String[]> found;
                String error = "";
                try {
                    found = HomeData.findCity(name);
                    if (found.isEmpty()) error = Ui.tr("Nenhuma cidade com esse nome", "No city with that name");
                } catch (Exception ex) {
                    found = new java.util.ArrayList<>();
                    error = Ui.tr("Não deu para buscar agora", "Couldn't search now");
                }
                final java.util.List<String[]> list = found;
                final String message = error;
                post(() -> {
                    places.clear();
                    for (int i = 0; i < list.size() && i < 6; i++) places.add(list.get(i));
                    cityStatus = message.isEmpty() ? Ui.tr("↓ para escolher", "↓ to choose") : message;
                    invalidate();
                });
            }, "city-search").start();
        }

        private void drawTyping(Canvas c) {
            float x = LIST_X * k, right = LIST_R * k;
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(typeFor == 7 ? Ui.tr("Chave do TMDB", "TMDB key") : typeFor == 6 ? Ui.tr("Time de futebol", "Football team")
                    : Ui.tr("Cidade", "City"), x + 30 * k, (LIST_TOP - 24) * k, heading);
            // What's typed, with a caret
            Paint big = Ui.text(Ui.medium, 44 * k, typed.isEmpty() ? 0x66FFFFFF : 0xFFFFFFFF);
            String shown = typed.isEmpty() ? (typeFor == 7 ? Ui.tr("A API Key de themoviedb.org (32 letras e números)", "The API Key from themoviedb.org (32 letters and digits)")
                    : typeFor == 6 ? Ui.tr("Nome do time", "Team name") : Ui.tr("Nome da cidade", "City name")) : typed;
            float ty = (LIST_TOP + 56) * k;
            c.drawText(shown, x + 30 * k, ty, big);
            if (!typed.isEmpty()) {
                float cx = x + 30 * k + big.measureText(typed) + 6 * k;
                white.setAlpha(230);
                c.drawRect(cx, ty - 38 * k, cx + 3 * k, ty + 8 * k, white);
                white.setAlpha(255);
            }
            if (!cityStatus.isEmpty()) {
                Paint status = Ui.text(Ui.regular, 24 * k, 0x99FFFFFF);
                c.drawText(cityStatus, x + 30 * k, ty + 46 * k, status);
            }
            // Two rows of keys
            Paint key = Ui.text(Ui.medium, 28 * k, 0xE6FFFFFF);
            key.setTextAlign(Paint.Align.CENTER);
            float rowTop = (LIST_TOP + 130) * k, kh = 58 * k, kw = (right - x) / KEY_ROWS[0].length();
            for (int r = 0; r < 2; r++) {
                String row = KEY_ROWS[r];
                for (int i = 0; i < row.length(); i++) {
                    float kx = x + i * kw, ky = rowTop + r * (kh + 10 * k);
                    boolean on = resultIndex < 0 && r == keyRow && i == Math.min(keyCol, row.length() - 1);
                    if (on) {
                        tmp.set(kx + 2 * k, ky, kx + kw - 2 * k, ky + kh);
                        c.drawRoundRect(tmp, 12 * k, 12 * k, white);
                    }
                    char ch = row.charAt(i);
                    key.setColor(on ? INK : ch == '\u2713' ? 0xFF0A84FF : 0xE6FFFFFF);
                    String label = ch == ' ' ? "\u2423" : String.valueOf(ch);
                    c.drawText(label, kx + kw / 2, ky + kh / 2 + Ui.halfCap(key), key);
                }
            }
            // The places found
            float y = rowTop + 2 * (kh + 10 * k) + 24 * k;
            for (int i = 0; i < places.size(); i++) {
                rowRect.set(x, y, right, y + 62 * k);
                boolean on = i == resultIndex;
                if (on) c.drawRoundRect(rowRect, 16 * k, 16 * k, white);
                rowValue.setTextAlign(Paint.Align.LEFT);
                rowValue.setColor(on ? INK : 0xD9FFFFFF);
                String label = TextUtils.ellipsize(places.get(i)[0], new TextPaint(rowValue), right - x - 60 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(label, x + 30 * k, rowRect.centerY() + Ui.halfCap(rowValue), rowValue);
                rowValue.setTextAlign(Paint.Align.RIGHT);
                y += 70 * k;
            }
            Paint hint = Ui.text(Ui.regular, 22 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("OK escreve  ·  \u2713 confirma  ·  Voltar sai", "OK types  ·  \u2713 confirms  ·  Back leaves"), x + 30 * k,
                    h - 70 * k, hint);
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Início", "Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Free My Projector", Ui.tr("Sem anúncios, com a tela de descanso nova", "No ads, with the new screensaver")},
                    {"Original", Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("A troca leva uns segundos e a tela pisca nas medições.",
                    "Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return v + " ms";
            if (key.equals("memAvailableMB") && v >= 1024) return String.format(Ui.en ? java.util.Locale.US : Ui.PT, "%.2f GB", v / 1024.0);
            return v + " MB";
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (Ui.es) return minutes < 1 ? "ahora" : minutes < 48 * 60 ? Es.ago(minutes) : "hace " + (minutes / 1440) + " días";
            if (minutes < 1) return Ui.tr("agora", "now");
            if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
            if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
            return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
        }

        private static String versionName(Context c) {
            try {
                return "Free My Projector " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My Projector";
            }
        }

        boolean onKey(KeyEvent e) {
            if (screen == TYPE) return onTypingKey(e);
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == PREFS) change(focus, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
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
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            focus = screen == PREFS ? 3 : 0;
                            screen = MAIN;
                        }
                        backDown = false;
                    }
                    invalidate();
                    return true;
                default:
                    return false;
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == PREFS) {
                if (focus == 1 || focus == 6 || focus == 7) startTyping(focus);
                else if (focus == 8) BackgroundActivity.open(c);
                else change(focus, 1);
                return;
            }
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                if (wantFree != freeActive() && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == 2) {
                // Screensaver style: framed landscapes with the time and weather, or the recommendations' photos.
                android.content.SharedPreferences ui = c.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
                int at = java.util.Arrays.asList(Screensaver.STYLES).indexOf(Screensaver.style(c));
                ui.edit().putString("dreamStyle", Screensaver.STYLES[(at + 1) % Screensaver.STYLES.length]).apply();
            } else if (focus == 3) {
                screen = PREFS;
                focus = 0;
            } else if (focus == 4) {
                // Home design: classic (tvOS glass) or light (a list of names, smooth on any projector).
                android.content.SharedPreferences ui = c.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
                ui.edit().putString("design", ListHome.chosen(c) ? "classic" : "list").apply();
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
        }
    }
}
