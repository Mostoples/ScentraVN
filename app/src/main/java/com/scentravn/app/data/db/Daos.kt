package com.scentravn.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAtMs = :endedAt WHERE id = :id")
    suspend fun finalize(id: Long, endedAt: Long)

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SampleDao {
    /**
     * Used for batched inserts -- batching is critical for EEG data which can
     * arrive at ~1024 samples/sec across 4 channels. The repository buffers
     * samples and flushes every ~250 ms.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(samples: List<SampleEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sample: SampleEntity): Long

    @Query("""
        SELECT * FROM samples
        WHERE sessionId = :sessionId AND type = :type AND deviceSource = :deviceSource
        ORDER BY timestampMs ASC
    """)
    suspend fun getForExport(sessionId: Long, type: String, deviceSource: String): List<SampleEntity>

    @Query("""
        SELECT * FROM samples
        WHERE sessionId = :sessionId
        ORDER BY timestampMs ASC
    """)
    suspend fun getAllForSession(sessionId: Long): List<SampleEntity>

    @Query("SELECT COUNT(*) FROM samples WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Long
}
