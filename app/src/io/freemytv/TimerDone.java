package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

/**
 * Free My Projector: the quick timer (Control Center widget) has ended. A small panel over whatever is playing, like a
 * tvOS alert, with a chime; OK or Back closes it, and it goes away by itself after a minute.
 */
public final class TimerDone extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ringtone chime;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        int minutes = Widgets.timerMinutes(this);
        Widgets.timerFinished(this);
        setContentView(new Panel(this, minutes));
        try {
            chime = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (chime != null) chime.play();
        } catch (RuntimeException e) {
            AppLog.w("timer chime: " + e.getMessage());
        }
        main.postDelayed(this::finish, 60_000);
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (chime != null) chime.stop();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private static final class Panel extends View {
        private final int minutes;
        private final RectF box = new RectF(), button = new RectF();
        private final Paint dim = new Paint(), glass = new Paint(Paint.ANTI_ALIAS_FLAG), white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint title, subtitle, ok;
        private float k = 1;

        Panel(Context c, int minutes) {
            super(c);
            this.minutes = minutes;
            dim.setColor(0x80000000);
            glass.setColor(0xF02C2C2E);
            white.setColor(0xF2FFFFFF);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            title = Ui.text(Ui.displaySemibold, 48 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 28 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            ok = Ui.text(Ui.semibold, 28 * k, 0xFF1C1C1E);
            ok.setTextAlign(Paint.Align.CENTER);
            box.set(w / 2f - 380 * k, h / 2f - 260 * k, w / 2f + 380 * k, h / 2f + 260 * k);
            button.set(w / 2f - 180 * k, box.bottom - 130 * k, w / 2f + 180 * k, box.bottom - 46 * k);
        }

        @Override protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), dim);
            c.drawRoundRect(box, 44 * k, 44 * k, glass);
            float cx = box.centerX();
            Symbols.draw(getContext(), c, "bell", cx, box.top + 120 * k, 96 * k, 0xFFFFD60A);
            c.drawText(Ui.tr("Tempo esgotado", "Time's up"), cx, box.top + 250 * k, title);
            if (minutes > 0) c.drawText(String.format(Ui.tr("Temporizador de %d min", "%d-minute timer"), minutes), cx, box.top + 300 * k, subtitle);
            c.drawRoundRect(button, button.height() / 2, button.height() / 2, white);
            c.drawText("OK", cx, button.centerY() + Ui.halfCap(ok), ok);
        }
    }
}
