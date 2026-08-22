package com.weatherwidget.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A city the user searched for and added as its own tab, beyond the GPS "current location" tab. */
data class SavedLocation(
    val id: String,
    val displayName: String,
    val lat: Double,
    val lon: Double
)

/** Persists the user's added locations as a small JSON array in SharedPreferences. */
object LocationStore {
    private const val PREFS_NAME = "weather"
    private const val KEY_LOCATIONS = "saved_locations"

    fun list(context: Context): List<SavedLocation> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LOCATIONS, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SavedLocation(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Adding the same coordinates again (same id) just replaces the existing entry instead of duplicating a tab. */
    fun add(context: Context, location: SavedLocation) {
        val updated = list(context).filterNot { it.id == location.id } + location
        save(context, updated)
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    private fun save(context: Context, locations: List<SavedLocation>) {
        val array = JSONArray()
        locations.forEach { loc ->
            array.put(
                JSONObject()
                    .put("id", loc.id)
                    .put("name", loc.displayName)
                    .put("lat", loc.lat)
                    .put("lon", loc.lon)
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCATIONS, array.toString())
            .apply()
    }
}
