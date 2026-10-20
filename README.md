<div align="center">

<img src="docs/assets/icon-512.png" width="96" alt="">

# Free My Projector

**English** · [Español](README.es.md)

**A fast, ad-free home screen for projectors running Android 9 or later.**

A fork of [Free My TV](https://github.com/simbasjungles/free-my-tv) adapted to projectors, tested on a Wanbo T6 Max (Amlogic T972, Android 9).<br>
The original project's README is in [README-original.md](README-original.md).

</div>

<p align="center"><img src="docs/screenshots/home.jpg" alt="The home screen, classic design"></p>

## What it does

- **Two designs** (Home Settings → Home design):
  - **Classic**: the tvOS one, with glass, cards and a shelf.
  - **Light**: a list of names by section (Apps, Up Next, Inputs, Projector, Display, More apps). The focused one grows, with its state underneath ("Recommended: …", "No signal", "On"); on the right, the recommended photo with the app's card; a greeting and the weather at the top, the date and a large clock at the bottom. No glass and no live blur: the smoothest option on a projector. ↑ ↓ move through it, ← → jump between sections, ≡ goes straight to Projector. **Background ‹ Sharp · Blurred · Black ›** changes with ← →, and **Next landscape** switches to another photo.
- **Your apps on a shelf.** SmartTube, Disney+, Crunchyroll, Netflix, Prime Video, AuraMusic, Twitch, Kodi, Spotify… whichever you have installed.
  - **Edit it like tvOS:** hold OK on an app and it starts to jiggle; ← → moves it, ↓ hides it (it goes to All Apps) and OK finishes. To bring a hidden app back, hold OK on it in All Apps.
- **Recommendations from your apps.** This week's trending titles and new releases, only from the services you have, in your country and language (TMDB). Picking one opens **the movie or show itself** inside its app (Disney+, Netflix, Prime Video, Crunchyroll), using the IDs kept by Wikidata; if there's none, the app opens.
- **Top Shelf**, like tvOS: when you pass over Disney+, Netflix, Prime Video or Crunchyroll on the shelf, the cards above show that app's picks; go up and open one directly.
- **Up Next**, like tvOS: a tab with what you last opened from the home screen, to pick it up again. It appears once you open your first title. Shows get a **New episode** badge when one comes out after you watched (or "New on Friday" when it's less than a week away).
- **Control Center** like tvOS: opens with the remote's ≡ button (or by going down to the drawer). The shortcuts row scrolls sideways when it doesn't fit.
  - Shortcuts to **Keystone**, **Picture** (mode, color, sharpness, noise reduction), **Sound** (equalizer, DTS, VirtualX) and **Sleep timer** (shows the minutes left).
  - **Focus**: a screen with fine text in the center and the corners. **OK = autofocus** (the projector's camera looks at the picture and sets the lens in about 5 s), ← → move the lens by hand (hold to keep going) and Back finishes.
  - **Audio only**: the lamp turns off and the music keeps playing (Spotify, AuraMusic, podcasts). Less heat and noise, and a longer-lasting LED. Any button, volume included, brings the picture back.
  - Switches that apply right away and light up in blue: **Auto Hz** (the panel follows the video's frame rate), **Dialogue** (clearer voices) and **Leveler** (no sudden volume jumps).
  - **Lamp**: Normal → **Eco** (≈75 %) → **Night** (≈50 %). It only ever dims: if the projector is hot and Wanbo has already dimmed it further, Wanbo wins. Normal hands the lamp back. There's no "Max" because it would overdrive the LED.
  - **When it ends** *(in testing)*: turns the projector off when whatever you're watching or listening to finishes (three minutes with nothing playing), then switches itself off. If the app moves on to the next episode by itself, it keeps waiting.
  - **Cinema mode**, one button for a movie: turns on Auto Hz and Dialogue and turns off Leveler (it flattens the soundtrack). Turning it off puts everything back as it was.
  - A **device status** card: processor temperature, fan, lamp with its **hours of use** (counted since the app was installed) and the panel's refresh rate (you'll see it switch to 48 or 50 Hz with AFR).
  - **Heat warning**: if the processor goes over 82 °C, a notice appears over any app so you can check the vents (at most once every half hour).
- Hourly **weather**, sunrise and sunset, and **crypto prices**.
- **Landscape screensaver**, like the Apple TV and Mac aerials: a full-screen landscape photo every minute (Bing's photos of the day, with the place's name) and a glass box with the time, date, weather and what's playing. Also as a **frame**, like The Frame's Art Mode, or with the recommendations' artwork. Choose it in Home Settings; it starts right away with **Back** on the home screen, like the Apple TV.
- **Soft navigation sounds**, like tvOS: a tick when you move, two notes when opening and going back.
- **Dark, light or automatic mode** (light by day, dark from sunset), and a **screensaver** with the artwork and a large clock.
- **Keystone button** at the top right instead of *Live TV* (projectors have no antenna).
- **Search**, like tvOS: a tab with a one-row keyboard; it shows only movies and shows that are in your apps and opens them directly. The screensaver's landscape of the day sits behind it.
- **Now Playing**, like tvOS: while Spotify, AuraMusic or another app is playing, the song and artist show at the top right instead of the prices.
- **Color glow and network button** (ideas from [Clean Your TV](https://github.com/Gathin23/cleanyourtv)): passing over a shelf app brings up a soft glow in its color, and a button at the top shows the Wi-Fi and signal.
- **Reduce motion** in Home Settings, for projectors with a weak GPU: focus and backgrounds change without animating.
- **Low memory use:** while you're in another app, the home screen lets go of the photos you can't see.
- **Automatic updates** for SmartTube, SmartTwitchTV and AuraMusic: once a day, while you're on the home screen, it downloads the latest stable release from their official GitHub and installs it only if it's newer and signed by the same author as the one you have. A short message lets you know.
- **One button to go back.** *Original* restores your previous home screen (Projectivy, for example), your screensaver and your apps as they were.

## Screenshots

<table>
<tr>
<td width="50%"><img src="docs/screenshots/top-shelf.jpg" alt="Top Shelf: focusing Disney+ shows its picks"><br><sub>Top Shelf: focusing Disney+ shows its picks</sub></td>
<td width="50%"><img src="docs/screenshots/up-next.jpg" alt="Up Next: pick up where you left off"><br><sub>Up Next: pick up where you left off</sub></td>
</tr>
<tr>
<td width="50%"><img src="docs/screenshots/control-center.jpg" alt="Control Center: projector controls and device status"><br><sub>Control Center: projector controls and device status</sub></td>
<td width="50%"><img src="docs/screenshots/screensaver.jpg" alt="Landscape screensaver with time, weather and the place"><br><sub>Landscape screensaver with time, weather and the place</sub></td>
</tr>
<tr>
<td width="50%"><img src="docs/screenshots/light.jpg" alt="Light design: a list with the recommended photo"><br><sub>Light design: a list with the recommended photo</sub></td>
<td width="50%"><img src="docs/screenshots/light-projector.jpg" alt="Light design: projector controls with values (‹ Normal ›)"><br><sub>Light design: projector controls with values (‹ Normal ›)</sub></td>
</tr>
</table>

## What's different from the original

| | Free My TV | Free My Projector |
|---|---|---|
| Android | 12 or later | **9 or later** |
| Languages | English, Portuguese | **+ Spanish** |
| Glass effects | Android 13 shaders | flat glass on Android 9–12 (like the original on Android 12) |
| Performance | modern TV chips | **light mode** on Android < 12: no blurred shadows (on the T972 it went from 46 to 7 ms per frame) |
| Internal ADB | `127.0.0.1:5555` | also `:3333`, the one Wanbo projectors ship open |
| Finding the previous home | HOME role (Android 10+) | also on Android 9, which has no roles |
| Going back to *Original* | re-enables the home screen, setup wizard and screensaver | **leaves off what was already off** and restores the screensaver you had chosen |
| Shelf apps | Google TV builds | also the phone builds projectors use |
| Building on Windows | — | `build.sh` works in Git Bash |

## Install

You need a computer on the same Wi-Fi as the projector, and **network debugging** turned on in the projector (Wanbo projectors ship with it open on port 3333).

### With Claude Code

1. Clone the repository:
   ```bash
   git clone <this-repository-url> free-my-projector && cd free-my-projector
   ```
2. Open [Claude Code](https://claude.com/claude-code) in that folder and tell it, for example:
   > Install Free My Projector on my projector at 192.168.1.50. I live in Madrid.

### By hand

1. **Tools:** a JDK 17+, the Android SDK (build-tools 34+, platform android-34) and adb. On Windows use Git Bash; if your JDK is Android Studio's:
   ```bash
   export JAVA_HOME='/c/Program Files/Android/Android Studio/jbr'
   ```
2. **Build:**
   ```bash
   bash build.sh
   ```
   The first run creates `config.properties`: put your city, country and TMDB key there, and build again.
3. **Install:**
   ```bash
   adb connect 192.168.1.50:3333
   adb install -r FreeMyTV.apk
   ```
4. **On the projector:** open *Free My Projector* → *Home Screen* → *Free My Projector*. Wait a minute (it measures and switches) and press Home.

## Configuration

Everything is in `config.properties` (not committed to git):

| Key | What for | Example |
|---|---|---|
| `tmdb_key` | Your free TMDB key, for the recommendations | — |
| `tmdb_region` | Your country (two letters): which title is on which app | `ES` |
| `latitude`, `longitude` | Your city, for the weather and sunset | `40.4168`, `-3.7038` |
| `city` | The name on the weather card | `Madrid` |
| `language` | `auto` (follows the projector), `es`, `en` or `pt` | `en` |
| `currency` | Currency for the crypto prices | `USD` |
| `temperature` | `C`, `F` or `auto` | `C` |
| `theme` | `auto` (light by day, dark at night), `dark` or `light` | `dark` |

**The TMDB key:** create a free account at [themoviedb.org](https://www.themoviedb.org/signup), go to [Settings → API](https://www.themoviedb.org/settings/api) and request a developer key. Paste the *API Key* after `tmdb_key=`.

## Going back

On the projector: *Free My Projector* → *Home Screen* → *Original*. Your previous home screen, screensaver and apps come back as they were. If the new home screen crashes three times in five minutes, it goes back by itself.

To remove it completely: first *Original*, then uninstall the app.

## For Wanbo projectors with a modified ROM

The **Picture** and **Sound effects** shortcuts open hidden Amlogic screens that aren't exported. They open through *Puente Wanbo*, a small app signed with the platform key that comes with that ROM. On other projectors those two shortcuts simply don't appear; everything else works the same.

## Credits

[Free My TV](https://github.com/simbasjungles/free-my-tv) by simbasjungles (MIT license). This fork keeps the same license and brings in the original's changes when possible.

This product uses the TMDB API but is not endorsed or certified by TMDB.
