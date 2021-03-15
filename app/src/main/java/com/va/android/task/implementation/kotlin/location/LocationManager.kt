package com.va.android.task.implementation.kotlin.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*

/**
 * Fetches location info and notifies the registered listener.
 *
 * <p>The implementation replies on Google location services and handles the follow functionality:
 * <ul>
 *     <li>Requesting the location permission if not granted.</li>
 *     <li>Checking if the device has the necessary location settings.</li>
 *     <li>Automatically start/stop listening for location updates when activity is resumed/paused.</li>
 * </ul>
 * </p>
 *
 * <p>The [AppCompatActivity] passed to the constructor <strong>must override some callbacks</strong>
 * & delegate them to these methods [onSaveInstanceState], [onActivityResult],
 * [onRequestPermissionsResult].</p>
 *
 * <p><strong>Do not use {@code 2000} or {@code 2001} as request codes</strong>,
 * they are used internally.</p>
 */
@Suppress("unused")
class LocationManager(
        private val activity: AppCompatActivity,
        savedInstanceState: Bundle? = null,
        private val options: LocationOptions = LocationOptions.Builder().build(),
        private var listener: Listener = NULL

) : LifecycleObserver {

    companion object {
        @VisibleForTesting
        internal const val REQUEST_LOCATION_PERMISSION = 2000

        @VisibleForTesting
        internal const val REQUEST_CHECK_SETTINGS = 2001

        @VisibleForTesting
        internal const val KEY_REQUESTING_LOCATION_UPDATES = "KEY_REQUESTING_LOCATION_UPDATES"

        @VisibleForTesting
        internal const val KEY_LOCATION = "KEY_LOCATION"

        private val NULL = object : Listener {
            override fun onProvideLocationPermissionRationale() { }

            override fun onLocationPermissionDenied() { }

            override fun shouldFetchLocationInfo(): Boolean = false

            override fun onLocationResult(latitude: Double, longitude: Double) { }
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var settingsClient: SettingsClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationSettingsRequest: LocationSettingsRequest
    private lateinit var locationCallback: LocationCallback

    @VisibleForTesting
    internal var location: Location? = null

    @VisibleForTesting
    internal var requestingLocationUpdates = false

    init {
        updateValuesFromBundle(savedInstanceState)
        activity.lifecycle.addObserver(this)
    }

    interface Listener {
        fun onProvideLocationPermissionRationale()

        fun onLocationPermissionDenied()

        fun shouldFetchLocationInfo(): Boolean

        fun onStartLocationListening() { }

        fun onLocationSettingsSuccess() { }

        fun onLocationSettingsFailure(error: String?) { }

        fun onLocationResult(latitude: Double, longitude: Double)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    internal fun initialize() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity)
        settingsClient = LocationServices.getSettingsClient(activity)
        locationCallback = createLocationCallback()
        locationRequest = createLocationRequest()
        locationSettingsRequest = buildLocationSettingsRequest()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    internal fun startListening() {
        if (listener.shouldFetchLocationInfo()) startLocationUpdates()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    internal fun stopListening() = stopLocationUpdates()

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    internal fun removeObserver() {
        listener = NULL
        activity.lifecycle.removeObserver(this)
    }

    /**
     * The activity must delegate the call to this method from the its similar callback.
     */
    fun onSaveInstanceState(bundle: Bundle) = with(bundle) {
        putBoolean(KEY_REQUESTING_LOCATION_UPDATES, requestingLocationUpdates)
        putParcelable(KEY_LOCATION, location)
    }

    /**
     * The activity must delegate the call to this method from the its similar callback.
     *
     * @param requestCode The requestCode
     * @param resultCode The resultCode
     */
    fun onActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            when (resultCode) {
                Activity.RESULT_OK -> startLocationUpdates()
                Activity.RESULT_CANCELED -> {
                    requestingLocationUpdates = false
                    listener.onLocationSettingsFailure(error = null)
                }
            }
        }
    }

    /**
     * The activity must override {@link AppCompatActivity#onRequestPermissionsResult(int, String[], int[])}
     * callback and delegate the call to this method.
     *
     * @param requestCode The requestCode
     * @param grantResults The grantResults
     */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                // Permission denied.
                // It is important to remember that a permission might have been
                // rejected without asking the user for permission (device policy or "Never ask
                // again" prompts). Therefore, a user interface affordance is typically implemented
                // when permissions are denied. Otherwise, your app could appear unresponsive to
                // touches or interactions which have required permissions.
                listener.onLocationPermissionDenied()
            }
        }
    }

    /**
     * If [enabled] is {@code true}, it checks permission & location settings then starts listening
     * for location updates, otherwise it stops listening.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            if (!isLocationPermissionGranted()) {
                grantLocationPermission()
                return
            }
            if (!requestingLocationUpdates) {
                startLocationUpdates()
            }
        } else {
            stopLocationUpdates()
            location = null
        }
    }

    /**
     * Requests the {@code Manifest.permission.ACCESS_FINE_LOCATION} permission.
     */
    fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
        )
    }

    private fun isLocationPermissionGranted(): Boolean {
        val state = ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        return state == PackageManager.PERMISSION_GRANTED
    }

    private fun grantLocationPermission() {
        val shouldProvideRationale =
                ActivityCompat.shouldShowRequestPermissionRationale(activity,
                        Manifest.permission.ACCESS_FINE_LOCATION)
        // Provide an additional rationale to the user. This would happen if the user denied the
        // request previously, but didn't check the "Don't ask again" checkbox.
        if (shouldProvideRationale) {
            listener.onProvideLocationPermissionRationale()
        } else {
            // Request permission. It's possible this can be auto answered if device policy
            // sets the permission in a given state or the user denied the permission
            // previously and checked "Never ask again".
            requestLocationPermission()
        }
    }

    private fun createLocationRequest(): LocationRequest = LocationRequest.create().apply {
        interval = options.interval
        fastestInterval = options.fastestInterval
        priority = options.priority
    }

    private fun createLocationCallback(): LocationCallback {
        return object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                super.onLocationResult(locationResult)
                location = locationResult?.lastLocation
                listener.onLocationResult(
                        latitude = location?.latitude ?: 0.0,
                        longitude = location?.longitude ?: 0.0
                )
            }
        }
    }

    private fun buildLocationSettingsRequest(): LocationSettingsRequest =
            LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build()

    private fun updateValuesFromBundle(bundle: Bundle?) {
        bundle?.let {
            if (it.keySet().contains(KEY_REQUESTING_LOCATION_UPDATES)) {
                requestingLocationUpdates = it.getBoolean(KEY_REQUESTING_LOCATION_UPDATES)
            }
            if (it.keySet().contains(KEY_LOCATION)) {
                location = it.getParcelable(KEY_LOCATION)
            }
        }
    }

    // Suppressing the location permission here is safe because this method will never be called
    // without the permission being granted.
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        requestingLocationUpdates = true
        listener.onStartLocationListening()
        // Begin by checking if the device has the necessary location settings.
        settingsClient.checkLocationSettings(locationSettingsRequest).addOnSuccessListener(activity) {
            fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
            )
            listener.onLocationSettingsSuccess()
        }
        .addOnFailureListener(activity) {
            when ((it as ApiException).statusCode) {
                LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                    try {
                        val rae = it as ResolvableApiException
                        rae.startResolutionForResult(activity, REQUEST_CHECK_SETTINGS)
                    } catch (sie: IntentSender.SendIntentException ) {
                        // Ignore the error.
                    }
                }
                LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
                    requestingLocationUpdates = false
                    val error = "Location settings are inadequate. Fix in Settings."
                    listener.onLocationSettingsFailure(error)
                }
            }
        }
    }

    private fun stopLocationUpdates() {
        if (!requestingLocationUpdates) {
            return
        }
        fusedLocationClient.removeLocationUpdates(locationCallback).addOnCompleteListener(activity) {
            requestingLocationUpdates = false
        }
    }
}