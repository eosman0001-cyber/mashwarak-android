package com.jekonix.mashwarak

internal object NativePolicy {
    fun validCoordinates(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    fun usableLocation(lat: Double, lng: Double, measuredAt: Long, accuracy: Float, now: Long): Boolean =
        validCoordinates(lat, lng) && measuredAt > 0 && now - measuredAt in 0L..600_000L &&
            accuracy.isFinite() && accuracy > 0f && accuracy <= 250f
}
