package com.jason.calendaralarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jason.calendaralarm.R
import com.jason.calendaralarm.permissions.PermissionStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration.Companion.minutes

object AlarmNotifications {
    // Channel sound can't be changed after creation; bump the ID to change it.
    private const val CHANNEL_ID = "event_alarms"
    private const val SNOOZED_CHANNEL_ID = "snoozed_alarms"
    const val TEST_ID = Int.MIN_VALUE
    private val ringFor = 10.minutes
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun show(context: Context, id: Int, title: String, text: String) {
        if (!PermissionStep.Notifications.isGranted(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(manager)

        val alarmScreen = PendingIntent.getActivity(
            context,
            id,
            AlarmActivity.intent(context, id, title, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_DISMISS)
                .putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_SNOOZE)
                .putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, id)
                .putExtra(AlarmReceiver.EXTRA_TITLE, title)
                .putExtra(AlarmReceiver.EXTRA_TEXT, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(alarmScreen)
            .setFullScreenIntent(alarmScreen, true)
            .addAction(0, "Snooze ${AlarmScheduler.SNOOZE.inWholeMinutes} min", snooze)
            .addAction(0, "Dismiss", dismiss)
            .setTimeoutAfter(ringFor.inWholeMilliseconds)
            .build()
        // Insistent makes the channel sound loop until the notification is dismissed or times out.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        manager.notify(id, notification)
    }

    /**
     * Stops the ringing alarm [id], schedules it to ring again, and leaves a silent notification in
     * its place that can cancel the snooze. The re-ring reuses [id], so it replaces that notification.
     */
    fun snooze(context: Context, id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(id)
        val ringsAt = AlarmScheduler.scheduleSnooze(context, id, title, text) ?: return
        if (!PermissionStep.Notifications.isGranted(context)) return
        ensureChannels(manager)

        val cancelSnooze = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_CANCEL_SNOOZE)
                .putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ringsAtLabel = timeFormat.format(Instant.ofEpochMilli(ringsAt).atZone(ZoneId.systemDefault()))

        val notification = NotificationCompat.Builder(context, SNOOZED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Snoozed: $title")
            .setContentText("Rings again at $ringsAtLabel")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setWhen(ringsAt)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(0, "Dismiss alarm", cancelSnooze)
            .build()
        manager.notify(id, notification)
    }

    fun cancelSnooze(context: Context, id: Int) {
        AlarmScheduler.cancelSnooze(context, id)
        dismiss(context, id)
    }

    fun dismiss(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }

    private fun ensureChannels(manager: NotificationManager) {
        if (manager.getNotificationChannel(SNOOZED_CHANNEL_ID) == null) {
            val snoozed = NotificationChannel(SNOOZED_CHANNEL_ID, "Snoozed alarms", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Shows a snoozed alarm and lets you dismiss it before it rings again."
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            manager.createNotificationChannel(snoozed)
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        val channel = NotificationChannel(CHANNEL_ID, "Event alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings when an event in your Alarm calendar, or tagged #alarm, starts."
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }
}
