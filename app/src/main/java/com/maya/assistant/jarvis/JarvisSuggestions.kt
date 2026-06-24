package com.maya.assistant.jarvis

import android.content.Context
import com.maya.assistant.jarvis.JarvisSession.getHabit
import com.maya.assistant.jarvis.JarvisSession.incrementHabit
import com.maya.assistant.utils.CalendarManager
import com.maya.assistant.utils.Logger

/**
 * JarvisSuggestions — proactive intelligent suggestions.
 * Like Jarvis: "Sir, you usually make your call at this time. Shall I proceed?"
 */
object JarvisSuggestions {
    private const val TAG = "JarvisSuggestions"

    fun getProactiveSuggestion(context: Context, lastCommand: String? = null): String? {
        // Track usage patterns
        if (lastCommand != null) {
            incrementHabit(lastCommand.lowercase())
        }

        // Time-based suggestions
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        val mostUsed = getTopHabit(context)
        if (mostUsed != null && hour in 9..22) {
            // Only suggest if user does this frequently (>3 times)
            if (mostUsed.count >= 3) {
                return "📌 আপনি সাধারণত ${mostUsed.action} করে থাকেন। করবেন?"
            }
        }

        // Calendar-based: upcoming events in next hour
        val upcoming = CalendarManager.getTodayEvents(context)
        val now = System.currentTimeMillis()
        for (event in upcoming) {
            val startTime = event["startTime"]?.toLongOrNull() ?: continue
            val diffMinutes = (startTime - now) / 60000
            if (diffMinutes in 0..60) {
                return "⏰ '${event["title"]}' ${diffMinutes} মিনিট পর। প্রস্তুত হও।"
            }
        }

        return null
    }

    fun getDailyBriefing(context: Context): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour > 10) return "" // Only in morning

        val events = CalendarManager.getTodayEvents(context)
        if (events.isEmpty()) return ""

        val sb = StringBuilder("🌅 আজকের সকাল — কী আছে:")
        for (event in events.take(3)) {
            val time = event["time"] ?: ""
            val title = event["title"] ?: ""
            sb.append("\n  • $time — $title")
        }
        if (events.size > 3) {
            sb.append("\n  ... এবং ${events.size - 3} আরো")
        }
        return sb.toString()
    }

    private data class Habit(val action: String, val count: Int)

    private fun getTopHabit(context: Context): Habit? {
        val top = JarvisSession.getTopHabits(1).firstOrNull()
        return top?.let { Habit(it.first, it.second) }
    }
}
