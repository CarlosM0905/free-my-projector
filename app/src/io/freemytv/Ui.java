package io.freemytv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fonts and text for the home screen, the screensaver and the settings. Font: Inter (variable, assets/fonts, SIL OFL),
 * with the "display" optical size for large text and the "text" size for small text; without it, the system font at a
 * similar weight.
 */
final class Ui {
    private Ui() {}

    /** Light drawing for projectors and old TV boxes (Android < 12): no blurred shadows, which their GPUs can't keep up with. */
    static final boolean LITE = android.os.Build.VERSION.SDK_INT < 31;
    /**
     * The language in use: "en", "es", "pt" (built in) or one with a catalogue in assets/i18n (fr, de, ru, zh-CN, ja, tr,
     * id), picked in Preferences or, with "auto", the TV's own (English when there's none for it).
     */
    static final String LANG = language();
    /** Spanish (Free My Projector): config.properties "language=es", or "auto" with the TV in Spanish. */
    static volatile boolean es = LANG.equals("es");
    /** English: the code's own texts. */
    static volatile boolean en = LANG.equals("en");
    /** Portuguese: the first text of each Ui.tr("pt", "en"), as upstream wrote them. */
    static final boolean pt = LANG.equals("pt");
    /**
     * The non-English locale, for dates and numbers: Spanish (Peru), Portuguese (Brazil) or the catalogue's language.
     * The upstream "en ? US : PT" branches work for every language unchanged.
     */
    static final Locale PT = es ? new Locale("es", "PE") : pt || en ? new Locale("pt", "BR") : Locale.forLanguageTag(LANG);
    /** The languages with a catalogue in assets/i18n, keyed by the English text in the code. */
    static final String[] CATALOGUES = {"fr", "de", "ru", "zh-CN", "ja", "tr", "id"};
    private static volatile java.util.Map<String, String> catalogue;
    static Typeface thin, light, displayMedium, displaySemibold, regular, medium, semibold;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getAssets();
        thin = font(a, 32, 200);
        light = font(a, 32, 300);
        displayMedium = font(a, 32, 500);
        displaySemibold = font(a, 32, 600);
        regular = font(a, 14, 400);
        medium = font(a, 14, 500);
        semibold = font(a, 14, 600);
    }

    /** Inter at an optical size (14 = text, 32 = display) and weight; the system font if the file is missing. */
    private static Typeface font(AssetManager a, int opticalSize, int weight) {
        if (has(a, FONT)) {
            try {
                Typeface t = new Typeface.Builder(a, FONT)
                        .setFontVariationSettings("'opsz' " + opticalSize + ", 'wght' " + weight).build();
                if (t != null) return t;
            } catch (RuntimeException ignored) {
            }
        }
        return Typeface.create(Typeface.DEFAULT, weight, false);
    }

    private static final String FONT = "fonts/InterVariable.ttf";

    /** Preferences' language, or with "auto" the TV's own if there are texts for it; English otherwise. */
    private static String language() {
        String lang = Prefs.language();   // picked on the projector, or config.properties
        if (!lang.equals("auto")) return lang;
        Locale l = Locale.getDefault();
        String tv = l.getLanguage();
        if (tv.equals("es") || tv.equals("pt") || tv.equals("en")) return tv;
        for (String c : CATALOGUES) if (c.equals(tv) || c.equals(tv + "-" + l.getCountry())) return c;
        return tv.equals("zh") ? "zh-CN" : "en";
    }

    /** Native names, for Preferences. */
    static String languageName(String code) {
        switch (code) {
            case "es": return "Español";
            case "en": return "English";
            case "pt": return "Português";
            case "fr": return "Français";
            case "de": return "Deutsch";
            case "ru": return "Русский";
            case "zh-CN": return "中文";
            case "ja": return "日本語";
            case "tr": return "Türkçe";
            case "id": return "Bahasa Indonesia";
            default: return tr("Automático", "Automatic");
        }
    }

    /**
     * BitmapShader.setFilterMode is Android 13+; before that the Paint's FILTER_BITMAP_FLAG decides, so on Android 9–12
     * (projectors) this does nothing instead of crashing the screensaver.
     */
    static void filter(android.graphics.BitmapShader s, int mode) {
        if (android.os.Build.VERSION.SDK_INT >= 33) s.setFilterMode(mode);
    }


    private static boolean has(AssetManager a, String path) {
        try (InputStream in = a.open(path)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Fixed-width digits (the clock and the prices don't jitter when a digit changes). */
    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    /** Height of capital letters (and digits) above the baseline. */
    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    static String tr(String pt, String english) {
        return en ? english : es ? Es.tr(english) : Ui.pt ? pt : fromCatalogue(english);
    }

    /** A text that only exists in English in the code (tables, weather): translated, or as it is. */
    static String t(String english) {
        return en || pt ? english : es ? Es.tr(english) : fromCatalogue(english);
    }

    /** Ui.t for each text of an English table. */
    static String[] trAll(String[] english) {
        String[] out = new String[english.length];
        for (int i = 0; i < english.length; i++) out[i] = t(english[i]);
        return out;
    }

    /** The catalogue's text (assets/i18n/<lang>.json), read once; English when it's missing. */
    private static String fromCatalogue(String english) {
        java.util.Map<String, String> c = catalogue;
        if (c == null) c = loadCatalogue();
        String s = c.get(english);
        return s == null || s.isEmpty() ? english : s;
    }

    private static synchronized java.util.Map<String, String> loadCatalogue() {
        if (catalogue != null) return catalogue;
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (App.context != null) {
            try (InputStream in = App.context.getAssets().open("i18n/" + LANG + ".json")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                org.json.JSONObject o = new org.json.JSONObject(out.toString("UTF-8"));
                java.util.Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    m.put(k, o.optString(k));
                }
            } catch (IOException | org.json.JSONException e) {
                AppLog.w("i18n " + LANG + ": " + e.getMessage());
            }
            catalogue = m;   // only cached once the context could be read
        }
        return m;
    }

    /** "21:47" (in English, "9:47", like the iPhone in the US). */
    static String clock(long now) {
        return en ? new SimpleDateFormat("h:mm", Locale.US).format(new Date(now))
                : new SimpleDateFormat("HH:mm", PT).format(new Date(now));
    }

    /** "5:21" (sunrise and sunset; in English, "5:21 AM"). */
    static String time(long at) {
        return en ? new SimpleDateFormat("h:mm a", Locale.US).format(new Date(at))
                : new SimpleDateFormat("H:mm", PT).format(new Date(at));
    }

    /** "domingo, 18 de outubro" (lowercase, like the iPhone in Portuguese); "Sunday, October 18" in English. */
    static String date(long now) {
        if (en) return new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date(now));
        if (es || pt) return new SimpleDateFormat("EEEE, d 'de' MMMM", PT).format(new Date(now));
        // Other languages: their own order ("jeudi 22 octobre", "10月22日 木曜日")
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(PT, "EEEEdMMMM");
        return new SimpleDateFormat(pattern, PT).format(new Date(now));
    }

    /** "Máx. 25°  Mín. 18°" ("H:25°  L:18°" in English). */
    static String highLow(String max, String min, String gap) {
        if (en) return "H:" + max + gap + "L:" + min;
        if (es || pt) return "Máx. " + max + gap + "Mín. " + min;
        return t("Max. ") + max + gap + t("Min. ") + min;
    }

    /** Spinning arc (work in progress). */
    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke);
    }
}
