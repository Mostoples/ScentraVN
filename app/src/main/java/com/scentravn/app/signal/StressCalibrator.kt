package com.scentravn.app.signal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calibrated stress estimator for the Galaxy Watch.
 *
 * The watch (Health Services) only exposes averaged BPM, not beat-to-beat
 * intervals, so an absolute clinical stress/HRV value is impossible. Instead we
 * compute a **raw arousal index** (rolling-mean heart rate) and let the user
 * calibrate it against the value shown on the watch's own stress widget.
 *
 * Calibration is a 3-point linear fit: the user reads the watch's stress number
 * and we pair it with the current raw index. After 3 points we fit
 * `watchStress ≈ a * rawIndex + b` (least squares) and from then on report
 * `calibratedStress = a * rawIndex + b`, clamped to 0..100. Persisted across
 * launches in SharedPreferences.
 */
@Singleton
class StressCalibrator @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("stress_calibration", Context.MODE_PRIVATE)

    private val window = ArrayDeque<Float>()           // recent BPM samples
    private val points = mutableListOf<Pair<Float, Float>>() // (rawIndex, watchStress)
    private var slope: Float? = null
    private var intercept: Float? = null

    init { load() }

    @Synchronized
    fun feedHr(bpm: Float) {
        if (bpm <= 0f) return
        window.addLast(bpm)
        while (window.size > WINDOW) window.removeFirst()
    }

    /** Rolling-mean HR as the raw arousal index, or null until enough samples. */
    @Synchronized
    fun currentRaw(): Float? =
        if (window.size >= MIN_SAMPLES) window.sum() / window.size else null

    @Synchronized fun pointCount(): Int = points.size
    // Require the full 3-point set so we report a real fitted line, never a
    // degenerate single-point constant.
    @Synchronized fun isCalibrated(): Boolean = points.size >= MAX_POINTS && slope != null

    /** Capture one calibration point from the watch's displayed stress value. */
    @Synchronized
    fun capture(watchStress: Float): CaptureResult {
        val raw = currentRaw() ?: return CaptureResult.NO_HR
        points.add(raw to watchStress)
        while (points.size > MAX_POINTS) points.removeAt(0)
        fit()
        save()
        return if (points.size >= MAX_POINTS) CaptureResult.DONE else CaptureResult.MORE
    }

    @Synchronized
    fun reset() {
        points.clear(); slope = null; intercept = null; save()
    }

    /** Calibrated stress 0..100, or null until 3 points are captured / no HR yet. */
    @Synchronized
    fun calibratedStress(): Float? {
        if (points.size < MAX_POINTS) return null
        val raw = currentRaw() ?: return null
        val a = slope ?: return null
        val b = intercept ?: return null
        return (a * raw + b).coerceIn(0f, 100f)
    }

    /** Least-squares line through the calibration points (degenerate → constant). */
    private fun fit() {
        val n = points.size
        if (n == 0) { slope = null; intercept = null; return }
        if (n == 1) { slope = 0f; intercept = points[0].second; return }
        val sx = points.sumOf { it.first.toDouble() }
        val sy = points.sumOf { it.second.toDouble() }
        val sxx = points.sumOf { it.first.toDouble() * it.first }
        val sxy = points.sumOf { it.first.toDouble() * it.second }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-6) {
            // All raw indices ~equal (e.g. calibrated at one state) → constant avg.
            slope = 0f; intercept = (sy / n).toFloat(); return
        }
        val a = (n * sxy - sx * sy) / denom
        val b = (sy - a * sx) / n
        slope = a.toFloat(); intercept = b.toFloat()
    }

    private fun save() {
        prefs.edit().apply {
            slope?.let { putFloat("slope", it) } ?: remove("slope")
            intercept?.let { putFloat("intercept", it) } ?: remove("intercept")
            putString("points", points.joinToString(";") { "${it.first}:${it.second}" })
            apply()
        }
    }

    private fun load() {
        if (prefs.contains("slope")) slope = prefs.getFloat("slope", 0f)
        if (prefs.contains("intercept")) intercept = prefs.getFloat("intercept", 0f)
        prefs.getString("points", "")?.split(";")?.forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val r = parts[0].toFloatOrNull(); val w = parts[1].toFloatOrNull()
                if (r != null && w != null) points.add(r to w)
            }
        }
    }

    enum class CaptureResult { NO_HR, MORE, DONE }

    companion object {
        private const val WINDOW = 30       // ~last 30 HR samples
        private const val MIN_SAMPLES = 3
        private const val MAX_POINTS = 3    // 3-point calibration
    }
}
