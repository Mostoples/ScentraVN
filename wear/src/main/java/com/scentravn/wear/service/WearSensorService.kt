package com.scentravn.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.scentravn.wear.R
import com.scentravn.wear.sensor.WatchSensorStreamer

/**
 * Foreground service that keeps Health Services callbacks alive while the
 * watch screen is off and the user is exercising. The alternative -- running
 * the streamer from the Activity -- would be killed immediately on screen-off.
 *
 * Started with ACTION_START from either the listener (in response to a phone
 * command) or the on-watch UI button. Stopped via ACTION_STOP.
 */
class WearSensorService : Service() {

    private lateinit var streamer: WatchSensorStreamer

    override fun onCreate() {
        super.onCreate()
        streamer = WatchSensorStreamer(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification())
                streamer.start()
            }
            ACTION_STOP -> {
                streamer.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { streamer.stop() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_streaming))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        const val ACTION_START = "com.scentravn.wear.action.START"
        const val ACTION_STOP = "com.scentravn.wear.action.STOP"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "scentravn_session"

        fun start(ctx: Context) {
            val i = Intent(ctx, WearSensorService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, WearSensorService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}
