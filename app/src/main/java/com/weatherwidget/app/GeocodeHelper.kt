package com.weatherwidget.app

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import java.util.Locale

/** One match from a text search — a city name or ZIP/postal code the user typed. */
data class LocationSearchResult(val displayName: String, val lat: Double, val lon: Double)

/**
 * Turns a raw lat/lon into a human-readable city name, and turns typed text
 * (a city name or a ZIP/postal code) into candidate lat/lon matches — both
 * using Android's built-in Geocoder, an on-device OS service, not a paid
 * third-party API. Not every device/ROM ships a working geocoder backend, so
 * both directions are best-effort: any failure (or an empty result) just
 * means the caller falls back to a generic label, or an empty match list.
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

    /** Blocks the calling (background) thread — never call this from the main thread. */
    @Suppress("DEPRECATION")
    fun search(context: Context, query: String, maxResults: Int = 8): List<LocationSearchResult> {
        if (!Geocoder.isPresent()) return emptyList()
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val results = geocoder.getFromLocationName(query, maxResults) ?: return emptyList()
            results.mapNotNull { address ->
                if (!address.hasLatitude() || !address.hasLongitude()) return@mapNotNull null
                LocationSearchResult(displayLabel(address), address.latitude, address.longitude)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun displayLabel(address: Address): String {
        val parts = listOfNotNull(
            address.locality ?: address.subAdminArea,
            address.adminArea,
            address.countryName
        )
        return if (parts.isNotEmpty()) parts.joinToString(", ") else address.getAddressLine(0) ?: "Unknown location"
    }
}
