package com.weatherwidget.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wraps the plain android.location.LocationManager (no Google Play Services
 * dependency needed) to get one best-effort location fix. Deliberately only
 * uses NETWORK_PROVIDER — that's the coarse-precision provider this app's
 * ACCESS_COARSE_LOCATION permission actually grants access to.
 */
object LocationHelper {
    private const val FIX_TIMEOUT_MS = 8000L
    private const val MAX_LAST_KNOWN_AGE_MS = 10 * 60 * 1000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Blocks the calling (background) thread for up to FIX_TIMEOUT_MS and returns a
     * location, or null if permission is missing, no provider is enabled, or no fix
     * arrived in time. Never call this from the main thread.
     */
    @SuppressLint("MissingPermission") // guarded by the hasPermission() check below
    fun getLocationBlocking(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        }

        val lastKnown = runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < MAX_LAST_KNOWN_AGE_MS) {
            return lastKnown
        }

        // No recent fix cached — ask for one fresh update. requestSingleUpdate needs a
        // Looper to deliver its callback on; the calling thread (a background Thread
        // started by the widget/activity) doesn't have one, so we spin up a short-lived
        // HandlerThread just to receive this one callback.
        val handlerThread = HandlerThread("WeatherLocationUpdate").apply { start() }
        val latch = CountDownLatch(1)
        var result = lastKnown
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result = location
                latch.countDown()
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        return try {
            manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, handlerThread.looper)
            latch.await(FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result
        } catch (e: Exception) {
            result
        } finally {
            manager.removeUpdates(listener)
            handlerThread.quitSafely()
        }
    }
}
