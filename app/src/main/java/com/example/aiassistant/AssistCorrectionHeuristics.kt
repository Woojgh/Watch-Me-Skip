package com.example.aiassistant

/**
 * Heuristics for deciding whether a user interaction should be treated as a
 * correction to the assistant's last action.
 */
object AssistCorrectionHeuristics {

    private val whitespaceRegex = Regex("\\s+")
    private const val SAME_TARGET_THRESHOLD = 0.94

    /**
     * Returns true only when the event strongly suggests user correction:
     * - explicit click event
     * - same screen state the assistant just acted on
     * - within correction window
     * - clicked target differs from what assistant just clicked
     */
    internal fun shouldRecordCorrection(
        isClickEvent: Boolean,
        actionText: String?,
        currentState: String?,
        assistState: String?,
        assistTarget: String?,
        timeSinceAssistMs: Long,
        correctionWindowMs: Long
    ): Boolean {
        if (!isClickEvent) return false
        if (actionText.isNullOrBlank()) return false
        if (currentState == null || assistState == null || assistTarget.isNullOrBlank()) return false
        if (currentState != assistState) return false
        if (timeSinceAssistMs < 0 || timeSinceAssistMs >= correctionWindowMs) return false
        return !matchesAssistTarget(actionText, assistTarget)
    }

    /**
     * True when both texts are effectively the same button target.
     */
    internal fun matchesAssistTarget(actionText: String, assistTarget: String): Boolean {
        val actionNorm = normalize(actionText)
        val targetNorm = normalize(assistTarget)
        if (actionNorm.isEmpty() || targetNorm.isEmpty()) return false
        if (actionNorm == targetNorm) return true
        return StringSimilarity.jaroWinkler(actionNorm, targetNorm) >= SAME_TARGET_THRESHOLD
    }

    private fun normalize(value: String): String {
        return value.lowercase().trim().replace(whitespaceRegex, " ")
    }
}
