package hu.szfvbus.notify

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
import hu.szfvbus.MainActivity
import hu.szfvbus.R
import hu.szfvbus.data.api.MavinformParser
import hu.szfvbus.data.model.Alert

/** Értesítések a forgalmi változásokról; egyre koppintva megnyílnak a forgalmi hírek. */
object AlertNotifier {
    private const val CHANNEL_ID = "traffic_changes"
    private const val GROUP = "hu.szfvbus.TRAFFIC_CHANGES"
    private const val SUMMARY_ID = 1
    private const val MAX_SINGLE = 4
    const val EXTRA_OPEN_ALERTS = "hu.szfvbus.OPEN_ALERTS"

    fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, "Forgalmi változások", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Új terelések, megállóáthelyezések és más változások a helyi járatoknál"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Új változásonként egy értesítés; ha egyszerre sok van, egy összesítő alá csoportosítva. */
    fun notifyNew(context: Context, alerts: List<Alert>) {
        if (alerts.isEmpty() || !canNotify(context)) return
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        alerts.take(MAX_SINGLE).forEach { alert ->
            val text = alert.lines.takeIf { it.isNotEmpty() }?.let { "Érintett vonalak: ${it.joinToString(", ")}" }
                ?: "Székesfehérvári helyi járatok"
            val details = MavinformParser.htmlToLines(alert.description)
            val notification = builder(context)
                .setContentTitle(alert.header)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(text, details).filter { it.isNotEmpty() }.joinToString("\n")))
                .setGroup(GROUP)
                .build()
            send(manager, alert.id.hashCode(), notification)
        }
        if (alerts.size > 1) notifySummary(context, alerts)
    }

    /** Egyetlen értesítés a változások számával, a telepítés utáni első ellenőrzéskor. */
    fun notifySummary(context: Context, alerts: List<Alert>) {
        if (alerts.isEmpty() || !canNotify(context)) return
        ensureChannel(context)
        val title = "${alerts.size} forgalmi változás a helyi járatoknál"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        alerts.take(6).forEach { style.addLine(it.header) }
        val notification = builder(context)
            .setContentTitle(title)
            .setContentText(alerts.first().header)
            .setStyle(style)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .build()
        send(NotificationManagerCompat.from(context), SUMMARY_ID, notification)
    }

    private fun builder(context: Context): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_ALERTS, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFE65100.toInt())
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
    }

    private fun send(manager: NotificationManagerCompat, id: Int, notification: android.app.Notification) {
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Az engedélyt időközben visszavonták.
        }
    }
}
