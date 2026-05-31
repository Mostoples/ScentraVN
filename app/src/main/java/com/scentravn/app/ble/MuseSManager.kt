package com.scentravn.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.scentravn.app.signal.PpgHeartRateDetector
import com.scentravn.shared.model.BioSample
import com.scentravn.shared.model.DeviceConnectionState
import com.scentravn.shared.model.DeviceSource
import com.scentravn.shared.protocol.MuseGattProfile
import com.scentravn.shared.util.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 *  - We must request a preset (p21 = 4ch EEG + accel + gyro + PPG) before
 *    "d" or the device will refuse to stream.
 *  - PPG2 is the infrared channel used for heart-rate detection.
 *
 * EEG channels (TP9, AF7, AF8, TP10) are emitted as parallel streams;
 * we time-align them by packet counter and emit one [BioSample.EegRaw] per
 * sample tick (256 EegRaw/s). PPG2 samples are fed into [PpgHeartRateDetector]
 * which emits [BioSample.HeartRate] when a stable pulse is detected.
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
    private var ppg1Char: BluetoothGattCharacteristic? = null  // ambient/green
    private var ppg2Char: BluetoothGattCharacteristic? = null  // infrared (HR source)
    private var ppg3Char: BluetoothGattCharacteristic? = null  // red
    private var telemetryChar: BluetoothGattCharacteristic? = null

    /**
     * Buffers the latest 12 samples for each EEG channel keyed by packet
     * counter; once all 4 channels are present for a counter we emit the
     * synchronised [BioSample.EegRaw] events for those 12 sample ticks.
     */
    private val eegBuffer = HashMap<Int, EegPacketAggregate>()

    private val ppgHrDetector = PpgHeartRateDetector(MuseGattProfile.PPG_SAMPLE_RATE_HZ)

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
                ppgHrDetector.reset()
            }
        })
    }

    // ---- Public state control (called by ViewModel during BLE scan) ----

    /** Set to Scanning state while the ViewModel is looking for the device. */
    fun setScanState() {
        _connectionState.value = DeviceConnectionState.Scanning
    }

    /** Called by ViewModel when the BLE scan times out without finding the device. */
    fun setScanFailed(message: String) {
        _connectionState.value = DeviceConnectionState.Error(message)
    }

    /** Called by ViewModel when the scan dialog is dismissed without connecting. */
    fun cancelScan() {
        _connectionState.value = DeviceConnectionState.Disconnected
    }

    // ---- BleManager overrides ----

    override fun getMinLogPriority(): Int = Log.DEBUG
    override fun log(priority: Int, message: String) = Log.println(priority, TAG, message).let {}

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(MuseGattProfile.MUSE_SERVICE) ?: return false
        controlChar = service.getCharacteristic(MuseGattProfile.CONTROL_CHAR)
        eegTp9     = service.getCharacteristic(MuseGattProfile.EEG_TP9_CHAR)
        eegAf7     = service.getCharacteristic(MuseGattProfile.EEG_AF7_CHAR)
        eegAf8     = service.getCharacteristic(MuseGattProfile.EEG_AF8_CHAR)
        eegTp10    = service.getCharacteristic(MuseGattProfile.EEG_TP10_CHAR)
        accelChar  = service.getCharacteristic(MuseGattProfile.ACCEL_CHAR)
        gyroChar   = service.getCharacteristic(MuseGattProfile.GYRO_CHAR)
        ppg1Char   = service.getCharacteristic(MuseGattProfile.PPG1_CHAR)
        ppg2Char   = service.getCharacteristic(MuseGattProfile.PPG2_CHAR)
        ppg3Char   = service.getCharacteristic(MuseGattProfile.PPG3_CHAR)
        telemetryChar = service.getCharacteristic(MuseGattProfile.TELEMETRY_CHAR)
        // Only EEG channels + control are mandatory; PPG/accel/gyro are optional.
        return controlChar != null &&
               eegTp9 != null && eegAf7 != null && eegAf8 != null && eegTp10 != null
    }

    override fun initialize() {
        requestMtu(247).enqueue()

        // EEG channels (required).
        eegTp9?.let  { setNotificationCallback(it).with { _, d -> onEeg(EegChannel.TP9,  d) }; enableNotifications(it).enqueue() }
        eegAf7?.let  { setNotificationCallback(it).with { _, d -> onEeg(EegChannel.AF7,  d) }; enableNotifications(it).enqueue() }
        eegAf8?.let  { setNotificationCallback(it).with { _, d -> onEeg(EegChannel.AF8,  d) }; enableNotifications(it).enqueue() }
        eegTp10?.let { setNotificationCallback(it).with { _, d -> onEeg(EegChannel.TP10, d) }; enableNotifications(it).enqueue() }

        // Motion sensors (optional).
        accelChar?.let { setNotificationCallback(it).with { _, d -> onAccel(d) };    enableNotifications(it).enqueue() }
        gyroChar?.let  { setNotificationCallback(it).with { _, d -> onGyro(d) };     enableNotifications(it).enqueue() }

        // PPG — three channels: ambient (ppg1), infrared (ppg2), red (ppg3).
        // PPG2 infrared is additionally fed to the HR detector.
        ppg1Char?.let { setNotificationCallback(it).with { _, d -> onPpg(PpgChannel.AMBIENT,  d) }; enableNotifications(it).enqueue() }
        ppg2Char?.let { setNotificationCallback(it).with { _, d -> onPpg(PpgChannel.INFRARED, d) }; enableNotifications(it).enqueue() }
        ppg3Char?.let { setNotificationCallback(it).with { _, d -> onPpg(PpgChannel.RED,      d) }; enableNotifications(it).enqueue() }

        // Battery telemetry (optional).
        telemetryChar?.let { setNotificationCallback(it).with { _, d -> onTelemetry(d) }; enableNotifications(it).enqueue() }

        // Send streaming commands AFTER all CCCD writes are done.
        // Use a coroutine with explicit delays: Muse S Gen 2 requires time
        // between preset selection and start-stream when using WRITE_NO_RESPONSE
        // (no acknowledgement, so we must pace manually).
        // p21 = preset 21: 4-ch EEG + accel + gyro + PPG.
        scope.launch {
            delay(1500) // wait for ~10 CCCD writes to complete (~1 s typical)
            sendCommand(MuseGattProfile.Cmd.VERSION)        // wake up command processor
            delay(300)
            sendCommand(MuseGattProfile.Cmd.PRESET_MUSE_S) // p50 = Muse S Gen 1/2
            delay(500)  // let device switch preset mode
            sendCommand(MuseGattProfile.Cmd.START_STREAM)
        }
    }

    override fun onServicesInvalidated() {
        controlChar = null
        eegTp9 = null; eegAf7 = null; eegAf8 = null; eegTp10 = null
        accelChar = null; gyroChar = null
        ppg1Char = null; ppg2Char = null; ppg3Char = null
        telemetryChar = null
    }

    fun connectTo(device: BluetoothDevice) {
        connect(device).useAutoConnect(false).retry(3, 200).timeout(15_000).enqueue()
    }

    fun stopStreamingAndDisconnect() {
        if (isConnected) sendCommand(MuseGattProfile.Cmd.STOP_STREAM)
        disconnect().enqueue()
    }

    private fun sendCommand(cmd: String) {
        val ch = controlChar ?: return
        val cmdBytes = cmd.toByteArray(StandardCharsets.US_ASCII)
        // Muse protocol: first byte = length of command bytes (including newline).
        // Format: [len] [cmd] [0x0A]  e.g. "d\n" → [0x02, 0x64, 0x0A]
        // This matches the muse-js implementation used by web-based Muse apps.
        val packet = ByteArray(cmdBytes.size + 1)
        packet[0] = cmdBytes.size.toByte()
        cmdBytes.copyInto(packet, 1)
        writeCharacteristic(ch, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).enqueue()
    }

    // ---- Decoders ----

    private enum class EegChannel { TP9, AF7, AF8, TP10 }
    private enum class PpgChannel  { AMBIENT, INFRARED, RED }

    /**
     * Decodes a 20-byte Muse EEG packet:
     *   bytes 0..1 = uint16 BE counter
     *   bytes 2..19 = 12 samples × 12-bit packed big-endian
     * Converts each sample: (raw - 2048) × 0.488 µV.
     */
    private fun onEeg(channel: EegChannel, data: Data) {
        val raw = data.value ?: return
        if (raw.size < 20) return

        val counter = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        val samples = FloatArray(MuseGattProfile.EEG_SAMPLES_PER_PACKET)

        for (i in 0 until 6) {
            val offset = 2 + i * 3
            val b0 = raw[offset].toInt() and 0xFF
            val b1 = raw[offset + 1].toInt() and 0xFF
            val b2 = raw[offset + 2].toInt() and 0xFF
            val s0 = (b0 shl 4) or (b1 ushr 4)
            val s1 = ((b1 and 0x0F) shl 8) or b2
            samples[i * 2]     = (s0 - 2048) * MuseGattProfile.EEG_LSB_TO_MICROVOLT
            samples[i * 2 + 1] = (s1 - 2048) * MuseGattProfile.EEG_LSB_TO_MICROVOLT
        }

        val agg = eegBuffer.getOrPut(counter) { EegPacketAggregate() }
        when (channel) {
            EegChannel.TP9  -> agg.tp9  = samples
            EegChannel.AF7  -> agg.af7  = samples
            EegChannel.AF8  -> agg.af8  = samples
            EegChannel.TP10 -> agg.tp10 = samples
        }

        if (agg.isComplete()) {
            eegBuffer.remove(counter)
            val baseTs = MonotonicClock.nowMs()
            val stepMs = 1000.0 / MuseGattProfile.EEG_SAMPLE_RATE_HZ
            for (i in 0 until MuseGattProfile.EEG_SAMPLES_PER_PACKET) {
                val ts = baseTs + (i * stepMs).toLong()
                emit(BioSample.EegRaw(
                    timestampMs = ts,
                    source      = DeviceSource.MUSE_S,
                    tp9         = agg.tp9!![i],
                    af7         = agg.af7!![i],
                    af8         = agg.af8!![i],
                    tp10        = agg.tp10!![i],
                ))
            }
        }

        // Aggressively prune stale buffer entries: keep only the 8 most-recent
        // counters to bound memory if one channel is consistently dropping.
        if (eegBuffer.size > 16) {
            val sorted = eegBuffer.keys.sorted()
            sorted.take(sorted.size - 8).forEach { eegBuffer.remove(it) }
        }
    }

    private class EegPacketAggregate {
        var tp9: FloatArray? = null
        var af7: FloatArray? = null
        var af8: FloatArray? = null
        var tp10: FloatArray? = null
        fun isComplete() = tp9 != null && af7 != null && af8 != null && tp10 != null
    }

    private fun onAccel(data: Data) {
        // 20 bytes = 2 byte counter + 9 × int16 BE samples (3 axes × 3 samples).
        val raw = data.value ?: return
        if (raw.size < 20) return
        val ts = MonotonicClock.nowMs()
        // Take the last of the 3 triplets (most recent).
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

    /**
     * Decodes a 20-byte PPG packet: 2-byte counter + 6 × 24-bit BE samples.
     * All 6 samples are emitted as [BioSample.Ppg]. For the infrared channel
     * (PPG2), each sample is additionally fed to [ppgHrDetector]; when a beat
     * is confirmed a [BioSample.HeartRate] is emitted.
     */
    private fun onPpg(channel: PpgChannel, data: Data) {
        val raw = data.value ?: return
        if (raw.size < 20) return

        val baseTs = MonotonicClock.nowMs()
        val stepMs = 1000.0 / MuseGattProfile.PPG_SAMPLE_RATE_HZ

        for (i in 0 until MuseGattProfile.PPG_SAMPLES_PER_PACKET) {
            val base = 2 + i * 3
            val v = ((raw[base].toInt()     and 0xFF) shl 16) or
                    ((raw[base + 1].toInt() and 0xFF) shl 8)  or
                     (raw[base + 2].toInt() and 0xFF)
            val vf = v.toFloat()
            val ts = baseTs + (i * stepMs).toLong()

            val ppgSample = when (channel) {
                PpgChannel.AMBIENT  -> BioSample.Ppg(ts, DeviceSource.MUSE_S, ambient = vf, infrared = 0f, red = 0f)
                PpgChannel.INFRARED -> BioSample.Ppg(ts, DeviceSource.MUSE_S, ambient = 0f, infrared = vf, red = 0f)
                PpgChannel.RED      -> BioSample.Ppg(ts, DeviceSource.MUSE_S, ambient = 0f, infrared = 0f, red = vf)
            }
            emit(ppgSample)

            // Feed infrared channel to heart-rate detector.
            if (channel == PpgChannel.INFRARED) {
                ppgHrDetector.feed(vf, ts)?.let { bpm ->
                    emit(BioSample.HeartRate(
                        timestampMs    = ts,
                        source         = DeviceSource.MUSE_S,
                        bpm            = bpm,
                        contactQuality = 3,
                    ))
                }
            }
        }
    }

    private fun onTelemetry(data: Data) {
        // counter (2 bytes) + battery%*512 as uint16 BE + ...
        val raw = data.value ?: return
        if (raw.size < 4) return
        val rawBattery = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val percent = (rawBattery / 512).coerceIn(0, 100)
        emit(BioSample.Battery(MonotonicClock.nowMs(), DeviceSource.MUSE_S, percent))
    }

    private fun readInt16BE(b: ByteArray, offset: Int): Int {
        val high = b[offset].toInt()          // sign-extended
        val low  = b[offset + 1].toInt() and 0xFF
        return (high shl 8) or low
    }

    private fun emit(sample: BioSample) {
        scope.launch { _samples.emit(sample) }
    }

    companion object {
        private const val TAG = "MuseSManager"
    }
}
