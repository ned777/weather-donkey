package com.weatherwidget.app

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * The screen you land on when you tap the widget (before granting location)
 * or the app icon. Same "only updates when you touch it" rule as the widget
 * itself applies here too: onCreate only ever renders whatever's cached —
 * nothing is fetched automatically on launch or resume. The only ways to
 * trigger a fetch are swiping down to refresh, or granting location
 * permission for the first time (itself an explicit tap).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var fahrenheitButton: Button
    private lateinit var celsiusButton: Button
    private lateinit var locationText: TextView
    private lateinit var tempText: TextView
    private lateinit var todayHighLowText: TextView
    private lateinit var conditionEmoji: TextView
    private lateinit var conditionText: TextView
    private lateinit var sunriseText: TextView
    private lateinit var sunsetText: TextView
    private lateinit var updatedText: TextView
    private lateinit var grantPermissionButton: Button
    private lateinit var forecastHeader: TextView
    private lateinit var forecastContainer: LinearLayout

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            grantPermissionButton.visibility = if (granted) View.GONE else View.VISIBLE
            if (granted) {
                startRefresh()
            } else {
                swipeRefresh.isRefreshing = false
                updatedText.text = getString(R.string.widget_error_no_location)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        fahrenheitButton = findViewById(R.id.fahrenheitButton)
        celsiusButton = findViewById(R.id.celsiusButton)
        locationText = findViewById(R.id.locationText)
        tempText = findViewById(R.id.tempText)
        todayHighLowText = findViewById(R.id.todayHighLowText)
        conditionEmoji = findViewById(R.id.conditionEmoji)
        conditionText = findViewById(R.id.conditionText)
        sunriseText = findViewById(R.id.sunriseText)
        sunsetText = findViewById(R.id.sunsetText)
        updatedText = findViewById(R.id.updatedText)
        grantPermissionButton = findViewById(R.id.grantPermissionButton)
        forecastHeader = findViewById(R.id.forecastHeader)
        forecastContainer = findViewById(R.id.forecastContainer)

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.retro_cyan))
        swipeRefresh.setOnRefreshListener { startRefresh() }

        grantPermissionButton.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        fahrenheitButton.setOnClickListener { setUnit(fahrenheit = true) }
        celsiusButton.setOnClickListener { setUnit(fahrenheit = false) }

        updateUnitButtonsUi()
        renderFromCache()
    }

    // A unit switch is purely a display change — reformat whatever's cached and
    // repaint any placed widget, no network call.
    private fun setUnit(fahrenheit: Boolean) {
        UnitPreference.setFahrenheit(this, fahrenheit)
        updateUnitButtonsUi()
        renderFromCache()
        WeatherWidgetProvider.repaintAllWidgets(this)
    }

    private fun updateUnitButtonsUi() {
        val fahrenheit = UnitPreference.isFahrenheit(this)
        val activeColor = ContextCompat.getColor(this, R.color.retro_yellow)
        val inactiveColor = ContextCompat.getColor(this, R.color.retro_white_dim)
        fahrenheitButton.setTextColor(if (fahrenheit) activeColor else inactiveColor)
        celsiusButton.setTextColor(if (!fahrenheit) activeColor else inactiveColor)
    }

    private fun renderFromCache() {
        val hasPermission = LocationHelper.hasPermission(this)
        grantPermissionButton.visibility = if (hasPermission) View.GONE else View.VISIBLE

        val snapshot = WeatherCache.read(this)
        if (snapshot != null) {
            render(snapshot)
            updatedText.text = WeatherFormat.updatedAgoString(snapshot.fetchedAt)
        } else {
            forecastHeader.visibility = View.GONE
            forecastContainer.removeAllViews()
            updatedText.text = if (hasPermission) {
                getString(R.string.app_swipe_to_load)
            } else {
                getString(R.string.location_permission_rationale)
            }
        }
    }

    private fun startRefresh() {
        if (!LocationHelper.hasPermission(this)) {
            swipeRefresh.isRefreshing = false
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            return
        }
        swipeRefresh.isRefreshing = true
        Thread {
            val location = LocationHelper.getLocationBlocking(this)
            if (location == null) {
                runOnUiThread {
                    updatedText.text = getString(R.string.widget_error_no_location)
                    swipeRefresh.isRefreshing = false
                }
                return@Thread
            }
            val fetched = WeatherClient.fetchWeather(location.latitude, location.longitude)
            if (fetched == null) {
                runOnUiThread {
                    updatedText.text = getString(R.string.widget_error_network)
                    swipeRefresh.isRefreshing = false
                }
                return@Thread
            }
            val snapshot = fetched.copy(cityName = GeocodeHelper.cityName(this, location))
            WeatherCache.save(this, snapshot)
            runOnUiThread {
                renderFromCache()
                swipeRefresh.isRefreshing = false
                // Let any placed widget pick up this same fetch immediately, instead of
                // waiting for its own separate tap.
                WeatherWidgetProvider.repaintAllWidgets(this)
            }
        }.start()
    }

    private fun render(snapshot: WeatherSnapshot) {
        val fahrenheit = UnitPreference.isFahrenheit(this)
        locationText.text = snapshot.cityName ?: getString(R.string.current_location)
        tempText.text = WeatherFormat.tempString(snapshot.tempF, fahrenheit)
        todayHighLowText.text = WeatherFormat.highLowString(snapshot.todayHighF, snapshot.todayLowF, fahrenheit)
        val condition = WeatherCondition.fromCode(snapshot.code)
        conditionEmoji.text = condition.emoji(snapshot.isDay)
        conditionText.text = condition.label
        sunriseText.text = "↑ Sunrise ${WeatherFormat.clockTime(snapshot.sunrise)}"
        sunsetText.text = "↓ Sunset ${WeatherFormat.clockTime(snapshot.sunset)}"
        populateForecast(snapshot.forecast, fahrenheit)
    }

    private fun populateForecast(forecast: List<DailyForecast>, fahrenheit: Boolean) {
        forecastContainer.removeAllViews()
        forecastHeader.visibility = if (forecast.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        forecast.forEach { day ->
            val row = inflater.inflate(R.layout.item_forecast_day, forecastContainer, false)
            row.findViewById<TextView>(R.id.dayLabel).text = day.dateLabel
            row.findViewById<TextView>(R.id.conditionEmoji).text = WeatherCondition.fromCode(day.code).emoji(true)
            row.findViewById<TextView>(R.id.highLowText).text = WeatherFormat.highLowString(day.highF, day.lowF, fahrenheit)
            forecastContainer.addView(row)
        }
    }
}
