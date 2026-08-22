package com.weatherwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

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
         * call this right after MainActivity does its own fetch, so any placed widget
         * picks up that same data immediately instead of waiting for its own tap.
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
                snapshot != null -> WeatherFormat.updatedAgoString(snapshot.fetchedAt)
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
            manager.updateAppWidget(id, buildViews(context, manager, id, snapshot, WeatherFormat.updatedAgoString(snapshot.fetchedAt)))
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
         * we have location permission yet — see setClickIntent below).
         */
        private fun buildViews(context: Context, manager: AppWidgetManager, id: Int, snapshot: WeatherSnapshot?, statusText: String): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)

            if (snapshot != null) {
                views.setTextViewText(R.id.locationText, snapshot.cityName ?: context.getString(R.string.current_location))
                views.setTextViewText(R.id.tempText, WeatherFormat.tempString(snapshot.tempF))
                val condition = WeatherCondition.fromCode(snapshot.code)
                views.setTextViewText(R.id.conditionEmoji, condition.emoji(snapshot.isDay))
                views.setTextViewText(R.id.conditionText, condition.label)
                views.setTextViewText(R.id.sunriseText, "↑ ${WeatherFormat.clockTime(snapshot.sunrise)}")
                views.setTextViewText(R.id.sunsetText, "↓ ${WeatherFormat.clockTime(snapshot.sunset)}")
            } else {
                views.setTextViewText(R.id.locationText, context.getString(R.string.app_title))
                views.setTextViewText(R.id.tempText, "--°")
                views.setTextViewText(R.id.conditionEmoji, "")
                views.setTextViewText(R.id.conditionText, "--")
                views.setTextViewText(R.id.sunriseText, "↑ --:--")
                views.setTextViewText(R.id.sunsetText, "↓ --:--")
            }
            views.setTextViewText(R.id.updatedText, statusText)

            // Resizing the widget on the home screen (down to as small as 2x1-ish)
            // hides the less essential rows instead of letting them clip/overlap.
            val options = manager.getAppWidgetOptions(id)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            views.setViewVisibility(R.id.sunRow, if (minHeight >= 110) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.updatedText, if (minHeight >= 130) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.locationText, if (minHeight >= 90) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.conditionText, if (minWidth >= 110) View.VISIBLE else View.GONE)

            setClickIntent(context, views, id)
            return views
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
