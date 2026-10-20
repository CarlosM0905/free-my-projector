package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Free My Projector: manual focus from the Control Center. A pattern of fine text and lines fills the screen; ← and →
 * step the lens motor (Wanbo: /sys/class/jixin-mx166l/len_goback and len_goforward, root only, so through the TV's own
 * ADB), holding moves it continuously. OK or Back leaves.
 */
public final class FocusActivity extends Activity {
    static final String MOTOR = "/sys/class/jixin-mx166l/";

    private final ExecutorService motor = Executors.newSingleThreadExecutor();
    private Adb adb;
    private int queued;

    static boolean supported() {
        return new java.io.File(MOTOR + "len_goforward").exists();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(new Pattern(this));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
            if (down) step(code == KeyEvent.KEYCODE_DPAD_RIGHT);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK) {
            if (!down) finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** One step of the lens; held, the steps queue up (at most a few, so it stops when you let go). */
    private void step(boolean forward) {
        synchronized (this) {
            if (queued >= 2) return;
            queued++;
        }
        final Context app = getApplicationContext();
        motor.execute(() -> {
            try {
                if (adb == null) adb = Adb.connect(app, 5000, null);
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

    /** Fine text and lines in the center and the corners (focus is often softer at the edges), and the hint. */
    private static final class Pattern extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(), hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);

        Pattern(Context c) {
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
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = getWidth() / 1920f, w = getWidth(), h = getHeight();
            c.drawColor(0xFF000000);
            big.setTextSize(64 * k);
            c.drawText(Ui.tr("Foco", "Focus"), w / 2, h / 2 - 150 * k, big);
            hint.setTextSize(30 * k);
            c.drawText(Ui.tr("← → para focar · OK para terminar", "← → to focus · OK when done"), w / 2, h / 2 + 250 * k, hint);
            block(c, w / 2 - 300 * k, h / 2 - 90 * k, k);
            float m = 60 * k;
            block(c, m, m, k);
            block(c, w - m - 600 * k, m, k);
            block(c, m, h - m - 180 * k, k);
            block(c, w - m - 600 * k, h - m - 180 * k, k);
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
