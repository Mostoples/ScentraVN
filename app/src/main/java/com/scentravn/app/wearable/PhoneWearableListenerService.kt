package com.scentravn.app.wearable

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Wear Data Layer listener — kept for backward compatibility with the manifest
 * registration. We no longer use the Data Layer for Galaxy Watch communication;
 * instead [GalaxyWatchManager] connects directly to the wear app via a TCP
 * socket over local WiFi. This service simply logs any incoming messages and
 * ignores them.
 */
class PhoneWearableListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        Log.v(TAG, "Received unused Data Layer event: ${event.path}")
    }

    companion object {
        private const val TAG = "PhoneWearListener"
    }
}
