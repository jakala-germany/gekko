package com.jakala.gekko.runner

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The location of a physical device (and of an emulator) through Android's test location providers, the same way Appium
 * and Maestro do it: the providers `gps` and `network` answer with the coordinates of [set] until [clear]. Needs `adb
 * shell appops set <this package> android:mock_location allow`, which the CLI runs; without it [set] throws a
 * [SecurityException].
 */
internal class MockLocation(context: Context) {
    private val manager = checkNotNull(context.getSystemService(LocationManager::class.java))
    private var scheduler: ScheduledExecutorService? = null
    @Volatile private var coordinates: Pair<Double, Double>? = null

    @Synchronized
    fun set(latitude: Double, longitude: Double) {
        coordinates = latitude to longitude
        if (scheduler == null) {
            PROVIDERS.forEach(::enable)
            // Apps that ask for updates only hear about a new location, so the same one is sent again every second
            scheduler =
                Executors.newSingleThreadScheduledExecutor().also {
                    it.scheduleWithFixedDelay(::publish, REPUBLISH_MS, REPUBLISH_MS, TimeUnit.MILLISECONDS)
                }
        }
        publish()
    }

    @Synchronized
    fun clear() {
        scheduler?.shutdownNow()
        scheduler = null
        coordinates = null
        PROVIDERS.forEach(::disable)
    }

    @Suppress("DEPRECATION")
    private fun enable(provider: String) {
        // A provider of an earlier run that was not cleaned up would make addTestProvider fail
        disable(provider)
        manager.addTestProvider(
            provider,
            false,
            false,
            false,
            false,
            true,
            true,
            true,
            Criteria.POWER_LOW,
            Criteria.ACCURACY_FINE,
        )
        manager.setTestProviderEnabled(provider, true)
    }

    private fun disable(provider: String) {
        runCatching { manager.setTestProviderEnabled(provider, false) }
        runCatching { manager.removeTestProvider(provider) }
    }

    private fun publish() {
        val (latitude, longitude) = coordinates ?: return
        PROVIDERS.forEach { provider ->
            val location =
                Location(provider).apply {
                    this.latitude = latitude
                    this.longitude = longitude
                    accuracy = ACCURACY_METERS
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
            // A provider that was removed in the meantime is not an error of the test
            runCatching { manager.setTestProviderLocation(provider, location) }
        }
    }

    private companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        const val REPUBLISH_MS = 1000L
        const val ACCURACY_METERS = 3f
    }
}
