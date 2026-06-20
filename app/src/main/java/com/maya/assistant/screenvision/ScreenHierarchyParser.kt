package com.maya.assistant.screenvision

import android.view.accessibility.AccessibilityNodeInfo
import com.maya.assistant.accessibility.NodeReader
import com.maya.assistant.models.ScreenNodeModel
import com.maya.assistant.service.SmartAccessibilityEngine

object ScreenHierarchyParser {

    fun getCurrentNodes(): List<ScreenNodeModel> {
        val root = SmartAccessibilityEngine.service?.rootInActiveWindow ?: return emptyList()
        return NodeReader.readAll(root)
    }

    fun getCurrentText(): String {
        val root = SmartAccessibilityEngine.service?.rootInActiveWindow ?: return ""
        return NodeReader.dumpText(root)
    }

    fun getCurrentPackage(): String {
        return SmartAccessibilityEngine.service?.rootInActiveWindow?.packageName?.toString() ?: ""
    }

    fun summarizeScreen(): String {
        val nodes = getCurrentNodes()
        val texts = nodes.mapNotNull { it.text?.ifBlank { null } ?: it.contentDesc?.ifBlank { null } }
        return "Package: ${getCurrentPackage()} | UI: ${texts.take(10).joinToString(", ")}"
    }
}
