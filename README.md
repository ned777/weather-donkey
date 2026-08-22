# Weather

A resizable Android home-screen widget for a glance at the weather right
where you are. **Tap it to update — there's no background polling, no
periodic timer, no cloud relay.** It only ever refreshes at the moment you
touch it.

## What it shows

```
CURRENT LOCATION
      72°
  ☀ Sunny
↑ 6:32 AM   ↓ 7:45 PM
Updated just now
```

- Current temperature (°F) at your approximate location
- Condition, bucketed into exactly three states: **Sunny**, **Cloudy**, or
  **Raining**
- Sunrise and sunset, in local clock time

Resize the widget freely (drag its corners on the home screen) — smaller
sizes hide the less essential rows (sunrise/sunset, then the "Updated…"
line) instead of clipping text.

## Weather data: Open-Meteo

Weather comes from [Open-Meteo](https://open-meteo.com) — a free,
open-source weather API. No API key, no account, no cloud relay of your
own. It's not a government weather service; it's an independent open-data
project. `weather_code` (the standard WMO weather code) is what gets
bucketed into Sunny/Cloudy/Raining — see `WeatherCondition.kt`.

## Location

The widget uses `ACCESS_COARSE_LOCATION` (network-based, city-level
precision) — deliberately not fine/GPS location, since a weather forecast
doesn't need to know your exact position. The first tap (or opening the
app) prompts you to grant it; nothing is fetched before you do.

## Why tap-only, no auto-refresh

Same philosophy as this author's other widgets ([today-widget](https://github.com/ned777/today-widget),
[sysmon-widget](https://github.com/ned777/sysmon-widget)): a widget that's
"not running in the background" shouldn't quietly poll in the background.
`updatePeriodMillis="0"` in `weather_widget_info.xml` means Android never
wakes this widget on a timer — the only thing that ever triggers a location
fix + network fetch is your own tap (`WeatherWidgetProvider.ACTION_REFRESH`).
The app screen follows the same rule: opening it or resuming it never
fetches on its own, only tapping **Refresh** (or granting permission, which
is itself a tap) does.

## Project structure

```
app/src/main/java/com/weatherwidget/app/
  WeatherWidgetProvider.kt   — AppWidgetProvider: tap-to-refresh, resize handling, rendering
  MainActivity.kt             — full-screen view + permission prompt + Refresh button
  LocationHelper.kt            — plain android.location.LocationManager fix (no Play Services)
  WeatherClient.kt              — HttpURLConnection call to Open-Meteo + JSON parsing
  WeatherCondition.kt            — WMO weather code → Sunny / Cloudy / Raining
  WeatherCache.kt                  — SharedPreferences: last successful fetch
  GeocodeHelper.kt                  — best-effort lat/lon → city name (on-device Geocoder)
  WeatherFormat.kt                   — shared temp/time/"Updated Xm ago" formatting
```

## Building

```sh
export JAVA_HOME=<path to a JDK 17>
./gradlew installDebug     # installs over adb (USB or wireless debugging)
```

Then long-press your home screen → Widgets → **Weather** and drag it on.
