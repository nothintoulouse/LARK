package com.brayden.lark.ui.main

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.brayden.lark.ble.BleScanner
import com.brayden.lark.ble.OmiDevice

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val scanner = BleScanner(application)

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _devices = MutableLiveData<List<OmiDevice>>(emptyList())
    val devices: LiveData<List<OmiDevice>> = _devices

    private val _scanError = MutableLiveData<String?>()
    val scanError: LiveData<String?> = _scanError

    private val discoveredDevices = mutableListOf<OmiDevice>()

    fun startScan() {
        if (_isScanning.value == true) return
        discoveredDevices.clear()
        _devices.value = emptyList()
        _scanError.value = null
        _isScanning.value = true

        scanner.startScan(
            onDeviceFound = { device ->
                val existing = discoveredDevices.indexOfFirst { it.address == device.address }
                if (existing >= 0) {
                    discoveredDevices[existing] = device
                } else {
                    discoveredDevices.add(device)
                }
                _devices.postValue(ArrayList(discoveredDevices))
            },
            onScanFailed = { errorCode ->
                _isScanning.postValue(false)
                _scanError.postValue("Scan failed (error $errorCode)")
            },
            onScanTimeout = {
                _isScanning.postValue(false)
            }
        )
    }

    fun stopScan() {
        scanner.stopScan()
        _isScanning.value = false
    }

    fun getBluetoothDevice(omiDevice: OmiDevice): BluetoothDevice = omiDevice.bluetoothDevice

    override fun onCleared() {
        super.onCleared()
        scanner.stopScan()
    }
}
