package com.weatherwidget.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// `data class` is Kotlin's shorthand for a plain value-holder: listing the fields once
// in the constructor automatically gives you equals()/toString()/copy() for free, so
// there's no boilerplate to write by hand for a type that's just "a bundle of fields."
/** One day's high/low/condition, used for the 5-day forecast list and the widget's 2-day row. */
data class DailyForecast(
    val dateLabel: String,
    val highF: Double,
    val lowF: Double,
    val code: Int,
    val windSpeedMph: Double,
    val rainChancePercent: Int
) {
    val condition: WeatherCondition get() = WeatherCondition.fromCodeAndWind(code, windSpeedMph)
}

/**
 * One successful fetch's worth of weather data: today's current reading plus
 * the next 5 days, everything the widget/app need to render.
 */
data class WeatherSnapshot(
    val cityName: String?,
    val tempF: Double,
    val code: Int,
    val isDay: Boolean,
    val windSpeedMph: Double,
    val sunrise: String,
    val sunset: String,
    val todayHighF: Double,
    val todayLowF: Double,
    val todayRainChancePercent: Int,
    val forecast: List<DailyForecast>, // tomorrow .. +5 days, oldest first
    val fetchedAt: Long
) {
    val condition: WeatherCondition get() = WeatherCondition.fromCodeAndWind(code, windSpeedMph)

    fun toJson(): String {
        val root = JSONObject()
        root.put("city", cityName ?: JSONObject.NULL)
        root.put("temp_f", tempF)
        root.put("code", code)
        root.put("is_day", isDay)
        root.put("wind_mph", windSpeedMph)
        root.put("sunrise", sunrise)
        root.put("sunset", sunset)
        root.put("today_high_f", todayHighF)
        root.put("today_low_f", todayLowF)
        root.put("today_rain_pct", todayRainChancePercent)
        root.put("fetched_at", fetchedAt)
        val forecastArray = JSONArray()
        forecast.forEach { day ->
            forecastArray.put(
                JSONObject()
                    .put("label", day.dateLabel)
                    .put("high_f", day.highF)
                    .put("low_f", day.lowF)
                    .put("code", day.code)
                    .put("wind_mph", day.windSpeedMph)
                    .put("rain_pct", day.rainChancePercent)
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
                    code = day.getInt("code"),
                    windSpeedMph = day.optDouble("wind_mph", 0.0),
                    rainChancePercent = day.optInt("rain_pct", 0)
                )
            }
            WeatherSnapshot(
                cityName = root.optString("city", "").takeUnless { it.isEmpty() },
                tempF = root.getDouble("temp_f"),
                code = root.getInt("code"),
                isDay = root.getBoolean("is_day"),
                windSpeedMph = root.optDouble("wind_mph", 0.0),
                sunrise = root.getString("sunrise"),
                sunset = root.getString("sunset"),
                todayHighF = root.getDouble("today_high_f"),
                todayLowF = root.getDouble("today_low_f"),
                todayRainChancePercent = root.optInt("today_rain_pct", 0),
                forecast = forecast,
                fetchedAt = root.getLong("fetched_at")
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Holds the last successful fetch per location in SharedPreferences, so the
 * widget/app always have something to show immediately (even stale) instead
 * of a blank screen. Only a *successful* fetch ever overwrites what's stored
 * here. Keyed by locationId so the GPS "current location" tab and every
 * searched-city tab each keep their own independent last-known reading —
 * see CURRENT_LOCATION_ID and LocationStore.
 */
object WeatherCache {
    const val CURRENT_LOCATION_ID = "current"

    private const val PREFS_NAME = "weather"
    private const val KEY_SNAPSHOT_PREFIX = "snapshot_json_"

    // SharedPreferences is Android's built-in tiny key-value store — good for small
    // settings/state like this, not for large structured data (which is why the
    // WeatherSnapshot itself gets serialized to a single JSON string first, rather
    // than trying to store each of its fields as a separate preference key).
    // `.edit()` opens a batch of changes, and `.apply()` saves them all to disk in
    // the background — the standard read/modify/write pattern for SharedPreferences.
    fun save(context: Context, locationId: String, snapshot: WeatherSnapshot) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SNAPSHOT_PREFIX + locationId, snapshot.toJson())
            .apply()
    }

    fun read(context: Context, locationId: String): WeatherSnapshot? {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT_PREFIX + locationId, null) ?: return null
        return WeatherSnapshot.fromJson(json)
    }

    fun clear(context: Context, locationId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_SNAPSHOT_PREFIX + locationId)
            .apply()
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
