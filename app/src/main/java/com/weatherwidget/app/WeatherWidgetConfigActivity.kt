package com.weatherwidget.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Shown automatically by Android right after you drag a new Weather widget
 * onto your home screen (declared via android:configure in
 * weather_widget_info.xml) — lets you pick which location THIS widget
 * instance should show: "Current" (GPS) or any city already added as a tab
 * in the app. The choice is saved by WidgetLocationBinding, keyed to this
 * specific widget id, so multiple Weather widgets can each watch a
 * different city.
 */
class WeatherWidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Standard widget-config convention: default to CANCELED so backing out
        // of this screen without picking anything discards the half-added widget
        // instead of leaving a broken one on the home screen.
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val container = findViewById<LinearLayout>(R.id.configLocationList)
        val inflater = LayoutInflater.from(this)

        addLocationRow(container, inflater, getString(R.string.tab_current), WeatherCache.CURRENT_LOCATION_ID)
        LocationStore.list(this).forEach { location ->
            addLocationRow(container, inflater, location.displayName, location.id)
        }
    }

    private fun addLocationRow(container: LinearLayout, inflater: LayoutInflater, label: String, locationId: String) {
        val row = inflater.inflate(R.layout.item_widget_config_location, container, false)
        row.findViewById<TextView>(R.id.configLocationLabel).text = label
        row.setOnClickListener {
            WidgetLocationBinding.set(this, appWidgetId, locationId)
            // Render once immediately from whatever's cached (nothing, for a brand new
            // binding) — same "never fetch on its own" rule the widget always follows;
            // the very first tap on the placed widget is what triggers its first fetch.
            WeatherWidgetProvider.renderConfiguredWidget(this, appWidgetId)

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }
        container.addView(row)
    }
}
