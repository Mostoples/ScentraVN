package com.biocompare.app.wearable

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives messages from the Galaxy Watch companion. Declared in the manifest
 * with intent filters for Wearable's data/message/channel actions, so it is
 * woken up by the system whenever the watch sends data -- even when the app
 * is not in foreground.
 *
 * We forward decoded samples into [GalaxyWatchManager.publish], where the
 * repository merges them with BLE streams from the other devices.
 */
@AndroidEntryPoint
class PhoneWearableListenerService : WearableListenerService() {

    @Inject lateinit var galaxyWatchManager: GalaxyWatchManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onMessageReceived(event: MessageEvent) {
        try {
            val sample = galaxyWatchManager.decode(event) ?: run {
                Log.v(TAG, "Ignored event path=${event.path}")
                return
            }
            scope.launch { galaxyWatchManager.publish(sample) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode message: ${event.path}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        private const val TAG = "PhoneWearListener"
    }
}
