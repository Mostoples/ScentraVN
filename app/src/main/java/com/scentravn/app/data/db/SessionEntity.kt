package com.scentravn.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One recording session, e.g. "Demo lomba 2025-05-28". Created when the user
 * taps Start Session and finalised on Stop. Samples are linked back to a
 * session via [SampleEntity.sessionId].
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAtMs: Long,
    /** null while the session is still running. */
    val endedAtMs: Long? = null,
    val notes: String? = null,
)
