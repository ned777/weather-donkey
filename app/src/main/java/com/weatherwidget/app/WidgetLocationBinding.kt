package com.weatherwidget.app

import android.content.Context

/**
 * Which location EACH placed widget instance shows — set once via
 * WeatherWidgetConfigActivity when the widget is added, read by
 * WeatherWidgetProvider on every render/refresh. Defaults to the GPS
 * "current location" if a widget id was somehow never configured.
 */
object WidgetLocationBinding {
    private const val PREFS_NAME = "weather"
    private const val KEY_PREFIX = "widget_location_"

    fun get(context: Context, appWidgetId: Int): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + appWidgetId, WeatherCache.CURRENT_LOCATION_ID)
            ?: WeatherCache.CURRENT_LOCATION_ID

    fun set(context: Context, appWidgetId: Int, locationId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PREFIX + appWidgetId, locationId)
            .apply()
    }

    fun clear(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_PREFIX + appWidgetId)
            .apply()
    }
}
