package com.jason.calendaralarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jason.calendaralarm.MainActivity
import com.jason.calendaralarm.calendar.CalendarEvent
import com.jason.calendaralarm.calendar.CalendarRepository
import com.jason.calendaralarm.permissions.PermissionStep
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object AlarmScheduler {
    private const val REQUEST_NEXT = 1
    private const val REQUEST_TEST = 2
    val SNOOZE = 5.minutes

    /**
     * Points the single system alarm at the earliest alarm event starting after [after], or
     * cancels it when there is none. Returns the events that alarm will ring for.
     * Must run off the main thread.
     */
    fun scheduleNext(context: Context, after: Long = System.currentTimeMillis()): List<CalendarEvent> {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val canSchedule = PermissionStep.Calendar.isGranted(context) && PermissionStep.ExactAlarms.isGranted(context)
        val next = if (canSchedule) CalendarRepository.nextAlarmEvents(context, after) else emptyList()
        val begin = next.firstOrNull()?.begin

        val fire = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_BEGIN, begin ?: -1L)
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_NEXT,
            fire,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (begin == null) {
            alarmManager.cancel(operation)
        } else {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(begin, showAppIntent(context)), operation)
        }
        return next
    }

    fun scheduleTest(context: Context, delay: Duration = 10.seconds) {
        if (!PermissionStep.ExactAlarms.isGranted(context)) return
        val fire = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TEST)
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_TEST,
            fire,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() + delay.inWholeMilliseconds
        context.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showAppIntent(context)), operation)
    }

    /**
     * Re-rings notification [id] after [SNOOZE]. Independent of the next-event alarm chain.
     * Returns when it will ring, or null if exact alarms aren't allowed.
     */
    fun scheduleSnooze(context: Context, id: Int, title: String, text: String): Long? {
        if (!PermissionStep.ExactAlarms.isGranted(context)) return null
        val fire = snoozeIntent(context, id)
            .putExtra(AlarmReceiver.EXTRA_TITLE, title)
            .putExtra(AlarmReceiver.EXTRA_TEXT, text)
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            fire,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() + SNOOZE.inWholeMilliseconds
        context.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showAppIntent(context)), operation)
        return triggerAt
    }

    fun cancelSnooze(context: Context, id: Int) {
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            snoozeIntent(context, id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(operation)
        operation.cancel()
    }

    private fun snoozeIntent(context: Context, id: Int): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_SNOOZE_FIRE)
            // A per-notification data URI keeps each snooze from replacing the others or the chain's alarm.
            .setData(Uri.parse("calendaralarm://snooze/$id"))
            .putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, id)

    /** Opened when the user taps the alarm icon in the status bar or clock settings. */
    private fun showAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
