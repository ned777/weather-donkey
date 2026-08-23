package com.weatherwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * How much room the widget currently has, driving both font sizes and which
 * rows show at all. Taken from the smaller of the widget's reported min
 * width/height, so a widget that's short-and-wide or tall-and-narrow is
 * judged by its tightest dimension either way. Only genuinely tiny sizes
 * (roughly a 1x1/2x1 grid cell) drop to COMPACT — anything past that gets
 * the full layout, since a half-empty "medium" tier just meant smaller text
 * and a missing forecast row for no real reason.
 */
private enum class WidgetSizeTier { COMPACT, FULL }

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

        /** Called by WeatherWidgetConfigActivity right after binding a location to a brand-new widget id. */
        fun renderConfiguredWidget(context: Context, appWidgetId: Int) {
            renderFromCache(context, AppWidgetManager.getInstance(context), appWidgetId)
        }

        private fun renderFromCache(context: Context, manager: AppWidgetManager, id: Int) {
            val locationId = WidgetLocationBinding.get(context, id)
            val needsPermission = locationId == WeatherCache.CURRENT_LOCATION_ID && !LocationHelper.hasPermission(context)
            val snapshot = WeatherCache.read(context, locationId)
            val statusText = when {
                needsPermission -> context.getString(R.string.widget_need_permission)
                snapshot != null -> WeatherFormat.lastUpdatedTimestamp(snapshot.fetchedAt)
                else -> context.getString(R.string.widget_tap_to_load)
            }
            manager.updateAppWidget(id, buildViews(context, manager, id, locationId, snapshot, statusText))
        }

        /**
         * The actual fetch: for the "current" binding, a GPS location fix then
         * Open-Meteo; for a widget bound to a searched city, straight to Open-Meteo
         * using that city's fixed coordinates — no location permission needed at all
         * for that case. Always called from a background Thread (see onReceive) —
         * never the main thread. Pushes an "Updating…" frame immediately so the tap
         * feels responsive even before the network call has finished.
         */
        private fun performRefresh(context: Context, manager: AppWidgetManager, id: Int) {
            val locationId = WidgetLocationBinding.get(context, id)

            if (locationId == WeatherCache.CURRENT_LOCATION_ID) {
                if (!LocationHelper.hasPermission(context)) {
                    renderFromCache(context, manager, id)
                    return
                }
                val cached = WeatherCache.read(context, locationId)
                manager.updateAppWidget(id, buildViews(context, manager, id, locationId, cached, context.getString(R.string.widget_loading)))

                val location = LocationHelper.getLocationBlocking(context)
                if (location == null) {
                    manager.updateAppWidget(id, buildViews(context, manager, id, locationId, cached, statusWithFallback(context, cached, R.string.widget_error_no_location)))
                    return
                }

                val fetched = WeatherClient.fetchWeather(location.latitude, location.longitude)
                if (fetched == null) {
                    manager.updateAppWidget(id, buildViews(context, manager, id, locationId, cached, statusWithFallback(context, cached, R.string.widget_error_network)))
                    return
                }

                val snapshot = fetched.copy(cityName = GeocodeHelper.cityName(context, location))
                WeatherCache.save(context, locationId, snapshot)
                manager.updateAppWidget(id, buildViews(context, manager, id, locationId, snapshot, WeatherFormat.lastUpdatedTimestamp(snapshot.fetchedAt)))
            } else {
                val saved = LocationStore.list(context).find { it.id == locationId }
                if (saved == null) {
                    // The location this widget was bound to got removed from the app
                    // since — fall back to whatever's cached (nothing) rather than crash.
                    renderFromCache(context, manager, id)
                    return
                }
                val cached = WeatherCache.read(context, locationId)
                manager.updateAppWidget(id, buildViews(context, manager, id, locationId, cached, context.getString(R.string.widget_loading)))

                val fetched = WeatherClient.fetchWeather(saved.lat, saved.lon)
                if (fetched == null) {
                    manager.updateAppWidget(id, buildViews(context, manager, id, locationId, cached, statusWithFallback(context, cached, R.string.widget_error_network)))
                    return
                }

                val snapshot = fetched.copy(cityName = saved.displayName)
                WeatherCache.save(context, locationId, snapshot)
                manager.updateAppWidget(id, buildViews(context, manager, id, locationId, snapshot, WeatherFormat.lastUpdatedTimestamp(snapshot.fetchedAt)))
            }
        }

        // e.g. "3:45 PM · Aug 23 · PDT — Couldn't reach weather service — tap to retry", so a
        // failed refresh still shows when the last good data came in, not just "error".
        private fun statusWithFallback(context: Context, cached: WeatherSnapshot?, errorRes: Int): String {
            val error = context.getString(errorRes)
            return if (cached != null) "${WeatherFormat.lastUpdatedTimestamp(cached.fetchedAt)} — $error" else error
        }

        /**
         * Builds one fully-ready RemoteViews frame: content, size-based row
         * visibility, and the click PendingIntent (which itself depends on whether
         * we have location permission yet — see setClickIntent below). An empty
         * statusText means "nothing wrong to report" and hides that line entirely.
         */
        private fun buildViews(context: Context, manager: AppWidgetManager, id: Int, locationId: String, snapshot: WeatherSnapshot?, statusText: String): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val fahrenheit = UnitPreference.isFahrenheit(context)

            val cityStr: String
            val tempStr: String
            val todayHighLowStr: String
            val todayRainStr: String
            val conditionStr: String
            val sunriseStr: String
            val sunsetStr: String
            val day1: DailyForecast?
            val day2: DailyForecast?

            if (snapshot != null) {
                cityStr = widgetCityLabel(snapshot.cityName ?: context.getString(R.string.current_location))
                tempStr = WeatherFormat.tempString(snapshot.tempF, fahrenheit)
                todayHighLowStr = WeatherFormat.highLowString(snapshot.todayHighF, snapshot.todayLowF, fahrenheit)
                todayRainStr = WeatherFormat.rainChanceString(snapshot.todayRainChancePercent)
                conditionStr = snapshot.condition.label
                sunriseStr = "↑ ${WeatherFormat.clockTime(snapshot.sunrise)}"
                sunsetStr = "↓ ${WeatherFormat.clockTime(snapshot.sunset)}"
                day1 = snapshot.forecast.getOrNull(0)
                day2 = snapshot.forecast.getOrNull(1)
            } else {
                // No fetch has happened yet for this widget — still show which location
                // it's bound to (helpful right after configuring it) if it's not "current".
                cityStr = widgetCityLabel(
                    if (locationId == WeatherCache.CURRENT_LOCATION_ID) {
                        context.getString(R.string.current_location)
                    } else {
                        LocationStore.list(context).find { it.id == locationId }?.displayName
                            ?: context.getString(R.string.current_location)
                    }
                )
                tempStr = "--°"
                todayHighLowStr = "H:--°  L:--°"
                todayRainStr = "Rain: --%"
                conditionStr = "--"
                sunriseStr = "↑ --:--"
                sunsetStr = "↓ --:--"
                day1 = null
                day2 = null
            }
            val todayLabel = context.getString(R.string.widget_today_label)
            val forecast1Day = (day1?.dateLabel ?: "--").uppercase()
            val forecast2Day = (day2?.dateLabel ?: "--").uppercase()
            val forecast1HighLow = forecastHighLowString(day1, fahrenheit)
            val forecast2HighLow = forecastHighLowString(day2, fahrenheit)
            val forecast1Condition = day1?.condition?.label ?: "--"
            val forecast2Condition = day2?.condition?.label ?: "--"
            val forecast1Rain = forecastRainString(day1)
            val forecast2Rain = forecastRainString(day2)

            views.setTextViewText(R.id.cityText, cityStr)
            views.setTextViewText(R.id.todayLabelText, todayLabel)
            views.setTextViewText(R.id.tempText, tempStr)
            views.setTextViewText(R.id.todayHighLowText, todayHighLowStr)
            views.setTextViewText(R.id.todayRainText, todayRainStr)
            views.setTextViewText(R.id.conditionText, conditionStr)
            views.setTextViewText(R.id.sunriseText, sunriseStr)
            views.setTextViewText(R.id.sunsetText, sunsetStr)
            views.setTextViewText(R.id.updatedText, statusText)
            views.setTextViewText(R.id.forecast1Day, forecast1Day)
            views.setTextViewText(R.id.forecast1HighLow, forecast1HighLow)
            views.setTextViewText(R.id.forecast1Condition, forecast1Condition)
            views.setTextViewText(R.id.forecast1Rain, forecast1Rain)
            views.setTextViewText(R.id.forecast2Day, forecast2Day)
            views.setTextViewText(R.id.forecast2HighLow, forecast2HighLow)
            views.setTextViewText(R.id.forecast2Condition, forecast2Condition)
            views.setTextViewText(R.id.forecast2Rain, forecast2Rain)

            applyResponsiveSizing(
                context, views, manager, id,
                tempStr, todayHighLowStr, todayRainStr, todayLabel, conditionStr, sunriseStr, sunsetStr,
                forecast1Day, forecast2Day, forecast1HighLow, forecast2HighLow,
                forecast1Condition, forecast2Condition, forecast1Rain, forecast2Rain
            )
            setClickIntent(context, views, id, locationId)
            return views
        }

        // The city header is narrow — a long name gets a hard character-count
        // truncation ("San Francisco" -> "San Fran..") on top of (not instead of) the
        // normal width-based fit-sizing/ellipsize. 8 chars + ".." = 10 chars max.
        private fun widgetCityLabel(name: String): String =
            if (name.length > 8) name.take(8) + ".." else name

        private fun forecastHighLowString(day: DailyForecast?, fahrenheit: Boolean): String =
            if (day != null) "${WeatherFormat.tempString(day.highF, fahrenheit)}/${WeatherFormat.tempString(day.lowF, fahrenheit)}" else "--°/--°"

        private fun forecastRainString(day: DailyForecast?): String =
            if (day != null) WeatherFormat.rainChanceString(day.rainChancePercent) else "Rain: --%"

        /**
         * COMPACT (roughly a 1x1/2x1 grid cell) drops to just the temperature,
         * location, and condition word — genuinely too little room for more. Every
         * other size gets the FULL layout: today's high/low, sunrise/sunset (each on
         * their own line), and the two-day forecast row filling the bottom half.
         * Padding is always a visible constant margin either way, never near-zero.
         * No icons anywhere in this widget, by request — condition is always words.
         *
         * Every text size below is a *maximum* — fitWidthSp() measures the actual
         * string against the column it has to live in and shrinks from there if
         * needed (the same "measure once, scale proportionally" trick
         * DateWidgetProvider uses for the date widget's day number), so nothing ever
         * wraps onto a second line and eats space the forecast row needed.
         */
        private fun applyResponsiveSizing(
            context: Context, views: RemoteViews, manager: AppWidgetManager, id: Int,
            tempStr: String, todayHighLowStr: String, todayRainStr: String, todayLabel: String, conditionStr: String,
            sunriseStr: String, sunsetStr: String, forecast1Day: String, forecast2Day: String,
            forecast1HighLow: String, forecast2HighLow: String, forecast1Condition: String, forecast2Condition: String,
            forecast1Rain: String, forecast2Rain: String
        ) {
            val options = manager.getAppWidgetOptions(id)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val tier = if (minOf(minWidthDp, minHeightDp) < 130) WidgetSizeTier.COMPACT else WidgetSizeTier.FULL

            // Sides/bottom keep the original margin; the top is trimmed down a bit
            // smaller by request, so the city name sits closer to the top edge.
            val paddingDp = if (tier == WidgetSizeTier.COMPACT) 10 else 16
            val topPaddingDp = if (tier == WidgetSizeTier.COMPACT) 6 else 10
            val paddingPx = dpToPx(context, paddingDp)
            val topPaddingPx = dpToPx(context, topPaddingDp)
            views.setViewPadding(R.id.weatherWidgetRoot, paddingPx, topPaddingPx, paddingPx, paddingPx)

            // Both the top row's two columns and the forecast row's two cells split the
            // remaining width 50/50 with no gap between them (see widget_weather.xml) —
            // one shared "half column" width, minus a small buffer so text never rides
            // right up against the middle. The city name spans the FULL width instead.
            val widthPx = dpToPx(context, minWidthDp)
            val fullWidthPx = (widthPx - 2 * paddingPx).toFloat().coerceAtLeast(0f)
            val halfColumnPx = (fullWidthPx / 2f - dpToPx(context, 4)).coerceAtLeast(0f)

            val tempMaxSp = if (tier == WidgetSizeTier.FULL) 56f else 30f
            val tempSp = fitWidthSp(context, tempStr, halfColumnPx, tempMaxSp, tempMaxSp * 0.45f, bold = true)
            setSp(context, views, R.id.tempText, tempSp)

            // Day label and condition word (forecastLabelSp) is computed up front — at
            // FULL tier, "TODAY" and the city name both match it exactly (by request),
            // rather than "TODAY" being fit independently.
            val forecastLabelSp = if (tier == WidgetSizeTier.FULL) {
                minOf(
                    fitWidthSp(context, forecast1Day, halfColumnPx, 13f, 8f, bold = true),
                    fitWidthSp(context, forecast2Day, halfColumnPx, 13f, 8f, bold = true),
                    fitWidthSp(context, forecast1Condition, halfColumnPx, 13f, 8f, bold = false),
                    fitWidthSp(context, forecast2Condition, halfColumnPx, 13f, 8f, bold = false)
                )
            } else {
                0f
            }

            val todaySp = if (tier == WidgetSizeTier.FULL) {
                forecastLabelSp
            } else {
                fitWidthSp(context, todayLabel, halfColumnPx, 11f, 9f, bold = true)
            }
            setSp(context, views, R.id.todayLabelText, todaySp)
            setSp(context, views, R.id.cityText, todaySp)

            // conditionText stays visible at every size (it's the only thing telling you
            // sunny/cloudy/rainy/etc. now that there's no icon) — just smaller when tight.
            val conditionSp = fitWidthSp(context, conditionStr, halfColumnPx, if (tier == WidgetSizeTier.FULL) 14f else 11f, 8f, bold = true)
            setSp(context, views, R.id.conditionText, conditionSp)

            if (tier == WidgetSizeTier.FULL) {
                val todayHighLowSp = fitWidthSp(context, todayHighLowStr, halfColumnPx, 14f, 10f, bold = true)
                val todayRainSp = fitWidthSp(context, todayRainStr, halfColumnPx, 12f, 9f, bold = false)
                val sunriseSp = fitWidthSp(context, sunriseStr, halfColumnPx, 13f, 10f, bold = false)
                val sunsetSp = fitWidthSp(context, sunsetStr, halfColumnPx, 13f, 10f, bold = false)
                setSp(context, views, R.id.todayHighLowText, todayHighLowSp)
                setSp(context, views, R.id.todayRainText, todayRainSp)
                setSp(context, views, R.id.sunriseText, sunriseSp)
                setSp(context, views, R.id.sunsetText, sunsetSp)

                // Both forecast cells use the smaller of the two needed sizes, so they stay
                // visually matched instead of one day's number being bigger than the other's.
                var forecastHighLowSp = minOf(
                    fitWidthSp(context, forecast1HighLow, halfColumnPx, 13f, 9f, bold = false),
                    fitWidthSp(context, forecast2HighLow, halfColumnPx, 13f, 9f, bold = false)
                )
                var finalForecastLabelSp = forecastLabelSp
                var forecastRainSp = minOf(
                    fitWidthSp(context, forecast1Rain, halfColumnPx, 11f, 8f, bold = false),
                    fitWidthSp(context, forecast2Rain, halfColumnPx, 11f, 8f, bold = false)
                )

                // Width-fitting alone isn't enough — the forecast cell stacks FOUR lines
                // (day/high-low/condition/rain) inside forecastRow's fixed, weighted height.
                // Nothing had ever checked whether that stack's total height actually fits,
                // so the last line (rain) could get silently clipped by the parent even
                // though each line individually fit its column's width. Measure the real
                // vertical budget (total height minus padding, the top row, and every
                // margin/line around it) and shrink the forecast text uniformly if the
                // stack is taller than what's actually left for it.
                val cityHeightPx = textHeightPx(context, todaySp, bold = true)
                val leftColHeightPx = textHeightPx(context, tempSp, bold = true) +
                    textHeightPx(context, todayHighLowSp, bold = true) +
                    textHeightPx(context, todayRainSp, bold = false)
                val rightColHeightPx = textHeightPx(context, todaySp, bold = true) +
                    textHeightPx(context, conditionSp, bold = true) +
                    textHeightPx(context, sunriseSp, bold = false) +
                    textHeightPx(context, sunsetSp, bold = false)
                val topRowHeightPx = maxOf(leftColHeightPx, rightColHeightPx)
                val updatedHeightPx = textHeightPx(context, 10f, bold = false)
                val totalHeightPx = dpToPx(context, minHeightDp).toFloat()

                val forecastAvailableHeightPx = (
                    totalHeightPx - topPaddingPx - paddingPx - cityHeightPx -
                        dpToPx(context, 2) - topRowHeightPx -
                        dpToPx(context, 8) - dpToPx(context, 4) - updatedHeightPx
                    ).coerceAtLeast(0f)

                val neededStackHeightPx = textHeightPx(context, finalForecastLabelSp, bold = true) +
                    textHeightPx(context, forecastHighLowSp, bold = false) +
                    textHeightPx(context, finalForecastLabelSp, bold = false) +
                    textHeightPx(context, forecastRainSp, bold = false)

                if (neededStackHeightPx > forecastAvailableHeightPx && neededStackHeightPx > 0f) {
                    val scale = forecastAvailableHeightPx / neededStackHeightPx
                    finalForecastLabelSp = (finalForecastLabelSp * scale).coerceAtLeast(7f)
                    forecastHighLowSp = (forecastHighLowSp * scale).coerceAtLeast(7f)
                    forecastRainSp = (forecastRainSp * scale).coerceAtLeast(7f)
                }

                setSp(context, views, R.id.forecast1HighLow, forecastHighLowSp)
                setSp(context, views, R.id.forecast2HighLow, forecastHighLowSp)
                setSp(context, views, R.id.forecast1Day, finalForecastLabelSp)
                setSp(context, views, R.id.forecast2Day, finalForecastLabelSp)
                setSp(context, views, R.id.forecast1Condition, finalForecastLabelSp)
                setSp(context, views, R.id.forecast2Condition, finalForecastLabelSp)
                setSp(context, views, R.id.forecast1Rain, forecastRainSp)
                setSp(context, views, R.id.forecast2Rain, forecastRainSp)
            }
            setSp(context, views, R.id.updatedText, 10f)

            views.setViewVisibility(R.id.todayHighLowText, if (tier == WidgetSizeTier.FULL) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.todayRainText, if (tier == WidgetSizeTier.FULL) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.sunRow, if (tier == WidgetSizeTier.FULL) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.forecastRow, if (tier == WidgetSizeTier.FULL) View.VISIBLE else View.GONE)
        }

        // RemoteViews are actually inflated and drawn by the HOST LAUNCHER process, not
        // this app's — and "sp" is a live unit that gets re-resolved against whatever the
        // system font scale is AT DRAW TIME, even for a frame that was already pushed and
        // never touched again. That means a widget already on the home screen can go from
        // "fits fine" to "text cut off" the moment the user changes Settings > Display >
        // Font size, with no tap or refresh involved at all. Setting the size in raw PIXELS
        // instead (COMPLEX_UNIT_PX) freezes it: every render here already measures against
        // the CURRENT font scale (spToPx below reads it via displayMetrics), so converting
        // that fitted result to px up front locks in a size immune to any font-scale change
        // that happens later — the next tap/refresh will simply recompute fresh against
        // whatever the scale is by then.
        private fun setSp(context: Context, views: RemoteViews, viewId: Int, sizeSp: Float) {
            views.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_PX, spToPx(context, sizeSp))
        }

        private fun dpToPx(context: Context, dp: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).roundToInt()

        private fun spToPx(context: Context, sp: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

        private fun textWidthPx(context: Context, text: String, sizeSp: Float, bold: Boolean): Float {
            if (text.isEmpty()) return 0f
            val paint = Paint().apply {
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textSize = spToPx(context, sizeSp)
            }
            return paint.measureText(text)
        }

        // Ascent is negative in Android's convention, so descent - ascent is the font's
        // full cap-to-descender line height in px — used to budget vertical space the same
        // way textWidthPx budgets horizontal space.
        private fun textHeightPx(context: Context, sizeSp: Float, bold: Boolean): Float {
            val paint = Paint().apply {
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textSize = spToPx(context, sizeSp)
            }
            val metrics = paint.fontMetrics
            return metrics.descent - metrics.ascent
        }

        // Shrinks (never grows) sizeSp so `text` renders within maxWidthPx at that size.
        private fun fitWidthSp(context: Context, text: String, maxWidthPx: Float, sizeSp: Float, minSp: Float, bold: Boolean): Float {
            if (maxWidthPx <= 0f) return sizeSp
            val measured = textWidthPx(context, text, sizeSp, bold)
            if (measured <= maxWidthPx || measured <= 0f) return sizeSp
            return (sizeSp * (maxWidthPx / measured)).coerceAtLeast(minSp)
        }

        // Tapping either fetches fresh weather or opens MainActivity to ask for
        // location permission — a widget/BroadcastReceiver has no UI of its own, so
        // only an Activity can show the system permission dialog. Only the "current"
        // (GPS) binding ever needs that fallback; a widget bound to a searched city
        // never needs location permission at all, so it always just refreshes.
        private fun setClickIntent(context: Context, views: RemoteViews, id: Int, locationId: String) {
            val needsPermission = locationId == WeatherCache.CURRENT_LOCATION_ID && !LocationHelper.hasPermission(context)
            val pendingIntent = if (!needsPermission) {
                PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, WeatherWidgetProvider::class.java)
                        .setAction(ACTION_REFRESH)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
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

    // Each removed widget's location binding is just dead weight once it's gone —
    // clear it out instead of letting these accumulate forever in SharedPreferences.
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetLocationBinding.clear(context, it) }
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
            // Different widget instances can now be bound to different locations (see
            // WeatherWidgetConfigActivity), so a tap only refreshes the ONE widget that
            // was actually tapped — refreshing every placed widget here would mean
            // tapping a widget showing one city also fetches for every other city's
            // widget, which isn't what "only updates when you touch it" means anymore.
            val tappedId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

            // goAsync() + a background Thread: refreshing means a location fix plus an
            // HTTP call, both of which can take real time, but a BroadcastReceiver is
            // normally expected to finish in milliseconds and would otherwise get killed
            // mid-fetch.
            val pending = goAsync()
            Thread {
                try {
                    val manager = AppWidgetManager.getInstance(context)
                    if (tappedId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        performRefresh(context, manager, tappedId)
                    } else {
                        // Defensive fallback only — every ACTION_REFRESH we send ourselves
                        // always carries EXTRA_APPWIDGET_ID (see setClickIntent).
                        val ids = manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
                        ids.forEach { performRefresh(context, manager, it) }
                    }
                } finally {
                    pending.finish()
                }
            }.start()
        } else {
            super.onReceive(context, intent)
        }
    }
}
