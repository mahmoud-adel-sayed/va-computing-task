package com.va.android.task.implementation.kotlin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import butterknife.BindView
import butterknife.ButterKnife
import butterknife.OnClick
import butterknife.OnItemSelected
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.material.tabs.TabLayout
import com.va.android.task.BuildConfig
import com.va.android.task.R
import com.va.android.task.implementation.kotlin.engine.MathEngineService
import com.va.android.task.implementation.kotlin.engine.data.MathQuestion
import com.va.android.task.implementation.kotlin.engine.data.Operator

@SuppressLint("NonConstantResourceId")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 34
        private const val REQUEST_CHECK_SETTINGS = 9000

        private const val UPDATE_INTERVAL_IN_MILLISECONDS = 10000L
        private const val FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS: Long =
                UPDATE_INTERVAL_IN_MILLISECONDS / 2

        private const val KEY_REQUESTING_LOCATION_UPDATES = "requesting-location-updates"
        private const val KEY_LOCATION = "location"
    }

    @BindView(R.id.root)
    lateinit var root: View

    @BindView(R.id.et_first_operand)
    lateinit var firstOperandEditText: EditText

    @BindView(R.id.et_second_operand)
    lateinit var secondOperandEditText: EditText

    @BindView(R.id.et_delay_time)
    lateinit var delayTimeEditText: EditText

    @BindView(R.id.spinner_operators)
    lateinit var operatorsSpinner: Spinner

    @BindView(R.id.current_location_container)
    lateinit var currentLocationContainer: View

    @BindView(R.id.cb_my_current_location)
    lateinit var currentLocationCB: CheckBox

    @BindView(R.id.progress_location)
    lateinit var currentLocationProgress: View

    @BindView(R.id.lat_long_container)
    lateinit var latLongContainer: View

    @BindView(R.id.latitude)
    lateinit var latTextView: TextView

    @BindView(R.id.longitude)
    lateinit var longTextView: TextView

    @BindView(R.id.rv_pending_operations)
    lateinit var pendingOperationsRV: RecyclerView

    @BindView(R.id.rv_operations_results)
    lateinit var operationsResultsRV: RecyclerView

    private lateinit var pendingOperationsAdapter: PendingOperationsAdapter
    private lateinit var operationsResultsAdapter: OperationsResultsAdapter
    private var selectedOperator: Operator = Operator.ADD

    // Service
    private var serviceReference: MathEngineService? = null
    private var isBound = false
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceReference = (binder as MathEngineService.LocalBinder).getService()
            serviceReference!!.addListener(serviceListener)
            pendingOperationsAdapter.replaceData(serviceReference!!.pendingOperations)
            operationsResultsAdapter.replaceData(serviceReference!!.operationsResults)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceReference = null
            isBound = false
        }
    }

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var settingsClient: SettingsClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationSettingsRequest: LocationSettingsRequest
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: Location? = null
    private var requestingLocationUpdates = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        title = getString(R.string.app_name)
        ButterKnife.bind(this)

        setupOperatorsSpinner()
        setupTabs()
        setupPendingOperations()
        setupOperationsResults()

        requestingLocationUpdates = false
        updateValuesFromBundle(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        settingsClient = LocationServices.getSettingsClient(this)
        locationCallback = createLocationCallback()
        locationRequest = createLocationRequest()
        locationSettingsRequest = createLocationSettingsRequest(locationRequest)

        MathEngineService.start(this)
    }

    override fun onStart() {
        super.onStart()
        if (!isBound) {
            val bindIntent = Intent(this, MathEngineService::class.java)
            isBound = bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            serviceReference?.removeListener(serviceListener)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentLocationCB.isChecked) {
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            when (resultCode) {
                Activity.RESULT_OK -> startLocationUpdates()
                Activity.RESULT_CANCELED -> {
                    requestingLocationUpdates = false
                    setButtonsEnabled(true)
                }
            }
        }
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.putBoolean(KEY_REQUESTING_LOCATION_UPDATES, requestingLocationUpdates)
        savedInstanceState.putParcelable(KEY_LOCATION, currentLocation)
        super.onSaveInstanceState(savedInstanceState)
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                // Permission denied.
                // It is important to remember that a permission might have been
                // rejected without asking the user for permission (device policy or "Never ask
                // again" prompts). Therefore, a user interface affordance is typically implemented
                // when permissions are denied. Otherwise, your app could appear unresponsive to
                // touches or interactions which have required permissions.
                showSnackBar(root, R.string.permission_denied_explanation, R.string.settings) {
                    // Build intent that displays the App settings screen.
                    val intent = Intent().apply {
                        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        data = Uri.fromParts("package", BuildConfig.APPLICATION_ID, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
            }
        }
    }

    @OnClick(R.id.current_location_container)
    fun onCurrentLocationContainerClick() {
        if (currentLocationCB.isChecked) {
            stopLocationUpdates()
            currentLocationCB.isChecked = false
            latLongContainer.visibility = View.GONE
            currentLocation = null
        } else {
            if (!isLocationPermissionGranted()) {
                requestLocationPermission(root, REQUEST_PERMISSIONS_REQUEST_CODE)
                return
            }
            if (!requestingLocationUpdates) {
                startLocationUpdates()
            }
        }
    }

    private fun updateValuesFromBundle(bundle: Bundle?) {
        if (bundle != null) {
            if (bundle.keySet().contains(KEY_REQUESTING_LOCATION_UPDATES)) {
                requestingLocationUpdates = bundle.getBoolean(KEY_REQUESTING_LOCATION_UPDATES)
            }
            if (bundle.keySet().contains(KEY_LOCATION)) {
                currentLocation = bundle.getParcelable(KEY_LOCATION)
            }
        }
    }

    private fun createLocationRequest(): LocationRequest = LocationRequest.create().apply {
            interval = UPDATE_INTERVAL_IN_MILLISECONDS
            fastestInterval = FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
    }

    private fun createLocationCallback(): LocationCallback {
        return object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                super.onLocationResult(locationResult)
                currentLocation = locationResult?.lastLocation
                setButtonsEnabled(true)
                latLongContainer.visibility = View.VISIBLE
                latTextView.text = getString(R.string.format_lat, currentLocation?.latitude)
                longTextView.text = getString(R.string.format_long, currentLocation?.longitude)
            }
        }
    }

    private fun createLocationSettingsRequest(request: LocationRequest): LocationSettingsRequest =
            LocationSettingsRequest.Builder().addLocationRequest(request).build()

    // Suppressing the location permission here is safe because this method will never be called
    // without the permission being granted.
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        requestingLocationUpdates = true
        setButtonsEnabled(false)
        // Begin by checking if the device has the necessary location settings.
        val task = settingsClient.checkLocationSettings(locationSettingsRequest)
        task.addOnSuccessListener(this) {
            fusedLocationClient.requestLocationUpdates(
                    locationRequest, locationCallback, Looper.getMainLooper()
            )
            currentLocationCB.isChecked = true
        }
        .addOnFailureListener(this) {
            when((it as ApiException).statusCode) {
                LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                    try {
                        val rae = it as ResolvableApiException
                        rae.startResolutionForResult(this, REQUEST_CHECK_SETTINGS)
                    } catch (sie: IntentSender.SendIntentException) {
                        // Ignore the error.
                    }
                }
                LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
                    showToast(R.string.err_location_settings, Toast.LENGTH_LONG)
                    requestingLocationUpdates = false
                    setButtonsEnabled(true)
                }
            }
        }
    }

    private fun stopLocationUpdates() {
        if (!requestingLocationUpdates) {
            return
        }
        fusedLocationClient.removeLocationUpdates(locationCallback).addOnCompleteListener(this) {
            requestingLocationUpdates = false
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        if (enabled) {
            currentLocationProgress.visibility = View.GONE
            with(currentLocationContainer) {
                isEnabled = true
                alpha = 1.0f
            }
        } else {
            currentLocationProgress.visibility = View.VISIBLE
            with(currentLocationContainer) {
                isEnabled = false
                alpha = 0.5f
            }
        }
    }

    private fun setupOperatorsSpinner() {
        operatorsSpinner.adapter = ArrayAdapter<Operator>(
                this, android.R.layout.simple_list_item_1, Operator.values()
        )
    }

    // For simplicity sake, I do not use viewPager with the tabLayout.
    // We could also use multiple fragments here.
    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.tabs)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab == null) {
                    return
                }
                if (tab.position == 0) {
                    pendingOperationsRV.visibility = View.VISIBLE
                    operationsResultsRV.visibility = View.GONE
                } else {
                    operationsResultsRV.visibility = View.VISIBLE
                    pendingOperationsRV.visibility = View.GONE
                }
            }

            override fun onTabReselected(tab: TabLayout.Tab?) { }

            override fun onTabUnselected(tab: TabLayout.Tab?) { }
        })
        tabLayout.addTab(tabLayout.newTab().setText(R.string.pending))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.results))
    }

    private fun setupPendingOperations() {
        pendingOperationsAdapter = PendingOperationsAdapter()
        with(pendingOperationsRV) {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.VERTICAL, false)
            adapter = pendingOperationsAdapter
        }
    }

    private fun setupOperationsResults() {
        operationsResultsAdapter = OperationsResultsAdapter()
        with(operationsResultsRV) {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.VERTICAL, false)
            adapter = operationsResultsAdapter
        }
    }

    @OnItemSelected(R.id.spinner_operators)
    fun onOperatorSelected(position: Int) {
        selectedOperator = Operator.values()[position]
    }

    @OnClick(R.id.btn_calculate)
    fun onCalculate() {
        val firstOperand: Double
        val secondOperand: Double
        try {
            firstOperand = firstOperandEditText.toDouble()
            secondOperand = secondOperandEditText.toDouble()
        } catch (e: NumberFormatException) {
            showToast(R.string.err_operand, Toast.LENGTH_SHORT)
            return
        }

        if (selectedOperator == Operator.DIVIDE && secondOperand == 0.0) {
            showToast(R.string.err_division_by_zero, Toast.LENGTH_SHORT)
            return
        }

        val delayTime: Long
        try {
            delayTime = delayTimeEditText.text.toString().toLong()
        } catch (e: NumberFormatException) {
            showToast(R.string.err_invalid_delay_time, Toast.LENGTH_SHORT)
            return
        }

        val mathQuestion = MathQuestion(
                firstOperand = firstOperand,
                secondOperand = secondOperand,
                operator = selectedOperator,
                delayTime = delayTime
        )
        MathEngineService.calculate(this, mathQuestion)

        clearInputs()
    }

    private fun clearInputs() {
        firstOperandEditText.text = null
        secondOperandEditText.text = null
        delayTimeEditText.text = null
    }

    private val serviceListener: MathEngineService.Listener = object : MathEngineService.Listener {
        override fun onResultsChanged() {
            if (isBound) {
                operationsResultsAdapter.replaceData(serviceReference!!.operationsResults)
            }
        }

        override fun onPendingOperationsChanged() {
            if (isBound) {
                pendingOperationsAdapter.replaceData(serviceReference!!.pendingOperations)
            }
        }

        override fun onNotificationActionCancelAllClick() = pendingOperationsAdapter.clearData()
    }
}