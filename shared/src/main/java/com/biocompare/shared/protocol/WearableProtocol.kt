package com.biocompare.shared.protocol

/**
 * Constants for the Wearable Data Layer protocol between the phone app and the
 * Galaxy Watch companion app. Both sides import these so paths/keys can never
 * drift out of sync.
 *
 * Contract:
 *  - Phone --(MessageClient)--> Watch: control commands (start/stop streaming).
 *  - Watch --(MessageClient)--> Phone: live HR / accel samples (low-latency).
 *  - Watch --(DataClient)----> Phone: session metadata, periodic snapshots.
 */
object WearablePaths {
    const val CMD_START_STREAM = "/biocompare/cmd/start"
    const val CMD_STOP_STREAM = "/biocompare/cmd/stop"
    const val CMD_PING = "/biocompare/cmd/ping"

    const val EVT_HEART_RATE = "/biocompare/evt/hr"
    const val EVT_ACCEL = "/biocompare/evt/accel"
    const val EVT_STEPS = "/biocompare/evt/steps"
    const val EVT_BATTERY = "/biocompare/evt/battery"
    const val EVT_STATE = "/biocompare/evt/state"

    const val DATA_SESSION = "/biocompare/data/session"

    /** Capability declared by the watch app so phone can find it via NodeClient. */
    const val CAP_WATCH_APP = "biocompare_watch_app"
}

/**
 * Compact binary payload encoding for low-latency MessageClient pushes.
 * MessageClient has a 100KB cap and is much faster than DataClient for
 * high-frequency streaming; we use a tight binary layout per event type.
 *
 * Format (little-endian):
 *   [0..7]  long  timestampMs
 *   [8..N]  payload bytes
 */
object WearablePayload {

    private fun ByteArray.putLong(offset: Int, value: Long) {
        for (i in 0..7) this[offset + i] = (value ushr (i * 8) and 0xFF).toByte()
    }

    private fun ByteArray.getLong(offset: Int): Long {
        var v = 0L
        for (i in 0..7) v = v or ((this[offset + i].toLong() and 0xFF) shl (i * 8))
        return v
    }

    private fun ByteArray.putFloat(offset: Int, value: Float) {
        val bits = java.lang.Float.floatToRawIntBits(value)
        for (i in 0..3) this[offset + i] = (bits ushr (i * 8) and 0xFF).toByte()
    }

    private fun ByteArray.getFloat(offset: Int): Float {
        var bits = 0
        for (i in 0..3) bits = bits or ((this[offset + i].toInt() and 0xFF) shl (i * 8))
        return java.lang.Float.intBitsToFloat(bits)
    }

    // --- Heart rate: timestamp (8) + bpm (4) + quality (1) = 13 bytes
    fun encodeHeartRate(timestampMs: Long, bpm: Float, quality: Int): ByteArray {
        val out = ByteArray(13)
        out.putLong(0, timestampMs)
        out.putFloat(8, bpm)
        out[12] = quality.toByte()
        return out
    }

    fun decodeHeartRate(bytes: ByteArray): HeartRatePayload {
        require(bytes.size >= 13) { "HR payload too short: ${bytes.size}" }
        return HeartRatePayload(
            timestampMs = bytes.getLong(0),
            bpm = bytes.getFloat(8),
            quality = bytes[12].toInt(),
        )
    }

    // --- Accelerometer: timestamp (8) + x,y,z (12) = 20 bytes
    fun encodeAccel(timestampMs: Long, x: Float, y: Float, z: Float): ByteArray {
        val out = ByteArray(20)
        out.putLong(0, timestampMs)
        out.putFloat(8, x)
        out.putFloat(12, y)
        out.putFloat(16, z)
        return out
    }

    fun decodeAccel(bytes: ByteArray): AccelPayload {
        require(bytes.size >= 20) { "Accel payload too short: ${bytes.size}" }
        return AccelPayload(
            timestampMs = bytes.getLong(0),
            x = bytes.getFloat(8),
            y = bytes.getFloat(12),
            z = bytes.getFloat(16),
        )
    }

    // --- Steps: timestamp (8) + total (8) = 16 bytes
    fun encodeSteps(timestampMs: Long, total: Long): ByteArray {
        val out = ByteArray(16)
        out.putLong(0, timestampMs)
        out.putLong(8, total)
        return out
    }

    fun decodeSteps(bytes: ByteArray): StepsPayload {
        require(bytes.size >= 16) { "Steps payload too short: ${bytes.size}" }
        return StepsPayload(
            timestampMs = bytes.getLong(0),
            total = bytes.getLong(8),
        )
    }

    data class HeartRatePayload(val timestampMs: Long, val bpm: Float, val quality: Int)
    data class AccelPayload(val timestampMs: Long, val x: Float, val y: Float, val z: Float)
    data class StepsPayload(val timestampMs: Long, val total: Long)
}
