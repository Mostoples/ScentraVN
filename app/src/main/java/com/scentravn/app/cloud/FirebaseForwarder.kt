package com.scentravn.app.cloud

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.scentravn.app.data.repository.ScentraVNRepository
import com.scentravn.shared.model.BioSample
import com.scentravn.shared.model.DeviceConnectionState
import com.scentravn.shared.model.DeviceSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge: pushes the live merged biosignal snapshot to Firebase Realtime
 * Database so the (frameworkless HTML/JS) web app can render everything via
 * `onValue("scentravn/live")`. The phone app is purely the connector; all
 * output lives in the web app.
 *
 * Firebase is initialised manually with [FirebaseOptions] (no google-services
 * plugin / google-services.json needed) using the project's web config.
 *
 * Writes are throttled to [WRITE_INTERVAL_MS] to stay well within RTDB quotas.
 * See WEB_APP_INTEGRATION.md for the data contract.
 */
@Singleton
class FirebaseForwarder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScentraVNRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var dirty = false
    private var liveRef: DatabaseReference? = null

    private val devices = linkedMapOf(
        KEY_GALAXY to DeviceLive("GALAXY_WATCH"),
        KEY_ESP32 to DeviceLive("ESP32_WATCH"),
        KEY_MUSE to DeviceLive("MUSE_S"),
    )
    @Volatile private var stressValue: Float? = null

    /** Idempotent — safe to call from multiple entry points (e.g. ViewModel init). */
    fun start() {
        if (started) return
        started = true
        liveRef = try {
            val app = ensureFirebaseApp()
            val db = FirebaseDatabase.getInstance(app, DATABASE_URL)
            db.getReference("scentravn/live")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase init failed: ${e.message}", e)
            started = false
            return
        }
        collectFlows()
        startWriter()
        Log.d(TAG, "FirebaseForwarder started → $DATABASE_URL")
    }

    private fun ensureFirebaseApp(): FirebaseApp = try {
        FirebaseApp.getInstance()
    } catch (e: IllegalStateException) {
        val options = FirebaseOptions.Builder()
            .setProjectId(PROJECT_ID)
            .setApplicationId(APP_ID)
            .setApiKey(API_KEY)
            .setDatabaseUrl(DATABASE_URL)
            .build()
        FirebaseApp.initializeApp(context, options)
    }

    private fun collectFlows() {
        scope.launch {
            repository.liveSamples.collect { sample ->
                applySample(sample)
                dirty = true
            }
        }
        scope.launch {
            repository.galaxyWatchConnection.collect { c ->
                devices[KEY_GALAXY]?.connected = c is DeviceConnectionState.Connected
                dirty = true
            }
        }
        scope.launch {
            repository.esp32Connection.collect { c ->
                devices[KEY_ESP32]?.connected = c is DeviceConnectionState.Connected
                dirty = true
            }
        }
        scope.launch {
            repository.museConnection.collect { c ->
                devices[KEY_MUSE]?.connected = c is DeviceConnectionState.Connected
                dirty = true
            }
        }
        scope.launch {
            repository.stressScore.collect { score ->
                stressValue = score
                dirty = true
            }
        }
    }

    private fun startWriter() {
        scope.launch {
            while (isActive) {
                delay(WRITE_INTERVAL_MS)
                if (!dirty) continue
                dirty = false
                try {
                    liveRef?.setValue(buildLiveMap())
                } catch (e: Exception) {
                    Log.w(TAG, "RTDB write failed: ${e.message}")
                }
            }
        }
    }

    private fun applySample(s: BioSample) {
        when (s) {
            is BioSample.HeartRate -> deviceFor(s.source)?.apply {
                bpm = s.bpm.toInt(); updatedAt = s.timestampMs
            }
            is BioSample.Battery -> deviceFor(s.source)?.apply {
                battery = s.percent; updatedAt = s.timestampMs
            }
            is BioSample.SpO2 -> deviceFor(s.source)?.apply {
                spo2 = s.percent.toInt(); updatedAt = s.timestampMs
            }
            is BioSample.EegBandPower -> devices[KEY_MUSE]?.apply {
                eeg = mapOf(
                    "delta" to s.delta.toDouble(), "theta" to s.theta.toDouble(),
                    "alpha" to s.alpha.toDouble(), "beta" to s.beta.toDouble(),
                    "gamma" to s.gamma.toDouble(),
                )
                betaAlpha = if (s.alpha > 0f) (s.beta / s.alpha).toDouble() else null
                updatedAt = s.timestampMs
            }
            else -> Unit
        }
    }

    private fun deviceFor(src: DeviceSource): DeviceLive? = when (src) {
        DeviceSource.GALAXY_WATCH -> devices[KEY_GALAXY]
        DeviceSource.ESP32_WATCH -> devices[KEY_ESP32]
        DeviceSource.MUSE_S -> devices[KEY_MUSE]
    }

    private fun buildLiveMap(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        // Stress is derived from the Galaxy Watch HR, so it lives INSIDE galaxyWatch
        // (path: /scentravn/live/galaxyWatch/stress).
        val s = stressValue
        val stressMap = hashMapOf<String, Any?>(
            "value" to s?.toDouble(),
            "level" to stressLevel(s),
            "source" to if (s != null) "WATCH_CALIBRATED" else null,
            "updatedAt" to now,
        )
        val out = HashMap<String, Any?>()
        for ((key, d) in devices) {
            val m = HashMap<String, Any?>()
            m["source"] = d.source
            m["connected"] = d.connected
            m["bpm"] = if (d.connected) d.bpm else null
            m["battery"] = d.battery
            if (key == KEY_ESP32) m["spo2"] = d.spo2
            if (key == KEY_MUSE) {
                m["eeg"] = d.eeg
                m["betaAlpha"] = d.betaAlpha
            }
            if (key == KEY_GALAXY) m["stress"] = stressMap
            m["updatedAt"] = d.updatedAt
            out[key] = m
        }
        return out
    }

    // Matches the Galaxy Watch's category bands (Rileks/Rendah/Sedang/Tinggi).
    private fun stressLevel(v: Float?): String = when {
        v == null -> "unavailable"
        v < 25f -> "rileks"
        v < 50f -> "rendah"
        v < 75f -> "sedang"
        else -> "tinggi"
    }

    private class DeviceLive(val source: String) {
        var connected = false
        var bpm: Int? = null
        var battery: Int? = null
        var spo2: Int? = null
        var eeg: Map<String, Double>? = null
        var betaAlpha: Double? = null
        var updatedAt: Long = 0
    }

    companion object {
        private const val TAG = "FirebaseForwarder"
        private const val WRITE_INTERVAL_MS = 500L

        // ScentraVN Firebase project (web config — reused for manual Android init).
        private const val PROJECT_ID = "scentravn"
        private const val APP_ID = "1:479113972827:web:399f5543c7624e75b1037e"
        private const val API_KEY = "AIzaSyCvYBVasZNLghuQhRhLwoYOPkdR3noVXrA"
        private const val DATABASE_URL =
            "https://scentravn-default-rtdb.asia-southeast1.firebasedatabase.app"

        private const val KEY_GALAXY = "galaxyWatch"
        private const val KEY_ESP32 = "esp32"
        private const val KEY_MUSE = "muse"
    }
}
