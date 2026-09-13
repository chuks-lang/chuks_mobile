// Location for Chuks Mobile, the Android half that is not the foreground service:
// the fix string both platforms agree on, the accuracy names and what they cost, a
// single fresh fix, the cached one, the compass, and the geocoder.
//
// Built on android.location.LocationManager, not Play Services, so an app has no
// dependency to ship and runs on devices without Google. The traps, most of them
// recorded by expo-location:
//
//   1. speed and bearing are 0, not absent, when the OS has no estimate. iOS says -1;
//      hasSpeed()/hasBearing() make Android say -1 too, so a first fix reads the same.
//   2. A "current" fix is not the last known one. getLastKnownLocation can be hours old
//      and streets away; getCurrentLocation (API 30) asks for a fresh one and answers
//      null when it cannot. Below 30, one live update does the same job.
//   3. Accuracy names map to LocationRequest quality on API 31, where the fused
//      provider also lives; below that, the name picks GPS or network, which is the
//      same trade in older clothes.
//   4. The compass is accelerometer + magnetometer through getRotationMatrix, not the
//      deprecated orientation sensor. True north needs magnetic declination, which needs
//      a location: without one trueHeading is -1, as on iOS. Readings are throttled to
//      a real turn (2 degrees, 50 ms) because the sensors report far more than that.
//   5. Geocoder may be absent (no Google services) and blocks on the network: it runs
//      on a worker thread, and its absence is a legible failure, not an empty answer.
package com.chuks.app

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

object ChuksGeo {
    private val geocodePool = Executors.newSingleThreadExecutor()

    /** "lat,lng,accuracyM,altitudeM,speedMps,headingDeg,timestampMs" (trap 1). */
    fun fixString(l: Location): String {
        val speed = if (l.hasSpeed()) l.speed else -1f
        val bearing = if (l.hasBearing()) l.bearing else -1f
        return "${l.latitude},${l.longitude},${l.accuracy},${l.altitude},$speed,$bearing,${l.time}"
    }

    private fun wantsGps(accuracy: String) = accuracy == "high" || accuracy == "highest" || accuracy == "navigation"

    /** The provider an accuracy name means on this device, or null when location is off. */
    fun provider(lm: LocationManager, accuracy: String): String? {
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER))
            return LocationManager.FUSED_PROVIDER
        val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        return when {
            wantsGps(accuracy) -> if (gps) LocationManager.GPS_PROVIDER else if (net) LocationManager.NETWORK_PROVIDER else null
            else -> if (net) LocationManager.NETWORK_PROVIDER else if (gps) LocationManager.GPS_PROVIDER else null
        }
    }

    private fun quality(accuracy: String): Int = when (accuracy) {
        "lowest" -> android.location.LocationRequest.QUALITY_LOW_POWER
        "low", "balanced" -> android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
        else -> android.location.LocationRequest.QUALITY_HIGH_ACCURACY
    }

    /** Start a stream with the accuracy, distance filter and interval the app asked for. */
    @Throws(SecurityException::class)
    fun requestUpdates(lm: LocationManager, provider: String, accuracy: String, distanceM: Float, intervalMs: Long, listener: LocationListener) {
        if (Build.VERSION.SDK_INT >= 31) {
            val req = android.location.LocationRequest.Builder(maxOf(intervalMs, 0L))
                .setQuality(quality(accuracy))
                .setMinUpdateDistanceMeters(maxOf(distanceM, 0f))
                .build()
            lm.requestLocationUpdates(provider, req, { r -> Looper.getMainLooper().let { android.os.Handler(it).post(r) } }, listener)
        } else {
            lm.requestLocationUpdates(provider, maxOf(intervalMs, 0L), maxOf(distanceM, 0f), listener, Looper.getMainLooper())
        }
    }

    /** One fresh fix (trap 2). `done` gets the fix or null, on the main thread. */
    @Throws(SecurityException::class)
    fun current(lm: LocationManager, provider: String, accuracy: String, done: (Location?) -> Unit) {
        if (Build.VERSION.SDK_INT >= 31) {
            val req = android.location.LocationRequest.Builder(0L).setQuality(quality(accuracy)).setDurationMillis(30_000L).setMaxUpdates(1).build()
            lm.getCurrentLocation(provider, req, CancellationSignal(), { r -> android.os.Handler(Looper.getMainLooper()).post(r) }) { done(it) }
        } else if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, CancellationSignal(), { r -> android.os.Handler(Looper.getMainLooper()).post(r) }) { done(it) }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) { done(l) }
                override fun onProviderDisabled(p: String) {}
                override fun onProviderEnabled(p: String) {}
                @Deprecated("kept for older API levels") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            }
            @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }

    /** The cached fix that satisfies the age and accuracy limits (0 = any), or null. */
    fun lastKnown(lm: LocationManager, maxAgeMs: Long, maxAccM: Float): Location? {
        var best: Location? = null
        for (p in lm.allProviders) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } ?: continue
            if (maxAgeMs > 0 && System.currentTimeMillis() - l.time > maxAgeMs) continue
            if (maxAccM > 0 && (!l.hasAccuracy() || l.accuracy > maxAccM)) continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    fun enabled(lm: LocationManager): Boolean =
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    // ---- the compass (trap 4) ------------------------------------------------

    class Heading(private val ctx: Context, private val emit: (String) -> Unit) : SensorEventListener {
        private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private var gravity: FloatArray? = null
        private var magnetic: FloatArray? = null
        private var field: GeomagneticField? = null
        private var grade = 0
        private var lastAzimuth = Float.NaN
        private var lastAt = 0L
        private var lm: LocationManager? = null
        private var fieldTriedAt = 0L

        // Declination needs a location. Looked up at start and, while there is none
        // (permission not yet granted, no fix ever), again every five seconds, so a
        // watch begun before the app asked for location still gets true north later.
        private fun refreshField() {
            if (field != null) return
            val now = System.currentTimeMillis()
            if (now - fieldTriedAt < 5000) return
            fieldTriedAt = now
            val m = lm ?: return
            lastKnown(m, 0, 0f)?.let { field = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), now) }
        }

        /** Null on success, else why the compass cannot run. */
        fun start(lm: LocationManager): String? {
            val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return "no compass on this device"
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return "no compass on this device"
            this.lm = lm
            refreshField()
            sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
            return null
        }
        fun stop() = sm.unregisterListener(this)

        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> gravity = e.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD -> magnetic = e.values.clone()
            }
            val g = gravity ?: return; val m = magnetic ?: return
            val r = FloatArray(9); val i = FloatArray(9)
            if (!SensorManager.getRotationMatrix(r, i, g, m)) return
            val o = FloatArray(3)
            SensorManager.getOrientation(r, o)
            val now = System.currentTimeMillis()
            if (!lastAzimuth.isNaN() && abs(o[0] - lastAzimuth) < 0.035f && now - lastAt < 50) return
            lastAzimuth = o[0]; lastAt = now
            refreshField()
            val magNorth = (Math.toDegrees(o[0].toDouble()).toFloat() + 360f) % 360f
            val trueNorth = field?.let { (magNorth + it.declination + 360f) % 360f } ?: -1f
            emit("$trueNorth,$magNorth,$grade")
        }
        override fun onAccuracyChanged(s: Sensor, a: Int) { if (s.type == Sensor.TYPE_MAGNETIC_FIELD) grade = a.coerceIn(0, 3) }
    }

    // ---- the geocoder (trap 5) -----------------------------------------------

    private fun clean(s: String?) = ChuksWire.esc(s)   // escapes the separators; see ChuksWire.kt

    /** `done(rows, error)` on the main thread: rows are "lat,lng" lines. */
    fun geocode(ctx: Context, address: String, post: (Runnable) -> Unit, done: (String, String?) -> Unit) {
        if (!Geocoder.isPresent()) { done("", "geocoding failed: no geocoder on this device"); return }
        geocodePool.execute {
            var rows = ""; var msg: String? = null
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(ctx, Locale.getDefault()).getFromLocationName(address, 5) ?: emptyList()
                rows = list.filter { it.hasLatitude() && it.hasLongitude() }.joinToString("\n") { "${it.latitude},${it.longitude}" }
            } catch (e: Exception) { msg = "geocoding failed: ${e.message}" }
            post(Runnable { done(rows, msg) })
        }
    }

    /** Rows are "name\tstreet\tcity\tregion\tpostalCode\tcountry\tisoCountryCode". */
    fun reverseGeocode(ctx: Context, lat: Double, lng: Double, post: (Runnable) -> Unit, done: (String, String?) -> Unit) {
        if (!Geocoder.isPresent()) { done("", "geocoding failed: no geocoder on this device"); return }
        geocodePool.execute {
            var rows = ""; var msg: String? = null
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lng, 5) ?: emptyList()
                rows = list.joinToString("\n") { a ->
                    val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ")
                    listOf(a.featureName, street, a.locality, a.adminArea, a.postalCode, a.countryName, a.countryCode).joinToString("\t") { clean(it) }
                }
            } catch (e: Exception) { msg = "geocoding failed: ${e.message}" }
            post(Runnable { done(rows, msg) })
        }
    }
}
