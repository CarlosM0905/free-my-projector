package io.freemytv;

import java.util.HashMap;
import java.util.Map;

/**
 * Spanish (Free My Projector). Keyed by the English text of each Ui.tr("pt", "en") call, so the upstream code only
 * needs Ui.tr to look here; a text missing from the table falls back to English.
 */
final class Es {
    private Es() {}

    private static final Map<String, String> T = new HashMap<>();

    private static void t(String en, String es) {
        T.put(en, es);
    }

    static {
        // Home screen and shortcuts
        t("Live TV", "TV en vivo");
        t("All Apps", "Todas las apps");
        t("Settings", "Ajustes");
        t("Home Settings", "Ajustes del inicio");
        t("TV Settings", "Ajustes TV");
        t("HDMI Inputs", "Entradas HDMI");
        t("Inputs", "Entradas");
        t("Shortcuts", "Atajos");
        t("Weather", "Clima");
        t("No weather data right now.", "Ahora no hay datos del clima.");
        t("Sunrise ", "Amanecer ");
        t("Sunset ", "Atardecer ");
        t("Crypto", "Cripto");
        t("24h change", "Cambio 24 h");
        t("Dollar", "Dólar");
        t("prices from ", "precios de ");
        t("Couldn't open ", "No se pudo abrir ");
        t("For You", "Para ti");
        t("Series", "Series");
        t("now", "ahora");

        // Projector controls (Projector.java)
        t("Keystone", "Keystone");
        t("Picture", "Imagen");
        t("Sound effects", "Sonido");
        t("Sleep timer", "Apagado");
        t("Audio only", "Solo audio");
        t("Preferences", "Preferencias");
        t("Language", "Idioma");
        t("City", "Ciudad");
        t("Temperature", "Temperatura");
        t("Theme", "Tema");
        t("Crypto prices", "Criptomonedas");
        t("Football team", "Equipo de fútbol");
        t("Automatic", "Automático");
        t("Dark", "Oscuro");
        t("Light theme", "Claro");
        t("None", "Ninguno");
        t("← → changes the value  ·  OK on City, Team and Key opens the keyboard", "← → cambia el valor  ·  OK en Ciudad, Equipo y Clave abre el teclado");
        t("City name", "Nombre de la ciudad");
        t("Team name", "Nombre del equipo");
        t("TMDB key", "Clave de TMDB");
        t("The API Key from themoviedb.org (32 letters and digits)", "La API Key de themoviedb.org (32 letras y números)");
        t("↓ to choose", "↓ para elegir");
        t("OK types  ·  \u2713 confirms  ·  Back leaves", "OK escribe  ·  \u2713 confirma  ·  Atrás sale");
        t("Search", "Buscar");
        t("Cancel", "Cancelar");
        t("Searching…", "Buscando…");
        t("No city with that name", "No hay ciudades con ese nombre");
        t("Couldn't search now", "No se pudo buscar ahora");
        t("Team name (empty = none)", "Nombre del equipo (vacío = ninguno)");
        t("NEXT EPISODE", "PRÓXIMO EPISODIO");
        t("NOW PLAYING", "AHORA SUENA");
        t("WEATHER", "CLIMA");
        t("Last: ", "Último: ");
        t("Last result", "Último resultado");
        t("at ", "en ");
        t("What should I watch?", "¿Qué veo hoy?");
        t("FOR TONIGHT", "PARA HOY");
        t("Another", "Otra");
        t("Excellent", "Excelente");
        t("Good", "Buena");
        t("Fair", "Regular");
        t("Weak", "Débil");
        t("No data", "Sin datos");
        t("link ", "enlace ");
        t("Today ", "Hoy ");
        t("Test ", "Prueba ");
        t("%s left", "faltan %s");
        t("S", "T");
        t("More like this", "Más como esta");
        t("Animated", "Animado");
        t("A movie or show picked at random from your apps", "Una película o serie al azar de tus apps");
        t("Cast: ", "Reparto: ");
        t("Watch on %s", "Ver en %s");
        t("Not available", "No disponible");
        t("Trailer", "Tráiler");
        t("1 season", "1 temporada");
        t(" seasons", " temporadas");
        t("When it ends", "Al terminar");
        t("Lamp", "Lámpara");
        t("Night", "Noche");
        t("Home design", "Diseño del inicio");
        t("Light", "Ligero");
        t("Background", "Fondo");
        t("Sharp", "Nítido");
        t("Blurred", "Desenfocado");
        t("Black", "Negro");
        t("Good morning", "Buenos días");
        t("Good afternoon", "Buenas tardes");
        t("Good evening", "Buenas noches");
        t("Classic", "Clásico");
        t("Up Next", "Continuar");
        t("Projector", "Proyector");
        t("Display", "Pantalla");
        t("Next landscape", "Siguiente paisaje");
        t("More apps", "Más apps");
        t("Recommended: ", "Recomendado: ");
        t("Signal", "Con señal");
        t("No signal", "Sin señal");
        t("On", "Activado");
        t("Off", "Desactivado");
        t("Focus", "Enfoque");
        t("Focusing…", "Enfocando…");
        t("Screen setup", "Pantalla");
        t("STEP %d OF 3", "PASO %d DE 3");
        t("OK: next  ·  ← →: adjust by hand  ·  Back: leave", "OK: siguiente  ·  ← →: ajustar a mano  ·  Atrás: salir");
        t("Picture shape", "Forma de la imagen");
        t("The edges should be straight, parallel to the wall's.", "Los bordes deben verse rectos, paralelos a los de la pared.");
        t("Looks right", "Está bien");
        t("Adjust corners", "Ajustar esquinas");
        t("Brightness", "Brillo");
        t("Normal by day, Eco in the evening, Night for a dark room (less heat and noise).", "Normal de día, Eco en la tarde-noche, Noche para un cuarto oscuro (menos calor y ruido).");
        t("Suggested now", "Sugerido ahora");
        t("All set", "Listo");
        t("OK to go back", "OK para volver");
        t("OK: autofocus  ·  ← →: manual  ·  Back: done", "OK: autoenfoque  ·  ← →: manual  ·  Atrás: terminar");
        t("← → to move · ↓ to hide · OK when done", "← → para mover · ↓ para ocultar · OK para terminar");
        t("%s is in All Apps now", "%s ahora está en Todas las apps");
        t("Back on the home screen", "De vuelta en el inicio");
        t("%s is on the home screen", "%s está en el inicio");
        t("The shelf is full: hide an app first (hold OK on it)", "El estante está lleno: oculta una app primero (mantén OK sobre ella)");
        t("New episode", "Episodio nuevo");
        t("New today", "Nuevo hoy");
        t("New on %s", "Nuevo el %s");
        t("The projector is running hot (%d °C). Make sure its vents aren't blocked.",
                "El proyector está muy caliente (%d °C). Revisa que las rejillas de ventilación estén libres.");
        t("Cinema mode", "Modo cine");
        t("The lamp turns off and the music keeps playing. Any button brings the picture back.",
                "La lámpara se apaga y la música sigue. Cualquier botón trae la imagen de vuelta.");
        t("Test pattern", "Patrón de prueba");
        t("Match frame rate", "Hz auto");
        t("Motion", "Movimiento");
        t("Screensaver style", "Estilo del protector");
        t("Landscapes", "Paisajes");
        t("Frame", "Marco");
        t("Recommendations", "Recomendaciones");
        t("H ", "Máx. ");
        t("L ", "Mín. ");
        t("Network", "Red");
        t("Excellent signal", "Señal excelente");
        t("Good signal", "Señal buena");
        t("Fair signal", "Señal regular");
        t("Weak signal", "Señal débil");
        t("Reduced", "Reducido");
        t("Full", "Completo");
        t("Movie or show", "Película o serie");
        t("Searching…", "Buscando…");
        t("Nothing on your apps.", "Nada en tus apps.");
        t("Enhance dialogue", "Diálogos");
        t("Reduce loud sounds", "Nivelar");
        t("Projector", "Proyector");
        t("Processor", "Procesador");
        t("Fan", "Ventilador");
        t("Lamp", "Lámpara");
        t("Panel", "Panel");

        // Switching the home screen (HomeMode)
        t("Free My Projector's home screen ran into a problem.\nGoing back to the TV's original home screen…",
                "El inicio de Free My Projector tuvo un problema.\nVolviendo al inicio original…");
        t("Measuring the original home screen…", "Midiendo el inicio original…");
        t("Switching the home screen and screensaver…", "Cambiando el inicio y el protector de pantalla…");
        t("Android didn't accept the new home screen", "Android no aceptó el nuevo inicio");
        t("Turning off apps (", "Desactivando apps (");
        t("Measuring Free My Projector…", "Midiendo Free My Projector…");
        t("Couldn't turn on Free My Projector: ", "No se pudo activar Free My Projector: ");
        t("Turning the apps back on…", "Reactivando las apps…");
        t("Turning apps back on (", "Reactivando apps (");
        t("Restoring the original home screen…", "Restaurando el inicio original…");
        t("Original mode: the factory home screen, screensaver and apps are back.",
                "Modo original: volvieron el inicio, el protector de pantalla y las apps de fábrica.");
        t("the original home screen", "el inicio original");
        t("Original mode, but I couldn't turn back on: ", "Modo original, pero no pude reactivar: ");
        t("Couldn't go back to the original: ", "No se pudo volver al original: ");
        t("Free My Projector's home screen crashed 3 times in a row; I switched back to the original one.",
                "El inicio de Free My Projector se cerró 3 veces seguidas; volví al original.");
        t("the home screen", "el inicio");
        t("the screensaver", "el protector de pantalla");
        t("Couldn't check Free My Projector: ", "No se pudo revisar Free My Projector: ");
        t("The TV update turned back on ", "La actualización del proyector reactivó ");
        t("Turned off what was missing: ", "Se desactivó lo que faltaba: ");
        t("The TV was updated; Free My Projector is still on.", "El proyector se actualizó; Free My Projector sigue activo.");
        t("Connecting…", "Conectando…");
        t("Accept “Allow USB debugging?” on the TV (tick “Always allow”).",
                "Acepta «¿Permitir la depuración USB?» en la pantalla (marca «Permitir siempre»).");

        // Settings screen
        t("Your TV's home screen and screensaver", "El inicio y el protector de pantalla del proyector");
        t("Original", "Original");
        t("Home Screen", "Inicio");
        t("Screensaver", "Protector de pantalla");
        t("Preview", "Vista previa");
        t("Starting…", "Iniciando…");
        t("On the TV: tick “Always allow” and press OK.", "En la pantalla: marca «Permitir siempre» y presiona OK.");
        t("RECOMMENDATIONS", "RECOMENDACIONES");
        t("No TMDB key: see the README (config.properties).", "Sin clave de TMDB: mira el README (config.properties).");
        t("Error: ", "Error: ");
        t("Fetching titles…", "Buscando títulos…");
        t("This product uses the TMDB API but is not endorsed or certified by TMDB.",
                "Este producto usa la API de TMDB, pero TMDB no lo respalda ni lo certifica.");
        t("COMPARISON", "COMPARACIÓN");
        t("Home button", "Botón Home");
        t("Free memory", "Memoria libre");
        t("Home screen memory", "Memoria del inicio");
        t("NOTICES", "AVISOS");
        t("None yet.", "Ninguno todavía.");
        t("No ads, with the new screensaver", "Sin anuncios, con el nuevo protector de pantalla");
        t("The TV's factory home screen", "El inicio de fábrica del proyector");
        t("Switching takes a few seconds; the screen blinks while it measures.",
                "El cambio tarda unos segundos; la pantalla parpadea mientras mide.");
    }

    /** The Spanish for an English text of Ui.tr, or the English one if it isn't in the table. */
    static String tr(String english) {
        String s = T.get(english);
        return s != null ? s : english;
    }

    static final String[] TABS = {"Explorar", "Películas", "Series", "A continuación", "Buscar"};
    static final String[] PERIOD_LABEL = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};

    /** TMDB returns these TV genres in English even when asked for Spanish. */
    static final String[][] GENRES = {
        {"Action & Adventure", "Acción y aventura"}, {"Sci-Fi & Fantasy", "Ciencia ficción y fantasía"},
        {"War & Politics", "Guerra y política"}, {"Kids", "Infantil"}, {"News", "Noticias"}, {"Soap", "Telenovela"},
        {"Talk", "Entrevistas"},
    };

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code == 0) return day ? "Despejado" : "Noche despejada";
        if (code == 1) return "Pocas nubes";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Llovizna";
        if (code == 65 || code == 67 || code == 82) return "Lluvia fuerte";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Nieve";
        if (code >= 95) return "Tormenta";
        return "Lluvia";
    }

    /** The notice after switching on (it has a number in the middle, so it can't be a table entry). */
    static String turnedOn(int apps) {
        return "Free My Projector activo: nuevo inicio y protector de pantalla, "
                + (apps == 1 ? "1 app desactivada." : apps + " apps desactivadas.");
    }

    /** "hace 12 min" / "hace 2 h". */
    static String ago(long minutes) {
        return minutes < 60 ? "hace " + minutes + " min" : "hace " + (minutes / 60) + " h";
    }
}
