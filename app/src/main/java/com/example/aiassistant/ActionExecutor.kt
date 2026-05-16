package com.example.aiassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

object ActionExecutor {

    /**
     * Execute an ActionCommand using the live accessibility tree.
     * Fetches rootInActiveWindow at execution time to avoid stale nodes.
     * Returns true if the action was performed successfully.
     */
    fun executeSafe(
        service: AccessibilityService,
        command: ActionCommand
    ): Boolean {
        return try {
            val root = service.rootInActiveWindow ?: return false
            when (command.type) {
                ActionType.CLICK -> clickByText(root, command.target)
                ActionType.SCROLL_FORWARD -> scrollByText(root, command.target, forward = true)
                ActionType.SCROLL_BACKWARD -> scrollByText(root, command.target, forward = false)
                ActionType.SWIPE -> performSwipe(service)
                ActionType.TYPE -> typeText(root, command.target)
            }
        } catch (_: Exception) {
            // Node tree may have gone stale
            false
        }
    }

    fun clickByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)

        for (node in nodes) {
            val clickable = findClickable(node)
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    fun scrollByText(root: AccessibilityNodeInfo, text: String, forward: Boolean): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)

        for (node in nodes) {
            val scrollable = findScrollable(node)
            if (scrollable != null) {
                val action = if (forward)
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                scrollable.performAction(action)
                return true
            }
        }
        return false
    }

    fun performSwipe(
        service: AccessibilityService,
        startX: Float = -1f,
        startY: Float = -1f,
        endX: Float = -1f,
        endY: Float = -1f,
        durationMs: Long = 300
    ): Boolean {
        // Compute screen-relative defaults so the swipe works on any resolution.
        // Negative sentinel values mean "use default"; explicit callers can override.
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val sx = if (startX >= 0) startX else w / 2f
        val sy = if (startY >= 0) startY else h * 0.75f
        val ex = if (endX >= 0) endX else w / 2f
        val ey = if (endY >= 0) endY else h * 0.25f

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun typeText(root: AccessibilityNodeInfo, text: String): Boolean {
        val focused = findFocusedEditable(root)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            return true
        }
        return false
    }

    private fun findClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isScrollable) return current
            current = current.parent
        }
        return null
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable && node.isFocused) return node
            for (i in 0 until node.childCount) {
                val result = walk(node.getChild(i))
                if (result != null) return result
            }
            return null
        }
        return walk(root)
    }
}
