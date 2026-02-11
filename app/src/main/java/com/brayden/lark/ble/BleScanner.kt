package com.brayden.lark.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class BleScanner(context: Context) {

    companion object {
        private const val TAG = "BleScanner"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var scanCallback: ScanCallback? = null
    private var isScanning = false

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    val isBluetoothAvailable: Boolean
        get() = bluetoothAdapter != null

    /**
     * Start an unfiltered BLE scan with post-filtering by device name and service UUID.
     *
     * We use an unfiltered scan because the Omi DevKit 2 does NOT include its custom
     * service UUID (19B10000-...) in advertisement data — it only exposes it after
     * connection during GATT service discovery. A ScanFilter by service UUID therefore
     * matches nothing.
     *
     * ACCESS_FINE_LOCATION must be granted for scan record data (device names, service
     * UUIDs) to be populated. Without it, Android redacts all scan record fields to null.
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        onDeviceFound: (OmiDevice) -> Unit,
        onScanFailed: (Int) -> Unit,
        onScanTimeout: () -> Unit
    ) {
        if (isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            onScanFailed(-1)
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val seenAddresses = mutableSetOf<String>()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address

                // Deduplicate
                if (!seenAddresses.add(address)) return

                @SuppressLint("MissingPermission")
                val name = device.name
                val localName = result.scanRecord?.deviceName
                val effectiveName = name ?: localName

                // Post-filter: only accept Omi-compatible devices
                if (!isOmiDevice(effectiveName, result)) return

                val displayName = effectiveName ?: "Omi Device"
                Log.i(TAG, ">>> Found Omi device: $displayName ($address), RSSI: ${result.rssi}")
                Log.d(TAG, "  Services: ${result.scanRecord?.serviceUuids}")

                onDeviceFound(
                    OmiDevice(
                        name = displayName,
                        address = address,
                        rssi = result.rssi,
                        bluetoothDevice = device
                    )
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "Batch scan results: ${results.size} devices")
                for (result in results) {
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val errorMsg = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "REGISTRATION_FAILED"
                    SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                    5 -> "OUT_OF_HARDWARE_RESOURCES"
                    6 -> "SCANNING_TOO_FREQUENTLY"
                    else -> "UNKNOWN($errorCode)"
                }
                Log.e(TAG, "Scan failed: $errorMsg (code $errorCode)")
                isScanning = false
                onScanFailed(errorCode)
            }
        }

        // Unfiltered scan — the Omi device does not advertise its service UUID,
        // so we must scan all devices and filter by name in the callback.
        scanner.startScan(null, settings, scanCallback)
        isScanning = true
        Log.d(TAG, "BLE scan started (unfiltered, post-filtering by name/UUID)")

        handler.postDelayed({
            if (isScanning) {
                stopScan()
                onScanTimeout()
            }
        }, BleConstants.SCAN_TIMEOUT_MS)
    }

    /**
     * Check if a scan result represents an Omi-compatible device.
     * Matches by device name or advertised service UUID.
     */
    private fun isOmiDevice(name: String?, result: ScanResult): Boolean {
        // Check device name first (primary match since device doesn't advertise service UUID)
        if (name != null) {
            val lower = name.lowercase()
            if (lower.startsWith("omi") || lower.startsWith("friend")) {
                return true
            }
        }

        // Also check advertised service UUIDs as fallback
        val serviceUuids = result.scanRecord?.serviceUuids
        if (serviceUuids != null) {
            for (uuid in serviceUuids) {
                if (uuid.uuid == BleConstants.AUDIO_SERVICE_UUID) {
                    return true
                }
            }
        }

        return false
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        scanCallback?.let { callback ->
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
        }
        scanCallback = null
        isScanning = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "BLE scan stopped")
    }

    fun isCurrentlyScanning(): Boolean = isScanning
}
