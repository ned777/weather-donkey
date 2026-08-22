package com.weatherwidget.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One day's high/low/condition, used for the 5-day forecast list and the widget's 2-day row. */
data class DailyForecast(
    val dateLabel: String,
    val highF: Double,
    val lowF: Double,
    val code: Int
)

/**
 * One successful fetch's worth of weather data: today's current reading plus
 * the next 5 days, everything the widget/app need to render.
 */
data class WeatherSnapshot(
    val cityName: String?,
    val tempF: Double,
    val code: Int,
    val isDay: Boolean,
    val sunrise: String,
    val sunset: String,
    val todayHighF: Double,
    val todayLowF: Double,
    val forecast: List<DailyForecast>, // tomorrow .. +5 days, oldest first
    val fetchedAt: Long
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("city", cityName ?: JSONObject.NULL)
        root.put("temp_f", tempF)
        root.put("code", code)
        root.put("is_day", isDay)
        root.put("sunrise", sunrise)
        root.put("sunset", sunset)
        root.put("today_high_f", todayHighF)
        root.put("today_low_f", todayLowF)
        root.put("fetched_at", fetchedAt)
        val forecastArray = JSONArray()
        forecast.forEach { day ->
            forecastArray.put(
                JSONObject()
                    .put("label", day.dateLabel)
                    .put("high_f", day.highF)
                    .put("low_f", day.lowF)
                    .put("code", day.code)
            )
        }
        root.put("forecast", forecastArray)
        return root.toString()
    }

    companion object {
        fun fromJson(json: String): WeatherSnapshot? = try {
            val root = JSONObject(json)
            val forecastArray = root.optJSONArray("forecast") ?: JSONArray()
            val forecast = (0 until forecastArray.length()).map { i ->
                val day = forecastArray.getJSONObject(i)
                DailyForecast(
                    dateLabel = day.getString("label"),
                    highF = day.getDouble("high_f"),
                    lowF = day.getDouble("low_f"),
                    code = day.getInt("code")
                )
            }
            WeatherSnapshot(
                cityName = root.optString("city", "").takeUnless { it.isEmpty() },
                tempF = root.getDouble("temp_f"),
                code = root.getInt("code"),
                isDay = root.getBoolean("is_day"),
                sunrise = root.getString("sunrise"),
                sunset = root.getString("sunset"),
                todayHighF = root.getDouble("today_high_f"),
                todayLowF = root.getDouble("today_low_f"),
                forecast = forecast,
                fetchedAt = root.getLong("fetched_at")
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Holds the last successful fetch in SharedPreferences, so the widget/app
 * always have something to show immediately (even stale) instead of a blank
 * screen. Only a *successful* fetch ever overwrites what's stored here.
 */
object WeatherCache {
    private const val PREFS_NAME = "weather"
    private const val KEY_SNAPSHOT = "snapshot_json"

    fun save(context: Context, snapshot: WeatherSnapshot) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SNAPSHOT, snapshot.toJson())
            .apply()
    }

    fun read(context: Context): WeatherSnapshot? {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SNAPSHOT, null)
            ?: return null
        return WeatherSnapshot.fromJson(json)
    }
}

/**
 * The user's Fahrenheit/Celsius display choice. Purely a display setting —
 * switching it never triggers a network fetch, it just reformats whatever's
 * already cached (see MainActivity/WeatherWidgetProvider). Stored in the
 * same prefs file as the cache since it's the same small "weather" concern.
 */
object UnitPreference {
    private const val PREFS_NAME = "weather"
    private const val KEY_FAHRENHEIT = "unit_fahrenheit"

    fun isFahrenheit(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_FAHRENHEIT, true)

    fun setFahrenheit(context: Context, fahrenheit: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FAHRENHEIT, fahrenheit)
            .apply()
    }
}
