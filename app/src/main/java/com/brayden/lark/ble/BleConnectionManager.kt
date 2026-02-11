package com.brayden.lark.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min
import kotlin.random.Random

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    READY,
    RECONNECTING
}

class BleConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "BleConnectionManager"
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var reconnectAttempts = 0
    private var targetDevice: BluetoothDevice? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * When true, reconnection is unlimited (exponential backoff, no cap).
     * Set to true when an active RecordingSession is running.
     * Set to false to revert to bounded reconnection behavior.
     */
    var unlimitedReconnect: Boolean = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _audioData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val audioData: SharedFlow<ByteArray> = _audioData

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error

    private var pendingDescriptorWrites = mutableListOf<BluetoothGattDescriptor>()

    // Flag to indicate intentional disconnect (user-initiated stop)
    private var intentionalDisconnect = false

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (_connectionState.value != ConnectionState.DISCONNECTED &&
            _connectionState.value != ConnectionState.RECONNECTING
        ) return

        targetDevice = device
        intentionalDisconnect = false
        _connectionState.value = ConnectionState.CONNECTING
        Log.d(TAG, "Connecting to ${device.address}")

        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    /**
     * Reconnect to a device by MAC address. Used for auto-resume after
     * service restart (process death recovery). Gets a BluetoothDevice
     * reference from the adapter without scanning.
     */
    @SuppressLint("MissingPermission")
    fun reconnectToDevice(address: String) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = manager?.adapter ?: run {
            Log.e(TAG, "BluetoothAdapter not available for reconnect")
            _error.tryEmit("Bluetooth not available")
            return
        }

        try {
            val device = adapter.getRemoteDevice(address)
            Log.d(TAG, "Auto-resume: reconnecting to $address")
            connect(device)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid device address for reconnect: $address", e)
            _error.tryEmit("Invalid device address: $address")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        intentionalDisconnect = true
        bluetoothGatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Exception closing GATT (non-fatal): ${e.message}")
        }
        bluetoothGatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Compute reconnection delay with exponential backoff + jitter.
     * 2s → 4s → 8s → 16s → 32s → 60s → 60s → 60s...
     * Plus random jitter of 0-1000ms to avoid thundering herd.
     */
    private fun computeReconnectDelay(): Long {
        val base = BleConstants.RECONNECT_BASE_DELAY_MS
        val maxDelay = BleConstants.RECONNECT_MAX_DELAY_MS
        val exponential = min(base * (1L shl reconnectAttempts.coerceAtMost(5)), maxDelay)
        val jitter = Random.nextLong(0, BleConstants.RECONNECT_JITTER_MAX_MS + 1)
        return exponential + jitter
    }

    @SuppressLint("MissingPermission")
    private fun attemptReconnect() {
        val device = targetDevice ?: return

        // If disconnect was intentional (user-initiated), don't reconnect
        if (intentionalDisconnect) {
            Log.d(TAG, "Intentional disconnect — not reconnecting")
            cleanup()
            return
        }

        // If unlimited reconnect is disabled, cap at legacy limit
        if (!unlimitedReconnect && reconnectAttempts >= 5) {
            Log.e(TAG, "Max reconnection attempts reached (limited mode)")
            _error.tryEmit("Could not reconnect to device")
            cleanup()
            return
        }

        reconnectAttempts++
        _connectionState.value = ConnectionState.RECONNECTING

        val delayMs = computeReconnectDelay()
        Log.d(TAG, "Reconnection attempt $reconnectAttempts (delay: ${delayMs}ms, unlimited: $unlimitedReconnect)")

        scope.launch {
            delay(delayMs)

            // Clear pending descriptor writes from previous connection
            pendingDescriptorWrites.clear()

            // Close old GATT safely
            try {
                bluetoothGatt?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Exception closing GATT before reconnect (non-fatal): ${e.message}")
            }

            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextDescriptor() {
        if (pendingDescriptorWrites.isEmpty()) {
            Log.d(TAG, "All descriptors written — reading battery then going READY")
            // Read battery level now that all descriptors are written.
            // onCharacteristicRead will transition to READY.
            val batteryService = bluetoothGatt?.getService(BleConstants.BATTERY_SERVICE_UUID)
            val batteryChar = batteryService?.getCharacteristic(BleConstants.BATTERY_LEVEL_CHAR_UUID)
            if (batteryChar != null) {
                bluetoothGatt?.readCharacteristic(batteryChar)
            } else {
                // No battery characteristic — go straight to READY
                Log.d(TAG, "No battery characteristic, device is READY")
                _connectionState.value = ConnectionState.READY
            }
            return
        }
        val descriptor = pendingDescriptorWrites.removeAt(0)
        Log.d(TAG, "Writing CCCD descriptor for ${descriptor.characteristic.uuid}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            bluetoothGatt?.writeDescriptor(descriptor)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempts = 0  // Reset on successful connect
                    _connectionState.value = ConnectionState.CONNECTED
                    Log.d(TAG, "Connected, requesting MTU")
                    gatt.requestMtu(BleConstants.MTU_SIZE)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    // Clear stale descriptor state from previous connection
                    pendingDescriptorWrites.clear()
                    attemptReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu (status=$status)")
            _connectionState.value = ConnectionState.DISCOVERING_SERVICES
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _error.tryEmit("Service discovery failed")
                return
            }

            Log.d(TAG, "Services discovered")
            pendingDescriptorWrites.clear()

            // Enable audio notifications
            val audioService = gatt.getService(BleConstants.AUDIO_SERVICE_UUID)
            if (audioService == null) {
                Log.e(TAG, "Audio service not found")
                _error.tryEmit("Audio service not found on device")
                return
            }

            val audioChar = audioService.getCharacteristic(BleConstants.AUDIO_DATA_CHAR_UUID)
            if (audioChar == null) {
                Log.e(TAG, "Audio characteristic not found")
                _error.tryEmit("Audio characteristic not found")
                return
            }

            gatt.setCharacteristicNotification(audioChar, true)
            val audioDesc = audioChar.getDescriptor(BleConstants.CCCD_UUID)
            if (audioDesc != null) {
                pendingDescriptorWrites.add(audioDesc)
            }

            // Enable battery notifications
            val batteryService = gatt.getService(BleConstants.BATTERY_SERVICE_UUID)
            if (batteryService != null) {
                val batteryChar = batteryService.getCharacteristic(BleConstants.BATTERY_LEVEL_CHAR_UUID)
                if (batteryChar != null) {
                    gatt.setCharacteristicNotification(batteryChar, true)
                    val batteryDesc = batteryChar.getDescriptor(BleConstants.CCCD_UUID)
                    if (batteryDesc != null) {
                        pendingDescriptorWrites.add(batteryDesc)
                    }
                    // Battery read is deferred to writeNextDescriptor() after all
                    // CCCD writes complete — GATT only allows one op at a time.
                }
            }

            // Start writing descriptors sequentially
            Log.d(TAG, "Queued ${pendingDescriptorWrites.size} CCCD descriptor writes")
            writeNextDescriptor()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor written for ${descriptor.characteristic.uuid}")
            } else {
                Log.e(TAG, "Descriptor write failed: $status")
            }
            writeNextDescriptor()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleCharacteristicChanged(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                handleCharacteristicRead(characteristic.uuid, characteristic.value)
            }
            // Transition to READY after the deferred battery read completes
            if (_connectionState.value != ConnectionState.READY) {
                Log.d(TAG, "Characteristic read complete, device is READY")
                _connectionState.value = ConnectionState.READY
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicRead(characteristic.uuid, value)
            }
            // Transition to READY after the deferred battery read completes
            if (_connectionState.value != ConnectionState.READY) {
                Log.d(TAG, "Characteristic read complete, device is READY")
                _connectionState.value = ConnectionState.READY
            }
        }
    }

    private fun handleCharacteristicChanged(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            BleConstants.AUDIO_DATA_CHAR_UUID -> {
                _audioData.tryEmit(value)
            }

            BleConstants.BATTERY_LEVEL_CHAR_UUID -> {
                if (value.isNotEmpty()) {
                    _batteryLevel.value = value[0].toInt() and 0xFF
                }
            }
        }
    }

    private fun handleCharacteristicRead(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            BleConstants.BATTERY_LEVEL_CHAR_UUID -> {
                if (value.isNotEmpty()) {
                    _batteryLevel.value = value[0].toInt() and 0xFF
                    Log.d(TAG, "Battery level: ${_batteryLevel.value}%")
                }
            }

            BleConstants.CODEC_TYPE_CHAR_UUID -> {
                if (value.isNotEmpty()) {
                    val codec = AudioCodec.fromValue(value[0].toInt() and 0xFF)
                    Log.d(TAG, "Codec type: $codec")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun destroy() {
        intentionalDisconnect = true
        scope.cancel()
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Exception closing GATT on destroy (non-fatal): ${e.message}")
        }
        bluetoothGatt = null
    }
}
