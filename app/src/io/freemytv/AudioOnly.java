package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Free My Projector: "Solo audio" (Control Center). The lamp goes off and the music keeps playing (Spotify, AuraMusic,
 * a podcast): less heat and noise, and the LED lasts longer. Any button brings the picture back (the volume and media
 * buttons still do their job too). The screen stays "on" for Android, so the screensaver doesn't start and nothing goes to sleep.
 */
public final class AudioOnly extends Activity {
    static final String LAMP_OFF = "/sys/class/jixin-misc/lcd_bl_en";   // Wanbo: 1 = the LED's driver off

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean dark;

    /** This projector can turn its lamp off by itself. */
    static boolean supported() {
        return new java.io.File(LAMP_OFF).exists();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        setContentView(new Hint(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A moment to read the hint, then the lamp goes off.
        main.postDelayed(() -> {
            dark = true;
            lamp(this, false);
            main.postDelayed(watch, 2000);
        }, 2500);
    }

    /** The volume buttons never reach apps, but Wanbo's system turns the lamp on with them: then this screen leaves. */
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!dark) return;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(LAMP_OFF))) {
                if ("0".equals(r.readLine().trim())) {
                    dark = false;
                    finish();
                    return;
                }
            } catch (Exception ignored) {
            }
            main.postDelayed(this, 700);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacksAndMessages(null);
        if (dark) lamp(this, true);   // whatever takes over (a notification, another app) must be seen
        dark = false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean passes = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_NEXT
                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_STOP;
        // Wanbo's system turns the lamp back on with any button (PhoneWindowManager), the volume too: the volume and
        // media buttons still do their job, and every button leaves this screen.
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return passes ? super.dispatchKeyEvent(e) : true;
    }

    /** Lamp on or off, straight to the file when allowed, otherwise through the TV's own ADB. */
    static void lamp(Context c, boolean on) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try (java.io.FileWriter w = new java.io.FileWriter(LAMP_OFF)) {
                w.write(on ? "0" : "1");
                return;
            } catch (Exception ignored) {
            }
            try (Adb adb = Adb.connect(app, 5000, null)) {
                adb.shell("echo " + (on ? 0 : 1) + " > " + LAMP_OFF);
            } catch (Exception e) {
                AppLog.w("lamp: " + e.getMessage());
            }
        }, "lamp").start();
    }

    /** "Solo audio · cualquier botón vuelve a la imagen", centered, while the lamp is still on. */
    private static final class Hint extends View {
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);

        Hint(Context c) {
            super(c);
            big.setColor(0xF2FFFFFF);
            big.setTextAlign(Paint.Align.CENTER);
            big.setTypeface(Ui.displaySemibold);
            small.setColor(0x99FFFFFF);
            small.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(Ui.regular);
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = getWidth() / 1920f, cx = getWidth() / 2f, cy = getHeight() / 2f;
            Symbols.draw(getContext(), c, "music", cx, cy - 120 * k, 80 * k, 0xF2FFFFFF);
            big.setTextSize(52 * k);
            small.setTextSize(30 * k);
            c.drawText(Ui.tr("Só áudio", "Audio only"), cx, cy + 20 * k, big);
            c.drawText(Ui.tr("A lâmpada apaga e a música continua. Qualquer botão traz a imagem de volta.",
                    "The lamp turns off and the music keeps playing. Any button brings the picture back."), cx, cy + 80 * k, small);
        }
    }
}
