package com.weatherwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * How much room the widget currently has, driving both font sizes and which
 * rows show at all. Boundaries are in dp, taken from the smaller of the
 * widget's reported min width/height, so a widget that's short-and-wide or
 * tall-and-narrow is judged by its tightest dimension either way.
 */
private enum class WidgetSizeTier { COMPACT, REGULAR, EXPANDED }

/**
 * The widget's brain. There is no periodic timer at all (see
 * weather_widget_info.xml's updatePeriodMillis="0") — the ONLY thing that
 * ever triggers a network fetch is the user tapping the widget, handled in
 * onReceive() below. onUpdate() and onAppWidgetOptionsChanged() (called on
 * first placement, reboot, and resize) only ever repaint from whatever is
 * already cached.
 */
class WeatherWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.weatherwidget.app.ACTION_REFRESH"

        /**
         * Repaints every placed widget from whatever's cached, with no network call —
         * call this right after MainActivity does its own fetch (or a unit toggle), so
         * any placed widget picks up that same data immediately instead of waiting for
         * its own tap.
         */
        fun repaintAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
            ids.forEach { renderFromCache(context, manager, it) }
        }

        private fun renderFromCache(context: Context, manager: AppWidgetManager, id: Int) {
            val snapshot = WeatherCache.read(context)
            val statusText = when {
                !LocationHelper.hasPermission(context) -> context.getString(R.string.widget_need_permission)
                snapshot != null -> ""
                else -> context.getString(R.string.widget_tap_to_load)
            }
            manager.updateAppWidget(id, buildViews(context, manager, id, snapshot, statusText))
        }

        /**
         * The actual fetch: a location fix, then Open-Meteo, saving to cache only on
         * success. Always called from a background Thread (see onReceive) — never the
         * main thread, since both the location fix and the HTTP call can block for
         * seconds. Pushes an "Updating…" frame immediately so the tap feels responsive
         * even before the network call has finished.
         */
        private fun performRefresh(context: Context, manager: AppWidgetManager, id: Int) {
            if (!LocationHelper.hasPermission(context)) {
                renderFromCache(context, manager, id)
                return
            }

            val cached = WeatherCache.read(context)
            manager.updateAppWidget(id, buildViews(context, manager, id, cached, context.getString(R.string.widget_loading)))

            val location = LocationHelper.getLocationBlocking(context)
            if (location == null) {
                manager.updateAppWidget(id, buildViews(context, manager, id, cached, statusWithFallback(context, cached, R.string.widget_error_no_location)))
                return
            }

            val fetched = WeatherClient.fetchWeather(location.latitude, location.longitude)
            if (fetched == null) {
                manager.updateAppWidget(id, buildViews(context, manager, id, cached, statusWithFallback(context, cached, R.string.widget_error_network)))
                return
            }

            val snapshot = fetched.copy(cityName = GeocodeHelper.cityName(context, location))
            WeatherCache.save(context, snapshot)
            manager.updateAppWidget(id, buildViews(context, manager, id, snapshot, ""))
        }

        // e.g. "Updated 14m ago — Couldn't reach weather service — tap to retry", so a
        // failed refresh still shows how stale the last-known data is, not just "error".
        private fun statusWithFallback(context: Context, cached: WeatherSnapshot?, errorRes: Int): String {
            val error = context.getString(errorRes)
            return if (cached != null) "${WeatherFormat.updatedAgoString(cached.fetchedAt)} — $error" else error
        }

        /**
         * Builds one fully-ready RemoteViews frame: content, size-based row
         * visibility, and the click PendingIntent (which itself depends on whether
         * we have location permission yet — see setClickIntent below). An empty
         * statusText means "nothing wrong to report" and hides that line entirely.
         */
        private fun buildViews(context: Context, manager: AppWidgetManager, id: Int, snapshot: WeatherSnapshot?, statusText: String): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val fahrenheit = UnitPreference.isFahrenheit(context)

            if (snapshot != null) {
                views.setTextViewText(R.id.locationText, snapshot.cityName ?: context.getString(R.string.current_location))
                views.setTextViewText(R.id.tempText, WeatherFormat.tempString(snapshot.tempF, fahrenheit))
                views.setTextViewText(R.id.todayHighLowText, WeatherFormat.highLowString(snapshot.todayHighF, snapshot.todayLowF, fahrenheit))
                val condition = WeatherCondition.fromCode(snapshot.code)
                views.setTextViewText(R.id.conditionEmoji, condition.emoji(snapshot.isDay))
                views.setTextViewText(R.id.conditionText, condition.label)
                views.setTextViewText(R.id.sunriseText, "↑ ${WeatherFormat.clockTime(snapshot.sunrise)}")
                views.setTextViewText(R.id.sunsetText, "↓ ${WeatherFormat.clockTime(snapshot.sunset)}")

                val day1 = snapshot.forecast.getOrNull(0)
                val day2 = snapshot.forecast.getOrNull(1)
                bindForecastCell(views, day1, fahrenheit, R.id.forecast1Day, R.id.forecast1Emoji, R.id.forecast1HighLow)
                bindForecastCell(views, day2, fahrenheit, R.id.forecast2Day, R.id.forecast2Emoji, R.id.forecast2HighLow)
            } else {
                views.setTextViewText(R.id.locationText, context.getString(R.string.app_title))
                views.setTextViewText(R.id.tempText, "--°")
                views.setTextViewText(R.id.todayHighLowText, "H:--°  L:--°")
                views.setTextViewText(R.id.conditionEmoji, "")
                views.setTextViewText(R.id.conditionText, "--")
                views.setTextViewText(R.id.sunriseText, "↑ --:--")
                views.setTextViewText(R.id.sunsetText, "↓ --:--")
                bindForecastCell(views, null, fahrenheit, R.id.forecast1Day, R.id.forecast1Emoji, R.id.forecast1HighLow)
                bindForecastCell(views, null, fahrenheit, R.id.forecast2Day, R.id.forecast2Emoji, R.id.forecast2HighLow)
            }
            views.setTextViewText(R.id.updatedText, statusText)
            applyResponsiveSizing(context, views, manager, id, statusText)
            setClickIntent(context, views, id)
            return views
        }

        /**
         * Resizing the widget hides the less-essential rows (from the bottom up) AND
         * shrinks every remaining font, instead of clipping/overlapping a size that was
         * only ever laid out for the large default placement. At COMPACT (roughly a 2x2
         * grid cell) this drops down to just the temperature, location, and condition
         * icon — everything else is genuinely too tight to read at that size.
         */
        private fun applyResponsiveSizing(context: Context, views: RemoteViews, manager: AppWidgetManager, id: Int, statusText: String) {
            val options = manager.getAppWidgetOptions(id)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val tier = when (minOf(minWidth, minHeight)) {
                in 0 until 150 -> WidgetSizeTier.COMPACT
                in 150 until 230 -> WidgetSizeTier.REGULAR
                else -> WidgetSizeTier.EXPANDED
            }

            val paddingDp = when (tier) { WidgetSizeTier.COMPACT -> 8; WidgetSizeTier.REGULAR -> 10; WidgetSizeTier.EXPANDED -> 14 }
            val paddingPx = dpToPx(context, paddingDp)
            views.setViewPadding(R.id.weatherWidgetRoot, paddingPx, paddingPx, paddingPx, paddingPx)

            setSp(views, R.id.tempText, when (tier) { WidgetSizeTier.COMPACT -> 28f; WidgetSizeTier.REGULAR -> 38f; WidgetSizeTier.EXPANDED -> 48f })
            setSp(views, R.id.locationText, when (tier) { WidgetSizeTier.COMPACT -> 11f; WidgetSizeTier.REGULAR -> 13f; WidgetSizeTier.EXPANDED -> 15f })
            setSp(views, R.id.conditionEmoji, when (tier) { WidgetSizeTier.COMPACT -> 13f; WidgetSizeTier.REGULAR -> 15f; WidgetSizeTier.EXPANDED -> 16f })
            setSp(views, R.id.conditionText, when (tier) { WidgetSizeTier.REGULAR -> 12f; WidgetSizeTier.EXPANDED -> 14f; else -> 12f })
            setSp(views, R.id.todayHighLowText, when (tier) { WidgetSizeTier.REGULAR -> 12f; WidgetSizeTier.EXPANDED -> 14f; else -> 12f })
            setSp(views, R.id.sunriseText, if (tier == WidgetSizeTier.EXPANDED) 13f else 11f)
            setSp(views, R.id.sunsetText, if (tier == WidgetSizeTier.EXPANDED) 13f else 11f)
            setSp(views, R.id.updatedText, if (tier == WidgetSizeTier.EXPANDED) 10f else 8f)

            views.setViewVisibility(R.id.todayHighLowText, if (tier != WidgetSizeTier.COMPACT) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.conditionText, if (tier != WidgetSizeTier.COMPACT) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.sunRow, if (tier != WidgetSizeTier.COMPACT) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.forecastRow, if (tier == WidgetSizeTier.EXPANDED) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.updatedText, if (statusText.isNotEmpty()) View.VISIBLE else View.GONE)
        }

        private fun setSp(views: RemoteViews, viewId: Int, sizeSp: Float) {
            views.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, sizeSp)
        }

        private fun dpToPx(context: Context, dp: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).roundToInt()

        private fun bindForecastCell(views: RemoteViews, day: DailyForecast?, fahrenheit: Boolean, dayId: Int, emojiId: Int, highLowId: Int) {
            if (day != null) {
                views.setTextViewText(dayId, day.dateLabel)
                views.setTextViewText(emojiId, WeatherCondition.fromCode(day.code).emoji(true))
                views.setTextViewText(highLowId, "${WeatherFormat.tempString(day.highF, fahrenheit)}/${WeatherFormat.tempString(day.lowF, fahrenheit)}")
            } else {
                views.setTextViewText(dayId, "--")
                views.setTextViewText(emojiId, "")
                views.setTextViewText(highLowId, "--°/--°")
            }
        }

        // Tapping either fetches fresh weather (permission already granted) or opens
        // MainActivity to ask for it — a widget/BroadcastReceiver has no UI of its own,
        // so only an Activity can show the system permission dialog.
        private fun setClickIntent(context: Context, views: RemoteViews, id: Int) {
            val pendingIntent = if (LocationHelper.hasPermission(context)) {
                PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, WeatherWidgetProvider::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getActivity(
                    context, id,
                    Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.weatherWidgetRoot, pendingIntent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { renderFromCache(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        renderFromCache(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            // goAsync() + a background Thread: refreshing means a location fix plus an
            // HTTP call, both of which can take real time, but a BroadcastReceiver is
            // normally expected to finish in milliseconds and would otherwise get killed
            // mid-fetch.
            val pending = goAsync()
            Thread {
                try {
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
                    ids.forEach { performRefresh(context, manager, it) }
                } finally {
                    pending.finish()
                }
            }.start()
        } else {
            super.onReceive(context, intent)
        }
    }
}
