package com.biocompare.app.signal

import com.biocompare.shared.model.BioSample
import com.biocompare.shared.model.DeviceSource
import com.biocompare.shared.protocol.MuseGattProfile

/**
 * Streaming EEG pipeline. Maintains a circular buffer per channel and emits
 * a [BioSample.EegBandPower] every [updateIntervalSamples] new samples by
 * running the [EegBandPowerAnalyzer] on the most-recent window.
 *
 * Default config:
 *   - 1-second window (256 samples @ 256 Hz)
 *   - emit every 0.25 s (4 Hz update rate; smooth without overloading UI)
 *   - average across all 4 channels (TP9, AF7, AF8, TP10)
 *
 * Typical usage:
 *   val pipeline = EegPipeline()
 *   museManager.samples
 *     .filterIsInstance<BioSample.EegRaw>()
 *     .mapNotNull { pipeline.feed(it) }
 *     .collect { bandPower -> repository.insert(bandPower) }
 */
class EegPipeline(
    private val sampleRateHz: Int = MuseGattProfile.EEG_SAMPLE_RATE_HZ,
    private val windowSamples: Int = 256,
    private val updateIntervalSamples: Int = 64,
) {
    private val analyzer = EegBandPowerAnalyzer(sampleRateHz, windowSamples)

    private val tp9 = FloatArray(windowSamples)
    private val af7 = FloatArray(windowSamples)
    private val af8 = FloatArray(windowSamples)
    private val tp10 = FloatArray(windowSamples)
    private var writeIdx = 0
    private var samplesSinceLastEmit = 0
    private var samplesAccumulated = 0

    /**
     * Push one sample (one tick across all 4 channels) into the buffer.
     * Returns a [BioSample.EegBandPower] when the pipeline produces a fresh
     * spectrum, otherwise null.
     */
    fun feed(sample: BioSample.EegRaw): BioSample.EegBandPower? {
        tp9[writeIdx] = sample.tp9
        af7[writeIdx] = sample.af7
        af8[writeIdx] = sample.af8
        tp10[writeIdx] = sample.tp10
        writeIdx = (writeIdx + 1) % windowSamples
        samplesAccumulated++
        samplesSinceLastEmit++

        // Need a full window before the first emission, then emit at fixed cadence.
        if (samplesAccumulated < windowSamples) return null
        if (samplesSinceLastEmit < updateIntervalSamples) return null
        samplesSinceLastEmit = 0

        val bp1 = analyzer.analyze(linearise(tp9))
        val bp2 = analyzer.analyze(linearise(af7))
        val bp3 = analyzer.analyze(linearise(af8))
        val bp4 = analyzer.analyze(linearise(tp10))

        return BioSample.EegBandPower(
            timestampMs = sample.timestampMs,
            source = DeviceSource.MUSE_S,
            delta = avg(bp1.delta, bp2.delta, bp3.delta, bp4.delta),
            theta = avg(bp1.theta, bp2.theta, bp3.theta, bp4.theta),
            alpha = avg(bp1.alpha, bp2.alpha, bp3.alpha, bp4.alpha),
            beta = avg(bp1.beta, bp2.beta, bp3.beta, bp4.beta),
            gamma = avg(bp1.gamma, bp2.gamma, bp3.gamma, bp4.gamma),
        )
    }

    /**
     * The circular buffer is convenient for inserts but FFT needs a linear
     * time-ordered array. Roll the buffer so [writeIdx] becomes the start.
     */
    private fun linearise(buf: FloatArray): FloatArray {
        if (writeIdx == 0) return buf
        val out = FloatArray(buf.size)
        var dst = 0
        for (i in writeIdx until buf.size) out[dst++] = buf[i]
        for (i in 0 until writeIdx) out[dst++] = buf[i]
        return out
    }

    private fun avg(vararg v: Float): Float = v.sum() / v.size
}
