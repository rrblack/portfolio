package dev.kyleporter.harnessremote.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.kyleporter.harnessremote.MainActivity
import dev.kyleporter.harnessremote.R
import dev.kyleporter.harnessremote.hub

class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            hub.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val name = hub.client.value?.server?.name ?: "computer"
        val n = NotificationCompat.Builder(this, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_harness)
            .setContentTitle("Connected to $name")
            .setContentText("Watching your agent sessions")
            .setContentIntent(open)
            .addAction(0, "Disconnect", disconnect)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "dev.kyleporter.harnessremote.DISCONNECT"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
