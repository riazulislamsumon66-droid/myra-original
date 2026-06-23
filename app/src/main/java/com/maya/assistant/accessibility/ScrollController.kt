package com.maya.assistant.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.maya.assistant.service.SmartAccessibilityEngine

object ScrollController {
    private const val MAX_DEPTH = 40
    fun scrollDown() = scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    fun scrollUp() = scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    private fun scroll(action: Int): Boolean {
        val root = SmartAccessibilityEngine.service?.rootInActiveWindow ?: return false
        return doScroll(root, action, 0)
    }
    private fun doScroll(node: AccessibilityNodeInfo, action: Int, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        if (node.isScrollable) return node.performAction(action)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (doScroll(c, action, depth + 1)) return true
        }
        return false
    }
}
