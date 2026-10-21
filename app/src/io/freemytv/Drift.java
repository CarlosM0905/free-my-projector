package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.List;

/**
 * Free My Projector: the light design's animated background. The screensaver's landscapes, one after another: each
 * drifts in slowly (a Ken Burns move) and fades into the next every 30 s. Two image layers on the GPU: moving and
 * fading them redraws nothing, so the list stays as smooth as with a still photo.
 */
final class Drift extends FrameLayout {
    private static final long EACH = 30_000, FADE = 2_500;
    private final Layer[] layers = new Layer[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int front, index;
    private boolean running;

    private final Runnable next = new Runnable() {
        @Override public void run() {
            if (!running) return;
            show(index + 1);
            handler.postDelayed(this, EACH);
        }
    };

    Drift(Context c) {
        super(c);
        for (int i = 0; i < 2; i++) {
            Layer v = new Layer(c);
            v.setLayerType(LAYER_TYPE_HARDWARE, null);
            v.setAlpha(0);
            addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            layers[i] = v;
        }
    }

    void start(int from) {
        if (running) return;
        running = true;
        setVisibility(VISIBLE);
        index = from - 1;
        handler.post(next);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(next);
        for (Layer v : layers) v.animate().cancel();
    }

    void hide() {
        stop();
        setVisibility(GONE);
        for (Layer v : layers) v.set(null);
    }

    /** Decodes photo `i` in the background, then fades it in over the current one while it starts drifting. */
    private void show(int i) {
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            List<Landscapes.Photo> photos = Landscapes.ready(app);
            if (photos.isEmpty()) return;
            final int at = ((i % photos.size()) + photos.size()) % photos.size();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;   // 960×540: it's dimmed under the list, and drifting hides the softness
            o.inMutable = true;
            final Bitmap b = BitmapFactory.decodeFile(photos.get(at).file.getPath(), o);
            if (b == null) return;
            // Dimmed once here, not with a layer over it on every frame (each full-screen layer costs ~7 ms on a T972).
            new android.graphics.Canvas(b).drawColor(0x800B0B0D);
            handler.post(() -> {
                if (!running) return;
                index = at;
                int back = 1 - front;
                Layer in = layers[back], out = layers[front];
                in.animate().cancel();
                in.set(b);
                in.setAlpha(0f);
                // Each photo drifts a different way: toward the right or the left, always slowly zooming in.
                boolean right = at % 2 == 0;
                in.setScaleX(1.02f);
                in.setScaleY(1.02f);
                in.setTranslationX(right ? -30 : 30);
                in.animate().alpha(1).setDuration(FADE).start();
                if (!Spring.instant) {   // a second animation on other properties: the fade keeps running
                    in.animate().scaleX(1.14f).scaleY(1.14f).translationX(right ? 30 : -30)
                            .setDuration(EACH + FADE * 2).setInterpolator(new LinearInterpolator()).start();
                }
                out.animate().alpha(0).setDuration(FADE).start();
                front = back;
            });
        }, "drift").start();
    }

    /** One photo, cropped to fill the screen (drawn once into its GPU layer; moving it redraws nothing). */
    private static final class Layer extends View {
        private Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();

        Layer(Context c) {
            super(c);
        }

        void set(Bitmap b) {
            bitmap = b;
            invalidate();
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

    int index() {
        return index;
    }
}
