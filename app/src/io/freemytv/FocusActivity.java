package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Free My Projector: the screen setup assistant (Control Center). Three steps, like a tvOS setup screen:
 * 1. Focus: the autofocus runs right away (the projector's camera and jfocus); ← → step the lens by hand
 *    (/sys/class/jixin-mx166l/len_goback and len_goforward, root only, so through the TV's own ADB).
 * 2. Shape: a frame along the edges to see whether the picture is a true rectangle; "Adjust corners" opens Wanbo's
 *    manual keystone, finer than its automatic one.
 * 3. Brightness: Normal, Eco or Night for the lamp (Lamp), with a suggestion for the time of day.
 * OK goes on, Back leaves at any step.
 */
public final class FocusActivity extends Activity {
    static final String MOTOR = "/sys/class/jixin-mx166l/";
    private static final int FOCUS = 0, SHAPE = 1, BRIGHTNESS = 2, DONE = 3;

    private final ExecutorService motor = Executors.newSingleThreadExecutor();
    private Adb adb;
    private int queued, step, choice;
    private Screen screen;
    private Click click;

    static boolean supported() {
        return new java.io.File(MOTOR + "len_goforward").exists();
    }

    /**
     * The lens motor and jfocus need root, which the TV's own ADB only has on a debuggable system (the community ROM);
     * on a Wanbo as it came the assistant starts at the shape step, and focus stays with Wanbo's own button.
     */
    static final boolean focusable = "1".equals(prop("ro.debuggable"));

    static String prop(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        click = new Click(this);
        screen = new Screen(this);
        setContentView(screen);
        if (focusable) {
            auto();
        } else {
            step = SHAPE;   // a projector as it came: Wanbo's own focus, then shape and brightness here
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        click.release();
        motor.execute(() -> {
            if (adb != null) adb.close();
            adb = null;
        });
        motor.shutdown();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!down) return true;
            boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (step == FOCUS) {
                lens(right);
            } else if (step == SHAPE || step == BRIGHTNESS) {
                int n = step == SHAPE ? 2 : 3, to = Math.max(0, Math.min(n - 1, choice + (right ? 1 : -1)));
                if (to != choice) {
                    choice = to;
                    click.move();
                    screen.invalidate();
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (!down) next();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) finish();
            return true;
        }
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || super.dispatchKeyEvent(e);
    }

    private void next() {
        click.open();
        switch (step) {
            case FOCUS:
                if (focusing) return;
                step = SHAPE;
                choice = 0;
                break;
            case SHAPE:
                if (choice == 1) {   // Wanbo's manual keystone; back here when it closes
                    HomeApps.Item ks = Projector.keystone(this);
                    if (ks != null) {
                        try {
                            startActivity(Projector.intent(ks.key).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (RuntimeException e) {
                            AppLog.w("keystone: " + e.getMessage());
                        }
                    }
                    choice = 0;
                    break;
                }
                step = BRIGHTNESS;
                choice = Math.max(0, java.util.Arrays.asList(Lamp.MODES).indexOf(Lamp.mode(this)));
                break;
            case BRIGHTNESS:
                while (!Lamp.mode(this).equals(Lamp.MODES[choice])) Lamp.next(this);
                step = DONE;
                break;
            default:
                finish();
                return;
        }
        screen.invalidate();
    }

    /** The lamp mode to suggest: Night late in the evening (a dark room), Eco in the evening, Normal by day. */
    private static int suggested() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 6 ? 2 : h >= 18 ? 1 : 0;
    }

    static volatile boolean focusing;

    /**
     * Autofocus, as Wanbo's own settings do it: the projector's camera looks at the picture and jfocus moves the lens
     * until it's sharp (about 5 s).
     */
    private void auto() {
        if (focusing) return;
        focusing = true;
        final Context app = getApplicationContext();
        motor.execute(() -> {
            try {
                if (adb == null) adb = Adb.connectRoot(app);
                adb.shell("setprop jx.sensor.status steady; setprop ctl.start jfocus; sleep 1; "
                        + "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do [ \"$(getprop init.svc.jfocus)\" = running ] || break; sleep 1; done");
            } catch (Exception e) {
                AppLog.w("autofocus: " + e.getMessage());
            } finally {
                focusing = false;
                runOnUiThread(() -> screen.invalidate());
            }
        });
    }

    /** One step of the lens; held, the steps queue up (at most a few, so it stops when you let go). */
    private void lens(boolean forward) {
        synchronized (this) {
            if (queued >= 2) return;
            queued++;
        }
        final Context app = getApplicationContext();
        motor.execute(() -> {
            try {
                if (adb == null) adb = Adb.connectRoot(app);
                adb.shell("echo 1 > " + MOTOR + (forward ? "len_goforward" : "len_goback"));
            } catch (Exception e) {
                AppLog.w("focus: " + e.getMessage());
                if (adb != null) adb.close();
                adb = null;
            } finally {
                synchronized (this) { queued--; }
            }
        });
    }

    private final class Screen extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(), hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Screen(Context c) {
            super(c);
            text.setColor(0xFFFFFFFF);
            text.setTypeface(Ui.regular);
            line.setColor(0xFFFFFFFF);
            hint.setColor(0xB3FFFFFF);
            hint.setTypeface(Ui.medium);
            hint.setTextAlign(Paint.Align.CENTER);
            big.setColor(0xFFFFFFFF);
            big.setTypeface(Ui.displaySemibold);
            big.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(Ui.semibold);
            small.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(Ui.semibold);
            label.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = getWidth() / 1920f, w = getWidth(), h = getHeight();
            c.drawColor(0xFF000000);
            // "1 of 3", over the title
            small.setTextSize(22 * k);
            small.setColor(0xFF0A84FF);
            if (step < DONE) {
                int first = focusable ? FOCUS : SHAPE;
                c.drawText(String.format(Ui.tr("PASSO %d DE %d", "STEP %d OF %d"), step - first + 1, DONE - first), w / 2, h / 2 - 230 * k, small);
            }
            big.setTextSize(64 * k);
            hint.setTextSize(30 * k);
            switch (step) {
                case FOCUS: {
                    c.drawText(Ui.tr("Foco", "Focus"), w / 2, h / 2 - 150 * k, big);
                    block(c, w / 2 - 300 * k, h / 2 - 90 * k, k);
                    float m = 60 * k;
                    block(c, m, m, k);
                    block(c, w - m - 600 * k, m, k);
                    block(c, m, h - m - 180 * k, k);
                    block(c, w - m - 600 * k, h - m - 180 * k, k);
                    c.drawText(focusing ? Ui.tr("Focando…", "Focusing…")
                            : Ui.tr("OK: seguinte  ·  ← →: ajustar à mão  ·  Voltar: sair", "OK: next  ·  ← →: adjust by hand  ·  Back: leave"),
                            w / 2, h / 2 + 250 * k, hint);
                    if (focusing) postInvalidateDelayed(500);
                    break;
                }
                case SHAPE: {
                    // A frame on the very edges, with marks in the corners and the middle: straight lines = a true rectangle.
                    line.setStrokeWidth(6 * k);
                    line.setStyle(Paint.Style.STROKE);
                    c.drawRect(3 * k, 3 * k, w - 3 * k, h - 3 * k, line);
                    line.setStrokeWidth(2 * k);
                    c.drawLine(w / 2, 0, w / 2, 60 * k, line);
                    c.drawLine(w / 2, h - 60 * k, w / 2, h, line);
                    c.drawLine(0, h / 2, 60 * k, h / 2, line);
                    c.drawLine(w - 60 * k, h / 2, w, h / 2, line);
                    line.setStyle(Paint.Style.FILL);
                    c.drawText(Ui.tr("Forma da imagem", "Picture shape"), w / 2, h / 2 - 150 * k, big);
                    c.drawText(Ui.tr("As bordas devem ficar retas, paralelas às da parede.",
                            "The edges should be straight, parallel to the wall's."), w / 2, h / 2 - 80 * k, hint);
                    buttons(c, new String[] {Ui.tr("Está bem", "Looks right"), Ui.tr("Ajustar cantos", "Adjust corners")}, -1, w, h, k);
                    break;
                }
                case BRIGHTNESS: {
                    c.drawText(Ui.tr("Brilho", "Brightness"), w / 2, h / 2 - 150 * k, big);
                    c.drawText(Ui.tr("Normal para o dia, Eco para a noite, Noite para um quarto escuro (menos calor e ruído).",
                            "Normal by day, Eco in the evening, Night for a dark room (less heat and noise)."), w / 2, h / 2 - 80 * k, hint);
                    buttons(c, new String[] {"Normal", "Eco", Ui.tr("Noite", "Night")}, suggested(), w, h, k);
                    break;
                }
                default: {
                    Symbols.draw(getContext(), c, "check", w / 2, h / 2 - 150 * k, 90 * k, 0xFF30D158);
                    c.drawText(Ui.tr("Pronto", "All set"), w / 2, h / 2, big);
                    c.drawText(Ui.tr("OK para voltar", "OK to go back"), w / 2, h / 2 + 70 * k, hint);
                }
            }
        }

        /** Pills in a row, centered; the focused one white; the suggested one says so under it. */
        private void buttons(Canvas c, String[] names, int suggested, float w, float h, float k) {
            label.setTextSize(30 * k);
            float bh = 84 * k, gap = 28 * k, total = -gap;
            float[] widths = new float[names.length];
            for (int i = 0; i < names.length; i++) {
                widths[i] = Math.max(220 * k, label.measureText(names[i]) + 90 * k);
                total += widths[i] + gap;
            }
            float x = (w - total) / 2, y = h / 2 + 40 * k;
            for (int i = 0; i < names.length; i++) {
                boolean on = i == choice;
                r.set(x, y, x + widths[i], y + bh);
                fill.setColor(on ? 0xFFFFFFFF : 0x33FFFFFF);
                c.drawRoundRect(r, bh / 2, bh / 2, fill);
                label.setColor(on ? 0xFF1C1C1E : 0xF2FFFFFF);
                c.drawText(names[i], r.centerX(), r.centerY() + Ui.halfCap(label), label);
                if (i == suggested) {
                    small.setTextSize(20 * k);
                    small.setColor(0xB3FFFFFF);
                    c.drawText(Ui.tr("Sugerido agora", "Suggested now"), r.centerX(), r.bottom + 40 * k, small);
                }
                x += widths[i] + gap;
            }
        }

        /** 600 × 180: lines of shrinking text, then 1 px lines. */
        private void block(Canvas c, float x, float y, float k) {
            float yy = y;
            String sample = "Free My Projector  0123456789  Aa Ee Ññ";
            for (float size : new float[] {34, 24, 18, 14}) {
                text.setTextSize(size * k);
                text.setTextScaleX(1);
                float wide = text.measureText(sample);
                if (wide > 600 * k) text.setTextScaleX(600 * k / wide);   // the big line fits the block
                yy += size * k * 1.25f;
                c.drawText(sample, x, yy, text);
            }
            line.setStrokeWidth(Math.max(1, k));
            for (int i = 0; i < 6; i++) {
                float ly = yy + (14 + i * 4) * k;
                c.drawLine(x, ly, x + 600 * k, ly, line);
            }
        }
    }
}
