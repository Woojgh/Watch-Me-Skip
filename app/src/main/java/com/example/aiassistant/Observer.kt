package com.example.aiassistant

import android.content.Context
import android.view.accessibility.AccessibilityEvent

/**
 * Observes user-initiated actions and records them as patterns.
 * This runs only in OBSERVE mode — learning is disabled during ASSIST.
 */
object Observer {

    /**
     * Record a user action from pre-extracted data.
     * The caller must extract text/eventType from the AccessibilityEvent
     * SYNCHRONOUSLY before launching a coroutine, because Android recycles
     * event objects after onAccessibilityEvent returns.
     */
    suspend fun onUserActionDirect(
        context: Context,
        actionText: String,
        eventType: Int,
        currentState: String,
        packageName: String
    ) {
        val actionType = mapEventType(eventType)

        DatabaseHelper.recordPattern(
            context = context,
            state = currentState,
            packageName = packageName,
            actionText = actionText,
            actionType = actionType
        )
    }

    private fun mapEventType(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLL"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE"
            else -> "CLICK"
        }
    }
}
