package com.maya.assistant.jarvis

import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import com.maya.assistant.utils.CalendarManager
import com.maya.assistant.utils.Logger
import java.text.SimpleDateFormat
import java.util.*

/**
 * JarvisGreeting — generates personalized, time-aware greetings.
 * Like Jarvis: "Good morning, Sir. All systems operational."
 */
object JarvisGreeting {
    private const val TAG = "JarvisGreeting"

    fun getGreeting(context: Context, userName: String = "Sir"): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when {
            hour < 6 -> "শুভ রাত্রি"  // Good night
            hour < 12 -> "শুভ সকাল"  // Good morning
            hour < 17 -> "শুভ অপরাহ্ন"  // Good afternoon
            hour < 21 -> "শুভ সন্ধ্যা"  // Good evening
            else -> "শুভ রাত্রি"  // Good night
        }

        val batteryInfo = getBatteryStatus(context)
        val todayEvents = CalendarManager.getTodayEvents(context)
        val eventCount = todayEvents.size

        val sb = StringBuilder()

        // Base greeting
        sb.append("$timeGreeting, $userName.")

        // System status
        sb.append(" সব সিস্টেম সচল আছে।")

        // Battery warning
        if (batteryInfo.percent < 20) {
            sb.append(" ব্যাটারি ${batteryInfo.percent}% — চার্জার দরকার।")
        }

        // Calendar summary
        if (eventCount > 0) {
            val nextEvent = todayEvents.first()
            val time = nextEvent["time"] ?: ""
            val title = nextEvent["title"] ?: ""
            sb.append(" আজকে $time সময় '$title' আছে।")
        }

        return sb.toString()
    }

    fun getWelcomeBack(context: Context, userName: String, faceName: String? = null): String {
        val name = faceName ?: userName
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when {
            hour < 6 -> "রাত্রি"
            hour < 12 -> "সকাল"
            hour < 17 -> "দুপুর"
            hour < 21 -> "সন্ধ্যা"
            else -> "রাত্রি"
        }

        return if (faceName != null) {
            "স্বাগতম $faceName, $timeGreeting। আমি আপনাকে চিনতে পেরেছি।"
        } else {
            "স্বাগতম ফিরে এসেছেন, $name। $timeGreeting।"
        }
    }

    fun getSleepGreeting(userName: String): String {
        return "শুভ রাত্রি, $userName। আমি জেগে থাকব আপনার জন্য।"
    }

    fun getMeetingReminder(eventTitle: String, time: String): String {
        return "⏰ মিটিং আসছে: '$time' সময় '$eventTitle'। প্রস্তুত হও।"
    }

    private data class BatteryStatus(val percent: Int, val isCharging: Boolean)

    private fun getBatteryStatus(context: Context): BatteryStatus {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isCharging = bm?.isCharging ?: false
        return BatteryStatus(percent, isCharging)
    }
}
