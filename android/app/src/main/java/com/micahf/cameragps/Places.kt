package com.micahf.cameragps

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/** Short place names for coordinates, e.g. "Wicker Park", via the system geocoder. */
object Places {
    private const val TAG = "Places"
    private const val TIMEOUT_MS = 10_000L

    /** Keyed by coordinates rounded to about 100 m. */
    private val cache = HashMap<Pair<Long, Long>, String>()

    /** The neighbourhood or town at [lat], [lon]; null offline or if there's none. */
    suspend fun name(context: Context, lat: Double, lon: Double): String? {
        val key = (lat * 1000).roundToLong() to (lon * 1000).roundToLong()
        synchronized(cache) { cache[key] }?.let { return it }
        if (!Geocoder.isPresent()) return null
        val address = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Address?> { cont ->
                Geocoder(context).getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.firstOrNull())
                    }

                    override fun onError(message: String?) {
                        Log.w(TAG, "geocode: $message")
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }
        val name = address?.run { subLocality ?: locality ?: subAdminArea ?: adminArea } ?: return null
        synchronized(cache) { cache[key] = name }
        return name
    }
}
