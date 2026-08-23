package com.weatherwidget.app

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
    private val dayLabelFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

    // The `: WeatherSnapshot?` return type — with a `?` — means this function can
    // return either a real WeatherSnapshot OR `null`. Kotlin forces every caller to
    // handle both cases (e.g. `WeatherClient.fetchWeather(...) ?: return`) instead of
    // letting a missing result silently crash later, which is what "null safety" means.
    fun fetchWeather(lat: Double, lon: Double): WeatherSnapshot? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,wind_speed_10m_max,precipitation_probability_max" +
                    "&forecast_days=6" + // today + the next 5 days
                    "&temperature_unit=fahrenheit" +
                    "&wind_speed_unit=mph" +
                    "&timezone=auto"
            )
            // HttpURLConnection is the plain-Java way to make an HTTP request — no extra
            // library needed. It's a blocking call (the line waits here until the server
            // responds), which is exactly why fetchWeather() is always run on a background
            // Thread by its callers, never directly on the UI thread.
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 6000

            try {
                if (connection.responseCode != 200) return null
                val body = connection.inputStream.bufferedReader().readText()
                parseResponse(body)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            // No network, DNS failure, malformed response, etc. — from the caller's
            // point of view these all mean the same thing: "couldn't fetch weather
            // right now", so we don't distinguish which one happened.
            null
        }
    }

    private fun parseResponse(body: String): WeatherSnapshot? {
        val json = JSONObject(body)
        val current = json.optJSONObject("current") ?: return null
        val daily = json.optJSONObject("daily") ?: return null

        val dates = daily.optJSONArray("time") ?: return null
        val codes = daily.optJSONArray("weather_code") ?: return null
        val highs = daily.optJSONArray("temperature_2m_max") ?: return null
        val lows = daily.optJSONArray("temperature_2m_min") ?: return null
        val sunrises = daily.optJSONArray("sunrise") ?: return null
        val sunsets = daily.optJSONArray("sunset") ?: return null
        val winds = daily.optJSONArray("wind_speed_10m_max") ?: return null
        val rainChances = daily.optJSONArray("precipitation_probability_max") ?: return null
        if (dates.length() == 0) return null

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
