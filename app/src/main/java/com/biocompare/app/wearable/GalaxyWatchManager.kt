package com.biocompare.app.wearable

import android.content.Context
import android.util.Log
import com.biocompare.shared.model.BioSample
import com.biocompare.shared.model.DeviceConnectionState
import com.biocompare.shared.model.DeviceSource
import com.biocompare.shared.protocol.WearablePaths
import com.biocompare.shared.protocol.WearablePayload
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side coordinator for the Galaxy Watch companion app.
 *
 * Unlike a BLE peripheral, a Wear OS peer is reached through Google's Data
 * Layer using node IDs. The watch app advertises a *capability*
 * (CAP_WATCH_APP); we look up the connected node that has it, then push
 * commands to it via [MessageClient].
 *
 * Inbound samples are not pulled here -- they arrive in the always-on
 * [PhoneWearableListenerService] which forwards them through the shared
 * [_samples] flow exposed below.
 */
@Singleton
class GalaxyWatchManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    private val _connectionState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    private val _samples = MutableSharedFlow<BioSample>(extraBufferCapacity = 256)
    val samples: SharedFlow<BioSample> = _samples.asSharedFlow()

    /** Internal hook used by [PhoneWearableListenerService] to publish samples. */
    internal suspend fun publish(sample: BioSample) = _samples.emit(sample)

    /**
     * Look up the watch node, send the start command. Suspends until the watch
     * either ACKs the command or times out. Idempotent.
     */
    suspend fun startStreaming(): Result<Unit> = runCatching {
        val node = findWatchNode() ?: error("Tidak menemukan Galaxy Watch dengan aplikasi BioCompare terinstall")
        _connectionState.value = DeviceConnectionState.Connecting(node.displayName)
        messageClient.sendMessage(node.id, WearablePaths.CMD_START_STREAM, ByteArray(0)).await()
        _connectionState.value = DeviceConnectionState.Connected(node.displayName, node.id)
    }.onFailure { e ->
        Log.w(TAG, "startStreaming failed", e)
        _connectionState.value = DeviceConnectionState.Error(e.message ?: "Unknown error", e)
    }

    suspend fun stopStreaming(): Result<Unit> = runCatching {
        val node = findWatchNode() ?: return@runCatching
        messageClient.sendMessage(node.id, WearablePaths.CMD_STOP_STREAM, ByteArray(0)).await()
        _connectionState.value = DeviceConnectionState.Disconnected
    }

    /**
     * Returns the first connected node that advertises our watch capability,
     * or null if no companion is reachable. We use the capability lookup
     * (rather than nodeClient.connectedNodes) so that we ignore phones, tablets,
     * and other non-watch peers that may also be paired.
     */
    private suspend fun findWatchNode(): Node? {
        val info = capabilityClient
            .getCapability(WearablePaths.CAP_WATCH_APP, CapabilityClient.FILTER_REACHABLE)
            .await()
        return info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
    }

    /** Decode a MessageClient inbound event from the watch into a BioSample. */
    internal fun decode(event: MessageEvent): BioSample? {
        return when (event.path) {
            WearablePaths.EVT_HEART_RATE -> {
                val p = WearablePayload.decodeHeartRate(event.data)
                BioSample.HeartRate(p.timestampMs, DeviceSource.GALAXY_WATCH, p.bpm, p.quality)
            }
            WearablePaths.EVT_ACCEL -> {
                val p = WearablePayload.decodeAccel(event.data)
                BioSample.Accelerometer(p.timestampMs, DeviceSource.GALAXY_WATCH, p.x, p.y, p.z)
            }
            WearablePaths.EVT_STEPS -> {
                val p = WearablePayload.decodeSteps(event.data)
                BioSample.Steps(p.timestampMs, DeviceSource.GALAXY_WATCH, p.total)
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "GalaxyWatchManager"
    }
}
