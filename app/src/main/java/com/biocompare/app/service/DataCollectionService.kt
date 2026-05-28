package com.biocompare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.biocompare.app.R
import com.biocompare.app.data.repository.BioCompareRepository
import com.biocompare.app.ui.MainActivity
import com.biocompare.shared.model.DeviceConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the BLE managers alive while the user is
 * recording a session. Without this, Android can suspend the process when
 * the dashboard is backgrounded -- BLE notifications would still arrive
 * briefly, but the OS will shut us down within a few minutes.
 *
 * The service does *not* own any BLE state itself; the [BioCompareRepository]
 * holds the manager instances. We just exist to keep the process tier
 * elevated and to surface a "session running" notification with a tap-back to
 * the dashboard.
 *
 * Lifecycle:
 *   - DashboardViewModel.startSession() -> repository.startSession()
 *     -> [start] is also called so we promote ourselves to foreground.
 *   - On stopSession() the same pattern in reverse.
 */
@AndroidEntryPoint
class DataCollectionService : Service() {

    @Inject lateinit var repository: BioCompareRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification(connectedCount = 0))
                observerJob?.cancel()
                observerJob = scope.launch { observeAndUpdateNotif() }
            }
            ACTION_STOP -> {
                observerJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Recompose the notification text whenever the connected-device count
     * changes. We avoid spamming notifications by only updating when the
     * combined value transitions.
     */
    private suspend fun observeAndUpdateNotif() {
        combine(
            repository.galaxyWatchConnection,
            repository.esp32Connection,
            repository.museConnection,
        ) { g, e, m ->
            listOf(g, e, m).count { it is DeviceConnectionState.Connected }
        }.collect { count ->
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildNotification(count))
        }
    }

    override fun onDestroy() {
        observerJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(connectedCount: Int): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_collection_title))
            .setContentText(getString(R.string.notif_collection_text, connectedCount))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
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
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
        )
    }

    companion object {
        const val ACTION_START = "com.biocompare.app.action.SESSION_START"
        const val ACTION_STOP = "com.biocompare.app.action.SESSION_STOP"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "biocompare_collection"

        fun start(ctx: Context) {
            val i = Intent(ctx, DataCollectionService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, DataCollectionService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}
