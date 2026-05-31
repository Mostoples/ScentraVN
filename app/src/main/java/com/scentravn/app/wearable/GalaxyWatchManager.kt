package com.scentravn.app.wearable

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.scentravn.shared.model.BioSample
import com.scentravn.shared.model.DeviceConnectionState
import com.scentravn.shared.model.DeviceSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side TCP client for the Galaxy Watch (ScentraVN wear app).
 *
 * Architecture: watch runs a TCP server on port 9876 over local WiFi. Phone
 * connects to <watch_ip>:9876, sends commands as line-delimited JSON, and
 * receives sensor data on the same socket.
 *
 * This bypasses Wear OS Data Layer / Galaxy Wearable entirely — phone and
 * watch just need to be on the same WiFi. No Bluetooth pairing required.
 *
 * Public surface:
 *  - [connect] / [disconnect] — manage the TCP session
 *  - [startHr] / [stopHr] — toggle heart-rate streaming
 *  - [measureSpO2] — one-shot SpO2 measurement
 *  - [samples] — flow of BioSamples from the watch
 *  - [connectionState] — Disconnected / Connecting / Connected / Error
 */
@Singleton
class GalaxyWatchManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    private val _samples = MutableSharedFlow<BioSample>(extraBufferCapacity = 256)
    val samples: SharedFlow<BioSample> = _samples.asSharedFlow()

    /** Per-feature measurement status, surfaced as its own card on the phone. */
    private val _featureStatus = MutableStateFlow(defaultFeatureStatus())
    val featureStatus: StateFlow<Map<WatchFeature, WatchFeatureStatus>> = _featureStatus.asStateFlow()

    private fun defaultFeatureStatus(): Map<WatchFeature, WatchFeatureStatus> =
        WatchFeature.entries.associateWith { WatchFeatureStatus.Idle }

    private fun setFeature(feature: WatchFeature, status: WatchFeatureStatus) {
        _featureStatus.value = _featureStatus.value.toMutableMap().apply { put(feature, status) }
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var wantConnected = false
    private var connectionJob: Job? = null
    private var lastWatchIp: String? = null

    /**
     * Connect to a watch at the given IP and keep the session alive. The watch's
     * WiFi radio can briefly sleep and half-close the TCP socket; instead of
     * dying we transparently reconnect. "start_hr" is sent automatically on each
     * (re)connect so heart rate keeps streaming.
     */
    fun connect(watchIp: String) {
        disconnect()
        lastWatchIp = watchIp
        wantConnected = true
        _featureStatus.value = defaultFeatureStatus()
        connectionJob = scope.launch { connectionLoop(watchIp) }
    }

    private suspend fun connectionLoop(watchIp: String) {
        var attempt = 0
        var everConnected = false
        while (wantConnected) {
            _connectionState.value = DeviceConnectionState.Connecting(
                if (attempt == 0) "Watch @ $watchIp" else "Menyambung ulang… ($attempt)"
            )
            var heartbeatJob: Job? = null
            try {
                val s = Socket()
                s.keepAlive = true
                s.tcpNoDelay = true
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    s.connect(InetSocketAddress(watchIp, WATCH_PORT), CONNECT_TIMEOUT_MS.toInt())
                } ?: throw java.net.SocketTimeoutException("Timeout connecting to $watchIp:$WATCH_PORT")
                socket = s
                writer = PrintWriter(s.getOutputStream(), true)
                everConnected = true
                attempt = 0
                _connectionState.value = DeviceConnectionState.Connected("Galaxy Watch ($watchIp)", watchIp)
                Log.d(TAG, "Connected to watch at $watchIp:$WATCH_PORT")

                setFeature(WatchFeature.HEART_RATE, WatchFeatureStatus.Measuring)
                delay(300)
                sendCommand("start_hr")
                sendCommand("get_battery")

                heartbeatJob = scope.launch { heartbeat() }
                readLoop(s) // blocks until the socket dies / EOF
            } catch (e: Exception) {
                Log.w(TAG, "Connection attempt failed: ${e.message}")
            } finally {
                heartbeatJob?.cancel()
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                writer = null
            }

            if (!wantConnected) break

            // Give up only if we never managed to connect (likely wrong IP / app off).
            if (!everConnected && attempt >= MAX_INITIAL_ATTEMPTS) {
                wantConnected = false
                _connectionState.value = DeviceConnectionState.Error(
                    "Gagal connect ke Watch.\n\n" +
                    "Pastikan:\n" +
                    "• Wear app ScentraVN aktif & ditekan Start di Galaxy Watch\n" +
                    "• HP dan Watch di WiFi yang sama\n" +
                    "• IP Watch ($watchIp) benar"
                )
                break
            }
            attempt++
            delay(RECONNECT_DELAY_MS)
        }
    }

    /** Periodic ping keeps NAT/idle timers alive and detects a dead socket fast. */
    private suspend fun heartbeat() {
        while (true) {
            delay(HEARTBEAT_MS)
            val w = writer ?: return
            try {
                w.println(JSONObject().apply { put("cmd", "ping") }.toString())
                w.flush()
                if (w.checkError()) throw java.io.IOException("heartbeat write error")
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat failed — forcing reconnect: ${e.message}")
                try { socket?.close() } catch (_: Exception) {} // breaks readLoop → reconnect
                return
            }
        }
    }

    fun disconnect() {
        wantConnected = false
        connectionJob?.cancel()
        connectionJob = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        _featureStatus.value = defaultFeatureStatus()
        if (_connectionState.value !is DeviceConnectionState.Error) {
            _connectionState.value = DeviceConnectionState.Disconnected
        }
    }

    fun startHr()      = sendCommand("start_hr")
    fun stopHr()       = sendCommand("stop_hr")
    fun getBattery()   = sendCommand("get_battery")

    /** One-shot measurements triggered by tapping "Ukur" on a feature card. */
    fun measureSpO2() {
        setFeature(WatchFeature.SPO2, WatchFeatureStatus.Measuring)
        sendCommand("measure_spo2")
    }
    fun measureBloodPressure() {
        setFeature(WatchFeature.BLOOD_PRESSURE, WatchFeatureStatus.Measuring)
        sendCommand("measure_bp")
    }
    fun measureEcg() {
        setFeature(WatchFeature.ECG, WatchFeatureStatus.Measuring)
        sendCommand("measure_ecg")
    }

    /**
     * Scan the local WiFi subnet for watches running the ScentraVN wear server.
     * Calls [onProgress] with (current, total) and [onFound] with each discovered
     * IP. The phone's own subnet (derived from WifiManager) is scanned in parallel
     * with short connection timeouts.
     */
    fun scanForWatches(
        onProgress: (Int, Int) -> Unit,
        onFound: (String) -> Unit,
        onComplete: () -> Unit,
    ): Job {
        val subnet = getLocalSubnet() ?: return scope.launch {
            Log.w(TAG, "Cannot determine local subnet — WiFi probably off")
            onComplete()
        }
        Log.d(TAG, "Scanning $subnet.1..254 for watches on port $WATCH_PORT")
        val total = 254
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val parentJob = SupervisorJob()
        val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return scanScope.launch {
            try {
                val jobs = (1..254).map { i ->
                    launch {
                        val ip = "$subnet.$i"
                        try {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(ip, WATCH_PORT), 250)
                                Log.d(TAG, "Found watch at $ip")
                                onFound(ip)
                            }
                        } catch (_: Exception) {
                            // Most addresses will fail — silent.
                        } finally {
                            onProgress(done.incrementAndGet(), total)
                        }
                    }
                }
                jobs.forEach { it.join() }
            } finally {
                onComplete()
            }
        }
    }

    /** Returns the /24 subnet prefix (e.g. "192.168.1") of the current WiFi network. */
    private fun getLocalSubnet(): String? {
        return try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            @Suppress("DEPRECATION")
            val ipInt = wifi.connectionInfo.ipAddress
            if (ipInt == 0) return null
            val b1 = ipInt and 0xff
            val b2 = ipInt shr 8 and 0xff
            val b3 = ipInt shr 16 and 0xff
            "$b1.$b2.$b3"
        } catch (e: Exception) {
            Log.w(TAG, "getLocalSubnet failed: ${e.message}")
            null
        }
    }

    /** Used by repository's merge pipeline (compat with old API). */
    suspend fun startStreaming(): Result<Unit> = runCatching {
        val ip = lastWatchIp ?: error("Watch IP belum di-set. Buka dialog 'Hubungkan Galaxy Watch' lebih dulu.")
        connect(ip)
    }
    suspend fun stopStreaming(): Result<Unit> = runCatching { disconnect() }

    private fun sendCommand(cmd: String) {
        val payload = JSONObject().apply { put("cmd", cmd) }.toString()
        // Dispatched to the IO scope: sendCommand is often called from the main
        // thread (a button tap), where a direct socket write throws
        // NetworkOnMainThreadException and the command silently never leaves.
        scope.launch {
            val w = writer ?: run {
                Log.w(TAG, "sendCommand($cmd): not connected")
                return@launch
            }
            try {
                w.println(payload)
                w.flush()
            } catch (e: Exception) {
                Log.w(TAG, "sendCommand failed: ${e.message}")
            }
        }
    }

    /** Blocks reading messages until EOF/socket death. State is owned by [connectionLoop]. */
    private suspend fun readLoop(s: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            while (scope.isActive && !s.isClosed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                handleMessage(line)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Read loop ended: ${e.message}")
        }
    }

    private suspend fun handleMessage(line: String) {
        try {
            val obj = JSONObject(line)
            when (obj.optString("type")) {
                "hr" -> {
                    val bpm = obj.optDouble("bpm").toFloat()
                    setFeature(WatchFeature.HEART_RATE, WatchFeatureStatus.Value("${bpm.toInt()} bpm"))
                    _samples.emit(BioSample.HeartRate(
                        timestampMs    = obj.optLong("ts", System.currentTimeMillis()),
                        source         = DeviceSource.GALAXY_WATCH,
                        bpm            = bpm,
                        contactQuality = obj.optInt("quality", 3),
                    ))
                }
                "hr_status" -> {
                    // Availability hint from the watch — tells the user to wear it.
                    when (obj.optString("status")) {
                        "off_body", "unavailable" -> setFeature(
                            WatchFeature.HEART_RATE,
                            WatchFeatureStatus.Error("Tempelkan watch ke pergelangan"),
                        )
                        "acquiring" -> setFeature(WatchFeature.HEART_RATE, WatchFeatureStatus.Measuring)
                        // "available" → wait for the next hr sample to show bpm.
                    }
                }
                "spo2" -> {
                    val pct = obj.optDouble("percent").toFloat()
                    setFeature(WatchFeature.SPO2, WatchFeatureStatus.Value("${pct.toInt()}%"))
                    _samples.emit(BioSample.SpO2(
                        timestampMs = obj.optLong("ts", System.currentTimeMillis()),
                        source      = DeviceSource.GALAXY_WATCH,
                        percent     = pct,
                    ))
                }
                "battery" -> _samples.emit(BioSample.Battery(
                    timestampMs = obj.optLong("ts", System.currentTimeMillis()),
                    source      = DeviceSource.GALAXY_WATCH,
                    percent     = obj.optInt("percent"),
                ))
                "hello" -> Log.d(TAG, "Watch hello: model=${obj.optString("model")}")
                "ack"   -> handleAck(obj)
                "pong"  -> Log.d(TAG, "Pong received")
                else -> Log.d(TAG, "Unknown message: $line")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse: $line", e)
        }
    }

    /** Resolve a measure command's ack into a feature card status. */
    private fun handleAck(obj: JSONObject) {
        val cmd = obj.optString("cmd")
        val ok = obj.optBoolean("ok")
        val msg = obj.optString("msg").ifBlank { null }
        Log.d(TAG, "Ack $cmd ok=$ok msg=$msg")
        val feature = when (cmd) {
            "measure_spo2" -> WatchFeature.SPO2
            "measure_bp"   -> WatchFeature.BLOOD_PRESSURE
            "measure_ecg"  -> WatchFeature.ECG
            else -> return
        }
        // ok=false means the watch can't provide this reading (no public API).
        // A successful SpO2 value, if ever supported, arrives later as a "spo2" message.
        if (!ok) {
            setFeature(feature, WatchFeatureStatus.Unsupported(msg ?: "Tidak didukung perangkat ini"))
        }
    }

    companion object {
        private const val TAG = "GalaxyWatchManager"
        private const val WATCH_PORT = 9876
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val HEARTBEAT_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MAX_INITIAL_ATTEMPTS = 4
    }
}

/** Galaxy Watch features surfaced as individual cards on the phone dashboard. */
enum class WatchFeature { HEART_RATE, SPO2, STRESS, BLOOD_PRESSURE, ECG }

/** Measurement status for a single [WatchFeature] card. */
sealed interface WatchFeatureStatus {
    /** No measurement requested yet. */
    data object Idle : WatchFeatureStatus
    /** Request sent, waiting for the watch. */
    data object Measuring : WatchFeatureStatus
    /** A reading is available (already formatted for display, e.g. "72 bpm"). */
    data class Value(val text: String) : WatchFeatureStatus
    /** The watch cannot provide this reading via a public API. */
    data class Unsupported(val msg: String) : WatchFeatureStatus
    /** A transient problem (e.g. watch off-wrist). */
    data class Error(val msg: String) : WatchFeatureStatus
}
