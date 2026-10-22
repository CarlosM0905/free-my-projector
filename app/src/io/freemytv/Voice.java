package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Free My Projector: voice, like Siri on the Apple TV. Hold the remote's microphone button and talk; the words appear
 * as you say them, over whatever is on screen, and when you let go (or stop talking) Free My Projector does what you
 * asked (VoiceCommands): opens an app, sets the sleep timer, a scene, the volume… or searches your apps for it.
 * The listening is Android's own (the system's recognition service, Google's on Android TV): only the text comes here.
 */
public final class Voice extends Activity {
    private static final int LISTENING = 0, THINKING = 1, DONE = 2, FAILED = 3;
    private static WeakReference<Voice> current = new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private Panel panel;
    private int state = LISTENING;
    private String heard = "", answer = "";
    private boolean held;
    private long startedAt;

    /** The microphone button was let go (RemoteKeys, same process): stop listening, as on the Siri Remote. */
    static void release() {
        Voice v = current.get();
        if (v == null) return;
        v.main.post(() -> {
            // A tap starts it and it listens until you pause; a hold listens until you let go.
            if (v.state == LISTENING && SystemClock.uptimeMillis() - v.startedAt > 450 && v.recognizer != null) {
                v.recognizer.stopListening();
                v.setState(THINKING);
            }
        });
    }

    static boolean available(Context c) {
        return SpeechRecognizer.isRecognitionAvailable(c);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        current = new WeakReference<>(this);
        held = getIntent().getBooleanExtra("held", false);
        panel = new Panel(this);
        setContentView(panel);
        startedAt = SystemClock.uptimeMillis();
        listen();
    }

    private void listen() {
        if (!available(this)) {
            fail(Ui.tr("Sem reconhecimento de voz neste aparelho.", "No voice recognition on this device."));
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {
                panel.level = Math.max(0, Math.min(1, (rmsdB + 2) / 12f));
            }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                setState(THINKING);
            }
            @Override public void onError(int error) {
                if (state == DONE) return;
                fail(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? Ui.tr("Não ouvi nada. Tente de novo.", "I didn't catch that. Try again.")
                        : error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                        ? Ui.tr("Sem internet para a voz agora.", "No internet for voice right now.")
                        : Ui.tr("A voz não respondeu. Tente de novo.", "Voice didn't answer. Try again."));
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> found = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = found == null || found.isEmpty() ? heard : found.get(0);
                if (text == null || text.trim().isEmpty()) {
                    fail(Ui.tr("Não ouvi nada. Tente de novo.", "I didn't catch that. Try again."));
                    return;
                }
                heard = text.trim();
                act();
            }
            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> found = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (found != null && !found.isEmpty() && !found.get(0).trim().isEmpty()) {
                    heard = found.get(0).trim();
                    panel.invalidate();
                }
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Ui.en ? "en-US" : Ui.PT.toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizer.startListening(intent);
    }

    /** What was said, turned into an action: shown for a moment ("Opening Netflix"), then done. */
    private void act() {
        VoiceCommands.Command cmd = VoiceCommands.parse(this, heard);
        answer = cmd.label;
        setState(DONE);
        main.postDelayed(() -> {
            cmd.run(this);
            finish();
        }, 650);
    }

    private void fail(String message) {
        answer = message;
        setState(FAILED);
        main.postDelayed(this::finish, 2200);
    }

    private void setState(int s) {
        state = s;
        panel.invalidate();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {   // OK: done talking
            if (state == LISTENING && recognizer != null) {
                recognizer.stopListening();
                setState(THINKING);
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onPause() {
        super.onPause();
        if (!isFinishing()) finish();   // something else came to the front: the panel goes away
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
        }
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }

    /** The panel at the bottom of the screen: the listening orb, the words, and a hint. */
    private final class Panel extends View {
        volatile float level;
        private float shown, k = 1;
        private final RectF box = new RectF();
        private final Paint scrim = new Paint(), glass = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
                orb = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private TextPaint words, dim, hint;
        private final long born = SystemClock.uptimeMillis();

        Panel(Context c) {
            super(c);
            glass.setColor(0xE61C1C1E);
            rim.setStyle(Paint.Style.STROKE);
            rim.setColor(0x26FFFFFF);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            scrim.setShader(new LinearGradient(0, h * 0.35f, 0, h, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP));
            words = new TextPaint(Ui.text(Ui.displayMedium, 44 * k, 0xFFFFFFFF));
            dim = new TextPaint(Ui.text(Ui.medium, 40 * k, 0x80FFFFFF));
            hint = new TextPaint(Ui.text(Ui.regular, 24 * k, 0x8CFFFFFF));
            hint.setTextAlign(Paint.Align.CENTER);
            rim.setStrokeWidth(1.5f * k);
            box.set(w / 2f - 760 * k, h - 250 * k, w / 2f + 760 * k, h - 110 * k);
        }

        @Override protected void onDraw(Canvas c) {
            float t = (SystemClock.uptimeMillis() - born) / 1000f;
            float in = Math.min(1, t / 0.25f);   // a quick fade and rise
            c.drawRect(0, 0, getWidth(), getHeight(), scrim);
            c.save();
            c.translate(0, (1 - in) * 30 * k);
            c.drawRoundRect(box, box.height() / 2, box.height() / 2, glass);
            c.drawRoundRect(box, box.height() / 2, box.height() / 2, rim);

            // The orb: breathes while waiting, swells with the voice, spins softly while thinking
            shown += (level - shown) * 0.3f;
            float cx = box.left + box.height() / 2 + 6 * k, cy = box.centerY();
            float base = 38 * k, r = base * (1 + 0.32f * shown + 0.05f * (float) Math.sin(t * 3));
            int a = state == FAILED ? 0xFF8E8E93 : 0xFF7B4DFF, b = state == FAILED ? 0xFF636366 : 0xFFFF7AB6;
            halo.setShader(new RadialGradient(cx, cy, r * 2.2f, (a & 0x00FFFFFF) | 0x66000000, a & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 2.2f, halo);
            float spin = state == THINKING ? t * 4 : t * 0.6f;
            orb.setShader(new LinearGradient(cx - r * (float) Math.cos(spin), cy - r * (float) Math.sin(spin),
                    cx + r * (float) Math.cos(spin), cy + r * (float) Math.sin(spin), a, b, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, orb);

            // The words (or what's happening)
            float x = cx + base * 2 + 24 * k, right = box.right - 50 * k, y = cy + Ui.halfCap(words);
            String line;
            TextPaint p;
            if (state == DONE || state == FAILED) {
                line = answer;
                p = state == FAILED ? dim : words;
            } else if (!heard.isEmpty()) {
                line = heard;
                p = words;
            } else {
                line = state == THINKING ? Ui.tr("Um momento…", "One moment…") : Ui.tr("Estou ouvindo…", "Listening…");
                p = dim;
            }
            // Long sentences keep their end in view, like a live caption
            String shownText = line;
            if (p.measureText(line) > right - x) {
                shownText = TextUtils.ellipsize(new StringBuilder(line).reverse(), p, right - x, TextUtils.TruncateAt.END).toString();
                shownText = new StringBuilder(shownText).reverse().toString();
            }
            c.drawText(shownText, x, y, p);
            c.restore();

            if (state == LISTENING) {
                c.drawText(Ui.tr("Experimente: «Abra a Netflix»  ·  «Filmes de ação»  ·  «Desligue em 30 minutos»",
                        "Try: “Open Netflix”  ·  “Action movies”  ·  “Turn off in 30 minutes”"),
                        getWidth() / 2f, box.bottom + 56 * k, hint);
            }
            postInvalidateOnAnimation();
        }
    }
}
