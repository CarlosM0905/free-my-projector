package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RenderNode;
import android.os.Build;

/**
 * A recorded drawing layer, the same subset of RenderNode that Scene and DreamView use. On Android 10+ it is a
 * RenderNode (kept on the GPU); on Android 9, where RenderNode isn't public, it records into a Bitmap of the same
 * size and draws it with the same position, translation and alpha. The RenderNode lives in a nested class so Android 9
 * never loads it.
 */
abstract class Layer {
    static Layer create(String name) {
        return Build.VERSION.SDK_INT >= 29 ? new Node(name) : new Picture();
    }

    abstract Canvas beginRecording(int width, int height);
    abstract void endRecording();
    abstract void setPosition(int left, int top, int right, int bottom);
    abstract void setAlpha(float alpha);
    abstract void setTranslationX(float x);
    abstract void setTranslationY(float y);
    abstract void setUseCompositingLayer(boolean use);
    abstract void draw(Canvas canvas);

    /** Android 10+: the original RenderNode. */
    private static final class Node extends Layer {
        private final RenderNode node;

        Node(String name) { node = new RenderNode(name); }

        @Override Canvas beginRecording(int width, int height) { return node.beginRecording(width, height); }
        @Override void endRecording() { node.endRecording(); }
        @Override void setPosition(int l, int t, int r, int b) { node.setPosition(l, t, r, b); }
        @Override void setAlpha(float alpha) { node.setAlpha(alpha); }
        @Override void setTranslationX(float x) { node.setTranslationX(x); }
        @Override void setTranslationY(float y) { node.setTranslationY(y); }
        @Override void setUseCompositingLayer(boolean use) { node.setUseCompositingLayer(use, null); }
        @Override void draw(Canvas canvas) { canvas.drawRenderNode(node); }
    }

    /** Android 9: a Bitmap recorded once and redrawn every frame (no GPU display lists available to apps). */
    private static final class Picture extends Layer {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap bitmap;
        private int left, top;
        private float tx, ty, alpha = 1;

        @Override Canvas beginRecording(int width, int height) {
            width = Math.max(1, width);
            height = Math.max(1, height);
            if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                if (bitmap != null) bitmap.recycle();
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            } else {
                bitmap.eraseColor(Color.TRANSPARENT);
            }
            return new Canvas(bitmap);
        }

        @Override void endRecording() {}
        @Override void setPosition(int l, int t, int r, int b) { left = l; top = t; }
        @Override void setAlpha(float a) { alpha = Math.max(0, Math.min(1, a)); }
        @Override void setTranslationX(float x) { tx = x; }
        @Override void setTranslationY(float y) { ty = y; }
        @Override void setUseCompositingLayer(boolean use) {}

        @Override void draw(Canvas canvas) {
            if (bitmap == null || alpha <= 0.004f) return;
            paint.setAlpha(Math.round(255 * alpha));
            canvas.drawBitmap(bitmap, left + tx, top + ty, paint);
        }
    }
}
