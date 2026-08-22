package com.weatherwidget.app

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * The screen you land on when you tap the widget (before granting location)
 * or the app icon. Same "only updates when you touch it" rule as the widget
 * itself applies here too: onCreate only ever renders whatever's cached —
 * nothing is fetched automatically on launch or resume, only when you tap
 * Refresh (or just granted permission, which is itself an explicit tap).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var locationText: TextView
    private lateinit var tempText: TextView
    private lateinit var conditionEmoji: TextView
    private lateinit var conditionText: TextView
    private lateinit var sunriseText: TextView
    private lateinit var sunsetText: TextView
    private lateinit var updatedText: TextView
    private lateinit var grantPermissionButton: Button
    private lateinit var refreshButton: Button

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            grantPermissionButton.visibility = if (granted) View.GONE else View.VISIBLE
            if (granted) {
                startRefresh()
            } else {
                updatedText.text = getString(R.string.widget_error_no_location)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        locationText = findViewById(R.id.locationText)
        tempText = findViewById(R.id.tempText)
        conditionEmoji = findViewById(R.id.conditionEmoji)
        conditionText = findViewById(R.id.conditionText)
        sunriseText = findViewById(R.id.sunriseText)
        sunsetText = findViewById(R.id.sunsetText)
        updatedText = findViewById(R.id.updatedText)
        grantPermissionButton = findViewById(R.id.grantPermissionButton)
        refreshButton = findViewById(R.id.refreshButton)

        grantPermissionButton.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        refreshButton.setOnClickListener { startRefresh() }

        renderFromCache()
    }

    private fun renderFromCache() {
        val hasPermission = LocationHelper.hasPermission(this)
        grantPermissionButton.visibility = if (hasPermission) View.GONE else View.VISIBLE

        val snapshot = WeatherCache.read(this)
        if (snapshot != null) {
            render(snapshot)
            updatedText.text = WeatherFormat.updatedAgoString(snapshot.fetchedAt)
        } else {
            updatedText.text = if (hasPermission) {
                getString(R.string.widget_tap_to_load)
            } else {
                getString(R.string.location_permission_rationale)
            }
        }
    }

    private fun startRefresh() {
        if (!LocationHelper.hasPermission(this)) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            return
        }
        updatedText.text = getString(R.string.widget_loading)
        Thread {
            val location = LocationHelper.getLocationBlocking(this)
            if (location == null) {
                runOnUiThread { updatedText.text = getString(R.string.widget_error_no_location) }
                return@Thread
            }
            val fetched = WeatherClient.fetchWeather(location.latitude, location.longitude)
            if (fetched == null) {
                runOnUiThread { updatedText.text = getString(R.string.widget_error_network) }
                return@Thread
            }
            val snapshot = fetched.copy(cityName = GeocodeHelper.cityName(this, location))
            WeatherCache.save(this, snapshot)
            runOnUiThread {
                render(snapshot)
                updatedText.text = WeatherFormat.updatedAgoString(snapshot.fetchedAt)
                // Let any placed widget pick up this same fetch immediately, instead of
                // waiting for its own separate tap.
                WeatherWidgetProvider.repaintAllWidgets(this)
            }
        }.start()
    }

    private fun render(snapshot: WeatherSnapshot) {
        locationText.text = snapshot.cityName ?: getString(R.string.current_location)
        tempText.text = WeatherFormat.tempString(snapshot.tempF)
        val condition = WeatherCondition.fromCode(snapshot.code)
        conditionEmoji.text = condition.emoji(snapshot.isDay)
        conditionText.text = condition.label
        sunriseText.text = "↑ Sunrise ${WeatherFormat.clockTime(snapshot.sunrise)}"
        sunsetText.text = "↓ Sunset ${WeatherFormat.clockTime(snapshot.sunset)}"
    }
}
