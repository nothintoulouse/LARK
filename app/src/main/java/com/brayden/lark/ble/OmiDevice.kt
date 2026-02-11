package com.brayden.lark.ble

import android.bluetooth.BluetoothDevice

data class OmiDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val bluetoothDevice: BluetoothDevice
)
