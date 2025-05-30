package xyz.malkki.neostumbler.extensions

import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun Geocoder.getFromLocationSuspending(
    latitude: Double,
    longitude: Double,
    maxResults: Int,
): List<Address> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return suspendCoroutine { continuation ->
            getFromLocation(
                latitude,
                longitude,
                maxResults,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        continuation.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        continuation.resumeWithException(
                            IOException("Geocoding failed: $errorMessage")
                        )
                    }
                },
            )
        }
    } else {
        return withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            return@withContext getFromLocation(latitude, longitude, maxResults) ?: emptyList()
        }
    }
}
