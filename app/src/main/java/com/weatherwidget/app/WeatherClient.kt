package com.weatherwidget.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to Open-Meteo (https://open-meteo.com) — a free, open-source weather
 * API that needs no API key and no account. It's not a government weather
 * service: it's an independent open-data project that blends several
 * national weather models together, and its own API is what this app calls.
 */
object WeatherClient {

    fun fetchWeather(lat: Double, lon: Double): WeatherSnapshot? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=sunrise,sunset" +
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

        val sunrise = daily.optJSONArray("sunrise")?.optString(0) ?: return null
        val sunset = daily.optJSONArray("sunset")?.optString(0) ?: return null

        return WeatherSnapshot(
            cityName = null, // filled in separately via reverse geocoding, see MainActivity/WeatherWidgetProvider
            tempF = current.optDouble("temperature_2m", Double.NaN).takeIf { !it.isNaN() } ?: return null,
            code = current.optInt("weather_code", 0),
            isDay = current.optInt("is_day", 1) == 1,
            sunrise = sunrise,
            sunset = sunset,
            fetchedAt = System.currentTimeMillis()
        )
    }
}
