package com.maya.assistant.screenvision

import com.maya.assistant.accessibility.DynamicClickEngine
import com.maya.assistant.accessibility.TypingController
import com.maya.assistant.utils.Logger

object VisionDecisionEngine {
    private val TAG = "VISION_DECISION"

    fun executeVisualAction(action: String): Boolean {
        val nodes = ScreenHierarchyParser.getCurrentNodes()
        val target = VisualUIAnalyzer.findBestActionTarget(nodes, action)
        if (target != null) {
            Logger.d(TAG, "Visual target found: ${target.text ?: target.contentDesc}")
            val text = target.text ?: target.contentDesc ?: return false
            return DynamicClickEngine.clickByText(text)
        }
        Logger.w(TAG, "No visual target for: $action")
        return false
    }

    fun fillInputField(text: String): Boolean = TypingController.typeText(text)

    fun describeCurrentScreen(): String = ScreenHierarchyParser.summarizeScreen()
}
