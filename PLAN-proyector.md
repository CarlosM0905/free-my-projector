# Plan — Fork de Free My TV para proyectores (base: Wanbo T6 Max, Android 9+)

## Qué es el original
[simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv) (MIT, 167 ★, activo): pantalla de inicio para Google TV sin anuncios. Java puro sin Gradle ni librerías (`build.sh`: aapt2 → javac → d8 → zipalign → apksigner, APK de 2 MB), dibujado a mano en un Canvas. 7.664 líneas en 23 archivos.

Trae: estante de apps "de vidrio", recomendaciones de TMDB solo de tus apps (por región), clima y amanecer/atardecer (Open-Meteo), precios cripto (Binance), tema claro/oscuro según la puesta de sol, protector de pantalla con reloj, y cambio entre "Free My TV" y "Original" usando el ADB del propio TV (`127.0.0.1:5555`) con watchdog que vuelve al inicio de fábrica si falla 3 veces.

## Análisis de compatibilidad con Android 9 (medido, no estimado)
Compilé el código contra Android 14 y crucé cada clase/método de Android que usa con `api-versions.xml` del SDK. **Solo 2 APIs no existen en Android 9**, y en 3 archivos:

| API | Desde | Dónde | Qué hace | Reemplazo en Android 9 |
|---|---|---|---|---|
| `RuntimeShader` (AGSL) | 13 | `Shaders`, `Scene`, `DreamView` | Vidrio esmerilado y cielo animado | Ya tiene respaldo propio ("gradiente y vidrio translúcido plano", `Scene.java:20`); se elige por versión en vez de por excepción |
| `RenderNode` / `RecordingCanvas` | 10 | `Scene`, `DreamView` | Caché de capas en GPU | Dibujar directo en el Canvas o en `Bitmap` cacheado (Android 9 sí tiene `View.setLayerType(HARDWARE)`) |

Todo lo demás (Adb, HomeMode, HomeApps, Recs, HomeData, Settings, Watchdog, Dream) usa APIs de Android 9 o anteriores. `build.sh` fija `--min-api 31`: se baja a 28.

## Diferencias del proyector que hay que adaptar
| Tema | Original (Google TV / TCL) | Fork (Wanbo T6 Max) |
|---|---|---|
| ADB interno | `127.0.0.1:5555` con llave y aviso "Permitir depuración" | Wanbo trae ADB en **:3333 sin autenticación**; puerto configurable y detectar ambos |
| Listas DEBLOAT / PROTECTED | TCL y Google TV | Wanbo/Amlogic: proteger `com.luxcine_3` (keystone + térmica), `com.droidlogic.*`, `com.android.tv.settings`, Puente, Atajos; no desactivar nada que ya no esté en la ROM |
| Estante (SHELF) | Netflix, YouTube TV, Max, Hulu… | SmartTube, Disney+, Crunchyroll, Prime, Netflix, Twitch, Kodi, Spotify, AuraMusic, TV Bro (paquetes reales del proyector) |
| Entradas | Antena/HDMI de TV | HDMI del proyector (droidlogic tvinput), si aplica |
| Botón Home | `set-home-activity` | Ya funciona: parche v3 de `services.jar` abre el launcher predeterminado |
| Idioma | en / pt | Agregar **es** |
| Clima/región | Nueva York, US | Lima, PE (`tmdb_region=PE`) |
| Rendimiento | Chip de TV moderno | T972 (Mali-G31 MP2, 2 GB RAM): medir FPS y memoria; sin shaders; reducir redibujos; respetar térmica |

## Lo propio de proyector (el valor del fork)
Panel "Proyector" en el cajón lateral, reutilizando lo que ya construimos:
- **Imagen**: keystone/4 esquinas, modo de imagen (panel oculto de Wanbo vía Puente), patrón de calibración, recordatorio de enfoque (botones ⟳ ⟲).
- **Sonido**: efectos de Amlogic (vía Puente).
- **Estado del equipo**: temperatura del SoC y del LED, velocidad del ventilador, Hz del panel (AFR), horas de lámpara si se puede leer.
- **Temporizador de apagado** (Puente).
- Se cambia la cripto por algo útil en proyector (o se deja opcional).

## Fases
1. **Fork y compilar en Android 9** — fork en GitHub, `build.ps1`/WSL, `--min-api 28`, respaldo sin `RuntimeShader`/`RenderNode`, instalar junto a Projectivy (sin ser Home todavía). Medir FPS/memoria/temperatura.
2. **Adaptación Wanbo** — ADB :3333, listas PROTECTED/DEBLOAT/SHELF/HIDDEN, idioma español, config Lima/PE, clave TMDB de Carlos.
3. **Panel Proyector** — imagen, sonido, estado térmico/AFR, temporizador.
4. **Prueba como Home** — `HomeMode` cambia a Free My Projector; watchdog vuelve a Projectivy si falla; Carlos lo usa unos días. Projectivy queda instalado como respaldo.
5. **ROM v5** — integrarlo en `/system/app` si convence; README del fork y crédito al original (MIT).

## Riesgos
- Rendimiento del Canvas a 1080p en el Mali-G31 (Spotify ya mostró el límite térmico del GPU).
- Perder la configuración de Projectivy: no se toca; el fork se prueba aparte y el cambio de Home es reversible con un botón.
- TMDB necesita una clave gratuita de Carlos (themoviedb.org).
