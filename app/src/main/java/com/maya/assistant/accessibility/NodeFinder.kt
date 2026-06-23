package com.maya.assistant.accessibility

import android.view.accessibility.AccessibilityNodeInfo

object NodeFinder {
    private const val MAX_DEPTH = 15

    fun findByText(root: AccessibilityNodeInfo?, text: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (root == null || depth > MAX_DEPTH) return null
        if (root.text?.toString()?.contains(text, true) == true) return root
        if (root.contentDescription?.toString()?.contains(text, true) == true) return root
        for (i in 0 until root.childCount) {
            findByText(root.getChild(i), text, depth + 1)?.let { return it }
        }
        return null
    }

    fun findEditText(root: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (root == null || depth > MAX_DEPTH) return null
        if (root.className?.contains("EditText") == true) return root
        for (i in 0 until root.childCount) {
            findEditText(root.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    fun findClickable(root: AccessibilityNodeInfo?, hint: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (root == null || depth > MAX_DEPTH) return null
        val m = root.text?.toString()?.contains(hint, true) == true ||
                root.contentDescription?.toString()?.contains(hint, true) == true
        if (root.isClickable && m) return root
        for (i in 0 until root.childCount) {
            findClickable(root.getChild(i), hint, depth + 1)?.let { return it }
        }
        return null
    }
}
