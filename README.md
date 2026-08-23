# Weather Donkey

An Android app **and** a home-screen widget, not just one or the other —
current-location weather (temperature, condition, sunrise/sunset, rain
chance) from [Open-Meteo](https://open-meteo.com), a free open-source API.
**Both only ever update when you touch them — no background polling, no
periodic timer, no cloud relay of your own.**

## App features

- **Tabs, one per location**: "Current" (GPS) is always the default, first
  tab. Tap **+** to search a city name or ZIP code (Android's on-device
  Geocoder — no key, no second API) and add a match as its own tab. Each
  tab keeps its own independently cached weather and its own independent
  refresh.
- **Huge, hollow, glowing-outline temperature** — no solid fill at all, just
  a stroked outline in the theme's cyan (`OutlinedTextView.kt`, since Android
  has no built-in outline-text attribute), with the existing glow shadow
  sitting on both sides of that stroke line. Sized by measuring the actual
  string against the real available width and picking the largest size that
  still fits on one line (`MainActivity.applyTempTextSize()`) — a
  double-digit or negative reading shrinks itself down just enough rather
  than wrapping or overflowing.
- **°F / °C toggle** (for the weather display) — a pure display switch.
  Flipping it reformats whatever's already cached instantly; it never
  triggers a new fetch.
- **5-day forecast** — today's high/low plus the next 5 days, each with its
  own condition and a flat vector icon (`res/drawable/ic_weather_*.xml`) —
  no emoji, plain solid-color shapes in the app's own retro palette. Rain
  chance and wind speed sit stacked under the high/low on the right side of
  each row, with a thin divider between rows.
- **Pull down to refresh** — no separate Refresh button; swipe down on any
  tab to fetch that tab's location.
- **Standalone °F ⇄ °C converter** at the bottom of the screen — two plain
  number boxes, unrelated to the weather display above (no location, no
  network call, just arithmetic). Typing in either one live-fills in the
  other.

## Each widget can watch a different location

Dragging a new widget onto the home screen shows a config screen first —
pick **Current** (GPS) or any city already added as a tab in the app above.
Each widget instance remembers its own choice independently
(`WidgetLocationBinding`, keyed per widget id), reusing the same
per-location cache the app's tabs populate. A widget bound to a searched
city needs no location permission at all — only a "Current"-bound widget
ever does. Tapping one widget only ever refreshes that one widget, never
every placed widget.

## Weather data: Open-Meteo

Weather comes from [Open-Meteo](https://open-meteo.com) — a free,
open-source weather API. No API key, no account, no cloud relay of your
own. `weather_code` (the standard WMO weather code) plus wind speed is what
gets bucketed into Sunny/Partial/Cloudy/Windy/Rainy/Snowy — see
`WeatherCondition.kt`. Windy only wins over a plain sunny/cloudy/partial
reading once wind crosses a threshold; rain/snow always take priority over
wind, since getting wet matters more than a breeze.

## Location

The widget/Current tab use `ACCESS_COARSE_LOCATION` (network-based,
city-level precision) — deliberately not fine/GPS location, since a
weather forecast doesn't need to know your exact position. The first tap
(or opening the app) prompts you to grant it; nothing is fetched before
you do. Searched-city tabs need no location permission at all — their
coordinates come from the search result, not GPS.

## Why tap-only, no auto-refresh

Same philosophy as this author's other widgets ([today-widget](https://github.com/ned777/today-widget),
[sysmon-widget](https://github.com/ned777/sysmon-widget)): a widget that's
"not running in the background" shouldn't quietly poll in the background.
`updatePeriodMillis="0"` in `weather_widget_info.xml` means Android never
wakes this widget on a timer — the only thing that ever triggers a location
fix + network fetch is your own tap (`WeatherWidgetProvider.ACTION_REFRESH`).
The app follows the same rule on every tab: opening it, resuming it, or
switching tabs never fetches on its own — only swiping down to refresh (or
granting permission for the first time, on the Current tab) does.

## Project structure

```
app/src/main/java/com/weatherwidget/app/
  WeatherWidgetProvider.kt     — AppWidgetProvider: tap-to-refresh, fit-to-size rendering
  WeatherWidgetConfigActivity.kt — per-widget location picker, shown when adding a widget
  WidgetLocationBinding.kt        — SharedPreferences: which location each widget id watches
  MainActivity.kt                   — tabs, search dialog, unit toggle, 5-day forecast, pull-to-refresh
  OutlinedTextView.kt                — hollow, stroke-only "outlined" TextView, used for the app's temp number
  LocationStore.kt                    — SharedPreferences-backed list of searched/added cities
  LocationHelper.kt                    — plain android.location.LocationManager fix (no Play Services)
  WeatherClient.kt                      — HttpURLConnection call to Open-Meteo + JSON parsing
  WeatherCondition.kt                    — WMO weather code + wind speed → the six condition states
  WeatherCache.kt                         — SharedPreferences: last successful fetch, per location
  GeocodeHelper.kt                         — on-device Geocoder: lat/lon → city name, and city/ZIP → matches
  WeatherFormat.kt                          — shared temp/time/"Updated Xm ago" formatting
```

## Building

```sh
export JAVA_HOME=<path to a JDK 17>
./gradlew installDebug     # installs over adb (USB or wireless debugging)
```

Then long-press your home screen → Widgets → **Weather Donkey** and drag it
on — you'll be asked which location it should watch. It only comes in one
size (2×2) — there's nothing to resize.
