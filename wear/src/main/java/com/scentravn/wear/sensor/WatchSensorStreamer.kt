package com.scentravn.wear.sensor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.unregisterMeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.SampleDataPoint
import com.scentravn.shared.util.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * ScentraVN watch streamer — runs a TCP server that the phone app connects to
 * over WiFi (same LAN). All commands come from the phone; the watch only
 * exposes sensor readings.
 *
 * Protocol: line-delimited JSON over TCP on port [PORT].
 *
 *   Phone → Watch:
 *     {"cmd":"start_hr"}         start streaming heart rate
 *     {"cmd":"stop_hr"}          stop streaming heart rate
 *     {"cmd":"measure_spo2"}     one-shot SpO2 (where available)
 *     {"cmd":"get_battery"}      reply with battery
 *     {"cmd":"ping"}             reply with pong
 *
 *   Watch → Phone:
 *     {"type":"hr","ts":<ms>,"bpm":<float>,"quality":<int>}
 *     {"type":"spo2","ts":<ms>,"percent":<float>}
 *     {"type":"battery","ts":<ms>,"percent":<int>}
 *     {"type":"ack","cmd":"<cmd>","ok":<bool>,"msg":"<optional>"}
 *     {"type":"hello","model":"<watch model>","port":<port>}   on connect
 *
 * Only one client connection at a time. New connection replaces the old one.
 */
class WatchSensorStreamer(
    private val context: Context,
) {
    private val measureClient = HealthServices.getClient(context).measureClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var hrActive = false

    // Keep WiFi radio + CPU awake while streaming, otherwise the watch puts the
    // WiFi radio to sleep when the screen turns off and the TCP socket silently
    // half-closes — the phone then sees the connection "freeze".
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val hrCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            if (availability !is DataTypeAvailability) return
            Log.d(TAG, "HR availability=$availability")
            // Forward availability so the phone can tell the user to put the
            // watch on the wrist (off-body = no HR samples at all).
            val status = when (availability) {
                DataTypeAvailability.AVAILABLE   -> "available"
                DataTypeAvailability.ACQUIRING   -> "acquiring"
                DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> "off_body"
                DataTypeAvailability.UNAVAILABLE -> "unavailable"
                else -> "unknown"
            }
            send(JSONObject().apply {
                put("type", "hr_status")
                put("ts", MonotonicClock.nowMs())
                put("status", status)
            })
        }
        override fun onDataReceived(data: DataPointContainer) {
            val hrPoints = data.getData(DataType.HEART_RATE_BPM)
            for (point in hrPoints) {
                if (point !is SampleDataPoint<Double>) continue
                send(JSONObject().apply {
                    put("type", "hr")
                    put("ts", MonotonicClock.nowMs())
                    put("bpm", point.value)
                    put("quality", 3)
                })
            }
        }
    }

    fun start() {
        if (serverSocket != null) return
        acquireLocks()
        scope.launch {
            try {
                val server = ServerSocket(PORT)
                serverSocket = server
                Log.d(TAG, "TCP server listening on :${server.localPort}")
                acceptLoop(server)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TCP server: ${e.message}", e)
            }
        }
        // Periodically broadcast battery so phone can show it without asking.
        scope.launch {
            while (isActive) {
                if (clientSocket != null) sendBattery()
                delay(30_000)
            }
        }
    }

    fun stop() {
        stopHr()
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        serverSocket = null
        writer = null
        releaseLocks()
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF keeps the radio fully powered with screen off.
    private fun acquireLocks() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ScentraVN:wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScentraVN:stream")?.apply {
                setReferenceCounted(false)
                acquire(STREAM_WAKELOCK_TIMEOUT_MS)
            }
            Log.d(TAG, "WiFi + wake locks acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wifiLock = null
        wakeLock = null
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                val client = server.accept()
                try { client.keepAlive = true; client.tcpNoDelay = true } catch (_: Exception) {}
                Log.d(TAG, "Phone connected from ${client.inetAddress.hostAddress}")
                // Drop any previous client.
                try { clientSocket?.close() } catch (_: Exception) {}
                clientSocket = client
                writer = PrintWriter(client.getOutputStream(), true)
                send(JSONObject().apply {
                    put("type", "hello")
                    put("model", android.os.Build.MODEL)
                    put("port", PORT)
                })
                sendBattery()
                scope.launch { readCommandsFrom(client) }
            } catch (e: Exception) {
                if (!server.isClosed) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
        }
    }

    private fun readCommandsFrom(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (!client.isClosed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                handleCommand(line)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client read loop ended: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
            if (clientSocket == client) {
                clientSocket = null
                writer = null
                stopHr()
            }
            Log.d(TAG, "Phone disconnected")
        }
    }

    private fun handleCommand(line: String) {
        val cmd = try {
            JSONObject(line).optString("cmd")
        } catch (_: Exception) { return }
        Log.d(TAG, "Received cmd=$cmd")
        when (cmd) {
            "start_hr"     -> { startHr(); ack(cmd, true) }
            "stop_hr"      -> { stopHr();  ack(cmd, true) }
            "measure_spo2" -> measureSpo2()
            "measure_bp"   -> measureBloodPressure()
            "measure_ecg"  -> measureEcg()
            "get_battery"  -> sendBattery()
            "ping"         -> send(JSONObject().apply { put("type","pong"); put("ts", MonotonicClock.nowMs()) })
            else           -> ack(cmd, false, "unknown command")
        }
    }

    private fun startHr() {
        if (hrActive) return
        try {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, hrCallback)
            hrActive = true
            Log.d(TAG, "HR streaming started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HR: ${e.message}", e)
        }
    }

    private fun stopHr() {
        if (!hrActive) return
        hrActive = false
        scope.launch {
            try {
                measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, hrCallback)
                Log.d(TAG, "HR streaming stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister HR: ${e.message}")
            }
        }
    }

    private fun measureSpo2() {
        // SpO2 tidak diekspos MeasureClient di Galaxy Watch (Wear OS). Hanya
        // tersedia lewat Samsung Health / Privileged Health SDK yang butuh
        // persetujuan kemitraan Samsung — tidak ada API publik untuk app ini.
        ack("measure_spo2", false, "SpO2 belum didukung lewat API publik di watch ini")
    }

    private fun measureBloodPressure() {
        // Tekanan darah hanya bisa diukur lewat Samsung Health Monitor (perlu
        // kalibrasi manset, teregulasi). Tidak ada API untuk app pihak ketiga.
        ack("measure_bp", false, "Tekanan darah hanya via Samsung Health Monitor — tak ada API publik")
    }

    private fun measureEcg() {
        // EKG hanya tersedia lewat Samsung Health Monitor (teregulasi sebagai
        // alat medis). Tidak ada API untuk app pihak ketiga.
        ack("measure_ecg", false, "EKG hanya via Samsung Health Monitor — tak ada API publik")
    }

    private fun sendBattery() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: run {
            // Fallback for older devices: sticky broadcast.
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (lvl >= 0 && scale > 0) (lvl * 100 / scale) else -1
        }
        if (level < 0) return
        send(JSONObject().apply {
            put("type", "battery")
            put("ts", MonotonicClock.nowMs())
            put("percent", level)
        })
    }

    private fun ack(cmd: String, ok: Boolean, msg: String? = null) {
        send(JSONObject().apply {
            put("type", "ack")
            put("cmd", cmd)
            put("ok", ok)
            if (msg != null) put("msg", msg)
        })
    }

    private fun send(obj: JSONObject) {
        // Only send when a client is connected. A transient write error is NOT
        // treated as a disconnect — the authoritative signal is readCommandsFrom
        // hitting EOF. Dropping the client on every hiccup caused reconnect storms.
        if (clientSocket == null) return
        val payload = obj.toString()
        // IMPORTANT: the Health Services MeasureCallback fires on the MAIN thread,
        // so writing the socket here directly throws NetworkOnMainThreadException
        // (which surfaces as "send failed: null"). Always write from the IO scope.
        // PrintWriter.println is internally synchronized, so concurrent sends stay
        // line-framed.
        scope.launch {
            val w = writer ?: return@launch
            try {
                w.println(payload)
                w.flush()
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "WatchSensorStreamer"
        /** Fixed TCP port — phone connects to <watch_ip>:9876. */
        const val PORT = 9876
        /** Safety cap so a forgotten lock can't drain the watch battery forever. */
        private const val STREAM_WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L
    }
}
