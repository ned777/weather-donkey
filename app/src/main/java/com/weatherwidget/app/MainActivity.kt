package com.weatherwidget.app

import android.Manifest
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import kotlin.math.abs

/**
 * The screen you land on when you tap the widget (before granting location)
 * or the app icon. One tab per location: "Current" (GPS, always first — the
 * widget itself only ever mirrors this tab) plus any cities the user has
 * searched for and added. Same "only updates when you touch it" rule as the
 * widget applies to every tab: onCreate/tab-switch only ever render whatever
 * that location has cached — nothing is fetched automatically on launch,
 * resume, or switching tabs. The only ways to trigger a fetch are swiping
 * down to refresh, or granting location permission for the first time
 * (itself an explicit tap).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TEMP_MAX_SP = 200f
        private const val TEMP_MIN_SP = 70f

        // Matches tempText's negative side margins in activity_main.xml (which claw back
        // most of the screen's 24dp content padding) — the small visible margin left over.
        private const val TEMP_SIDE_MARGIN_DP = 8f
    }

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tabLayout: TabLayout
    private lateinit var fahrenheitButton: Button
    private lateinit var celsiusButton: Button
    private lateinit var todayLabelText: TextView
    private lateinit var tempText: TextView
    private lateinit var todayHighLowText: TextView
    private lateinit var conditionIcon: ImageView
    private lateinit var conditionText: TextView
    private lateinit var sunriseText: TextView
    private lateinit var sunsetText: TextView
    private lateinit var updatedText: TextView
    private lateinit var grantPermissionButton: Button
    private lateinit var removeLocationButton: TextView
    private lateinit var forecastHeader: TextView
    private lateinit var forecastContainer: LinearLayout
    private lateinit var fahrenheitInput: EditText
    private lateinit var celsiusInput: EditText
    private lateinit var converterSection: View

    private var savedLocations: List<SavedLocation> = emptyList()
    private var activeLocationId: String = WeatherCache.CURRENT_LOCATION_ID

    // Guards against the two converter boxes' TextWatchers feeding off each other —
    // set right before/after a programmatic setText() on the OTHER box.
    private var isConvertingTemp = false

    // Set on every ACTION_DOWN — whether THIS gesture started inside the converter, so a
    // swipe there never changes tabs (the converter "stays still", per request) while a
    // swipe starting anywhere else on the weather content does.
    private var gestureStartedInConverter = false

    private val tabSwipeDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (gestureStartedInConverter || e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                // Mostly horizontal, and a deliberate flick — not an incidental wobble
                // during a vertical scroll or the SwipeRefreshLayout's own pull-down.
                if (abs(diffX) <= abs(diffY)) return false
                val metrics = resources.displayMetrics
                val minDistancePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, metrics)
                val minVelocity = ViewConfiguration.get(this@MainActivity).scaledMinimumFlingVelocity
                if (abs(diffX) < minDistancePx || abs(velocityX) < minVelocity) return false
                // Finger moving left (diffX negative) reveals the next tab, like flipping
                // to the next card — right moves back to the previous one.
                goToAdjacentTab(if (diffX < 0) 1 else -1)
                return true
            }
        })
    }

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
        tabLayout = findViewById(R.id.tabLayout)
        fahrenheitButton = findViewById(R.id.fahrenheitButton)
        celsiusButton = findViewById(R.id.celsiusButton)
        todayLabelText = findViewById(R.id.todayLabelText)
        tempText = findViewById(R.id.tempText)
        todayHighLowText = findViewById(R.id.todayHighLowText)
        conditionIcon = findViewById(R.id.conditionIcon)
        conditionText = findViewById(R.id.conditionText)
        sunriseText = findViewById(R.id.sunriseText)
        sunsetText = findViewById(R.id.sunsetText)
        updatedText = findViewById(R.id.updatedText)
        grantPermissionButton = findViewById(R.id.grantPermissionButton)
        removeLocationButton = findViewById(R.id.removeLocationButton)
        forecastHeader = findViewById(R.id.forecastHeader)
        forecastContainer = findViewById(R.id.forecastContainer)
        fahrenheitInput = findViewById(R.id.fahrenheitInput)
        celsiusInput = findViewById(R.id.celsiusInput)
        converterSection = findViewById(R.id.converterSection)

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.retro_cyan))
        swipeRefresh.setOnRefreshListener { startRefresh() }
        setupTempConverter()

        grantPermissionButton.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        removeLocationButton.setOnClickListener { confirmRemoveActiveLocation() }
        fahrenheitButton.setOnClickListener { setUnit(fahrenheit = true) }
        celsiusButton.setOnClickListener { setUnit(fahrenheit = false) }

        savedLocations = LocationStore.list(this)
        setupTabs()
        updateUnitButtonsUi()
        renderFromCache()
    }

    // Observes every touch the Activity sees (without consuming it — normal scrolling,
    // button taps, and EditText focus/typing all still work exactly as before) purely to
    // feed tabSwipeDetector's fling detection and to remember whether THIS gesture started
    // inside the converter.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            gestureStartedInConverter = isPointInsideView(ev.rawX, ev.rawY, converterSection)
        }
        tabSwipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isPointInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + view.width &&
            rawY >= location[1] && rawY <= location[1] + view.height
    }

    // Swipes to the next/previous REAL location tab — never onto the trailing "+" tab,
    // which is an action (opens the search dialog), not a page to land on.
    private fun goToAdjacentTab(delta: Int) {
        val lastRealIndex = tabLayout.tabCount - 2 // last tab is always "+"
        if (lastRealIndex < 0) return
        val newIndex = (tabLayout.selectedTabPosition + delta).coerceIn(0, lastRealIndex)
        if (newIndex != tabLayout.selectedTabPosition) {
            tabLayout.getTabAt(newIndex)?.select()
        }
    }

    // --- Tabs -----------------------------------------------------------

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val tag = tab.tag as? String
                if (tag == null) {
                    // The trailing "+" tab has no tag — it's an action, not a real
                    // page, so open the search dialog and snap the strip back to
                    // whichever tab was actually active.
                    showAddLocationDialog()
                    rebuildTabs()
                    return
                }
                activeLocationId = tag
                swipeRefresh.isRefreshing = false
                renderFromCache()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        rebuildTabs()
    }

    private fun rebuildTabs() {
        tabLayout.removeAllTabs()
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.tab_current)).setTag(WeatherCache.CURRENT_LOCATION_ID))
        savedLocations.forEach { loc ->
            tabLayout.addTab(tabLayout.newTab().setText(loc.displayName.substringBefore(",")).setTag(loc.id))
        }
        tabLayout.addTab(tabLayout.newTab().setText("+")) // no tag — see setupTabs()

        val activeIndex = if (activeLocationId == WeatherCache.CURRENT_LOCATION_ID) {
            0
        } else {
            savedLocations.indexOfFirst { it.id == activeLocationId }.let { if (it < 0) 0 else it + 1 }
        }
        tabLayout.getTabAt(activeIndex)?.select()
    }

    // --- Adding / removing locations -------------------------------------

    private fun showAddLocationDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.add_location_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_location_title)
            .setView(input)
            .setPositiveButton(R.string.search) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) performLocationSearch(query)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performLocationSearch(query: String) {
        Thread {
            val results = GeocodeHelper.search(this, query)
            runOnUiThread {
                if (results.isEmpty()) {
                    Toast.makeText(this, getString(R.string.no_location_matches, query), Toast.LENGTH_SHORT).show()
                } else {
                    showSearchResultsDialog(results)
                }
            }
        }.start()
    }

    private fun showSearchResultsDialog(results: List<LocationSearchResult>) {
        val labels = results.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_location)
            .setItems(labels) { _, index -> addSavedLocation(results[index]) }
            .show()
    }

    private fun addSavedLocation(result: LocationSearchResult) {
        val location = SavedLocation(
            id = "%.4f,%.4f".format(result.lat, result.lon),
            displayName = result.displayName,
            lat = result.lat,
            lon = result.lon
        )
        LocationStore.add(this, location)
        savedLocations = LocationStore.list(this)
        activeLocationId = location.id
        rebuildTabs() // selecting the new tab triggers renderFromCache() via the listener
    }

    private fun confirmRemoveActiveLocation() {
        val id = activeLocationId
        if (id == WeatherCache.CURRENT_LOCATION_ID) return
        AlertDialog.Builder(this)
            .setMessage(R.string.remove_location_confirm)
            .setPositiveButton(R.string.remove) { _, _ ->
                LocationStore.remove(this, id)
                WeatherCache.clear(this, id)
                savedLocations = LocationStore.list(this)
                activeLocationId = WeatherCache.CURRENT_LOCATION_ID
                rebuildTabs()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Rendering / refreshing -------------------------------------------

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
        val id = activeLocationId
        val isCurrentTab = id == WeatherCache.CURRENT_LOCATION_ID
        val hasPermission = LocationHelper.hasPermission(this)
        grantPermissionButton.visibility = if (isCurrentTab && !hasPermission) View.VISIBLE else View.GONE
        removeLocationButton.visibility = if (isCurrentTab) View.GONE else View.VISIBLE

        val snapshot = WeatherCache.read(this, id)
        if (snapshot != null) {
            render(snapshot)
            updatedText.text = WeatherFormat.lastUpdatedTimestamp(snapshot.fetchedAt)
        } else {
            clearWeatherFieldsToPlaceholder()
            forecastHeader.visibility = View.GONE
            forecastContainer.removeAllViews()
            updatedText.text = when {
                isCurrentTab && !hasPermission -> getString(R.string.location_permission_rationale)
                else -> getString(R.string.app_swipe_to_load)
            }
        }
    }

    // So switching to a tab with nothing cached yet doesn't keep showing the
    // previous tab's numbers underneath the "swipe down to load" message.
    private fun clearWeatherFieldsToPlaceholder() {
        applyTempTextSize("--°")
        tempText.text = "--°"
        todayHighLowText.text = "H:--°  L:--°"
        conditionIcon.setImageResource(R.drawable.ic_weather_cloudy)
        conditionText.text = "--"
        sunriseText.text = "↑ Sunrise --:--"
        sunsetText.text = "↓ Sunset --:--"
    }

    private fun startRefresh() {
        val id = activeLocationId
        if (id == WeatherCache.CURRENT_LOCATION_ID) {
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
                fetchAndSave(id, location.latitude, location.longitude) { GeocodeHelper.cityName(this, location) }
            }.start()
        } else {
            val saved = savedLocations.find { it.id == id } ?: return
            swipeRefresh.isRefreshing = true
            Thread { fetchAndSave(id, saved.lat, saved.lon) { saved.displayName } }.start()
        }
    }

    // Runs on a background thread (called from startRefresh's Thread above). resolveCityName
    // is a lambda rather than a plain value so the (possibly blocking) reverse-geocode call
    // for the current-location tab only happens after the network fetch itself succeeds.
    private fun fetchAndSave(locationId: String, lat: Double, lon: Double, resolveCityName: () -> String?) {
        val fetched = WeatherClient.fetchWeather(lat, lon)
        if (fetched == null) {
            runOnUiThread {
                updatedText.text = getString(R.string.widget_error_network)
                swipeRefresh.isRefreshing = false
            }
            return
        }
        val snapshot = fetched.copy(cityName = resolveCityName())
        WeatherCache.save(this, locationId, snapshot)
        runOnUiThread {
            swipeRefresh.isRefreshing = false
            // The user may have switched tabs while this fetch was in flight — only repaint
            // if they're still looking at the location this fetch was actually for.
            if (activeLocationId == locationId) renderFromCache()
            // The widget only ever mirrors the current-location tab.
            if (locationId == WeatherCache.CURRENT_LOCATION_ID) WeatherWidgetProvider.repaintAllWidgets(this)
        }
    }

    private fun render(snapshot: WeatherSnapshot) {
        val fahrenheit = UnitPreference.isFahrenheit(this)
        val tempStr = WeatherFormat.tempString(snapshot.tempF, fahrenheit)
        applyTempTextSize(tempStr)
        tempText.text = tempStr
        todayHighLowText.text = WeatherFormat.highLowString(snapshot.todayHighF, snapshot.todayLowF, fahrenheit)
        val condition = snapshot.condition
        conditionIcon.setImageResource(condition.iconRes(snapshot.isDay))
        conditionText.text = condition.label
        sunriseText.text = "↑ Sunrise ${WeatherFormat.clockTime(snapshot.sunrise)}"
        sunsetText.text = "↓ Sunset ${WeatherFormat.clockTime(snapshot.sunset)}"
        populateForecast(snapshot.forecast, fahrenheit)
    }

    // Measures the exact string against the screen's actual available width and picks the
    // largest size (within TEMP_MAX_SP/TEMP_MIN_SP) that still fits on one line — same
    // "measure once, scale proportionally" trick WeatherWidgetProvider already uses, just
    // run here in the Activity instead of relying on framework autosize (which turned out
    // to be unreliable combined with OutlinedTextView's custom onDraw).
    private fun applyTempTextSize(text: String) {
        val metrics = resources.displayMetrics
        val paint = Paint().apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEMP_MAX_SP, metrics)
        }
        // tempText's negative side margins claw back most of the screen's padding — its
        // real available width is the full screen minus just the small margin left over.
        val availableWidthPx = metrics.widthPixels -
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEMP_SIDE_MARGIN_DP * 2, metrics)
        val measuredPx = paint.measureText(text)
        val fitSp = if (measuredPx > availableWidthPx && measuredPx > 0f) {
            (TEMP_MAX_SP * (availableWidthPx / measuredPx)).coerceAtLeast(TEMP_MIN_SP)
        } else {
            TEMP_MAX_SP
        }
        tempText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fitSp)
    }

    private fun populateForecast(forecast: List<DailyForecast>, fahrenheit: Boolean) {
        forecastContainer.removeAllViews()
        forecastHeader.visibility = if (forecast.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        forecast.forEach { day ->
            val row = inflater.inflate(R.layout.item_forecast_day, forecastContainer, false)
            row.findViewById<TextView>(R.id.dayLabel).text = day.dateLabel.uppercase()
            row.findViewById<TextView>(R.id.conditionText).text = day.condition.label
            row.findViewById<ImageView>(R.id.conditionIcon).setImageResource(day.condition.iconRes(isDay = true))
            row.findViewById<TextView>(R.id.highLowText).text = WeatherFormat.highLowString(day.highF, day.lowF, fahrenheit)
            row.findViewById<TextView>(R.id.rainText).text = WeatherFormat.rainChanceString(day.rainChancePercent)
            row.findViewById<TextView>(R.id.windText).text = WeatherFormat.windString(day.windSpeedMph)
            forecastContainer.addView(row)
        }
    }

    // Standalone unit converter at the bottom of the screen — unrelated to the weather
    // display above (no location, no tabs, just arithmetic). Typing in one box live-fills
    // the other; isConvertingTemp stops that programmatic setText() from re-triggering
    // the watcher on the box that was just filled in, which would otherwise loop forever.
    private fun setupTempConverter() {
        fahrenheitInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isConvertingTemp) return
                val f = s?.toString()?.toDoubleOrNull()
                isConvertingTemp = true
                celsiusInput.setText(if (f != null) formatConvertedTemp((f - 32.0) * 5.0 / 9.0) else "")
                isConvertingTemp = false
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        celsiusInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isConvertingTemp) return
                val c = s?.toString()?.toDoubleOrNull()
                isConvertingTemp = true
                fahrenheitInput.setText(if (c != null) formatConvertedTemp(c * 9.0 / 5.0 + 32.0) else "")
                isConvertingTemp = false
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun formatConvertedTemp(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        // Whole numbers show without a trailing ".0" — 37.0 reads as "37", 37.2 stays "37.2".
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
}
