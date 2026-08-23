package com.weatherwidget.app

/**
 * Buckets Open-Meteo's WMO weather code (https://open-meteo.com/en/docs,
 * "WMO Weather interpretation codes") plus wind speed into six everyday
 * states. Wind speed isn't part of the WMO code at all — it's a separate
 * field Open-Meteo reports alongside it — so WINDY only fires when wind
 * crosses WINDY_THRESHOLD_MPH; rain/snow still take priority over it either
 * way, since getting wet matters more than a breeze.
 */
enum class WeatherCondition(val label: String) {
    SUNNY("Sunny"),
    PARTIAL("Partial"),
    CLOUDY("Cloudy"),
    WINDY("Windy"),
    RAINY("Rainy"),
    SNOWY("Snowy");

    /** SUNNY swaps to a moon icon at night; every other state is day/night-agnostic. */
    fun iconRes(isDay: Boolean): Int = when (this) {
        SUNNY -> if (isDay) R.drawable.ic_weather_sunny else R.drawable.ic_weather_clear_night
        PARTIAL -> R.drawable.ic_weather_partial
        CLOUDY -> R.drawable.ic_weather_cloudy
        WINDY -> R.drawable.ic_weather_windy
        RAINY -> R.drawable.ic_weather_rainy
        SNOWY -> R.drawable.ic_weather_snowy
    }

    companion object {
        private const val WINDY_THRESHOLD_MPH = 20.0

        private val RAIN_CODES = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
        private val SNOW_CODES = setOf(71, 73, 75, 77, 85, 86)

        fun fromCodeAndWind(code: Int, windSpeedMph: Double): WeatherCondition = when {
            code in RAIN_CODES -> RAINY
            code in SNOW_CODES -> SNOWY
            windSpeedMph >= WINDY_THRESHOLD_MPH -> WINDY
            code == 2 -> PARTIAL
            code == 0 || code == 1 -> SUNNY
            else -> CLOUDY // 3 (overcast), 45/48 (fog), or anything unmapped
        }
    }
}
