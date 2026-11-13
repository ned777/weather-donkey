package com.weatherwidget.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Talks to Open-Meteo (https://open-meteo.com) — a free, open-source weather
 * API that needs no API key and no account.
 *
 * Always fetched/stored in Fahrenheit regardless of the user's display
 * preference — see WeatherFormat for the Celsius conversion used only at
 * render time, so flipping the unit toggle never needs a new network call.
 */
// `object` (instead of `class`) declares a SINGLETON — Kotlin creates exactly
// one instance of WeatherClient automatically, the first time it's touched,
// and every caller shares that same instance. There's never a reason to have
// two WeatherClients, so this skips the usual `WeatherClient().fetchWeather(...)`
// and lets callers just write `WeatherClient.fetchWeather(...)` directly.
object WeatherClient {
    private const val HOURLY_FORECAST_COUNT = 12
    private const val TAG = "WeatherClient"

    // A single attempt fails on any momentary blip (phone just woke up, Wi-Fi ↔ cell
    // handoff, VPN reconnecting, one-off 5xx from the server), which used to surface as
    // "Couldn't reach weather service" until the next tap. Retry a couple of times with
    // a short pause first; the total stays well inside a widget broadcast's time budget.
    private val RETRY_DELAYS_MS = longArrayOf(1500L, 3000L)

    private val dayLabelFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

    // The `: WeatherSnapshot?` return type — with a `?` — means this function can
    // return either a real WeatherSnapshot OR `null`. Kotlin forces every caller to
    // handle both cases (e.g. `WeatherClient.fetchWeather(...) ?: return`) instead of
    // letting a missing result silently crash later, which is what "null safety" means.
    fun fetchWeather(lat: Double, lon: Double): WeatherSnapshot? {
        for (attempt in 0..RETRY_DELAYS_MS.size) {
            if (attempt > 0) Thread.sleep(RETRY_DELAYS_MS[attempt - 1])
            when (val result = fetchOnce(lat, lon)) {
                is FetchResult.Success -> return result.snapshot
                is FetchResult.Retryable -> Log.w(TAG, "Attempt ${attempt + 1} failed: ${result.reason}")
                is FetchResult.Fatal -> {
                    Log.w(TAG, "Giving up: ${result.reason}")
                    return null
                }
            }
        }
        return null
    }

    private sealed class FetchResult {
        class Success(val snapshot: WeatherSnapshot) : FetchResult()
        class Retryable(val reason: String) : FetchResult()
        class Fatal(val reason: String) : FetchResult()
    }

    private fun fetchOnce(lat: Double, lon: Double): FetchResult {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,wind_speed_10m_max,precipitation_probability_max" +
                    "&hourly=temperature_2m,weather_code,is_day,wind_speed_10m,precipitation_probability" +
                    "&forecast_days=6" + // today + the next 5 days
                    // The hourly list starts at the hour already in progress, which
                    // parseResponse drops — one extra leaves a full 12 ahead of now.
                    "&forecast_hours=${HOURLY_FORECAST_COUNT + 1}" +
                    "&temperature_unit=fahrenheit" +
                    "&wind_speed_unit=mph" +
                    "&timezone=auto"
            )
            // HttpURLConnection is the plain-Java way to make an HTTP request — no extra
            // library needed. It's a blocking call (the line waits here until the server
            // responds), which is exactly why fetchWeather() is always run on a background
            // Thread by its callers, never directly on the UI thread.
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            try {
                val code = connection.responseCode
                when {
                    code == 200 -> {
                        val body = connection.inputStream.bufferedReader().readText()
                        parseResponse(body)?.let { FetchResult.Success(it) }
                            ?: FetchResult.Fatal("unparseable response")
                    }
                    // 429 (rate limited) and 5xx are the server's temporary problems.
                    code == 429 || code >= 500 -> FetchResult.Retryable("HTTP $code")
                    else -> FetchResult.Fatal("HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            // No network, DNS failure, malformed response, etc. — from the caller's
            // point of view these all mean the same thing: "couldn't fetch weather
            // right now" — but it's logged (adb logcat -s WeatherClient) and retried.
            FetchResult.Retryable("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun parseResponse(body: String): WeatherSnapshot? {
        val json = JSONObject(body)
        val current = json.optJSONObject("current") ?: return null
        val daily = json.optJSONObject("daily") ?: return null
        val hourly = json.optJSONObject("hourly") ?: return null

        val dates = daily.optJSONArray("time") ?: return null
        val codes = daily.optJSONArray("weather_code") ?: return null
        val highs = daily.optJSONArray("temperature_2m_max") ?: return null
        val lows = daily.optJSONArray("temperature_2m_min") ?: return null
        val sunrises = daily.optJSONArray("sunrise") ?: return null
        val sunsets = daily.optJSONArray("sunset") ?: return null
        val winds = daily.optJSONArray("wind_speed_10m_max") ?: return null
        val rainChances = daily.optJSONArray("precipitation_probability_max") ?: return null
        if (dates.length() == 0) return null

        val hourTimes = hourly.optJSONArray("time") ?: return null
        val hourTemps = hourly.optJSONArray("temperature_2m") ?: return null
        val hourCodes = hourly.optJSONArray("weather_code") ?: return null
        val hourIsDay = hourly.optJSONArray("is_day") ?: return null
        val hourWinds = hourly.optJSONArray("wind_speed_10m") ?: return null
        val hourRainChances = hourly.optJSONArray("precipitation_probability") ?: return null

        val forecast = (1 until dates.length()).map { i ->
            DailyForecast(
                dateLabel = dayLabel(dates.optString(i)),
                highF = highs.optDouble(i),
                lowF = lows.optDouble(i),
                code = codes.optInt(i),
                windSpeedMph = winds.optDouble(i),
                rainChancePercent = rainChances.optInt(i)
            )
        }

        // Both timestamps are the same fixed-width local ISO format, so comparing them as
        // plain strings also compares them chronologically.
        val currentTime = current.optString("time")
        val hourlyForecast = (0 until hourTimes.length())
            .filter { i -> hourTimes.optString(i) > currentTime }
            .take(HOURLY_FORECAST_COUNT)
            .map { i ->
                HourlyForecast(
                    time = hourTimes.optString(i),
                    tempF = hourTemps.optDouble(i),
                    code = hourCodes.optInt(i),
                    isDay = hourIsDay.optInt(i, 1) == 1,
                    windSpeedMph = hourWinds.optDouble(i),
                    rainChancePercent = hourRainChances.optInt(i)
                )
            }

        return WeatherSnapshot(
            cityName = null, // filled in separately via reverse geocoding, see MainActivity/WeatherWidgetProvider
            tempF = current.optDouble("temperature_2m", Double.NaN).takeIf { !it.isNaN() } ?: return null,
            code = current.optInt("weather_code", 0),
            isDay = current.optInt("is_day", 1) == 1,
            windSpeedMph = current.optDouble("wind_speed_10m", 0.0),
            sunrise = sunrises.optString(0),
            sunset = sunsets.optString(0),
            todayHighF = highs.optDouble(0),
            todayLowF = lows.optDouble(0),
            // Open-Meteo has no "current" precipitation probability field, only daily —
            // today's chance is the same daily max used for today's high/low above.
            todayRainChancePercent = rainChances.optInt(0),
            hourly = hourlyForecast,
            forecast = forecast,
            fetchedAt = System.currentTimeMillis()
        )
    }

    private fun dayLabel(isoDate: String): String = try {
        LocalDate.parse(isoDate).format(dayLabelFormatter)
    } catch (e: Exception) {
        "--"
    }
}
