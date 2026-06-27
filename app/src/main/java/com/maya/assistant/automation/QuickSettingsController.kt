package com.maya.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.util.Log
import kotlinx.coroutines.delay
import com.maya.assistant.service.SmartAccessibilityEngine

/**
 * QuickSettingsController — makes WiFi/Bluetooth on/off commands actually
 * hands-free.
 *
 * BACKGROUND: since Android 10, apps cannot directly toggle WiFi or
 * Bluetooth via WifiManager.setWifiEnabled()/BluetoothAdapter.enable() —
 * Google deprecated these for privacy/battery reasons. The only remaining
 * way for a third-party app to actually flip these switches (rather than
 * just opening the Settings screen and asking the user to tap it) is to
 * open the Quick Settings panel and tap the tile via Accessibility, which
 * is exactly what a sighted user would do by swiping down twice and tapping.
 *
 * This is inherently less robust than a direct API call: tile labels vary
 * slightly by Android version/OEM ("Wi-Fi" vs "WiFi" vs "Internet" on some
 * Samsung/Pixel variants), and the panel needs a short render delay before
 * its nodes are queryable. Both are handled below with multiple label
 * candidates and a delay + retry.
 */
object QuickSettingsController {
    private const val TAG = "QS_CONTROLLER"
    private const val PANEL_RENDER_DELAY_MS = 350L

    private val WIFI_LABELS = listOf("Wi-Fi", "WiFi", "Wifi", "Internet")
    private val BLUETOOTH_LABELS = listOf("Bluetooth")

    /**
     * Opens Quick Settings and taps the WiFi tile.
     * Returns true if the panel opened and a WiFi-like tile was found and tapped
     * (this means the tile was *tapped*, not necessarily that WiFi ended up in
     * the desired on/off state — toggling is relative to current state, same
     * as a human tapping the tile).
     */
    suspend fun toggleWifi(): Boolean = toggleTile(WIFI_LABELS)

    suspend fun toggleBluetooth(): Boolean = toggleTile(BLUETOOTH_LABELS)

    private suspend fun toggleTile(labelCandidates: List<String>): Boolean {
        val service = SmartAccessibilityEngine.service
        if (service == null) {
            Log.w(TAG, "Accessibility service not available — cannot control Quick Settings")
            return false
        }

        val opened = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        if (!opened) {
            Log.w(TAG, "GLOBAL_ACTION_QUICK_SETTINGS was rejected by the system")
            return false
        }

        // The panel needs a moment to render before its nodes exist.
        delay(PANEL_RENDER_DELAY_MS)

        for (label in labelCandidates) {
            if (ActionExecutor.clickByDescription(service, label)) {
                Log.d(TAG, "Tapped tile matching '$label'")
                closeQuickSettings(service)
                return true
            }
            if (ActionExecutor.clickByText(service, label)) {
                Log.d(TAG, "Tapped tile matching '$label' (by text)")
                closeQuickSettings(service)
                return true
            }
        }

        // One retry after a longer delay — slower devices/animations can
        // need more time before tiles are queryable.
        delay(PANEL_RENDER_DELAY_MS)
        for (label in labelCandidates) {
            if (ActionExecutor.clickByDescription(service, label) || ActionExecutor.clickByText(service, label)) {
                closeQuickSettings(service)
                return true
            }
        }

        Log.w(TAG, "Could not find a tile matching any of: $labelCandidates")
        // Leave the panel open rather than guessing — the user can see it
        // and tap manually as a fallback.
        return false
    }

    private fun closeQuickSettings(service: AccessibilityService) {
        // Collapse the panel after a successful tap so we don't leave the
        // notification shade hanging over whatever the user was doing.
        try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close Quick Settings: ${e.message}")
        }
    }
}
