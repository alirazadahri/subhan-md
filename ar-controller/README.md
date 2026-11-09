# AR Controller — AI-powered floating mobile controller (Android)

React Native setup screen + native Kotlin overlay service. Say **"Hey AR"** from anywhere, or tap the
floating **AR** icon to open the Control Panel → **ALL APPS** → tap an app and it opens as a floating
box in the middle of the screen.

> Android only. iOS doesn't allow overlays over other apps, Accessibility control, or launching other
> apps in windows, so this cannot be built for iPhone.

## What's inside

```
ar-controller/
├── App.tsx                              # Setup screen: permissions, AI settings, start/stop, test box
├── src/
│   ├── native/ARController.ts           # Typed JS bridge + runtime permission helpers
│   ├── components/PermissionRow.tsx
│   └── theme.ts
└── android/app/src/main/
    ├── AndroidManifest.xml              # Permissions, <queries>, overlay + accessibility services
    ├── res/xml/accessibility_service_config.xml
    ├── res/values/strings.xml
    └── java/com/arcontroller/
        ├── MainApplication.kt           # Registers ARControllerPackage
        ├── ARControllerPackage.kt
        ├── ARControllerModule.kt        # JS ⇄ native: status, settings screens, start/stop, config, runCommand
        ├── OverlayService.kt            # Floating icon, Control Panel, ALL APPS grid, Ask-AI page
        ├── FloatingAppLauncher.kt       # Opens another app as a floating (freeform) window
        ├── VoiceAssistant.kt            # "Hey AR" wake word, speech-to-text, text-to-speech
        ├── AIBrain.kt                   # LLM → JSON command (+ offline LocalParser)
        ├── CommandExecutor.kt           # Executes commands: apps, calls, SMS, WhatsApp, torch, volume…
        ├── ARAccessibilityService.kt    # Back/Home/Recents, lock, screenshot, click, type, scroll
        ├── AppRepository.kt             # Installed-app list + fuzzy name matching
        └── Prefs.kt                     # Settings shared by JS and the service
```

No extra npm libraries are needed beyond the React Native template (`react-native-safe-area-context`
is already included). Libraries such as `react-native-floating-bubble` only draw a bubble; they can't
expand into a panel, list apps or launch freeform windows, so this is implemented natively.

## Build & run

Requirements: Node 20+, JDK 17, Android SDK (Android Studio), a real Android phone (Android 8+; 10+ recommended).

```bash
npx @react-native-community/cli@latest init ARController
cd ARController

# copy this folder's files over the template (same paths)
cp -r ../ar-controller/App.tsx ../ar-controller/src .
cp -r ../ar-controller/android/app/src/main/. android/app/src/main/

npx react-native run-android        # or: cd android && ./gradlew assembleRelease
```

The package name must be `com.arcontroller` (the default for a project named `ARController`). If you
use another name, change the `package` line in every `.kt` file and move the folder.

## First-time setup on the phone

Open **AR Controller** and grant everything in step 1:

| Permission | Why | How |
|---|---|---|
| Display over other apps | floating icon + panel | system settings screen |
| Microphone | "Hey AR" | normal dialog |
| Accessibility | Home/Back/Recents, lock, screenshot, split screen, click/type/scroll | Settings → Accessibility → AR Controller |
| Contacts + Phone | "Ali ko call karo" | normal dialog |
| Notifications | keeps the service alive | normal dialog |
| Freeform windows (optional) | apps open as a floating box | Developer options |

**Android 13+ sideloaded APKs:** Accessibility shows *"Restricted setting"*. Open App info →
⋮ → **Allow restricted settings**, then enable it again (the app shows a shortcut for this).

Then in step 2 add an API key for any OpenAI-compatible endpoint (OpenAI, Groq, OpenRouter, a local
LLM…), set the model, and press **Start floating AR**.

## Using it

- **Floating icon**: drag anywhere (it snaps to the edge). Tap → the Control Panel expands *in place*
  (no new screen). Colour shows voice state: purple = waiting for "Hey AR", green = listening,
  orange = thinking, pink = speaking.
- **Control Panel**: Hey AR (mic), Ask AI (type), **ALL APPS**, Torch, Vol +/−, Mute, Home, Back,
  Recents, Notifications, Quick Settings, Screenshot, Lock, Split, Wi-Fi, Bluetooth, Location, Dialer,
  Stop.
- **ALL APPS**: grid of every launchable app with search. **Tap = floating window**,
  long-press = normal full screen.
- **Voice**: "Hey AR, YouTube kholo" (one breath) or "Hey AR" → "Ji, boliye" → your command.

Example commands (English / Roman Urdu / Hindi):

| Say | Does |
|---|---|
| open Instagram / YouTube kholo | opens the app |
| float WhatsApp / Chrome floating mein kholo | opens it as a floating box |
| call Ali / Ammi ko call karo | finds the contact and calls |
| message Ahmed that I'm coming | SMS composer filled in |
| WhatsApp Sara ke main 5 min mein aa raha hoon | WhatsApp chat with text filled in (with AI key) |
| torch on / volume barhao / mute | device controls |
| go home / back / recent apps / screenshot / lock screen | via Accessibility |
| type hello / click Send / scroll down | acts on the current screen |
| alarm 7:30 am / search weather Lahore / play Atif Aslam songs | intents |
| anything else ("Pakistan ka capital kya hai?") | AI answers by voice |

Without an API key, the offline `LocalParser` handles the simple patterns above; with a key, the LLM
understands free-form requests and replies in your language.

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
2. **Floating web box (no setup).** Without freeform, apps that have a web version (YouTube, WhatsApp,
   Facebook, Instagram, TikTok, Gmail, Maps, Telegram, X, Chrome, Spotify…; see `WebApps` in
   `FloatingWebWindow.kt`) open as their website inside AR's own overlay window: drag by the title bar,
   resize from the ◢ corner, minimise to a chip, ↗ to open the real app. Needs only "Display over other apps".
3. **Freeform attempt** for apps without a web version (some OEM ROMs honour it); never split screen.

Android 11+ blocks the hidden `setLaunchWindowingMode` call for normal apps, so the app uses
`org.lsposed.hiddenapibypass` (added in `android/app/build.gradle`). Optional one-tap freeform switch:
`adb shell pm grant com.arcontroller android.permission.WRITE_SECURE_SETTINGS`, then use the button in the app.

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
