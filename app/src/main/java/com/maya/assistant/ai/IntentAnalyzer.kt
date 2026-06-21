package com.maya.assistant.ai

import com.maya.assistant.models.CommandType
import com.maya.assistant.models.VoiceCommand

object IntentAnalyzer {

    // English
    private val OPEN_PATTERNS = listOf("open", "kholo", "khol", "launch", "start", "chalo", "chala", "kholো", "খোলো", "खोलो", "افتح", "ouvrir")
    private val CLOSE_PATTERNS = listOf("close", "band", "band karo", "বন্ধ", "বন্ধ করো", "बंद", "أغلق", "fermer")
    private val CALL_PATTERNS = listOf("call", "phone", "ring", "dial", "কল", "ফোন", "कॉल", "اتصل", "appeler")
    private val WHATSAPP_CALL = listOf("whatsapp call", "video call", "হোয়াটসঅ্যাপ কল")
    private val MSG_PATTERNS = listOf("message", "msg", "send", "bhejo", "likho", "মেসেজ", "পাঠাও", "संदेश", "رسالة", "message")
    private val YOUTUBE_PATTERNS = listOf("youtube", "video play", "play on youtube", "ইউটিউব")
    private val SPOTIFY_PATTERNS = listOf("spotify", "play music", "gaana", "song", "গান", "सॉन्ग", "أغنية", "musique")
    private val VOLUME_UP = listOf("volume up", "louder", "badhao", "tez karo", "ভলিউম বাড়াও", "आवाज़ बढ़ाओ", "ارفع الصوت", "augmenter le volume")
    private val VOLUME_DOWN = listOf("volume down", "lower", "kam karo", "dhima karo", "ভলিউম কমাও", "आवाज़ कम करो", "اخفض الصوت", "baisser le volume")
    private val FLASHLIGHT_ON = listOf("flashlight on", "torch on", "light on", "ফ্ল্যাশলাইট চালু", "टॉर्च चालূ", "شعلة على", "lampe allumée")
    private val FLASHLIGHT_OFF = listOf("flashlight off", "torch off", "light off", "ফ্ল্যাশলাইট বন্ধ", "टॉर्च बंद", "شعلة إيقاف", "lampe éteinte")
    private val SCREENSHOT_PATTERNS = listOf("screenshot", "screen shot", "capture screen", "স্ক্রিনশট", "स्क्रीनशॉट", "لقطة شاشة", "capture d'écran")
    private val SCROLL_UP_PATTERNS = listOf("scroll up", "upar scroll", "উপরে স্ক্রল", "ऊपर स्क्रॉल", "تمرير لأعلى", "défiler vers le haut")
    private val SCROLL_DOWN_PATTERNS = listOf("scroll down", "niche scroll", "নিচে স্ক্রল", "नीचे स्क्रॉल", "تمرير لأسفل", "défiler vers le bas")
    private val BACK_PATTERNS = listOf("back", "পেছনে", "पीछे", "رجوع", "retour")
    private val HOME_PATTERNS = listOf("home", "হোম", "घर", "الرئيسية", "accueil")
    private val NOTIFICATION_PATTERNS = listOf("notification", "notification bar", "নোটিফিকেশन", "सूचना", "إشعار", "notification")

    fun analyze(text: String): VoiceCommand {
        val lower = text.lowercase().trim()

        // Structured command passthrough
        val structured = parseStructured(text)
        if (structured != null) return structured

        // Natural language analysis - order matters! More specific patterns first
        return when {
            // Flashlight - highest priority for direct commands
            FLASHLIGHT_ON.any { lower.contains(it) } ->
                VoiceCommand(text, CommandType.FLASHLIGHT_ON)

            FLASHLIGHT_OFF.any { lower.contains(it) } ->
                VoiceCommand(text, CommandType.FLASHLIGHT_OFF)

            // Volume
            VOLUME_UP.any { lower.contains(it) } ->
                VoiceCommand(text, CommandType.VOLUME_UP)

            VOLUME_DOWN.any { lower.contains(it) } ->
                VoiceCommand(text, CommandType.VOLUME_DOWN)

            // Screenshot
            SCREENSHOT_PATTERNS.any { lower.contains(it) } ->
                VoiceCommand(text, CommandType.SCREENSHOT)

            // Navigation
            BACK_PATTERNS.any { lower.contains(it) } ->
                VoiceCommand(text, CommandType.NAVIGATE, mapOf("action" to "back"))

            HOME_PATTERNS.any { lower.contains(it) } ->
                VoiceCommand(text, CommandType.NAVIGATE, mapOf("action" to "home"))

            NOTIFICATION_PATTERNS.any { lower.contains(it) } ->
                VoiceCommand(text, CommandType.NAVIGATE, mapOf("action" to "notification"))

            // Scroll
            SCROLL_UP_PATTERNS.any { lower.contains(it) } ->
                VoiceCommand(text, CommandType.SCROLL_UP)

            SCROLL_DOWN_PATTERNS.any { lower.contains(it) } ->
                VoiceCommand(text, CommandType.SCROLL_DOWN)

            // WhatsApp call
            WHATSAPP_CALL.any { lower.contains(it) } -> {
                val name = extractAfter(lower, WHATSAPP_CALL)
                VoiceCommand(text, CommandType.WHATSAPP_CALL, mapOf("name" to name))
            }

            // YouTube
            YOUTUBE_PATTERNS.any { lower.contains(it) } -> {
                val song = extractAfter(lower, YOUTUBE_PATTERNS)
                VoiceCommand(text, CommandType.YOUTUBE_PLAY, mapOf("query" to song))
            }

            // Spotify
            SPOTIFY_PATTERNS.any { lower.contains(it) } -> {
                val song = extractAfter(lower, SPOTIFY_PATTERNS)
                VoiceCommand(text, CommandType.SPOTIFY_PLAY, mapOf("query" to song))
            }

            // Close app - before open app to catch "close X" patterns
            CLOSE_PATTERNS.any { lower.contains(it) } -> {
                val appName = extractAfter(lower, CLOSE_PATTERNS)
                VoiceCommand(text, CommandType.CLOSE_APP, mapOf("app" to appName))
            }

            // Call
            CALL_PATTERNS.any { lower.startsWith(it) || lower.contains(" $it ") } -> {
                val name = extractAfter(lower, CALL_PATTERNS)
                VoiceCommand(text, CommandType.CALL, mapOf("name" to name))
            }

            // Message
            MSG_PATTERNS.any { lower.contains(it) } -> {
                val parts = lower.split(" to ", " ko ")
                val name = if (parts.size > 1) parts[1].split(" ")[0] else ""
                VoiceCommand(text, CommandType.WHATSAPP_MSG, mapOf("name" to name, "message" to text))
            }

            // Open app - last among action patterns
            OPEN_PATTERNS.any { lower.startsWith(it) || lower.contains("$it ") } -> {
                val appName = extractAfter(lower, OPEN_PATTERNS)
                VoiceCommand(text, CommandType.OPEN_APP, mapOf("app" to appName))
            }

            // Natural language fallback for common Bangla patterns
            lower.contains("চালো") || lower.contains("চালাও") || lower.contains("play") -> {
                // Extract what to play
                val song = extractPlayQuery(lower)
                VoiceCommand(text, CommandType.YOUTUBE_PLAY, mapOf("query" to song))
            }

            lower.contains("বন্ধ") || lower.contains("বন্ধু") -> {
                val appName = extractAppNameFromContext(lower)
                VoiceCommand(text, CommandType.CLOSE_APP, mapOf("app" to appName))
            }

            else -> VoiceCommand(text, CommandType.CONVERSATION)
        }
    }

    private fun extractPlayQuery(text: String): String {
        val patterns = listOf("চালো", "চালাও", "play", "গান", "song")
        for (p in patterns) {
            val idx = text.indexOf(p)
            if (idx != -1) {
                val result = text.substring(idx + p.length).trim()
                return result.ifEmpty { text }
            }
        }
        return text
    }

    private fun extractAppNameFromContext(text: String): String {
        // Try to extract app name from patterns like "X বন্ধ করো"
        val parts = text.split(" ")
        for (i in parts.indices) {
            if (parts[i].contains("বন্ধ") && i > 0) {
                return parts[i - 1]
            }
        }
        return ""
    }

    private fun parseStructured(text: String): VoiceCommand? {
        val t = text.trim()
        return when {
            t.startsWith("OPEN_APP", true) -> {
                val app = t.removePrefix("OPEN_APP").removePrefix(":").trim()
                VoiceCommand(t, CommandType.OPEN_APP, mapOf("app" to app))
            }
            t.startsWith("CLOSE_APP", true) -> {
                val app = t.removePrefix("CLOSE_APP").removePrefix(":").trim()
                VoiceCommand(t, CommandType.CLOSE_APP, mapOf("app" to app))
            }
            t.startsWith("CALL", true) && !t.startsWith("WHATSAPP_CALL", true) -> {
                val name = t.removePrefix("CALL").trim()
                VoiceCommand(t, CommandType.CALL, mapOf("name" to name))
            }
            t.startsWith("WHATSAPP_CALL", true) -> {
                val name = t.removePrefix("WHATSAPP_CALL").trim()
                VoiceCommand(t, CommandType.WHATSAPP_CALL, mapOf("name" to name))
            }
            t.startsWith("WHATSAPP_MSG", true) -> {
                val rest = t.removePrefix("WHATSAPP_MSG").trim()
                val parts = rest.split(" ", limit = 2)
                VoiceCommand(t, CommandType.WHATSAPP_MSG, mapOf(
                    "name" to (parts.getOrNull(0) ?: ""),
                    "message" to (parts.getOrNull(1) ?: "")
                ))
            }
            t.startsWith("YOUTUBE_PLAY", true) -> {
                val q = t.removePrefix("YOUTUBE_PLAY").trim()
                VoiceCommand(t, CommandType.YOUTUBE_PLAY, mapOf("query" to q))
            }
            t.startsWith("SPOTIFY_PLAY", true) -> {
                val q = t.removePrefix("SPOTIFY_PLAY").trim()
                VoiceCommand(t, CommandType.SPOTIFY_PLAY, mapOf("query" to q))
            }
            t.equals("FLASHLIGHT_ON", true) -> VoiceCommand(t, CommandType.FLASHLIGHT_ON)
            t.equals("FLASHLIGHT_OFF", true) -> VoiceCommand(t, CommandType.FLASHLIGHT_OFF)
            t.equals("VOLUME_UP", true) -> VoiceCommand(t, CommandType.VOLUME_UP)
            t.equals("VOLUME_DOWN", true) -> VoiceCommand(t, CommandType.VOLUME_DOWN)
            t.equals("SCREENSHOT", true) -> VoiceCommand(t, CommandType.SCREENSHOT)
            t.equals("SCROLL_UP", true) -> VoiceCommand(t, CommandType.SCROLL_UP)
            t.equals("SCROLL_DOWN", true) -> VoiceCommand(t, CommandType.SCROLL_DOWN)
            t.startsWith("CLICK", true) -> {
                val target = t.removePrefix("CLICK").removePrefix(":").trim()
                VoiceCommand(t, CommandType.CLICK, mapOf("target" to target))
            }
            t.startsWith("SEARCH", true) -> {
                val query = t.removePrefix("SEARCH").removePrefix(":").trim()
                VoiceCommand(t, CommandType.SEARCH, mapOf("query" to query))
            }
            t.startsWith("TYPE_TEXT", true) -> {
                val text = t.removePrefix("TYPE_TEXT").removePrefix(":").trim()
                VoiceCommand(t, CommandType.TYPE_TEXT, mapOf("text" to text))
            }
            else -> null
        }
    }

    private fun extractAfter(text: String, patterns: List<String>): String {
        for (p in patterns) {
            val idx = text.indexOf(p)
            if (idx != -1) {
                return text.substring(idx + p.length).trim()
            }
        }
        return ""
    }
}
