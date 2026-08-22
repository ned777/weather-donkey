package com.weatherwidget.app

import android.content.Context

/** One successful fetch's worth of weather data, everything the widget/app need to render. */
data class WeatherSnapshot(
    val cityName: String?,
    val tempF: Double,
    val code: Int,
    val isDay: Boolean,
    val sunrise: String,
    val sunset: String,
    val fetchedAt: Long
)

/**
 * Holds the last successful fetch in SharedPreferences, so the widget/app
 * always have something to show immediately (even stale) instead of a blank
 * screen, and so a failed refresh can fall back to "last known" instead of
 * erasing everything. Same shape as SysMon's DeviceStatsCache: only a
 * *successful* fetch ever overwrites what's stored here.
 */
object WeatherCache {
    private const val PREFS_NAME = "weather"
    private const val KEY_CITY = "city"
    private const val KEY_TEMP_F = "temp_f"
    private const val KEY_CODE = "code"
    private const val KEY_IS_DAY = "is_day"
    private const val KEY_SUNRISE = "sunrise"
    private const val KEY_SUNSET = "sunset"
    private const val KEY_FETCHED_AT = "fetched_at"

    fun save(context: Context, snapshot: WeatherSnapshot) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CITY, snapshot.cityName)
            .putFloat(KEY_TEMP_F, snapshot.tempF.toFloat())
            .putInt(KEY_CODE, snapshot.code)
            .putBoolean(KEY_IS_DAY, snapshot.isDay)
            .putString(KEY_SUNRISE, snapshot.sunrise)
            .putString(KEY_SUNSET, snapshot.sunset)
            .putLong(KEY_FETCHED_AT, snapshot.fetchedAt)
            .apply()
    }

    fun read(context: Context): WeatherSnapshot? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (fetchedAt == 0L) return null
        return WeatherSnapshot(
            cityName = prefs.getString(KEY_CITY, null),
            tempF = prefs.getFloat(KEY_TEMP_F, 0f).toDouble(),
            code = prefs.getInt(KEY_CODE, 0),
            isDay = prefs.getBoolean(KEY_IS_DAY, true),
            sunrise = prefs.getString(KEY_SUNRISE, "") ?: "",
            sunset = prefs.getString(KEY_SUNSET, "") ?: "",
            fetchedAt = fetchedAt
        )
    }
}
