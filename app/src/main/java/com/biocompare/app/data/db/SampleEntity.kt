package com.biocompare.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Generic flat sample row. We use a single table with nullable v1..v5 columns
 * rather than one table per sample type because:
 *   - Adding new sample types only changes the [SampleType] string contract.
 *   - The sliding live-dashboard query is one indexed range scan (sessionId +
 *     timestampMs) regardless of sample type.
 *   - Storage overhead of nulls is negligible vs. EEG sample volume.
 *
 * Column meanings by [type]:
 *   HEART_RATE     v1 = bpm; iValue = quality
 *   SPO2           v1 = percent
 *   ACCEL          v1,v2,v3 = x,y,z (m/s²)
 *   GYRO           v1,v2,v3 = x,y,z (°/s)
 *   STEPS          iValue = total steps
 *   BATTERY        iValue = percent
 *   EEG_RAW        v1,v2,v3,v4 = TP9, AF7, AF8, TP10 (µV)
 *   EEG_BAND       v1..v5 = delta, theta, alpha, beta, gamma
 *   PPG            v1,v2,v3 = ambient, infrared, red
 *   STRESS_SCORE   v1 = 0..100 derived value (synthesised, not from device)
 */
@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "timestampMs"]),
        Index(value = ["sessionId", "type"]),
    ],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampMs: Long,
    /** [DeviceSource.name]. */
    val deviceSource: String,
    /** [SampleType] enum name. */
    val type: String,
    val v1: Float? = null,
    val v2: Float? = null,
    val v3: Float? = null,
    val v4: Float? = null,
    val v5: Float? = null,
    val iValue: Int? = null,
)

enum class SampleType {
    HEART_RATE, SPO2, ACCEL, GYRO, STEPS, BATTERY, EEG_RAW, EEG_BAND, PPG, STRESS_SCORE,
}
