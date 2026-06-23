package com.maya.assistant.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.maya.assistant.models.ScreenNodeModel

object NodeReader {
    // Guards against StackOverflowError on very deep accessibility trees
    // (e.g. YouTube, Instagram feed — RecyclerView nesting can exceed 30 levels).
    private const val MAX_DEPTH = 40

    fun readAll(root: AccessibilityNodeInfo?): List<ScreenNodeModel> {
        val nodes = mutableListOf<ScreenNodeModel>()
        if (root == null) return nodes
        traverse(root, nodes, 0)
        return nodes
    }

    private fun traverse(node: AccessibilityNodeInfo, list: MutableList<ScreenNodeModel>, depth: Int) {
        if (depth > MAX_DEPTH) return
        list.add(ScreenNodeModel(
            text = node.text?.toString(),
            contentDesc = node.contentDescription?.toString(),
            className = node.className?.toString(),
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            bounds = Rect().also { node.getBoundsInScreen(it) },
            viewId = node.viewIdResourceName
        ))
        for (i in 0 until node.childCount) { node.getChild(i)?.let { traverse(it, list, depth + 1) } }
    }

    fun dumpText(root: AccessibilityNodeInfo?): String =
        readAll(root).mapNotNull { it.text?.ifBlank { null } ?: it.contentDesc?.ifBlank { null } }
            .joinToString(" | ")
}
