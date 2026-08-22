package com.weatherwidget.app

import android.content.Context
import android.location.Geocoder
import android.location.Location
import java.util.Locale

/**
 * Turns a raw lat/lon into a human-readable city name using Android's
 * built-in Geocoder — an on-device OS service, not a paid third-party API.
 * Not every device/ROM ships a working geocoder backend, so this is
 * best-effort: any failure (or an empty result) just means the caller falls
 * back to showing "Current Location" instead of a real place name.
 */
object GeocodeHelper {
    @Suppress("DEPRECATION") // the synchronous overload is still the simplest option for a one-off lookup
    fun cityName(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val results = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            val address = results?.firstOrNull() ?: return null
            address.locality ?: address.subAdminArea ?: address.adminArea
        } catch (e: Exception) {
            null
        }
    }
}
