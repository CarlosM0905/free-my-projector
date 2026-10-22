package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free My Projector: what a sentence said to the remote means. Spanish, English and Portuguese, without accents or
 * capitals: "abre Netflix", "apaga en 30 minutos", "modo cine", "sube el volumen", "pausa", "pon la letra"… Anything
 * else is a search (the Search tab: your apps, the projector's controls, then movies and shows in your apps).
 */
final class VoiceCommands {
    private VoiceCommands() {}

    interface Action { void run(Context c); }

    /** What will happen (shown for a moment: "Opening Netflix") and the action itself. */
    static final class Command {
        final String label;
        final Action action;

        Command(String label, Action action) {
            this.label = label;
            this.action = action;
        }

        void run(Context c) {
            try {
                action.run(c);
            } catch (RuntimeException e) {
                AppLog.w("voice: " + e.getMessage());
            }
        }
    }

    static String plain(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "");
        return n.replaceAll("[¿?¡!.,;:\"“”«»]", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean has(String text, String regex) {
        return Pattern.compile("(^| )(" + regex + ")( |$)").matcher(text).find();
    }

    private static final Pattern OPEN = Pattern.compile(
            "^(abre|abrir|abra|abreme|open|launch|start|inicia|pon|ponme|poner|ir a|ve a|vamos a|entra a|entra en|quiero ver|quiero escuchar|ver|mostrar|muestra) (el |la |los |las |a |o )?(app |aplicacion |aplicativo )?(de )?(.+)$");
    private static final Pattern SLEEP = Pattern.compile(
            "(apaga|apagar|apague|apagate|desliga|desligar|desligue|turn off|switch off|sleep|duerme|dormir|dorme|apagado).*?(en|in|em|dentro de|daqui a)? (\\d+|un|una|uno|media|one|half an|half|an|a|meia) ?(minutos?|min|horas?|hours?|minutes?|hora)");

    static Command parse(Context c, String said) {
        String t = plain(said);

        // Sleep timer: "apaga en 30 minutos", "turn off in an hour", "desliga em meia hora"
        Matcher m = SLEEP.matcher(t);
        if (m.find()) {
            String n = m.group(3), unit = m.group(4);
            boolean half = n.startsWith("media") || n.startsWith("half") || n.startsWith("meia");
            int amount = n.matches("\\d+") ? Integer.parseInt(n) : 1;
            int minutes = half ? 30 : unit.startsWith("h") ? amount * 60 : amount;
            if (minutes > 0 && minutes <= 600) {
                final long at = System.currentTimeMillis() + minutes * 60_000L;
                return new Command(String.format(Ui.tr("O projetor vai dormir às %s", "The projector will sleep at %s"), Ui.clock(at)),
                        ctx -> Heat.sleepAt(ctx, at));
            }
        }
        if (has(t, "cancela el apagado|cancelar el apagado|no apagues|cancel the sleep timer|cancel sleep|cancela o desligamento")) {
            return new Command(Ui.tr("Desligamento cancelado", "Sleep timer off"), ctx -> Heat.sleepAt(ctx, 0));
        }

        // Scenes and the lamp
        if (has(t, "modo cine|modo cinema|cinema mode|movie mode|escena cine")) return scene("cine", Ui.tr("Cena: Cinema", "Scene: Cinema"));
        if (has(t, "modo deportes|modo deporte|sports mode|modo esportes|escena deportes")) return scene("sport", Ui.tr("Cena: Esportes", "Scene: Sports"));
        if (has(t, "modo musica|music mode|escena musica")) return scene("music", Ui.tr("Cena: Música", "Scene: Music"));
        if (has(t, "modo normal|quita la escena|sin escena|scene off|normal mode|tira a cena")) return scene("off", Ui.tr("Sem cena", "No scene"));
        if (has(t, "solo audio|solo el audio|audio only|so audio|apaga la lampara|apaga la pantalla|turn off the screen|desliga a tela")) {
            return new Command(Ui.tr("Só áudio", "Audio only"), ctx -> start(ctx, new Intent(ctx, AudioOnly.class)));
        }

        // Lyrics, screensaver
        if (has(t, "letra|letras|lyrics|la letra de la cancion|letra da musica")) {
            return new Command(Ui.tr("Letra", "Lyrics"), ctx -> start(ctx, new Intent(ctx, Lyrics.class)));
        }
        if (has(t, "protector|protector de pantalla|screensaver|screen saver|descanso de tela|tela de descanso")) {
            return new Command(Ui.tr("Proteção de tela", "Screensaver"), HomeView::startScreensaver);
        }

        // Volume
        if (has(t, "sube el volumen|subele|mas volumen|mas alto|volume up|louder|turn it up|aumenta o volume|aumentar volumen|sube volumen")) {
            return new Command(Ui.tr("Mais volume", "Volume up"), ctx -> volume(ctx, AudioManager.ADJUST_RAISE, 2));
        }
        if (has(t, "baja el volumen|bajale|menos volumen|mas bajo|volume down|quieter|turn it down|abaixa o volume|baja volumen")) {
            return new Command(Ui.tr("Menos volume", "Volume down"), ctx -> volume(ctx, AudioManager.ADJUST_LOWER, 2));
        }
        if (has(t, "silencio|silencia|mute|sin sonido|sem som|quita el sonido")) {
            return new Command(Ui.tr("Silêncio", "Mute"), ctx -> volume(ctx, AudioManager.ADJUST_TOGGLE_MUTE, 1));
        }

        // Playback: whatever is playing
        if (t.matches("(pausa|pausar|pause|para|detente|deten|stop|pare)( la musica| el video| la cancion| it)?")) {
            return new Command(Ui.tr("Pausa", "Pause"), ctx -> media(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE));
        }
        if (t.matches("(reproduce|reproducir|play|continua|continuar|sigue|seguir|resume|tocar|toca|dale play)( la musica| el video| la cancion| it)?")) {
            return new Command(Ui.tr("Reproduzir", "Play"), ctx -> media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY));
        }
        if (has(t, "siguiente cancion|siguiente|next song|next|salta|skip|proxima|proxima musica")) {
            return new Command(Ui.tr("Próxima", "Next"), ctx -> media(ctx, KeyEvent.KEYCODE_MEDIA_NEXT));
        }
        if (has(t, "anterior|cancion anterior|previous|previous song|volver a la anterior")) {
            return new Command(Ui.tr("Anterior", "Previous"), ctx -> media(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        }

        // Home
        if (t.matches("(inicio|ir al inicio|ve al inicio|home|go home|pantalla de inicio|tela inicial)")) {
            return new Command(Ui.tr("Início", "Home"), VoiceCommands::home);
        }

        // An app: "abre Netflix", or just its name ("Spotify")
        m = OPEN.matcher(t);
        String wanted = m.find() ? m.group(5) : t;
        ResolveInfo app = app(c, wanted);
        if (app != null) {
            final String pkg = app.activityInfo.packageName;
            String name = String.valueOf(app.loadLabel(c.getPackageManager()));
            return new Command(String.format(Ui.tr("Abrindo %s", "Opening %s"), name), ctx -> {
                PackageManager pm = ctx.getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i != null) start(ctx, i);
            });
        }

        // Anything else: a search, in the Search tab
        final String query = said.trim();
        return new Command(String.format(Ui.tr("Buscando “%s”", "Searching for “%s”"), query), ctx -> start(ctx,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setClassName(ctx.getPackageName(), ctx.getPackageName() + ".HomeActivity")
                        .putExtra(HomeActivity.EXTRA_SEARCH, query)));
    }

    /** The TV app whose name matches best (the whole name, or a name that starts the sentence: "spotify por favor"). */
    private static ResolveInfo app(Context c, String wanted) {
        String w = plain(wanted).replaceAll("^(el|la|a|o) ", "");
        if (w.isEmpty()) return null;
        PackageManager pm = c.getPackageManager();
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (ri.activityInfo.packageName.equals(c.getPackageName())) continue;
            String name = plain(String.valueOf(ri.loadLabel(pm)));
            String compact = name.replace(" ", ""), wc = w.replace(" ", "");
            int score = wc.equals(compact) ? 3 : (wc.startsWith(compact) || compact.startsWith(wc)) && wc.length() >= 3 ? 2 : 0;
            if (score > bestScore) {
                bestScore = score;
                best = ri;
            }
        }
        return best;
    }

    private static Command scene(String next, String label) {
        return new Command(label, ctx -> Projector.setScene(ctx, next));
    }

    private static void start(Context c, Intent i) {
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void home(Context c) {
        start(c, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(c.getPackageName(), c.getPackageName() + ".HomeActivity"));
    }

    private static void volume(Context c, int direction, int steps) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        for (int i = 0; i < steps; i++) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    private static void media(Context c, int key) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
    }
}
