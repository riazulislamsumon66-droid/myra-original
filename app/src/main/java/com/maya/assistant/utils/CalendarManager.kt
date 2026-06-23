package com.maya.assistant.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.util.Log

/**
 * CalendarManager — view events and create calendar events.
 * Supports both read and write operations for calendar.
 */
object CalendarManager {

    private const val TAG = "CalendarManager"

    // Projection for reading calendar events
    private val EVENT_PROJECTION = arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DESCRIPTION,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND,
        CalendarContract.Events.LOCATION,
        CalendarContract.Events.CALENDAR_ID,
        CalendarContract.Events.CALENDAR_DISPLAY_NAME
    )

    /**
     * Get upcoming calendar events for today.
     * @param context Application context
     * @return List of calendar event map
     */
    fun getTodayEvents(context: Context): List<Map<String, String>> {
        val events = mutableListOf<Map<String, String>>()

        try {
            // Check permission
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_CALENDAR
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Read calendar permission not granted")
                return events
            }

            val now = System.currentTimeMillis()
            val todayStart = now - (now % (24 * 60 * 60 * 1000))
            val todayEnd = todayStart + (24 * 60 * 60 * 1000) - 1

            val cursor: Cursor? = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                EVENT_PROJECTION,
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(todayStart.toString(), todayEnd.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.use {
                val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
                val descriptionIndex = it.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val dtStartIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
                val dtEndIndex = it.getColumnIndex(CalendarContract.Events.DTEND)
                val locationIndex = it.getColumnIndex(CalendarContract.Events.LOCATION)

                while (it.moveToNext() && events.size < 10) {
                    val title = if (titleIndex >= 0) it.getString(titleIndex) else "No Title"
                    val desc = if (descriptionIndex >= 0) it.getString(descriptionIndex) else ""
                    val startTime = if (dtStartIndex >= 0) it.getLong(dtStartIndex) else 0L
                    val endTime = if (dtEndIndex >= 0) it.getLong(dtEndIndex) else 0L
                    val location = if (locationIndex >= 0) it.getString(locationIndex) else ""

                    val timeStr = if (startTime > 0) {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = startTime }
                        String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                    } else ""

                    events.add(mapOf(
                        "title" to (title ?: "No Title"),
                        "description" to desc,
                        "time" to timeStr,
                        "location" to location,
                        "startTime" to startTime.toString(),
                        "endTime" to endTime.toString()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting calendar events: ${e.message}")
        }

        return events
    }

    /**
     * Get next upcoming events within the next N days.
     */
    fun getUpcomingEvents(context: Context, days: Int = 7): List<Map<String, String>> {
        val events = mutableListOf<Map<String, String>>()

        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_CALENDAR
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return events
            }

            val now = System.currentTimeMillis()
            val endTime = now + (days * 24 * 60 * 60 * 1000L)

            val cursor: Cursor? = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                EVENT_PROJECTION,
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(now.toString(), endTime.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.use {
                val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
                val descriptionIndex = it.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val dtStartIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
                val dtEndIndex = it.getColumnIndex(CalendarContract.Events.DTEND)
                val locationIndex = it.getColumnIndex(CalendarContract.Events.LOCATION)

                while (it.moveToNext() && events.size < 20) {
                    val title = if (titleIndex >= 0) it.getString(titleIndex) else "No Title"
                    val desc = if (descriptionIndex >= 0) it.getString(descriptionIndex) else ""
                    val startTime = if (dtStartIndex >= 0) it.getLong(dtStartIndex) else 0L
                    val endTime2 = if (dtEndIndex >= 0) it.getLong(dtEndIndex) else 0L
                    val location = if (locationIndex >= 0) it.getString(locationIndex) else ""

                    val timeStr = if (startTime > 0) {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = startTime }
                        String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                    } else ""

                    events.add(mapOf(
                        "title" to (title ?: ""),
                        "description" to desc,
                        "time" to timeStr,
                        "location" to location,
                        "startTime" to startTime.toString(),
                        "endTime" to endTime2.toString()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting upcoming events: ${e.message}")
        }

        return events
    }

    /**
     * Create a new calendar event.
     * @param context Application context
     * @param title Event title
     * @param description Event description
     * @param startTime Event start time in millis
     * @param endTime Event end time in millis
     * @param location Optional location string
     * @return Whether the event was created successfully
     */
    fun createEvent(
        context: Context,
        title: String,
        description: String = "",
        startTime: Long,
        endTime: Long,
        location: String = ""
    ): Boolean {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_CALENDAR
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Write calendar permission not granted")
                return false
            }

            // Find the primary calendar ID
            val calendarId = getPrimaryCalendarId(context)
            if (calendarId == null) {
                Log.e(TAG, "No calendar found")
                return false
            }

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                if (location.isNotEmpty()) {
                    put(CalendarContract.Events.LOCATION, location)
                }
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                Log.i(TAG, "Event created: ${uri.lastPathSegment}")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating event: ${e.message}")
        }
        return false
    }

    /**
     * Create a quick event (reminder) with a specific time and title.
     */
    fun createReminder(context: Context, title: String, minutesFromNow: Int = 30): Boolean {
        val now = System.currentTimeMillis()
        val startTime = now + (minutesFromNow * 60 * 1000L)
        val endTime = startTime + (60 * 60 * 1000L) // 1 hour duration
        return createEvent(context, "⏰ $title", "", startTime, endTime)
    }

    private fun getPrimaryCalendarId(context: Context): Long? {
        try {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY
            )
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null, null, null
            )
            cursor?.use {
                val idIndex = it.getColumnIndex(CalendarContract.Calendars._ID)
                val primaryIndex = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                while (it.moveToNext()) {
                    val id = if (idIndex >= 0) it.getLong(idIndex) else 0L
                    val isPrimary = if (primaryIndex >= 0) it.getInt(primaryIndex) else 0
                    if (isPrimary == 1) return id
                }
            }
            // Fallback to first calendar
            val cursor2 = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                null, null, null
            )
            cursor2?.use {
                if (it.moveToFirst()) {
                    return it.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting calendar ID: ${e.message}")
        }
        return null
    }

    /**
     * Format events into Bangla readable string.
     */
    fun formatEventsBangla(events: List<Map<String, String>>, timeFrame: String = "আজ"): String {
        if (events.isEmpty()) return "$timeFrame কোনো ইভেন্ট নেই।"

        val sb = StringBuilder("$timeFrame এর ইভেন্ট:")
        for ((index, event) in events.withIndex()) {
            val time = event["time"] ?: ""
            val title = event["title"] ?: ""
            val location = event["location"] ?: ""
            sb.append("
${index + 1}.")
            if (time.isNotEmpty()) sb.append(" $time সময়")
            sb.append(" — $title")
            if (location.isNotEmpty()) sb.append(" ($location)")
        }
        return sb.toString()
    }
}
