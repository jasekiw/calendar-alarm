package com.jason.calendaralarm.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jason.calendaralarm.calendar.CalendarRepository
import com.jason.calendaralarm.calendar.whenLabel
import kotlin.concurrent.thread

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> doAsync {
                val begin = intent.getLongExtra(EXTRA_BEGIN, -1L)
                if (begin > 0) {
                    // Re-query so events that were edited or deleted since scheduling don't ring.
                    CalendarRepository.alarmEventsStartingAt(context, begin).forEach { event ->
                        AlarmNotifications.show(
                            context,
                            id = event.instanceId.hashCode(),
                            title = event.title,
                            text = event.whenLabel(),
                        )
                    }
                }
                AlarmScheduler.scheduleNext(context, after = maxOf(begin, 0L))
            }

            ACTION_TEST -> AlarmNotifications.show(
                context,
                id = AlarmNotifications.TEST_ID,
                title = "Test alarm",
                text = "Calendar Alarm is working.",
            )

            ACTION_DISMISS -> AlarmNotifications.dismiss(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))

            ACTION_SNOOZE -> AlarmNotifications.snooze(
                context,
                id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            )

            ACTION_CANCEL_SNOOZE -> AlarmNotifications.cancelSnooze(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))

            ACTION_SNOOZE_FIRE -> AlarmNotifications.show(
                context,
                id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            )

            // Alarms are cleared on reboot and app update, and can't be set without exact-alarm access.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> doAsync {
                AlarmScheduler.scheduleNext(context)
                CalendarChangeJob.register(context)
            }
        }
    }

    private fun doAsync(block: () -> Unit) {
        val pending = goAsync()
        thread {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.jason.calendaralarm.action.FIRE"
        const val ACTION_TEST = "com.jason.calendaralarm.action.TEST"
        const val ACTION_DISMISS = "com.jason.calendaralarm.action.DISMISS"
        const val ACTION_SNOOZE = "com.jason.calendaralarm.action.SNOOZE"
        const val ACTION_SNOOZE_FIRE = "com.jason.calendaralarm.action.SNOOZE_FIRE"
        const val ACTION_CANCEL_SNOOZE = "com.jason.calendaralarm.action.CANCEL_SNOOZE"
        const val EXTRA_BEGIN = "begin"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }
}
