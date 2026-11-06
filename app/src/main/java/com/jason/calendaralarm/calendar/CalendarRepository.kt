package com.jason.calendaralarm.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration.Companion.days

data class CalendarEvent(
    val instanceId: Long,
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
    val calendarName: String,
    val calendarColor: Int,
    /** Tagged #alarm, or in the dedicated Alarm calendar. All-day events never ring. */
    val ringsAtStart: Boolean,
)

data class UpcomingEvents(val visibleCalendars: Int, val events: List<CalendarEvent>)

/** All queries must run off the main thread and only after READ_CALENDAR is granted. */
object CalendarRepository {
    /** Every timed event in a calendar with this name rings, matched case-insensitively. */
    const val ALARM_CALENDAR_NAME = "alarm"

    private val lookahead = 365.days
    private val alarmTag = Regex("#alarm\\b", RegexOption.IGNORE_CASE)

    private const val VISIBLE = "${Instances.VISIBLE} = 1"
    private const val NOT_CANCELED =
        "(${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Instances.STATUS_CANCELED})"

    // SQL LIKE is a cheap, case-insensitive pre-filter; alarmTag does the exact match afterwards.
    // The Alarm calendar counts even when hidden, since it exists only to drive alarms.
    private const val ALARM_CANDIDATE =
        "${Instances.ALL_DAY} = 0 AND (" +
            "($VISIBLE AND (${Instances.TITLE} LIKE '%#alarm%' OR ${Instances.DESCRIPTION} LIKE '%#alarm%'))" +
            " OR LOWER(TRIM(${Instances.CALENDAR_DISPLAY_NAME})) = '$ALARM_CALENDAR_NAME')"

    private val projection = arrayOf(
        Instances._ID,
        Instances.EVENT_ID,
        Instances.TITLE,
        Instances.BEGIN,
        Instances.END,
        Instances.ALL_DAY,
        Instances.EVENT_LOCATION,
        Instances.CALENDAR_DISPLAY_NAME,
        Instances.CALENDAR_COLOR,
        Instances.DESCRIPTION,
    )

    fun loadUpcoming(context: Context, limit: Int = 10): UpcomingEvents {
        val visibleCalendars = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null,
        )?.use { it.count } ?: 0

        val now = System.currentTimeMillis()
        val events = buildList {
            forEachInstance(context, now, now + lookahead.inWholeMilliseconds, "$VISIBLE AND $NOT_CANCELED") {
                add(it)
                size < limit
            }
        }
        return UpcomingEvents(visibleCalendars, events)
    }

    /** Every alarm event sharing the earliest start time strictly after [after]. */
    fun nextAlarmEvents(context: Context, after: Long): List<CalendarEvent> = buildList {
        forEachInstance(
            context,
            rangeStart = after,
            rangeEnd = after + lookahead.inWholeMilliseconds,
            selection = "$NOT_CANCELED AND $ALARM_CANDIDATE AND ${Instances.BEGIN} > ?",
            args = arrayOf(after.toString()),
        ) { event ->
            when {
                !event.ringsAtStart -> true
                isEmpty() || event.begin == first().begin -> add(event)
                else -> false
            }
        }
    }

    fun alarmEventsStartingAt(context: Context, begin: Long): List<CalendarEvent> = buildList {
        forEachInstance(
            context,
            rangeStart = begin,
            rangeEnd = begin + 1,
            selection = "$NOT_CANCELED AND $ALARM_CANDIDATE AND ${Instances.BEGIN} = ?",
            args = arrayOf(begin.toString()),
        ) { event ->
            if (event.ringsAtStart) add(event)
            true
        }
    }

    /**
     * Walks instances in start order until [onEvent] returns false. Instances expands recurring
     * events into individual occurrences within the range.
     */
    private fun forEachInstance(
        context: Context,
        rangeStart: Long,
        rangeEnd: Long,
        selection: String,
        args: Array<String>? = null,
        onEvent: (CalendarEvent) -> Boolean,
    ) {
        val uri = Instances.CONTENT_URI.buildUpon()
            .also {
                ContentUris.appendId(it, rangeStart)
                ContentUris.appendId(it, rangeEnd)
            }
            .build()
        context.contentResolver.query(uri, projection, selection, args, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(2)?.takeIf { it.isNotBlank() }
                val description = c.getString(9).orEmpty()
                val calendarName = c.getString(7).orEmpty()
                val keepGoing = onEvent(
                    CalendarEvent(
                        instanceId = c.getLong(0),
                        eventId = c.getLong(1),
                        title = title ?: "(No title)",
                        begin = c.getLong(3),
                        end = c.getLong(4),
                        allDay = c.getInt(5) == 1,
                        location = c.getString(6)?.takeIf { it.isNotBlank() },
                        calendarName = calendarName,
                        calendarColor = c.getInt(8),
                        ringsAtStart = c.getInt(5) == 0 && (
                            calendarName.trim().equals(ALARM_CALENDAR_NAME, ignoreCase = true) ||
                                alarmTag.containsMatchIn(title.orEmpty()) ||
                                alarmTag.containsMatchIn(description)
                            ),
                    ),
                )
                if (!keepGoing) break
            }
        }
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun CalendarEvent.whenLabel(zone: ZoneId = ZoneId.systemDefault()): String {
    if (allDay) {
        // All-day events are stored as UTC midnights, so read them back in UTC to keep the date.
        val first = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
        val last = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        return if (last.isAfter(first)) {
            "${dayFormat.format(first)} – ${dayFormat.format(last)} · All day"
        } else {
            "${dayFormat.format(first)} · All day"
        }
    }
    val start = Instant.ofEpochMilli(begin).atZone(zone)
    val finish = Instant.ofEpochMilli(end).atZone(zone)
    return if (start.toLocalDate() == finish.toLocalDate()) {
        "${dayFormat.format(start)} · ${timeFormat.format(start)} – ${timeFormat.format(finish)}"
    } else {
        "${dayFormat.format(start)} ${timeFormat.format(start)} – ${dayFormat.format(finish)} ${timeFormat.format(finish)}"
    }
}
