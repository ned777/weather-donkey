package com.weatherwidget.app

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared formatting for both the widget and MainActivity, so the two never drift apart. */
object WeatherFormat {
    private val clockFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    /** Open-Meteo returns sunrise/sunset as local time already (timezone=auto), e.g. "2026-08-22T06:32". */
    fun clockTime(isoLocalDateTime: String): String = try {
        LocalDateTime.parse(isoLocalDateTime).format(clockFormatter)
    } catch (e: Exception) {
        "--:--"
    }

    fun tempString(tempF: Double): String = "${Math.round(tempF)}°"

    /** "Updated just now" / "Updated 14m ago" / "Updated 3h ago" — no need to pull in a date library for this. */
    fun updatedAgoString(fetchedAt: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val minutes = (nowMillis - fetchedAt) / 60_000
        return when {
            minutes < 1 -> "Updated just now"
            minutes < 60 -> "Updated ${minutes}m ago"
            else -> "Updated ${minutes / 60}h ago"
        }
    }
}
