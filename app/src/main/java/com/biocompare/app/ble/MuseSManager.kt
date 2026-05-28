package com.biocompare.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.biocompare.shared.model.BioSample
import com.biocompare.shared.model.DeviceConnectionState
import com.biocompare.shared.model.DeviceSource
import com.biocompare.shared.protocol.MuseGattProfile
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
import java.nio.charset.StandardCharsets

/**
 * BLE manager for Muse S Gen 2 (Interaxon).
 *
 * Muse uses an ASCII command protocol over a single control characteristic
 * and emits sensor data on multiple notify characteristics. Quirks:
 *
 *  - Each EEG packet is exactly 20 bytes and contains 12 samples packed as
 *    12-bit unsigned integers, big-endian, with a 2-byte counter prefix.
 *  - Sample rate is 256 Hz per channel, so on each channel we receive a
 *    20-byte notification roughly every 47 ms.
 *  - We must request a preset (e.g. p21 = 4ch EEG + accel + PPG) before "d"
 *    or the device will refuse to stream.
 *
 * The four EEG channels (TP9, AF7, AF8, TP10) are emitted as parallel
 * streams; we time-align them by counter and emit one [BioSample.EegRaw]
 * per sample tick (so 256 EegRaw per second per device).
 */
class MuseSManager(
    context: Context,
) : BleManager(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    private val _samples = MutableSharedFlow<BioSample>(extraBufferCapacity = 4096)
    val samples: SharedFlow<BioSample> = _samples.asSharedFlow()

    private var controlChar: BluetoothGattCharacteristic? = null
    private var eegTp9: BluetoothGattCharacteristic? = null
    private var eegAf7: BluetoothGattCharacteristic? = null
    private var eegAf8: BluetoothGattCharacteristic? = null
    private var eegTp10: BluetoothGattCharacteristic? = null
    private var accelChar: BluetoothGattCharacteristic? = null
    private var gyroChar: BluetoothGattCharacteristic? = null
    private var ppg2Char: BluetoothGattCharacteristic? = null
    private var telemetryChar: BluetoothGattCharacteristic? = null

    /**
     * Buffers the latest 12 samples for each EEG channel keyed by packet
     * counter; once all 4 channels are present for a counter we emit the
     * synchronised [BioSample.EegRaw] events for those 12 sample ticks.
     */
    private val eegBuffer = HashMap<Int, EegPacketAggregate>()

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                _connectionState.value = DeviceConnectionState.Connecting(device.name)
            }
            override fun onDeviceConnected(device: BluetoothDevice) {}
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                _connectionState.value = DeviceConnectionState.Error("Gagal terhubung Muse S (reason=$reason)")
            }
            override fun onDeviceReady(device: BluetoothDevice) {
                _connectionState.value = DeviceConnectionState.Connected(
                    deviceName = device.name ?: "Muse S",
                    address = device.address,
                )
            }
            override fun onDeviceDisconnecting(device: BluetoothDevice) {}
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                _connectionState.value = DeviceConnectionState.Disconnected
                eegBuffer.clear()
            }
        })
    }

    override fun getMinLogPriority(): Int = Log.DEBUG
    override fun log(priority: Int, message: String) = Log.println(priority, TAG, message).let {}

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(MuseGattProfile.MUSE_SERVICE) ?: return false
        controlChar = service.getCharacteristic(MuseGattProfile.CONTROL_CHAR)
        eegTp9 = service.getCharacteristic(MuseGattProfile.EEG_TP9_CHAR)
        eegAf7 = service.getCharacteristic(MuseGattProfile.EEG_AF7_CHAR)
        eegAf8 = service.getCharacteristic(MuseGattProfile.EEG_AF8_CHAR)
        eegTp10 = service.getCharacteristic(MuseGattProfile.EEG_TP10_CHAR)
        accelChar = service.getCharacteristic(MuseGattProfile.ACCEL_CHAR)
        gyroChar = service.getCharacteristic(MuseGattProfile.GYRO_CHAR)
        ppg2Char = service.getCharacteristic(MuseGattProfile.PPG2_CHAR)
        telemetryChar = service.getCharacteristic(MuseGattProfile.TELEMETRY_CHAR)
        return controlChar != null && eegTp9 != null && eegAf7 != null && eegAf8 != null && eegTp10 != null
    }

    override fun initialize() {
        requestMtu(247).enqueue()

        eegTp9?.let { setNotificationCallback(it).with { _, d -> onEeg(EegChannel.TP9, d) }; enableNotifications(it).enqueue() }
        eegAf7?.let { setNotificationCallback(it).with { _, d -> onEeg(EegChannel.AF7, d) }; enableNotifications(it).enqueue() }
        eegAf8?.let { setNotificationCallback(it).with { _, d -> onEeg(EegChannel.AF8, d) }; enableNotifications(it).enqueue() }
        eegTp10?.let { setNotificationCallback(it).with { _, d -> onEeg(EegChannel.TP10, d) }; enableNotifications(it).enqueue() }

        accelChar?.let { setNotificationCallback(it).with { _, d -> onAccel(d) }; enableNotifications(it).enqueue() }
        gyroChar?.let { setNotificationCallback(it).with { _, d -> onGyro(d) }; enableNotifications(it).enqueue() }
        ppg2Char?.let { setNotificationCallback(it).with { _, d -> onPpg(d) }; enableNotifications(it).enqueue() }
        telemetryChar?.let { setNotificationCallback(it).with { _, d -> onTelemetry(d) }; enableNotifications(it).enqueue() }

        // Handshake: request preset 21 then start streaming.
        sendCommand(MuseGattProfile.Cmd.VERSION)
        sendCommand(MuseGattProfile.Cmd.PRESET_AB)
        sendCommand(MuseGattProfile.Cmd.STATUS)
        sendCommand(MuseGattProfile.Cmd.START_STREAM)
    }

    override fun onServicesInvalidated() {
        controlChar = null
        eegTp9 = null; eegAf7 = null; eegAf8 = null; eegTp10 = null
        accelChar = null; gyroChar = null; ppg2Char = null; telemetryChar = null
    }

    fun connectTo(device: BluetoothDevice) {
        connect(device).useAutoConnect(false).retry(3, 200).timeout(15_000).enqueue()
    }

    fun stopStreamingAndDisconnect() {
        sendCommand(MuseGattProfile.Cmd.STOP_STREAM)
        disconnect().enqueue()
    }

    private fun sendCommand(cmd: String) {
        val ch = controlChar ?: return
        // Muse expects the length byte as a prefix on its old firmwares; modern
        // firmware accepts the bare ASCII string. We send raw -- if your device
        // is on very old firmware, prepend the length byte here.
        val bytes = cmd.toByteArray(StandardCharsets.US_ASCII)
        writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    // ---- Decoders ----

    private enum class EegChannel { TP9, AF7, AF8, TP10 }

    /**
     * Decodes a 20-byte Muse EEG packet:
     *   bytes 0..1 = uint16 BE counter (this is the sample-tick index of the
     *                first sample in this 12-sample burst).
     *   bytes 2..19 = 12 samples × 12 bits, big-endian, packed.
     * Each sample is converted (sample - 2048) * 0.488 µV.
     */
    private fun onEeg(channel: EegChannel, data: Data) {
        val raw = data.value ?: return
        if (raw.size < 20) return

        val counter = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        val samples = FloatArray(MuseGattProfile.EEG_SAMPLES_PER_PACKET)

        // Unpack 12 × 12-bit samples from 18 bytes (= 144 bits).
        // Indexing: every two samples occupy three bytes.
        for (i in 0 until 6) {
            val offset = 2 + i * 3
            val b0 = raw[offset].toInt() and 0xFF
            val b1 = raw[offset + 1].toInt() and 0xFF
            val b2 = raw[offset + 2].toInt() and 0xFF
            val s0 = (b0 shl 4) or (b1 ushr 4)
            val s1 = ((b1 and 0x0F) shl 8) or b2
            samples[i * 2] = (s0 - 2048) * MuseGattProfile.EEG_LSB_TO_MICROVOLT
            samples[i * 2 + 1] = (s1 - 2048) * MuseGattProfile.EEG_LSB_TO_MICROVOLT
        }

        val agg = eegBuffer.getOrPut(counter) { EegPacketAggregate() }
        when (channel) {
            EegChannel.TP9 -> agg.tp9 = samples
            EegChannel.AF7 -> agg.af7 = samples
            EegChannel.AF8 -> agg.af8 = samples
            EegChannel.TP10 -> agg.tp10 = samples
        }

        if (agg.isComplete()) {
            eegBuffer.remove(counter)
            val baseTs = MonotonicClock.nowMs()
            val sampleIntervalMs = 1000.0 / MuseGattProfile.EEG_SAMPLE_RATE_HZ
            for (i in 0 until MuseGattProfile.EEG_SAMPLES_PER_PACKET) {
                val ts = baseTs + (i * sampleIntervalMs).toLong()
                emit(
                    BioSample.EegRaw(
                        timestampMs = ts,
                        source = DeviceSource.MUSE_S,
                        tp9 = agg.tp9!![i],
                        af7 = agg.af7!![i],
                        af8 = agg.af8!![i],
                        tp10 = agg.tp10!![i],
                    )
                )
            }
        }

        // Drop stale buffer entries to prevent unbounded memory growth if a
        // channel notification is permanently dropped.
        if (eegBuffer.size > 32) {
            val oldest = eegBuffer.keys.minOrNull()
            if (oldest != null) eegBuffer.remove(oldest)
        }
    }

    private class EegPacketAggregate {
        var tp9: FloatArray? = null
        var af7: FloatArray? = null
        var af8: FloatArray? = null
        var tp10: FloatArray? = null
        fun isComplete(): Boolean = tp9 != null && af7 != null && af8 != null && tp10 != null
    }

    private fun onAccel(data: Data) {
        // Muse accel: 20 bytes = 2 byte counter + 9 × int16 BE samples (3 axes × 3 samples).
        val raw = data.value ?: return
        if (raw.size < 20) return
        val ts = MonotonicClock.nowMs()
        // Use the latest of the 3 sample triplets; 50 Hz total.
        val x = readInt16BE(raw, 14) / 16384.0f * 9.80665f
        val y = readInt16BE(raw, 16) / 16384.0f * 9.80665f
        val z = readInt16BE(raw, 18) / 16384.0f * 9.80665f
        emit(BioSample.Accelerometer(ts, DeviceSource.MUSE_S, x, y, z))
    }

    private fun onGyro(data: Data) {
        val raw = data.value ?: return
        if (raw.size < 20) return
        val ts = MonotonicClock.nowMs()
        val x = readInt16BE(raw, 14) / 131.0f
        val y = readInt16BE(raw, 16) / 131.0f
        val z = readInt16BE(raw, 18) / 131.0f
        emit(BioSample.Gyroscope(ts, DeviceSource.MUSE_S, x, y, z))
    }

    private fun onPpg(data: Data) {
        // Muse PPG: 20 bytes = 2 byte counter + 6 × 24-bit BE sample.
        val raw = data.value ?: return
        if (raw.size < 20) return
        val ts = MonotonicClock.nowMs()
        // Take the most recent 24-bit sample.
        val v = ((raw[17].toInt() and 0xFF) shl 16) or
                ((raw[18].toInt() and 0xFF) shl 8) or
                (raw[19].toInt() and 0xFF)
        emit(
            BioSample.Ppg(
                timestampMs = ts,
                source = DeviceSource.MUSE_S,
                ambient = 0f,
                infrared = v.toFloat(),
                red = 0f,
            )
        )
    }

    private fun onTelemetry(data: Data) {
        // Telemetry packet: counter + battery%*512 (uint16 BE) + ...
        val raw = data.value ?: return
        if (raw.size < 4) return
        val rawBattery = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val percent = (rawBattery / 512).coerceIn(0, 100)
        emit(
            BioSample.Battery(
                timestampMs = MonotonicClock.nowMs(),
                source = DeviceSource.MUSE_S,
                percent = percent,
            )
        )
    }

    private fun readInt16BE(b: ByteArray, offset: Int): Int {
        val high = b[offset].toInt()           // sign-extended
        val low = b[offset + 1].toInt() and 0xFF
        return (high shl 8) or low
    }

    private fun emit(sample: BioSample) {
        scope.launch { _samples.emit(sample) }
    }

    companion object {
        private const val TAG = "MuseSManager"
    }
}
