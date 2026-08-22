package com.weatherwidget.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Talks to Open-Meteo (https://open-meteo.com) — a free, open-source weather
 * API that needs no API key and no account. It's not a government weather
 * service: it's an independent open-data project that blends several
 * national weather models together, and its own API is what this app calls.
 *
 * Always fetched/stored in Fahrenheit regardless of the user's display
 * preference — see WeatherFormat for the Celsius conversion used only at
 * render time, so flipping the unit toggle never needs a new network call.
 */
object WeatherClient {
    private val dayLabelFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

    fun fetchWeather(lat: Double, lon: Double): WeatherSnapshot? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                    "&forecast_days=6" + // today + the next 5 days
                    "&temperature_unit=fahrenheit" +
                    "&timezone=auto"
            )
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
        if (dates.length() == 0) return null

        val forecast = (1 until dates.length()).map { i ->
            DailyForecast(
                dateLabel = dayLabel(dates.optString(i)),
                highF = highs.optDouble(i),
                lowF = lows.optDouble(i),
                code = codes.optInt(i)
            )
        }

        return WeatherSnapshot(
            cityName = null, // filled in separately via reverse geocoding, see MainActivity/WeatherWidgetProvider
            tempF = current.optDouble("temperature_2m", Double.NaN).takeIf { !it.isNaN() } ?: return null,
            code = current.optInt("weather_code", 0),
            isDay = current.optInt("is_day", 1) == 1,
            sunrise = sunrises.optString(0),
            sunset = sunsets.optString(0),
            todayHighF = highs.optDouble(0),
            todayLowF = lows.optDouble(0),
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
