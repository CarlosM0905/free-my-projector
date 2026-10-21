package io.freemytv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import java.util.Random;

/**
 * Free My Projector: the weather in the sky behind the home screen (an idea from Cosy TV): rain, a storm with lightning,
 * snow or drifting fog, following the real weather. Each effect is a texture drawn once into a GPU layer and only slid
 * (rain falls, snow drifts and sways), so it costs no drawing per frame. Off with Reduce Motion.
 */
final class WeatherFx extends FrameLayout {
    private static final int NONE = 0, RAIN = 1, STORM = 2, SNOW = 3, FOG = 4;
    private final Sheet sheet;
    private final View flash;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ObjectAnimator fall, sway;
    private int kind = NONE;
    private boolean light;   // a light sky: darker drops and flakes, or they vanish against it
    private boolean running;

    WeatherFx(Context c) {
        super(c);
        sheet = new Sheet(c);
        sheet.setLayerType(LAYER_TYPE_HARDWARE, null);
        addView(sheet, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        flash = new View(c);
        flash.setBackgroundColor(0xFFFFFFFF);
        flash.setAlpha(0);
        addView(flash, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    /** The weather's code, or the one in home-ui "weatherFxTest" (to see an effect on a dry day). */
    static int code(Context c, int real) {
        return c.getSharedPreferences("home-ui", Context.MODE_PRIVATE).getInt("weatherFxTest", real);
    }

    /** From an Open-Meteo WMO code; NONE for clear or cloudy skies. */
    private static int kindOf(int code) {
        if (code >= 95) return STORM;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return SNOW;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return RAIN;
        if (code == 45 || code == 48) return FOG;
        return NONE;
    }

    /** Shows the effect for this weather (or none) and starts it if the home screen is showing. */
    void set(int code, boolean lightSky) {
        int k = Spring.instant ? NONE : kindOf(code);
        if (k == kind && lightSky == light) return;
        kind = k;
        light = lightSky;
        stopAnimations();
        if (kind == NONE) {
            setVisibility(GONE);
            sheet.set(null, 0);
            return;
        }
        setVisibility(VISIBLE);
        sheet.set(texture(kind), kind);
        sheet.requestLayout();
        if (running) startAnimations();
    }

    void start() {
        running = true;
        if (kind != NONE) startAnimations();
    }

    void stop() {
        running = false;
        stopAnimations();
    }

    private void startAnimations() {
        stopAnimations();
        float h = getHeight() > 0 ? getHeight() : 1080;
        long period = kind == SNOW ? 14_000 : kind == FOG ? 40_000 : 900;
        // The sheet is two screens tall: sliding it one screen and starting over is seamless (the texture repeats).
        if (kind == FOG) {
            fall = ObjectAnimator.ofFloat(sheet, "translationX", 0, -getWidth());
        } else {
            fall = ObjectAnimator.ofFloat(sheet, "translationY", -h, 0);
        }
        fall.setDuration(period);
        fall.setInterpolator(new LinearInterpolator());
        fall.setRepeatCount(ValueAnimator.INFINITE);
        fall.start();
        if (kind == SNOW) {
            sway = ObjectAnimator.ofFloat(sheet, "translationX", -24, 24);
            sway.setDuration(5000);
            sway.setRepeatMode(ValueAnimator.REVERSE);
            sway.setRepeatCount(ValueAnimator.INFINITE);
            sway.start();
        }
        if (kind == STORM) handler.postDelayed(this::lightning, 4000 + random.nextInt(8000));
    }

    private void stopAnimations() {
        if (fall != null) fall.cancel();
        if (sway != null) sway.cancel();
        fall = sway = null;
        handler.removeCallbacksAndMessages(null);
        flash.animate().cancel();
        flash.setAlpha(0);
    }

    /** A double flash, then the next one in 6–20 s. */
    private void lightning() {
        if (!running || kind != STORM) return;
        flash.setAlpha(0.32f);
        flash.animate().alpha(0).setDuration(140).withEndAction(() -> {
            flash.setAlpha(0.22f);
            flash.animate().alpha(0).setDuration(420).start();
        }).start();
        handler.postDelayed(this::lightning, 6000 + random.nextInt(14000));
    }

    /** A 512 px tile of streaks, flakes or haze, drawn once. */
    private Bitmap texture(int k) {
        int size = 512;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random r = new Random(7);
        if (k == RAIN || k == STORM) {
            p.setStrokeCap(Paint.Cap.ROUND);
            int drops = k == STORM ? 70 : 45;
            for (int i = 0; i < drops; i++) {
                float x = r.nextFloat() * size, y = r.nextFloat() * size, len = 22 + r.nextFloat() * 34;
                p.setStrokeWidth(1.2f + r.nextFloat() * 1.3f);
                p.setColor(((light ? 70 + r.nextInt(60) : 40 + r.nextInt(60)) << 24) | (light ? 0x5A6E85 : 0xDDE6F0));
                c.drawLine(x, y, x - len * 0.18f, y + len, p);   // slanted a little, as if blown
                c.drawLine(x, y - size, x - len * 0.18f, y - size + len, p);   // wraps at the tile's edge
            }
        } else if (k == SNOW) {
            for (int i = 0; i < 38; i++) {
                float x = r.nextFloat() * size, y = r.nextFloat() * size, rad = 1.6f + r.nextFloat() * 3.6f;
                p.setColor(((90 + r.nextInt(110)) << 24) | (light ? 0xA9B6C6 : 0xFFFFFF));
                c.drawCircle(x, y, rad, p);
            }
        } else {   // fog: soft blobs
            for (int i = 0; i < 9; i++) {
                float x = r.nextFloat() * size, y = r.nextFloat() * size, rad = 90 + r.nextFloat() * 120;
                p.setShader(new android.graphics.RadialGradient(x, y, rad, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, rad, p);
            }
        }
        return b;
    }

    /** The texture, repeated over two screens (so it can slide one screen and loop). */
    private static final class Sheet extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private int kind;

        Sheet(Context c) {
            super(c);
        }

        void set(Bitmap tile, int kind) {
            this.kind = kind;
            paint.setShader(tile == null ? null : new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w), height = MeasureSpec.getSize(h);
            // Two screens: tall for falling, wide for drifting fog.
            setMeasuredDimension(kind == FOG ? width * 2 : width + 60, kind == FOG ? height : height * 2);
        }

        @Override protected void onDraw(Canvas c) {
            if (paint.getShader() == null) return;
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }
}
