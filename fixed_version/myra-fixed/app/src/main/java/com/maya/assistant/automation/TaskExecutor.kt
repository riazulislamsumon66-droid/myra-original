package com.maya.assistant.automation

import com.maya.assistant.accessibility.DynamicClickEngine
import com.maya.assistant.accessibility.ScrollController
import com.maya.assistant.accessibility.TypingController
import com.maya.assistant.models.ActionModel
import com.maya.assistant.models.ActionType
import com.maya.assistant.utils.Logger

object TaskExecutor {
    private val TAG = "TASK_EXEC"

    fun execute(action: ActionModel): Boolean {
        Logger.d(TAG, "Executing: ${action.type} target=${action.target}")
        return when (action.type) {
            ActionType.CLICK_TEXT -> DynamicClickEngine.clickByText(action.target)
            ActionType.TYPE_TEXT -> TypingController.typeText(action.value)
            ActionType.SCROLL_DOWN -> ScrollController.scrollDown()
            ActionType.SCROLL_UP -> ScrollController.scrollUp()
            else -> {
                Logger.w(TAG, "Unhandled action: ${action.type}")
                false
            }
        }
    }
}
