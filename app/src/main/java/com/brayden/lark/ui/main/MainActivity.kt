package com.brayden.lark.ui.main

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.brayden.lark.R
import com.brayden.lark.databinding.ActivityMainBinding
import com.brayden.lark.service.RecordingService
import com.brayden.lark.ui.files.FilesActivity
import com.brayden.lark.ui.recording.RecordingActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceListAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d(TAG, "Permission results: ${results.entries.joinToString { "${it.key}=${it.value}" }}")
        if (results.values.all { it }) {
            startScanIfLocationEnabled()
        } else {
            val denied = results.filter { !it.value }.keys.joinToString()
            Log.w(TAG, "Permissions denied: $denied")
            Toast.makeText(this, R.string.error_permissions_required, Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (viewModel.scanner.isBluetoothEnabled) {
            checkPermissionsAndScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDeviceList()
        setupButtons()
        observeViewModel()
    }

    private fun setupDeviceList() {
        deviceAdapter = DeviceListAdapter { device ->
            viewModel.stopScan()
            RecordingService.startRecording(this, device.bluetoothDevice)
            startActivity(Intent(this, RecordingActivity::class.java))
        }

        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupButtons() {
        binding.btnScan.setOnClickListener {
            if (viewModel.isScanning.value == true) {
                viewModel.stopScan()
            } else {
                checkBluetoothAndScan()
            }
        }

        binding.btnFiles.setOnClickListener {
            startActivity(Intent(this, FilesActivity::class.java))
        }
    }

    private fun observeViewModel() {
        viewModel.isScanning.observe(this) { scanning ->
            binding.btnScan.text = if (scanning) {
                getString(R.string.stop_scan)
            } else {
                getString(R.string.scan_for_devices)
            }
            binding.progressScanning.isVisible = scanning
        }

        viewModel.devices.observe(this) { devices ->
            binding.tvDevicesHeader.isVisible = devices.isNotEmpty()
            deviceAdapter.clear()
            devices.forEach { deviceAdapter.addDevice(it) }
        }

        viewModel.scanError.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkBluetoothAndScan() {
        if (!viewModel.scanner.isBluetoothAvailable) {
            Toast.makeText(this, R.string.error_bluetooth_not_available, Toast.LENGTH_LONG).show()
            return
        }

        if (!viewModel.scanner.isBluetoothEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableIntent)
            return
        }

        checkPermissionsAndScan()
    }

    private fun checkPermissionsAndScan() {
        val allPerms = getRequiredPermissions()
        val needed = allPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        Log.d(TAG, "Required permissions: $allPerms")
        Log.d(TAG, "Needed permissions: $needed")

        if (needed.isEmpty()) {
            Log.d(TAG, "All permissions granted")
            startScanIfLocationEnabled()
        } else {
            Log.d(TAG, "Requesting permissions: $needed")
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startScanIfLocationEnabled() {
        if (!isLocationEnabled()) {
            Log.w(TAG, "Location Services is OFF — BLE scan will return no results")
            AlertDialog.Builder(this)
                .setTitle("Location Required")
                .setMessage("BLE device scanning requires Location Services to be turned on. Please enable Location in your phone settings.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        Log.d(TAG, "Location Services is ON, starting scan")
        viewModel.startScan()
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val network = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        Log.d(TAG, "Location providers: GPS=$gps, Network=$network")
        return gps || network
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        // Location is required on all versions for BLE scan record data to be populated.
        // Without it, Android redacts service UUIDs and device names from scan results,
        // which breaks ScanFilter matching on some devices.
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions
    }
}
