package com.example.aiassistant

import android.view.accessibility.AccessibilityNodeInfo

object UIUtils {

    /**
     * Build a ScreenSnapshot synchronously from the live node tree.
     * Must be called on the main/accessibility thread before launching coroutines.
     */
    fun snapshotScreen(packageName: String?, root: AccessibilityNodeInfo): ScreenSnapshot {
        val nodes = mutableListOf<NodeSnapshot>()
        val textElements = mutableListOf<String>()
        val structParts = mutableListOf<String>()
        // Loose fingerprint: only interactable nodes, collected for sorting.
        val looseParts = mutableListOf<String>()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val cls = node.className?.toString()

            nodes.add(
                NodeSnapshot(
                    text = text,
                    contentDesc = desc,
                    className = cls,
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable,
                    isEditable = node.isEditable
                )
            )

            text?.let { textElements.add(it) }
            desc?.let { textElements.add(it) }

            val flags = "${if (node.isClickable) "C" else ""}${if (node.isScrollable) "S" else ""}${if (node.isEditable) "E" else ""}"
            val descriptor = "${cls ?: "?"}:$flags"

            // Strict fingerprint: every node in tree order.
            structParts.add(descriptor)

            // Loose fingerprint: only nodes the user can actually interact with.
            // Sorting makes this order-independent so minor DOM reordering is ignored.
            if (node.isClickable || node.isScrollable || node.isEditable) {
                looseParts.add(descriptor)
            }

            for (i in 0 until node.childCount) {
                try {
                    walk(node.getChild(i))
                } catch (_: Exception) {
                    // Node may have become stale during walk
                }
            }
        }

        try {
            walk(root)
        } catch (_: Exception) {
            // Root may be stale
        }

        val pkg = packageName ?: "unknown"
        val structHash = structParts.joinToString("|").hashCode().toUInt().toString(16)
        val stableState = "$pkg:$structHash"

        // Prefix with 'L' so loose-state keys never collide with strict-state keys in the DB.
        val looseHash = looseParts.sorted().joinToString("|").hashCode().toUInt().toString(16)
        val looseState = "$pkg:L$looseHash"

        return ScreenSnapshot(
            packageName = pkg,
            stableState = stableState,
            looseState = looseState,
            nodes = nodes,
            textElements = textElements
        )
    }

    /**
     * Extract just the text elements (legacy helper, still used by some components).
     */
    fun extractUI(root: AccessibilityNodeInfo): List<String> {
        val list = mutableListOf<String>()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.let { list.add(it) }
            node.contentDescription?.toString()?.let { list.add(it) }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }

        walk(root)
        return list
    }
}
