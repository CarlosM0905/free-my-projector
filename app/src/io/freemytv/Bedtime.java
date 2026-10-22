package io.freemytv;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import java.util.Calendar;

/**
 * Free My Projector: bedtime (Preferences). At the time you chose, if the projector is on, a small panel over whatever
 * is playing, like TimerDone: OK puts it to sleep in 30 minutes (Heat does it), Back leaves it on. Once a night; it
 * goes away by itself after a minute.
 */
public final class Bedtime extends Activity {
    /** Minutes after midnight; -1: off. */
    static final int[] TIMES = {-1, 22 * 60, 22 * 60 + 30, 23 * 60, 23 * 60 + 30, 0, 30, 60};
    static final long DELAY = 30 * 60_000L;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        long now = System.currentTimeMillis(), at = prefs(this).getLong("bedtimeAt", 0);
        schedule(this);   // tomorrow's
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        // Asleep, or a late alarm (the projector was off at that time): nothing to ask.
        if (pm == null || !pm.isInteractive() || Math.abs(now - at) > 15 * 60_000L) {
            finish();
            return;
        }
        setContentView(new Panel(this));
        main.postDelayed(this::finish, 60_000);
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            Heat.sleepAt(this, System.currentTimeMillis() + DELAY);
            Toast.makeText(this, String.format(Ui.tr("O projetor vai dormir às %s", "The projector will sleep at %s"),
                    Ui.clock(System.currentTimeMillis() + DELAY)), Toast.LENGTH_LONG).show();
            finish();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    static int time(Context c) {
        return prefs(c).getInt("bedtime", -1);
    }

    /** "22:30", or "Never". */
    static String label(Context c) {
        int t = time(c);
        if (t < 0) return Ui.tr("Nunca", "Never");
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, t / 60);
        cal.set(Calendar.MINUTE, t % 60);
        return Ui.clock(cal.getTimeInMillis());
    }

    /** ← → in Preferences. */
    static void next(Context c, int dir) {
        int at = 0;
        for (int i = 0; i < TIMES.length; i++) if (TIMES[i] == time(c)) at = i;
        int n = TIMES.length;
        prefs(c).edit().putInt("bedtime", TIMES[((at + dir) % n + n) % n]).apply();
        schedule(c);
    }

    /** The next alarm (or none); also after a boot or an update, since alarms don't survive them. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent show = PendingIntent.getActivity(c, 8, new Intent(c, Bedtime.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int t = time(c);
        if (t < 0) {
            am.cancel(show);
            prefs(c).edit().remove("bedtimeAt").apply();
            return;
        }
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, t / 60);
        cal.set(Calendar.MINUTE, t % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 60_000) cal.add(Calendar.DAY_OF_YEAR, 1);
        am.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), show);
        prefs(c).edit().putLong("bedtimeAt", cal.getTimeInMillis()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("projector", Context.MODE_PRIVATE);
    }

    private static final class Panel extends View {
        private final RectF box = new RectF(), button = new RectF();
        private final Paint dim = new Paint(), glass = new Paint(Paint.ANTI_ALIAS_FLAG), white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint title, subtitle, ok, hint;
        private float k = 1;

        Panel(Context c) {
            super(c);
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
            hint = Ui.text(Ui.regular, 24 * k, 0x80FFFFFF);
            hint.setTextAlign(Paint.Align.CENTER);
            box.set(w / 2f - 420 * k, h / 2f - 280 * k, w / 2f + 420 * k, h / 2f + 280 * k);
            button.set(w / 2f - 230 * k, box.bottom - 170 * k, w / 2f + 230 * k, box.bottom - 86 * k);
        }

        @Override protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), dim);
            c.drawRoundRect(box, 44 * k, 44 * k, glass);
            float cx = box.centerX();
            Symbols.draw(getContext(), c, "bedtime", cx, box.top + 120 * k, 96 * k, 0xFFBF5AF2);
            c.drawText(String.format(Ui.tr("São %s", "It's %s"), Ui.clock(System.currentTimeMillis())), cx, box.top + 250 * k, title);
            c.drawText(Ui.tr("Hora de dormir", "Time for bed"), cx, box.top + 300 * k, subtitle);
            c.drawRoundRect(button, button.height() / 2, button.height() / 2, white);
            c.drawText(Ui.tr("OK: dormir em 30 min", "OK: sleep in 30 min"), cx, button.centerY() + Ui.halfCap(ok), ok);
            c.drawText(Ui.tr("Voltar: agora não", "Back: not now"), cx, box.bottom - 36 * k, hint);
        }
    }
}
