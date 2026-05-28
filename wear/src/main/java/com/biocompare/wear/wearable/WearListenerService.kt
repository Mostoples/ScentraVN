package com.biocompare.wear.wearable

import android.util.Log
import com.biocompare.shared.protocol.WearablePaths
import com.biocompare.wear.service.WearSensorService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Watch-side listener for commands from the phone. Translates a /cmd/start
 * message into starting the foreground sensor service, and /cmd/stop into
 * stopping it. Receiving a message wakes the watch app via the manifest
 * intent filter, so the user does not need to manually open the watch app
 * before kicking off a session from the phone.
 */
class WearListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        Log.d(TAG, "Received from phone: ${event.path}")
        when (event.path) {
            WearablePaths.CMD_START_STREAM -> WearSensorService.start(this)
            WearablePaths.CMD_STOP_STREAM -> WearSensorService.stop(this)
            WearablePaths.CMD_PING -> Log.d(TAG, "Pong (ping received)")
        }
    }

    companion object {
        private const val TAG = "WearListener"
    }
}
