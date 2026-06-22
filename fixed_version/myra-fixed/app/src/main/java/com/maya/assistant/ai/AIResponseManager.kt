package com.maya.assistant.ai

object AIResponseManager {

    private val THINKING_FILTERS = listOf(
        "Responding to the Greeting",
        "I've registered the Hindi greeting.",
        "Now, I'm formulating",
        "Interpreting user command",
        "adhering to appropriate cultural etiquette.",
        "Formulating",
        "Let me think",
        "Processing",
        "I'm now crafting",
        "I'm crafting",
        "I'm considering",
        "I'm thinking",
        "I'm working on",
        "I'm preparing",
        "I'm generating",
        "I'm formulating",
        "The focus is on",
        "I'm analyzing",
        "I'm evaluating",
        "I'm determining",
        "Let me consider",
        "Let me analyze",
        "Let me think about",
        "I need to",
        "I should",
        "I will",
        "I'll",
        "The only output needed",
        "the command itself",
        "different search modifiers",
        "optimize the search",
        "precise and will retrieve",
        "ensuring the command is precise",
        "crafting the",
        "considering different"
    )

    // Patterns that indicate Gemini is thinking instead of responding
    private val THINKING_PATTERNS = listOf(
        "I'm now crafting",
        "I'm crafting",
        "I'm considering",
        "I'm thinking",
        "I'm working on",
        "I'm preparing",
        "I'm generating",
        "I'm formulating",
        "I'm analyzing",
        "I'm evaluating",
        "I'm determining",
        "Let me consider",
        "Let me analyze",
        "Let me think",
        "I need to",
        "I should",
        "I will",
        "I'll",
        "The focus is on",
        "The only output needed",
        "the command itself",
        "different search modifiers",
        "optimize the search",
        "precise and will retrieve",
        "ensuring the command is precise",
        "crafting the",
        "considering different"
    )

    fun clean(raw: String): String {
        var result = raw
            .replace("```", "")
            .replace("**", "")
            .replace("*", "")

        // Remove all thinking patterns
        for (filter in THINKING_FILTERS) {
            result = result.replace(filter, "")
        }

        // Remove lines that start with thinking indicators
        val lines = result.lines().filter { line ->
            val trimmed = line.trim()
            THINKING_PATTERNS.none { pattern ->
                trimmed.startsWith(pattern, ignoreCase = true)
            } && trimmed.isNotEmpty()
        }

        return lines.joinToString("\n").trim()
    }

    fun extractCommand(text: String): String? {
        val commands = listOf(
            "OPEN_APP", "CLOSE_APP", "CALL", "WHATSAPP_CALL", "WHATSAPP_MSG",
            "YOUTUBE_PLAY", "SPOTIFY_PLAY", "MUSIC_PLAY", "FLASHLIGHT_ON",
            "FLASHLIGHT_OFF", "VOLUME_UP", "VOLUME_DOWN", "SCREENSHOT",
            "SCROLL_UP", "SCROLL_DOWN", "BACK", "HOME", "NOTIFICATION", "SMS",
            "READ_SCREEN", "BATTERY_CHECK", "SETTINGS_OPEN",
            "SETTINGS_WIFI_ON", "SETTINGS_WIFI_OFF",
            "SETTINGS_BLUETOOTH_ON", "SETTINGS_BLUETOOTH_OFF",
            "SETTINGS_BRIGHTNESS",
            "IMO_CALL", "MESSENGER_CALL", "TELEGRAM_CALL",
            "CLICK", "SEARCH", "TYPE_TEXT"
        )
        val upper = text.uppercase()
        for (cmd in commands) {
            val index = upper.indexOf(cmd)
            if (index != -1) {
                return text.substring(index).trim().lines().first().replace("`", "").trim()
            }
        }
        return null
    }

    fun hasThinkingText(text: String): Boolean {
        return THINKING_PATTERNS.any { pattern ->
            text.contains(pattern, ignoreCase = true)
        }
    }
}
