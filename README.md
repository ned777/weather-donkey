# Weather

A fixed-size (2×2, not resizable) Android home-screen widget for a glance at
the weather right where you are. **Tap it to update — there's no background
polling, no periodic timer, no cloud relay.** It only ever refreshes at the
moment you touch it.

## What it shows

```
CURRENT LOCATION
      72°
  ☀ Sunny
↑ 6:32 AM
↓ 7:45 PM
```

- Current temperature at your approximate location, in °F or °C (your
  choice, set in the app)
- Condition, bucketed into exactly three states: **Sunny**, **Cloudy**, or
  **Raining**
- Sunrise and sunset, in local clock time

Text sizes are all measured against the widget's actual rendered size and
shrunk to fit if needed, so nothing wraps or gets clipped.

## The app: multiple locations, unit toggle, 5-day forecast

The widget itself only ever shows your current GPS location — that's the
"Current" tab, and it's always the default. Opening the app gets you more:

- **Other locations**: tap **+** in the tab strip, search a city name or
  ZIP code (Android's on-device Geocoder — no key, no second API), and pick
  a match to add it as its own tab. Each tab keeps its own independently
  cached weather and its own independent refresh.
- **°F / °C toggle** — a pure display switch. Flipping it reformats
  whatever's already cached instantly; it never triggers a new fetch.
- **5-day forecast** — today's high/low plus the next 5 days, each with its
  own condition icon.
- **Pull down to refresh** — no separate Refresh button; swipe down on any
  tab to fetch that tab's location.

## Weather data: Open-Meteo

Weather comes from [Open-Meteo](https://open-meteo.com) — a free,
open-source weather API. No API key, no account, no cloud relay of your
own. `weather_code` (the standard WMO weather code) is what gets bucketed
into Sunny/Cloudy/Raining — see `WeatherCondition.kt`.

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
  WeatherWidgetProvider.kt   — AppWidgetProvider: tap-to-refresh, fit-to-size rendering
  MainActivity.kt             — tabs, search dialog, unit toggle, 5-day forecast, pull-to-refresh
  LocationStore.kt             — SharedPreferences-backed list of searched/added cities
  LocationHelper.kt             — plain android.location.LocationManager fix (no Play Services)
  WeatherClient.kt                — HttpURLConnection call to Open-Meteo + JSON parsing
  WeatherCondition.kt              — WMO weather code → Sunny / Cloudy / Raining
  WeatherCache.kt                   — SharedPreferences: last successful fetch, per location
  GeocodeHelper.kt                   — on-device Geocoder: lat/lon → city name, and city/ZIP → matches
  WeatherFormat.kt                    — shared temp/time/"Updated Xm ago" formatting
```

## Building

```sh
export JAVA_HOME=<path to a JDK 17>
./gradlew installDebug     # installs over adb (USB or wireless debugging)
```

Then long-press your home screen → Widgets → **Weather** and drag it on.
It only comes in one size (2×2) — there's nothing to resize.
