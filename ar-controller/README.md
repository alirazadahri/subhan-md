# AR Controller — AI-powered floating mobile controller (Android)

A native Android app (Kotlin) with a web-style UI (HTML/CSS in `assets/ui`, the Capacitor-style
approach) talking to native code through a JavaScript bridge. Say **"Hey AR"** from anywhere or tap
the assistant orb at the bottom centre; tap the floating photo icon to open the Control Panel in place;
**All apps** → tap an app to open it as a floating, resizable panel.

> Android only. iOS doesn't allow overlays over other apps, Accessibility control, or launching other
> apps in windows, so this cannot be built for iPhone.

## What's inside

```
ar-controller/
├── settings.gradle.kts, build.gradle.kts, gradlew      # standalone Gradle project (AGP 9, built-in Kotlin)
└── app/
    ├── build.gradle.kts                                # deps: androidx.core, hiddenapibypass
    └── src/main/
        ├── AndroidManifest.xml                         # permissions, <queries>, services
        ├── assets/ui/
        │   ├── index.html      # main app: hero, permissions, floating setup, AI engine, try-it, assistant orb
        │   ├── panel.html      # floating Control Panel: media, toggles, navigation, quick-open, All apps grid
        │   ├── assistant.html  # Alexa-style assistant sheet (light bar, live transcript, reply, chips)
        │   ├── style.css       # shared design system
        │   ├── bridge.js       # promise wrapper around window.ARNative (mock data in a desktop browser)
        │   ├── icons.js        # Lucide icons (ISC), inlined SVG
        │   └── avatar.jpg      # app logo / assistant photo
        ├── res/                # launcher icons, floating icon image, vector icons, accessibility config
        └── java/com/arcontroller/
            ├── MainActivity.kt          # hosts index.html, runtime permission dialogs, in-app voice
            ├── Bridge.kt                # @JavascriptInterface API shared by all HTML screens
            ├── OverlayService.kt        # floating icon, panel window, bottom orb + assistant sheet, voice
            ├── FloatingAppLauncher.kt   # freeform launch / floating web panel / no full-screen fallback
            ├── FloatingWebWindow.kt     # draggable, corner-resizable floating panel with size presets
            ├── VoiceAssistant.kt        # "Hey AR" wake word, speech-to-text, text-to-speech
            ├── AIBrain.kt               # local parser first, LLM (Groq/OpenAI-compatible/free) when needed
            ├── CommandExecutor.kt       # apps, calls, SMS, WhatsApp, torch, media, brightness, DND, …
            ├── ScreenAgent.kt           # multi-step "do anything" agent over Accessibility
            ├── ARAccessibilityService.kt
            ├── AppRepository.kt, Prefs.kt, CrashReporter.kt, MainApplication.kt
```

## Build

Requirements: JDK 17 and the Android SDK (platform 37).

```bash
cd ar-controller
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk (~2 MB, all CPUs)
```

Release builds are signed with the public React Native debug key in `app/debug.keystore` so they
install over earlier AR Controller releases. Use your own keystore before publishing anywhere.

The UI can be previewed in a desktop browser: open `app/src/main/assets/ui/index.html` (mock data).

## First-time setup on the phone

| Permission | Why |
|---|---|
| Display over other apps | floating icon, panel, assistant |
| Microphone | "Hey AR" |
| Accessibility | home/back/recents, lock, screenshot, tap/type/scroll, agent |
| Contacts + Phone | "Ali ko call karo" |
| Notifications | keeps the service alive |
| Modify system settings (optional) | brightness, auto-rotate |
| Do Not Disturb access (optional) | DND, silent mode |

**Android 13+ sideloaded APKs:** Accessibility shows *"Restricted setting"*. Open App info → menu →
**Allow restricted settings**, then enable it again (the app links there).

AI engine: **Groq** (free key, fast, recommended), **Free** (no key, ~1 answer / 45 s) or **Custom**
(any OpenAI-compatible API). Simple commands never wait for the AI: the local parser handles them.

## Using it

- **Floating icon (your photo)**: drag anywhere, it snaps to the edge. Tap → the Control Panel opens
  in place. Ring colour = voice state (violet idle, green listening, amber thinking, blue speaking).
- **Control Panel**: ask bar, media controls, quick toggles (torch, Wi-Fi, Bluetooth, data, rotate, DND,
  ringer, stay awake), brightness/volume, navigation (home, back, recents, notifications, quick settings,
  screenshot, lock, power), quick-open (camera, dialer, messages, contacts, gallery, calculator, calendar,
  clock, maps, music, browser, settings), web window, close windows, read screen, scroll.
- **All apps**: search + grid. **Tap = floating panel**, hold = full screen.
- **Assistant orb** (bottom centre): tap to talk; "Hey AR" also opens the assistant sheet.

## How the floating app window works (native details)

Android has **no public API to draw another app's UI inside your own window** — each app renders
into surfaces owned by the system. So `FloatingAppLauncher` asks the system window manager to do it:

1. **Freeform mode (used first).** `ActivityOptions.makeBasic().setLaunchBounds(rect)` with a centred
   `Rect`, plus `FLAG_ACTIVITY_NEW_TASK`. When freeform windowing is enabled, the system's launch-params
   logic puts a task launched with bounds into a resizable floating window. We also call the hidden
   `setLaunchWindowingMode(WINDOWING_MODE_FREEFORM = 5)` by reflection; where non-SDK APIs are blocked
   it's ignored and the bounds alone are used.
   - Enable on stock Android 10+: Developer options → **Enable freeform windows** and **Force
     activities to be resizable**, then reboot.
     Via ADB: `adb shell settings put global enable_freeform_support 1` and
     `adb shell settings put global force_resizable_activities 1`.
   - Samsung (Pop-up view), Xiaomi/HyperOS (Floating windows), OnePlus/Oppo/Realme (Flexible windows)
     have their own freeform modes; behaviour varies by ROM.
   - If the app is already open full screen, its existing task may keep its size — close it from
     Recents first.
2. **Floating web panel (no setup).** Without freeform, apps that have a web version (YouTube, WhatsApp,
   Facebook, Instagram, TikTok, Gmail, Maps, Telegram, X, Chrome, Spotify…; see `WebApps` in
   `FloatingWebWindow.kt`) open as their website inside AR's own overlay window: drag by the title bar,
   resize from the bottom corners or cycle small / medium / large / full, minimise to a chip, or open the real app. Needs only "Display over other apps".
3. **No full-screen fallback:** apps without a web version need freeform mode; the panel says so instead of opening them full screen.

Android 11+ blocks the hidden `setLaunchWindowingMode` call for normal apps, so the app uses
`org.lsposed.hiddenapibypass` (in `app/build.gradle.kts`).

### "Do anything" agent

Requests that need several taps ("WhatsApp pe Ali ko hello bhejo", "wifi off karo") become `agent_task`.
`ScreenAgent` loops: read the screen via Accessibility → ask the AI for the next step (open_app / tap /
type / enter / scroll / back) → perform it, up to 15 steps. Say "ruk jao"/"stop" to cancel.
Without a key the free Pollinations endpoint is used, but it allows ~1 reply per 45 s, so the agent is
slow; a free Groq key (`https://api.groq.com/openai/v1`, `openai/gpt-oss-120b`) makes it fast.

Background activity launches from the service are allowed because the app holds the overlay
permission and has a visible overlay window.

### True "app inside our box" (advanced, not included)

To render a third-party app *inside* your own draggable frame, the method is a **VirtualDisplay**:

1. Create a display: `DisplayManager.createVirtualDisplay(name, w, h, dpi, surface, flags)` where
   `surface` comes from a `TextureView`/`SurfaceView` inside your overlay window.
2. Launch the app on it: `ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)`.
3. Forward touches: convert taps on your `TextureView` into `MotionEvent`s with that display id and
   inject them with `InputManager.injectInputEvent`.

Normal apps can't do steps 2–3 for other apps' activities — that needs privileged permissions
(`INJECT_EVENTS`, trusted/public virtual displays, `ACTIVITY_EMBEDDING`). The usual way to get them is
**Shizuku** (ADB-level privileges granted once from a PC or with Wireless debugging), or root. That's
how apps like "Taskbar"/"Mini Desktop"-style tools do it. It's a much bigger project with private
APIs that change between Android versions, so this code uses the freeform approach.

## Voice & AI notes

- The wake word uses Android's built-in `SpeechRecognizer` in a restart loop: no extra library, but
  it uses more battery than a dedicated engine and some phones play a short beep each restart. For
  production, replace the wake stage with **Picovoice Porcupine** (`ai.picovoice:porcupine-android`,
  custom "Hey AR" keyword from the Picovoice Console) and only start `SpeechRecognizer` after it fires.
- Voice language is configurable (`en-IN` works well for Roman Urdu/Hinglish; `ur-PK` / `hi-IN` also
  work if your phone's speech service supports them).
- The AI receives your sentence and your installed-app names, and must reply with a JSON
  `{action, target, text, reply}`; `CommandExecutor` maps that to Android actions. Add a new ability by
  adding an action name in `AIBrain.ACTIONS` and a `when` branch in `CommandExecutor`.
- The API key is stored in app-private SharedPreferences on the device.

## Limitations

- AR can place a call, but Android doesn't let third-party apps speak into or listen to a phone call,
  so "call and talk to them for me" isn't possible.
- Google Play restricts the Accessibility API to accessibility use cases and requires a declaration;
  for personal use install the APK directly.
- Some OEMs (Xiaomi, Oppo, Vivo, Huawei) kill background services — set AR Controller's battery to
  "Unrestricted"/"No restrictions" and allow Autostart.
