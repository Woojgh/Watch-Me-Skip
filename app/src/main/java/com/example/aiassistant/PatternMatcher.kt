package com.example.aiassistant

import android.content.Context

/**
 * Finds the best action for a screen by looking up learned user patterns.
 * Falls back to rule-based matching if no confident pattern exists.
 */
object PatternMatcher {

    /**
     * Minimum number of times a pattern must have been observed
     * before the app will replay it automatically.
     */
    const val MIN_CONFIDENCE = 3

    /**
     * Higher bar for cross-state matching. If the current screen's stableState
     * doesn't match the one the pattern was learned on (e.g. after a rotation
     * or layout variant), we require stronger evidence before replaying it on
     * an unfamiliar structural fingerprint.
     */
    const val MIN_CROSS_STATE_CONFIDENCE = 5
    private const val TEXT_MATCH_THRESHOLD = 0.85

    /**
     * Find the best action for the given screen snapshot.
     * Returns null if no confident action is found.
     */
    suspend fun findBestAction(
        context: Context,
        snapshot: ScreenSnapshot
    ): ActionCommand? {
        // 1. Try learned patterns first
        val patternAction = findFromPatterns(context, snapshot)
        if (patternAction != null) return patternAction

        // 2. Fall back to rule-based matching
        return findFromRules(context, snapshot)
    }

    private suspend fun findFromPatterns(
        context: Context,
        snapshot: ScreenSnapshot
    ): ActionCommand? {
        val dao = DatabaseHelper.getDB(context).userPatternDao()

        // Tier 1: exact strict-state match — backward compat with old learned patterns.
        val top = dao.getTopByState(snapshot.stableState)
        pickActionForPattern(top, snapshot.textElements, MIN_CONFIDENCE)?.let { return it }

        // Tier 2: loose-state match — catches new patterns stored with the interactable-
        // only hash (more resilient to cosmetic layout changes like ad banners).
        // Only try if the two hashes actually differ (avoids a redundant DB query).
        if (snapshot.looseState != snapshot.stableState) {
            val looseTop = dao.getTopByState(snapshot.looseState)
            pickActionForPattern(looseTop, snapshot.textElements, MIN_CONFIDENCE)?.let { return it }
        }

        // Tier 3: package-level fallback for orientation / layout variant mismatches.
        val packagePatterns = dao.getConfidentByPackage(
            packageName = snapshot.packageName,
            minCount = MIN_CROSS_STATE_CONFIDENCE
        )
        return pickFirstMatchingPackagePattern(
            patterns = packagePatterns,
            excludeState = snapshot.stableState,
            textElements = snapshot.textElements
        )
    }

    /**
     * Pure helper: given a candidate pattern (or null) and the current screen's
     * text elements, returns an ActionCommand if the pattern meets [minConfidence]
     * and its target text fuzzy-matches something visible.
     *
     * Verifies the target still exists on-screen. Fuzzy matching (Jaro-Winkler ≥ 0.85)
     * handles minor dynamic text changes (e.g. "Allow" vs "Allow Once").
     * The command's target is the live element text, not the stored text.
     */
    internal fun pickActionForPattern(
        pattern: UserPatternEntity?,
        textElements: List<String>,
        minConfidence: Int
    ): ActionCommand? {
        if (pattern == null || pattern.count < minConfidence) return null
        val matched = findBestMatchingTextElement(textElements, pattern.actionText) ?: return null
        return ActionCommand(type = parseActionType(pattern.actionType), target = matched)
    }

    /**
     * Pure helper for the package-level fallback. Walks [patterns] in order
     * (caller supplies them count-DESC) and returns the first one whose stored
     * target text fuzzy-matches a currently visible element, excluding any
     * pattern whose state equals [excludeState] (already tried by the caller).
     */
    internal fun pickFirstMatchingPackagePattern(
        patterns: List<UserPatternEntity>,
        excludeState: String,
        textElements: List<String>
    ): ActionCommand? {
        for (pattern in patterns) {
            if (pattern.state == excludeState) continue
            val matched = findBestMatchingTextElement(textElements, pattern.actionText) ?: continue
            return ActionCommand(type = parseActionType(pattern.actionType), target = matched)
        }
        return null
    }

    private suspend fun findFromRules(
        context: Context,
        snapshot: ScreenSnapshot
    ): ActionCommand? {
        val rules = DatabaseHelper.getDB(context).ruleDao().getEnabled()
        if (rules.isEmpty()) return null

        for (element in snapshot.textElements) {
            val lower = element.lowercase()
            for (rule in rules) {
                if (lower.contains(rule.keyword.lowercase())) {
                    val type = parseActionType(rule.actionType)
                    return ActionCommand(type = type, target = element)
                }
            }
        }
        return null
    }

    private fun parseActionType(actionType: String): ActionType {
        return when (actionType.uppercase()) {
            "CLICK" -> ActionType.CLICK
            "SCROLL", "SCROLL_FORWARD" -> ActionType.SCROLL_FORWARD
            "SCROLL_BACKWARD" -> ActionType.SCROLL_BACKWARD
            "SWIPE" -> ActionType.SWIPE
            "TYPE" -> ActionType.TYPE
            else -> ActionType.CLICK
        }
    }

    /**
     * Picks the best screen text candidate for a learned target.
     * Returns null when nothing clears the similarity threshold.
     */
    internal fun findBestMatchingTextElement(
        elements: List<String>,
        targetText: String,
        threshold: Double = TEXT_MATCH_THRESHOLD
    ): String? {
        return elements
            .asSequence()
            .map { candidate ->
                val score = StringSimilarity.jaroWinkler(
                    candidate.lowercase(),
                    targetText.lowercase()
                )
                candidate to score
            }
            .filter { (_, score) -> score >= threshold }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }
}
