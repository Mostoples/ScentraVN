package com.biocompare.wear.sensor

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.SampleDataPoint
import com.biocompare.shared.protocol.WearablePaths
import com.biocompare.shared.protocol.WearablePayload
import com.biocompare.shared.util.MonotonicClock
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Reads sensors via Health Services on the watch and forwards each sample to
 * the paired phone via MessageClient. Health Services hides per-vendor sensor
 * differences and on Galaxy Watch routes us through Samsung Health.
 *
 * We deliberately stream individual data points rather than aggregating: at
 * 1-2 Hz for HR and ~50 Hz for accel the bandwidth is trivial and the latency
 * benefit makes the live dashboard look truly real-time.
 */
class WatchSensorStreamer(
    private val context: Context,
) {
    private val measureClient = HealthServices.getClient(context).measureClient
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var phoneNodeId: String? = null
    private var streamingJob: Job? = null

    private val hrCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            if (availability is DataTypeAvailability) {
                Log.d(TAG, "HR availability=$availability")
            }
        }

        override fun onDataReceived(data: DataPointContainer) {
            val hrPoints = data.getData(DataType.HEART_RATE_BPM)
            for (point in hrPoints) {
                if (point !is SampleDataPoint<Double>) continue
                val bpm = point.value.toFloat()
                val payload = WearablePayload.encodeHeartRate(
                    timestampMs = MonotonicClock.nowMs(),
                    bpm = bpm,
                    quality = 3,
                )
                send(WearablePaths.EVT_HEART_RATE, payload)
            }
        }
    }

    fun start() {
        streamingJob?.cancel()
        streamingJob = scope.launch {
            phoneNodeId = nodeClient.connectedNodes.await().firstOrNull()?.id
            Log.d(TAG, "Streaming start; phoneNode=$phoneNodeId")
            // Heart rate is delivered as a callback stream from Health Services.
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, hrCallback)
        }
    }

    fun stop() {
        streamingJob?.cancel()
        streamingJob = null
        scope.launch {
            runCatching {
                measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, hrCallback).await()
            }.onFailure { Log.w(TAG, "Failed to unregister HR callback", it) }
        }
    }

    private fun send(path: String, data: ByteArray) {
        val nodeId = phoneNodeId ?: return
        scope.launch {
            runCatching { messageClient.sendMessage(nodeId, path, data).await() }
                .onFailure { Log.w(TAG, "Send to phone failed: $path", it) }
        }
    }

    companion object {
        private const val TAG = "WatchSensorStreamer"
    }
}
