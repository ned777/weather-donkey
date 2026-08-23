# Building Weather Donkey — a from-scratch guide

This guide assumes you have **never used Android Studio and have never
written code before.** Every piece of jargon is explained the first time
it shows up. Follow it top to bottom and you'll go from "empty computer"
to "this app running on my own phone, with the widget on my home screen."

---

## 1. Vocabulary you'll see everywhere

Read this once before starting — you'll recognize these words constantly.

| Word | What it means |
|---|---|
| **Android Studio** | The program (an "IDE," or code editor with extra tools built in) you use to write, build, and run Android apps. Made by Google. It's free. |
| **Kotlin** | The programming language this app is written in. Android's modern default language. |
| **Gradle** | The tool that takes all the source code and turns it into an actual installable app. You'll rarely touch it directly — Android Studio drives it for you with buttons. |
| **SDK** (Software Development Kit) | The collection of Android-specific tools and libraries that let your code talk to an Android phone. Android Studio installs and manages this for you. |
| **APK** | The installable Android app file — think of it like a `.exe` on Windows, but for Android. Building the project produces one of these. |
| **Emulator** | A virtual Android phone that runs *inside* your computer, so you can test the app without owning a physical device. |
| **AVD** (Android Virtual Device) | One specific emulator "profile" — e.g. "Pixel 8, Android 14." You create one before you can use the emulator. |
| **adb** (Android Debug Bridge) | A tool that lets your computer talk to a real or virtual Android phone — install apps, see logs, etc. Android Studio uses this behind the scenes. |
| **Widget** | The small "weather card" you can drag onto a phone's home screen. This project builds both a full app *and* a widget. |
| **Repository ("repo")** | The project's folder of code, tracked by Git (a version-history tool) and hosted on GitHub. |

---

## 2. Install Android Studio

1. Go to `developer.android.com/studio` in your browser and download the
   installer for your operating system (Windows, macOS, or Linux).
2. Run the installer and accept the defaults. On first launch, a **Setup
   Wizard** appears — click through it with the default ("Standard")
   options. This step downloads the Android SDK, a system image for the
   emulator, and other required tools. **This can take 20–40 minutes** and
   several GB of disk space depending on your internet speed — that's
   normal, let it finish.
3. You do *not* need to separately install Java/JDK — Android Studio
   bundles its own compatible JDK and uses it automatically.

> **Minimum machine specs:** 8 GB RAM (16 GB is much more comfortable),
> and about 10 GB of free disk space for Android Studio + SDK + emulator
> images combined.

---

## 3. Get the project code onto your computer

The project lives on GitHub at
`https://github.com/ned777/weather-donkey`. Two ways to get it:

### Option A — Download as a ZIP (simplest, no extra tools)
1. Open that URL in a browser.
2. Click the green **Code** button → **Download ZIP**.
3. Extract the ZIP file somewhere you'll remember, e.g. your Desktop or
   home folder.

### Option B — Clone with Git (better if you'll make ongoing changes)
1. Install Git from `git-scm.com` if you don't have it.
2. Open a terminal (macOS: Terminal app; Windows: Git Bash, installed
   alongside Git; Linux: your usual terminal) and run:
   ```sh
   git clone https://github.com/ned777/weather-donkey.git
   ```
3. This creates a `weather-donkey` folder with the full project and its
   history.

Either way, you now have a folder on disk containing files like
`build.gradle.kts`, `settings.gradle.kts`, and an `app/` folder. That
folder is "the project."

---

## 4. Open the project in Android Studio

1. Launch Android Studio.
2. On the welcome screen, click **Open** (if a previous project is
   already open instead, use **File → Open**).
3. Navigate to and select the project folder from step 3 (the one
   containing `settings.gradle.kts`) and click **OK**.
4. Android Studio will start a **Gradle Sync** — you'll see a progress bar
   at the bottom of the window, often saying things like "Downloading
   dependencies." **The first sync on a new project can take several
   minutes** since it's fetching everything the app depends on over the
   internet. Just wait for it to finish; don't close the window.
5. When the progress bar disappears and no red error banner is showing,
   the project is ready.

**If sync fails:** the two most common causes are (a) no internet
connection at that moment, or (b) a corrupted cache. Try
**File → Invalidate Caches / Restart** and let it resync. Make sure
you're connected to the internet the first time you open any project.

---

## 5. A tour of what you're looking at

On the left side is the **Project** panel. The parts of this specific
project you'll care about:

```
weather-donkey/
├── app/
│   ├── src/main/
│   │   ├── java/com/weatherwidget/app/   ← all the Kotlin source code
│   │   ├── res/                           ← everything NOT code: colors,
│   │   │                                    layouts (screen designs),
│   │   │                                    icons, strings
│   │   └── AndroidManifest.xml            ← declares the app's components
│   │                                        (screens, widget, permissions)
│   └── build.gradle.kts                   ← this module's build settings
│                                             (dependencies, SDK versions)
├── build.gradle.kts                       ← project-wide build settings
└── settings.gradle.kts                    ← tells Gradle which modules exist
```

Inside `java/com/weatherwidget/app/`, the files that matter most:

| File | What it does |
|---|---|
| `MainActivity.kt` | The main app screen: tabs, search, 5-day forecast, °F/°C toggle |
| `WeatherWidgetProvider.kt` | The home-screen widget itself — tap-to-refresh logic |
| `WeatherWidgetConfigActivity.kt` | The "pick a location" screen shown when you add the widget |
| `WeatherClient.kt` | Fetches weather data from the internet (Open-Meteo API) |
| `WeatherCondition.kt` | Decides "Sunny/Cloudy/Rainy/etc." from the raw weather data |

You never need to memorize this — just know that **`res/`** is where
colors/icons/text live, and **`java/`** is where the logic lives.

---

## 6. Run the app

You need *something* to run it on: either the built-in emulator (a fake
phone on your computer) or your own real phone. Pick one:

### Option A — Emulator (easiest to start with)

1. In Android Studio, open **Device Manager** (View → Tool Windows →
   Device Manager, or the phone-shaped icon in the top-right toolbar).
2. Click **Create Device**, pick a phone (e.g. "Pixel 8"), click **Next**.
3. Pick a system image (e.g. the latest "Android 14" / API 34) — if it
   says "Download" next to it, click that first and wait.
4. Click **Finish**. Your new virtual device now appears in Device
   Manager — click its ▶ play icon to boot it. A phone-shaped window
   appears on screen; give it a minute to fully start up.

### Option B — Your own physical phone

1. On your phone: **Settings → About phone**, tap **Build number** 7
   times in a row. This unlocks hidden **Developer options**.
2. Go to **Settings → System → Developer options** (location varies by
   phone brand) and turn on **USB debugging** (or **Wireless debugging**
   if you'd rather not use a cable).
3. Connect the phone to your computer with a USB cable (or, for wireless
   debugging, pair it following the on-screen instructions under
   Wireless debugging → Pair device).
4. Your phone will show a popup asking to trust this computer's RSA key —
   tap **Allow**.
5. Android Studio's device dropdown (top toolbar) should now list your
   phone by name.

### Actually running it

1. At the top of the Android Studio window, make sure the device dropdown
   shows your emulator or phone.
2. Click the green ▶ **Run** button (or press Shift+F10).
3. Android Studio will build the app (first build is the slowest — a
   couple of minutes is normal) and then automatically install and open
   it on the selected device.

If you see the app open with a location prompt, **it worked.**

---

## 7. Add the widget to a home screen

The app itself is only half the project — the other half is the
home-screen widget. To see it:

1. On the emulator or phone (with the app already installed from step 6),
   long-press an empty spot on the home screen.
2. Tap **Widgets**.
3. Scroll to find **Weather Donkey** and drag it onto the home screen.
4. A config screen appears asking which location this widget should
   watch (your current GPS location, or any city you've already searched
   in the app). Pick one.
5. The widget appears on the home screen. Tap it any time to refresh —
   it deliberately never updates on its own in the background.

---

## 8. Make a small change and see it live (the core workflow)

This is the loop you'll repeat for every change, big or small:

1. Open a file in `app/src/main/res/values/colors.xml` (double-click it
   in the Project panel).
2. Change one of the hex color values, e.g. find `retro_cyan` and change
   `#00D9FF` to any other 6-digit hex color.
3. Click **Run** ▶ again.
4. Android Studio rebuilds and reinstalls — the change shows up on the
   device.

That's the entire cycle: **edit a file → click Run → look at the
result.** Everything else in this project is built the same way.

---

## 9. Produce an installable APK file (without Android Studio's Run button)

Sometimes you want the actual `.apk` file itself — to share it, or
install it without a cable.

**From Android Studio:**
**Build → Build App Bundle(s) / APK(s) → Build APK(s)**. When it
finishes, a notification appears in the bottom-right with a **locate**
link — click it to jump straight to the file, which lands at
`app/build/outputs/apk/debug/app-debug.apk`. Copy that file to a phone
(email, USB, cloud drive — any way you like) and open it there to
install (you may need to allow "install from unknown sources" once).

**From the command line instead**, if you're comfortable with a
terminal:
```sh
export JAVA_HOME=<path to a JDK 17>   # Android Studio's bundled JDK works
cd weather-donkey
./gradlew assembleDebug
```
This does exactly what the Android Studio menu option does, and produces
the same file at the same path. If you additionally have `adb` on your
system `PATH` and a device connected, `./gradlew installDebug` builds
**and** installs it in one step.

---

## 10. When something goes wrong

| Symptom | Likely fix |
|---|---|
| Gradle sync spins forever or fails | Check your internet connection; retry with **File → Invalidate Caches / Restart** |
| "SDK location not found" | Android Studio usually writes `local.properties` automatically on first open — if it's missing, add a line `sdk.dir=<path to your SDK>` (Android Studio shows this path under **Settings → Languages & Frameworks → Android SDK**) |
| Emulator won't boot / is extremely slow | Your CPU's virtualization feature (Intel VT-x / AMD-V, or "Hyper-V" on Windows) may need enabling in your BIOS settings |
| Device shows as "unauthorized" in the device dropdown | Look at the phone screen — there's a popup waiting for you to tap **Allow** on the USB-debugging trust prompt |
| App installs but crashes immediately when opening a location tab | You (or the emulator) haven't granted the location permission the app asked for — reopen the app and tap **Allow** when prompted |
| Build fails mentioning a Java/JDK version | This project targets JDK 17 (see `app/build.gradle.kts`) — Android Studio's bundled JDK already satisfies this; only an issue if you've manually overridden `JAVA_HOME` to something older |

---

## 11. Where to go next

Once you're comfortable with the edit → Run → look loop, read
[HOW_IT_WORKS.md](HOW_IT_WORKS.md) next — it explains *how the app and
widget are actually coded*: the architecture, why the code is shaped the
way it is, and what each source file teaches. Every source file also has
inline comments now explaining the Kotlin/Android building blocks the
first time each one appears, so reading the code itself alongside that
guide is the natural next step.
