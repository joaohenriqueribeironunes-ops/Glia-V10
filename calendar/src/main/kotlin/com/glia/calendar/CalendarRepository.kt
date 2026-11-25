package com.glia.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AgendaEvent(
    val id: Long,
    val title: String,
    val begin: Instant,
    val end: Instant,
    val allDay: Boolean,
    val location: String?,
    val calendarName: String?,
) {
    fun isOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val local = begin.atZone(zone).toLocalDate()
        val localEnd = end.atZone(zone).toLocalDate()
        return !date.isBefore(local) && !date.isAfter(localEnd)
    }
}

/**
 * Acesso ao CalendarProvider. Todas as operações degradam com elegância quando
 * a permissão não foi concedida (o Glia avisa no onboarding e nas configurações).
 */
class CalendarRepository(private val context: Context) {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun canRead(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    fun canWrite(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    fun eventsBetween(start: Instant, end: Instant, limit: Int = 40): List<AgendaEvent> {
        if (!canRead()) return emptyList()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start.toEpochMilli())
        ContentUris.appendId(builder, end.toEpochMilli())
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val events = ArrayList<AgendaEvent>()
        runCatching {
            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && events.size < limit) {
                    val id = cursor.getLong(0)
                    val title = cursor.getString(1) ?: "Compromisso"
                    val begin = cursor.getLong(2)
                    val finish = cursor.getLong(3)
                    val allDay = cursor.getInt(4) == 1
                    events.add(
                        AgendaEvent(
                            id = id,
                            title = title,
                            begin = Instant.ofEpochMilli(begin),
                            end = Instant.ofEpochMilli(finish),
                            allDay = allDay,
                            location = cursor.getString(5),
                            calendarName = cursor.getString(6),
                        )
                    )
                }
            }
        }
        return events
    }

    fun today(): List<AgendaEvent> {
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        return eventsBetween(start, start.plusSeconds(86_400))
    }

    fun tomorrow(): List<AgendaEvent> {
        val start = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
        return eventsBetween(start, start.plusSeconds(86_400))
    }

    fun thisWeek(): List<AgendaEvent> {
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        return eventsBetween(start, start.plusSeconds(7L * 86_400))
    }

    fun nextEvent(): AgendaEvent? {
        val now = Instant.now()
        return eventsBetween(now, now.plusSeconds(30L * 86_400), limit = 1).firstOrNull()
    }

    fun createEvent(
        title: String,
        start: Instant,
        end: Instant,
        location: String? = null,
        description: String? = null,
        reminderMinutes: Int? = 10,
    ): Long? {
        if (!canWrite()) return null
        val calendarId = primaryCalendarId() ?: return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.toEpochMilli())
            put(CalendarContract.Events.DTEND, end.toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description)
        }
        val uri = runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrNull() ?: return null
        val eventId = uri.lastPathSegment?.toLongOrNull() ?: return null
        if (reminderMinutes != null) {
            val reminder = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            runCatching { context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder) }
        }
        return eventId
    }

    private fun primaryCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.VISIBLE}=1 AND " +
            "${CalendarContract.Calendars.IS_PRIMARY}=1"
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        }
        // Fallback: qualquer calendário gravável.
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE}=1",
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        }
        return null
    }
}
