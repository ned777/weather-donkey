package com.weatherwidget.app

/**
 * Buckets Open-Meteo's WMO weather codes (https://open-meteo.com/en/docs,
 * "WMO Weather interpretation codes") down into exactly the three states
 * this widget cares about: sunny, cloudy, or raining. Anything involving
 * precipitation — drizzle, rain, snow, or a thunderstorm — is shown as
 * "Raining", since this widget doesn't try to distinguish precipitation
 * types, just whether you need an umbrella.
 */
enum class WeatherCondition(val label: String) {
    SUNNY("Sunny"),
    CLOUDY("Cloudy"),
    RAINY("Raining");

    /** Emoji swaps to a moon for a clear night sky; every other state is day/night-agnostic. */
    fun emoji(isDay: Boolean): String = when (this) {
        SUNNY -> if (isDay) "☀" else "🌙"
        CLOUDY -> "☁"
        RAINY -> "🌧"
    }

    companion object {
        fun fromCode(code: Int): WeatherCondition = when (code) {
            0, 1 -> SUNNY
            2, 3, 45, 48 -> CLOUDY
            else -> RAINY
        }
    }
}
