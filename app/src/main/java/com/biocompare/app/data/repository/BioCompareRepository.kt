package com.biocompare.app.data.repository

import com.biocompare.app.ble.Esp32WatchManager
import com.biocompare.app.ble.MuseSManager
import com.biocompare.app.data.db.SampleDao
import com.biocompare.app.data.db.SampleEntity
import com.biocompare.app.data.db.SampleType
import com.biocompare.app.data.db.SessionDao
import com.biocompare.app.data.db.SessionEntity
import com.biocompare.app.signal.EegPipeline
import com.biocompare.app.signal.HrvAnalyzer
import com.biocompare.app.signal.StressScore
import com.biocompare.app.wearable.GalaxyWatchManager
import com.biocompare.shared.model.BioSample
import com.biocompare.shared.model.DeviceConnectionState
import com.biocompare.shared.util.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single point of truth for live data + session persistence.
 *
 * Responsibilities:
 *  1. Merge sample streams from the three device managers into one
 *     [liveSamples] flow that the UI subscribes to.
 *  2. While a session is active, batch-write every sample to Room so we have
 *     a complete recording afterward.
 *  3. Drive the [EegPipeline] (raw EEG → band powers) and [HrvAnalyzer]
 *     (HR → RMSSD), then synthesise a [BioSample] STRESS_SCORE row.
 *  4. Expose merged connection state per device for the UI status row.
 *
 * Notes on batching: a Muse S streams ~1024 EEG samples/second. Inserting
 * each one with a separate transaction would saturate the WAL and the UI
 * thread. We collect into [insertBuffer] and flush every [FLUSH_INTERVAL_MS]
 * ms (or when buffer hits [FLUSH_THRESHOLD]).
 */
@Singleton
class BioCompareRepository @Inject constructor(
    val esp32: Esp32WatchManager,
    val muse: MuseSManager,
    val galaxyWatch: GalaxyWatchManager,
    private val sessionDao: SessionDao,
    private val sampleDao: SampleDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Hot stream of every sample that arrives, regardless of session state. */
    private val _liveSamples = MutableSharedFlow<BioSample>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val liveSamples: SharedFlow<BioSample> = _liveSamples.asSharedFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    private val _stressScore = MutableStateFlow<Float?>(null)
    val stressScore: StateFlow<Float?> = _stressScore.asStateFlow()

    private val eegPipeline = EegPipeline()
    private val hrvAnalyzer = HrvAnalyzer()

    private val insertBuffer = mutableListOf<SampleEntity>()
    private val bufferLock = Mutex()
    private var flushJob: Job? = null

    init {
        scope.launch { runMergePipeline() }
    }

    val esp32Connection: StateFlow<DeviceConnectionState> get() = esp32.connectionState
    val museConnection: StateFlow<DeviceConnectionState> get() = muse.connectionState
    val galaxyWatchConnection: StateFlow<DeviceConnectionState> get() = galaxyWatch.connectionState

    suspend fun startSession(name: String): Long {
        val id = sessionDao.insert(
            SessionEntity(name = name, startedAtMs = MonotonicClock.nowMs())
        )
        _activeSessionId.value = id
        flushJob = scope.launch { periodicFlush() }
        return id
    }

    suspend fun stopSession() {
        val id = _activeSessionId.value ?: return
        flushJob?.cancel()
        flushJob = null
        bufferLock.withLock { flushLocked() }
        sessionDao.finalize(id, MonotonicClock.nowMs())
        _activeSessionId.value = null
        hrvAnalyzer.reset()
    }

    private suspend fun runMergePipeline() {
        merge(esp32.samples, muse.samples, galaxyWatch.samples).collect { sample ->
            _liveSamples.emit(sample)
            processForDerivedSignals(sample)
            persistIfActive(sample)
        }
    }

    private suspend fun processForDerivedSignals(sample: BioSample) {
        when (sample) {
            is BioSample.EegRaw -> eegPipeline.feed(sample)?.let { bandPower ->
                _liveSamples.emit(bandPower)
                persistIfActive(bandPower)
                updateStressScore(betaAlphaRatio = if (bandPower.alpha > 0f) bandPower.beta / bandPower.alpha else null)
            }
            is BioSample.HeartRate -> {
                val rmssd = hrvAnalyzer.feed(sample.bpm)
                if (rmssd != null) updateStressScore(rmssdMs = rmssd)
            }
            else -> Unit
        }
    }

    private var lastBetaAlpha: Float? = null
    private var lastRmssd: Float? = null
    private suspend fun updateStressScore(rmssdMs: Float? = null, betaAlphaRatio: Float? = null) {
        if (rmssdMs != null) lastRmssd = rmssdMs
        if (betaAlphaRatio != null) lastBetaAlpha = betaAlphaRatio
        val score = StressScore.compute(lastRmssd, lastBetaAlpha)
        if (!score.isNaN()) {
            _stressScore.value = score
            // Persist as its own sample row so it shows up in CSV exports.
            val sessionId = _activeSessionId.value ?: return
            bufferLock.withLock {
                insertBuffer += SampleEntity(
                    sessionId = sessionId,
                    timestampMs = MonotonicClock.nowMs(),
                    deviceSource = "DERIVED",
                    type = SampleType.STRESS_SCORE.name,
                    v1 = score,
                )
            }
        }
    }

    private suspend fun persistIfActive(sample: BioSample) {
        val sessionId = _activeSessionId.value ?: return
        val entity = toEntity(sessionId, sample) ?: return
        val needsFlush: Boolean
        bufferLock.withLock {
            insertBuffer += entity
            needsFlush = insertBuffer.size >= FLUSH_THRESHOLD
        }
        if (needsFlush) bufferLock.withLock { flushLocked() }
    }

    private suspend fun periodicFlush() {
        while (true) {
            kotlinx.coroutines.delay(FLUSH_INTERVAL_MS)
            bufferLock.withLock { flushLocked() }
        }
    }

    /** Caller must hold [bufferLock]. */
    private suspend fun flushLocked() {
        if (insertBuffer.isEmpty()) return
        val toWrite = insertBuffer.toList()
        insertBuffer.clear()
        sampleDao.insertAll(toWrite)
    }

    private fun toEntity(sessionId: Long, s: BioSample): SampleEntity? = when (s) {
        is BioSample.HeartRate -> SampleEntity(
            sessionId = sessionId, timestampMs = s.timestampMs,
            deviceSource = s.source.name, type = SampleType.HEART_RATE.name,
            v1 = s.bpm, iValue = s.contactQuality,
        )
        is BioSample.SpO2 -> SampleEntity(
            sessionId = sessionId, timestampMs = s.timestampMs,
            deviceSource = s.source.name, type = SampleType.SPO2.name,
            v1 = s.percent,
        )
        is BioSample.Accelerometer -> SampleEntity(
            sessionId = sessionId, timestampMs = s.timestampMs,
            deviceSource = s.source.name, type = SampleType.ACCEL.name,
            v1 = s.x, v2 = s.y, v3 = s.z,
        )
        is BioSample.Gyroscope -> SampleEntity(
            sessionId = sessionId, timestampMs = s.timestampMs,
            deviceSource = s.source.name, type = SampleType.GYRO.name,
            v1 = s.x, v2 = s.y, v3 = s.z,
        )
        is BioSample.Steps -> SampleEntity(
            sessionId = sessionId, timestampMs = s.timestampMs,
            deviceSource = s.source.name, type = SampleType.STEPS.name,
            iValue = s.totalSteps.toInt(),
        )
        is BioSample.Battery -> SampleEntity(
            sessionId = sessionId, timestampMs = s.timestampMs,
            deviceSource = s.source.name, type = SampleType.BATTERY.name,
            iValue = s.percent,
        )
        is BioSample.EegRaw -> SampleEntity(
            sessionId = sessionId, timestampMs = s.timestampMs,
            deviceSource = s.source.name, type = SampleType.EEG_RAW.name,
            v1 = s.tp9, v2 = s.af7, v3 = s.af8, v4 = s.tp10,
        )
        is BioSample.EegBandPower -> SampleEntity(
            sessionId = sessionId, timestampMs = s.timestampMs,
            deviceSource = s.source.name, type = SampleType.EEG_BAND.name,
            v1 = s.delta, v2 = s.theta, v3 = s.alpha, v4 = s.beta, v5 = s.gamma,
        )
        is BioSample.Ppg -> SampleEntity(
            sessionId = sessionId, timestampMs = s.timestampMs,
            deviceSource = s.source.name, type = SampleType.PPG.name,
            v1 = s.ambient, v2 = s.infrared, v3 = s.red,
        )
    }

    fun observeSessions() = sessionDao.observeAll()
    suspend fun getSession(id: Long) = sessionDao.get(id)
    suspend fun getAllSamples(sessionId: Long) = sampleDao.getAllForSession(sessionId)

    private companion object {
        const val FLUSH_INTERVAL_MS = 250L
        const val FLUSH_THRESHOLD = 200
    }
}
