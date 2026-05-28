package com.biocompare.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.biocompare.shared.model.BioSample
import com.biocompare.shared.model.DeviceConnectionState
import com.biocompare.shared.model.DeviceSource
import com.biocompare.shared.protocol.Esp32GattProfile
import com.biocompare.shared.util.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BLE manager for the custom ESP32-C3 watch. Subclasses Nordic's [BleManager]
 * to inherit its connection state machine, MTU negotiation, retry / queueing
 * primitives, and notification subscription helpers.
 *
 * Wire format (must match firmware in /firmware/esp32-c3/):
 *   - Heart Rate: standard 0x2A37 (flags byte + uint8 OR uint16 BPM).
 *   - IMU: custom 14-byte packet, 6x int16 (LE) + uint16 device timestamp.
 *   - Battery: standard 0x2A19 (single uint8 percent).
 *
 * Output: [samples] is a hot SharedFlow of decoded [BioSample]s; [connectionState]
 * mirrors the Nordic manager state into our app-level state model.
 */
class Esp32WatchManager(
    context: Context,
) : BleManager(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    private val _samples = MutableSharedFlow<BioSample>(extraBufferCapacity = 256)
    val samples: SharedFlow<BioSample> = _samples.asSharedFlow()

    // GATT characteristic handles, populated in isRequiredServiceSupported().
    private var hrChar: BluetoothGattCharacteristic? = null
    private var imuChar: BluetoothGattCharacteristic? = null
    private var batteryChar: BluetoothGattCharacteristic? = null
    private var controlChar: BluetoothGattCharacteristic? = null

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                _connectionState.value = DeviceConnectionState.Connecting(device.name)
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                // Wait for service discovery before reporting Connected.
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                _connectionState.value = DeviceConnectionState.Error(
                    "Gagal terhubung ESP32-C3 (reason=$reason)"
                )
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                _connectionState.value = DeviceConnectionState.Connected(
                    deviceName = device.name ?: "ESP32-C3",
                    address = device.address,
                )
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                // No-op
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                _connectionState.value = DeviceConnectionState.Disconnected
            }
        })
    }

    override fun getMinLogPriority(): Int = Log.DEBUG

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    /**
     * Called by Nordic after services are discovered. Cache the chars we need
     * and return whether the device exposes our required profile. Returning
     * false here will force a disconnect.
     */
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val hrService = gatt.getService(Esp32GattProfile.HEART_RATE_SERVICE)
        val imuService = gatt.getService(Esp32GattProfile.IMU_SERVICE)

        hrChar = hrService?.getCharacteristic(Esp32GattProfile.HEART_RATE_MEASUREMENT_CHAR)
        imuChar = imuService?.getCharacteristic(Esp32GattProfile.IMU_DATA_CHAR)

        gatt.getService(Esp32GattProfile.BATTERY_SERVICE)?.let { battery ->
            batteryChar = battery.getCharacteristic(Esp32GattProfile.BATTERY_LEVEL_CHAR)
        }
        gatt.getService(Esp32GattProfile.CONTROL_SERVICE)?.let { control ->
            controlChar = control.getCharacteristic(Esp32GattProfile.CONTROL_CMD_CHAR)
        }

        // HR + IMU are the minimum required profile. Battery & control are optional.
        val ok = hrChar != null && imuChar != null
        if (!ok) Log.w(TAG, "ESP32 device missing required HR or IMU service")
        return ok
    }

    override fun initialize() {
        // Larger MTU = fewer BLE packets per IMU notification, much lower jitter.
        requestMtu(247).enqueue()

        // Subscribe to HR notifications.
        hrChar?.let { ch ->
            setNotificationCallback(ch).with { _, data -> onHeartRateData(data) }
            enableNotifications(ch).enqueue()
        }
        // Subscribe to IMU notifications.
        imuChar?.let { ch ->
            setNotificationCallback(ch).with { _, data -> onImuData(data) }
            enableNotifications(ch).enqueue()
        }
        // Battery notifications (optional).
        batteryChar?.let { ch ->
            setNotificationCallback(ch).with { _, data -> onBatteryData(data) }
            enableNotifications(ch).enqueue()
            readCharacteristic(ch).with { _, data -> onBatteryData(data) }.enqueue()
        }
        // Tell the watch to start streaming.
        controlChar?.let { ch ->
            writeCharacteristic(
                ch,
                byteArrayOf(0x01),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ).enqueue()
        }
    }

    override fun onServicesInvalidated() {
        hrChar = null
        imuChar = null
        batteryChar = null
        controlChar = null
    }

    /** Connect to a previously-scanned device. Idempotent. */
    fun connectTo(device: BluetoothDevice) {
        connect(device)
            .useAutoConnect(false)
            .retry(3, 200)
            .timeout(15_000)
            .enqueue()
    }

    fun stopStreamingAndDisconnect() {
        controlChar?.let { ch ->
            writeCharacteristic(
                ch,
                byteArrayOf(0x00),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ).enqueue()
        }
        disconnect().enqueue()
    }

    // ---- Decoders ----

    /**
     * Bluetooth SIG Heart Rate Measurement (0x2A37):
     *   byte 0 = flags. bit0: 0 = uint8 BPM, 1 = uint16 BPM.
     *   bytes 1..n = BPM value.
     */
    private fun onHeartRateData(data: Data) {
        val flags = data.getByte(0)?.toInt() ?: return
        val isUint16 = flags and 0x01 == 1
        val bpm = if (isUint16) {
            data.getIntValue(Data.FORMAT_UINT16_LE, 1)?.toFloat() ?: return
        } else {
            data.getIntValue(Data.FORMAT_UINT8, 1)?.toFloat() ?: return
        }
        emit(
            BioSample.HeartRate(
                timestampMs = MonotonicClock.nowMs(),
                source = DeviceSource.ESP32_WATCH,
                bpm = bpm,
                contactQuality = 3,
            )
        )
    }

    private fun onImuData(data: Data) {
        val raw = data.value ?: return
        if (raw.size < 14) return
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val ax = buf.short.toFloat() / Esp32GattProfile.ACCEL_LSB_PER_G * Esp32GattProfile.EARTH_GRAVITY_MS2
        val ay = buf.short.toFloat() / Esp32GattProfile.ACCEL_LSB_PER_G * Esp32GattProfile.EARTH_GRAVITY_MS2
        val az = buf.short.toFloat() / Esp32GattProfile.ACCEL_LSB_PER_G * Esp32GattProfile.EARTH_GRAVITY_MS2
        val gx = buf.short.toFloat() / Esp32GattProfile.GYRO_LSB_PER_DPS
        val gy = buf.short.toFloat() / Esp32GattProfile.GYRO_LSB_PER_DPS
        val gz = buf.short.toFloat() / Esp32GattProfile.GYRO_LSB_PER_DPS
        // We ignore the device's local 16-bit timestamp for now and tag with phone time.
        val ts = MonotonicClock.nowMs()

        emit(BioSample.Accelerometer(ts, DeviceSource.ESP32_WATCH, ax, ay, az))
        emit(BioSample.Gyroscope(ts, DeviceSource.ESP32_WATCH, gx, gy, gz))
    }

    private fun onBatteryData(data: Data) {
        val pct = data.getIntValue(Data.FORMAT_UINT8, 0) ?: return
        emit(
            BioSample.Battery(
                timestampMs = MonotonicClock.nowMs(),
                source = DeviceSource.ESP32_WATCH,
                percent = pct,
            )
        )
    }

    private fun emit(sample: BioSample) {
        scope.launch { _samples.emit(sample) }
    }

    companion object {
        private const val TAG = "Esp32WatchManager"
    }
}
