# How Weather Donkey was coded — an architecture walkthrough

This is the companion to [BUILD_GUIDE.md](BUILD_GUIDE.md). That guide gets the
project *running*; this one explains *how it's actually built* — the pieces,
why they're shaped the way they are, and the Android/Kotlin concepts you'll
run into reading the source. Written for someone comfortable with the
build-guide's vocabulary (Activity, Gradle, APK, etc.) but new to actually
writing Android code.

Every source file listed below now also has inline comments explaining the
Kotlin/Android constructs the first time each one shows up — this document
is the map; the comments are the annotations at each stop.

---

## 1. The two Android "entry points" this app has

Most tutorials teach you one thing: an app with one screen. This project
has **two separate things Android can launch**, and understanding the
difference is the single most important idea in this codebase.

| | An **Activity** | An **AppWidgetProvider** |
|---|---|---|
| What it is | One full screen the user looks at and taps around | A short-lived component Android wakes up for specific events, then lets go dormant |
| This app's example | `MainActivity.kt` | `WeatherWidgetProvider.kt` |
| Runs continuously? | Only while on screen | Never — it's asleep until Android has a reason to call it |
| How it draws UI | Directly: `findViewById`, set properties, done | Indirectly, through `RemoteViews` (explained below) — because the widget's pixels are actually drawn by the **home-screen launcher app**, a different process entirely |

Both entry points are declared in `AndroidManifest.xml` — that file is
where Android learns "this app has an Activity called MainActivity" and
"this app has a widget called WeatherWidgetProvider, and here's the XML
describing its default size/behavior" (`res/xml/weather_widget_info.xml`).

---

## 2. The one rule the whole app is designed around

> **Nothing fetches over the network unless the user physically taps
> something.**

No background service, no periodic timer (`updatePeriodMillis="0"` in
`weather_widget_info.xml` — this single line is what tells Android *not*
to wake the widget on a schedule), no push notifications. This one
constraint is *why* the code is shaped the way it is:

- Both the app and the widget always **render from cache first**
  (`WeatherCache.read(...)`), and only ever *replace* that cache after a
  network call that the user's tap directly caused.
- That's why almost every screen-drawing function in this codebase takes a
  `WeatherSnapshot?` (nullable — might be `null` if nothing's cached yet)
  rather than fetching anything itself. Fetching and rendering are two
  completely separate steps that only ever get chained together right
  after a tap.

Once you see this rule, most of the code's structure explains itself.

---

## 3. The data layer — plain files, no database

This app has no Room database, no SQL — everything persists through
**SharedPreferences**, Android's small built-in key-value store (think:
a tiny settings file the OS manages for you). Four different objects each
own their own slice of it, all sharing one file named `"weather"`:

| Object | What it stores |
|---|---|
| `WeatherCache` | The last successful fetch, one JSON blob per location |
| `LocationStore` | The list of cities the user has searched and added |
| `WidgetLocationBinding` | Which location *each individual placed widget* is watching |
| `UnitPreference` | The °F/°C display toggle |

Structured data (a `WeatherSnapshot`, with its nested list of
`DailyForecast` days) can't go into SharedPreferences directly — it only
stores simple types (String, Int, Boolean, etc.) — so `WeatherSnapshot`
has hand-written `toJson()` / `fromJson()` methods that turn it into one
string and back. That's what `org.json.JSONObject`/`JSONArray` are doing
throughout `WeatherCache.kt` and `LocationStore.kt`: manually building and
reading a small JSON document, one field at a time, with no external JSON
library — Android ships `org.json` for free.

---

## 4. Threading: the rule every network/location call follows

Android has one **main thread** responsible for all UI drawing and touch
handling. If you run something slow on it — a network request, waiting on
GPS — the whole screen freezes (and after a few seconds, Android shows the
user an "App Not Responding" crash dialog). Every slow operation in this
app follows the same three-step pattern:

```kotlin
Thread {
    val result = someSlowBlockingCall()   // runs on a background thread
    runOnUiThread {
        // back on the main thread — safe to touch views here
        someTextView.text = result
    }
}.start()
```

You'll see this exact shape in `MainActivity.performLocationSearch()`,
`MainActivity.startRefresh()`, and (in a slightly different form, since a
widget has no `runOnUiThread` of its own — see below) throughout
`WeatherWidgetProvider.kt`.

**Why `WeatherWidgetProvider` does it differently:** a `BroadcastReceiver`
(which is what an `AppWidgetProvider` actually is under the hood) is
normally expected to finish its work in milliseconds — Android may kill
the app's process right after `onReceive()` returns. But refreshing a
widget means a location fix *and* a network call, both of which take real
time. The fix is `goAsync()`: it tells Android "hold on, I'm not done,"
buying time for a background `Thread` to finish before calling
`pending.finish()`. Look at `onReceive()` at the bottom of
`WeatherWidgetProvider.kt` to see this in context.

---

## 5. How a widget actually gets pixels on the home screen

This is the part that looks like nothing you'd write for a normal app
screen, because a widget genuinely works differently:

1. The widget's UI is drawn by the **home-screen launcher app** — a
   different process than this one. This app can't just grab a `TextView`
   and call `.setText(...)` on it directly, because it doesn't own that
   `TextView`.
2. Instead, `WeatherWidgetProvider` builds a **`RemoteViews`** object — a
   recorded *list of instructions* ("set the view with this id's text to
   this string," "set this view's size to that," "hide this view") — and
   hands it to `AppWidgetManager`, which ships those instructions over to
   the launcher process to actually carry out.
3. Tapping the widget can't use a normal `View.OnClickListener` either
   (same reason — that code would need to run in this app's process, but
   the tap happens in the launcher's). Instead, `setClickIntent()` attaches
   a **`PendingIntent`** — a sealed "voucher" for an action that the
   launcher can trigger later, on this app's behalf, without needing to
   know or care what that action actually does. When tapped, it fires a
   broadcast this app receives in `onReceive()`, which is what actually
   kicks off a refresh.

Everything else in `WeatherWidgetProvider.kt` — the size-tier logic, the
manual text measurement in `fitWidthSp()`/`textWidthPx()` — exists because
a home-screen widget can be resized to almost any dimensions, and
`RemoteViews` has no equivalent of a modern layout's automatic text-scaling
tools. The code measures the actual pixel width a string of text would
need at a candidate size (`Paint().measureText(...)`) and shrinks it until
it fits the real space available, rather than guessing.

---

## 6. Every source file, and what it teaches

```
app/src/main/java/com/weatherwidget/app/
```

| File | Role | Concepts worth studying here |
|---|---|---|
| `WeatherCondition.kt` | Turns a raw weather code + wind speed into one of six labeled states | `enum class`, a `companion object` factory function |
| `WeatherClient.kt` | Makes the actual HTTP call to Open-Meteo and parses the JSON response | `object` singletons, `HttpURLConnection`, manual JSON parsing, nullable return types |
| `WeatherFormat.kt` | All the shared text-formatting (temperatures, clock times, "Updated 3m ago") | Pure functions with no side effects — same input always gives the same output, which is why the app and widget can share this one object and never drift apart |
| `WeatherCache.kt` | The `WeatherSnapshot`/`DailyForecast` data models, JSON (de)serialization, and the SharedPreferences-backed cache + unit preference | `data class`, SharedPreferences read/write |
| `LocationStore.kt` | The user's list of searched/added cities | Same SharedPreferences pattern as above, applied to a list instead of a single value |
| `LocationHelper.kt` | One best-effort GPS/network location fix | `CountDownLatch` (making a thread wait for a callback), `HandlerThread` (a background thread with its own callback queue) |
| `GeocodeHelper.kt` | Lat/lon → city name, and typed text → candidate city matches, via Android's on-device Geocoder | Wrapping a flaky OS service defensively — every call is `try`/`catch`-guarded and returns `null`/empty rather than crashing |
| `MainActivity.kt` | The full app screen: tabs, search, forecast list, unit toggle, converter | `Activity` lifecycle (`onCreate`), `findViewById`, `lateinit var`, `registerForActivityResult`, the Thread/`runOnUiThread` pattern, an inline `TextWatcher` |
| `OutlinedTextView.kt` | A custom hollow/glowing `TextView` for the big temperature number | Subclassing a View and overriding `onDraw(canvas)` |
| `WeatherWidgetProvider.kt` | The widget itself: rendering, tap-to-refresh, responsive sizing | `AppWidgetProvider`/`BroadcastReceiver`, `RemoteViews`, `PendingIntent`, `goAsync()` |
| `WeatherWidgetConfigActivity.kt` | The "pick a location" screen shown once per newly-placed widget | A second, much smaller Activity — shows the same lifecycle pattern as MainActivity at a fraction of the size |
| `WidgetLocationBinding.kt` | Which location each individual placed widget instance is bound to | The smallest possible SharedPreferences wrapper — a good first file to read end-to-end |

Reading order if you're new to this: **`WeatherCondition.kt` →
`WeatherFormat.kt` → `WidgetLocationBinding.kt`** (all short and
self-contained) **→ `WeatherCache.kt` → `MainActivity.kt` →
`WeatherWidgetProvider.kt`** (the two biggest, tying everything above
together).

---

## 7. Design decisions worth knowing the "why" of

- **No Google Play Services / Fused Location Provider.** Location comes
  from plain `android.location.LocationManager` (`LocationHelper.kt`)
  instead. This keeps the app buildable and runnable on any Android device
  or emulator image, even one without Google apps installed — at the cost
  of a coarser, less battery-optimized location fix, which is fine for a
  city-level weather forecast.
- **No API key for weather.** Open-Meteo was chosen specifically because
  it needs no signup, no key, and no billing account — removing an entire
  category of setup friction for anyone building this project themselves.
- **Everything stored/fetched in Fahrenheit, converted only at render
  time** (`WeatherFormat.tempString`). This means flipping the °F/°C
  toggle is instant and never triggers a new network call — it just
  reformats data that's already sitting in memory/cache.
- **A hand-rolled `OutlinedTextView`** instead of a plain `TextView` with
  a stroke attribute — because Android's View system has no built-in
  "outline-only text" style. Subclassing and overriding `onDraw()` is the
  standard escape hatch whenever a built-in view can't do what you need.

---

## 8. Things to try changing, to learn by doing

Once the app is running (see `BUILD_GUIDE.md`), these are small,
self-contained edits that touch exactly one concept each:

1. **Add a seventh `WeatherCondition`** (e.g. a "Foggy" state distinct from
   Cloudy) — touches the `enum class` in `WeatherCondition.kt`, its
   `fromCodeAndWind` mapping, and adding a matching
   `res/drawable/ic_weather_foggy.xml` icon.
2. **Change the "Updated Xm ago" wording** in
   `WeatherFormat.updatedAgoString()` to also show seconds for anything
   under a minute — practice with the existing `when` block.
3. **Add a 6th/7th forecast day** — Open-Meteo already returns more than 5
   days if you raise `forecast_days` in `WeatherClient.fetchWeather()`;
   trace that value through `parseResponse()` to `MainActivity.populateForecast()`
   to see how one number ripples through the whole data pipeline.
