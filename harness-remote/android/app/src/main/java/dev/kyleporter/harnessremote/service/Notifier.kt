package dev.kyleporter.harnessremote.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.kyleporter.harnessremote.MainActivity
import dev.kyleporter.harnessremote.R
import dev.kyleporter.harnessremote.net.Server
import dev.kyleporter.harnessremote.net.SessionSummary

const val CHANNEL_CONNECTION = "connection"
const val CHANNEL_AGENT = "agent"
const val EXTRA_SESSION = "session"

fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_CONNECTION, "Bridge connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps the connection to your computer open while the app is in the background"
        }
    )
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_AGENT, "Agent activity", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A turn finished or a harness is waiting for your input"
        }
    )
}

class Notifier(private val context: Context) {
    fun onSessions(
        server: Server,
        old: List<SessionSummary>,
        new: List<SessionSummary>,
        foreground: Boolean,
    ) {
        if (old.isEmpty()) return
        val before = old.associateBy { it.id }
        for (s in new) {
            val prev = before[s.id] ?: continue
            if (prev.state == s.state || foreground) continue
            val text = when {
                s.state == "waiting" -> "Waiting for your input"
                prev.busy && s.state == "idle" -> "Turn finished"
                prev.busy && s.state == "exited" -> "Process exited"
                else -> null
            } ?: continue
            post(server, s, text)
        }
    }

    private fun post(server: Server, s: SessionSummary, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_SESSION, s.id)
        val pi = PendingIntent.getActivity(
            context, s.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = s.preview.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: text
        val n = NotificationCompat.Builder(context, CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_harness)
            .setContentTitle("${s.title} · $text")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(server.name)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(s.id.hashCode(), n) }
    }
}
