package com.example.aiassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PatternMatcherTest {

    // Helper: build a UserPatternEntity without having to restate every field.
    private fun pattern(
        state: String = "state",
        packageName: String = "com.example.app",
        actionText: String = "Skip",
        actionType: String = "CLICK",
        count: Int = PatternMatcher.MIN_CONFIDENCE
    ) = UserPatternEntity(
        id = 0,
        state = state,
        packageName = packageName,
        actionText = actionText,
        actionType = actionType,
        count = count,
        lastSeen = 0L
    )

    // -------------------------------------------------------------------------
    // findBestMatchingTextElement
    // -------------------------------------------------------------------------

    @Test
    fun findBestMatchingTextElement_exactMatchPreferred() {
        val elements = listOf("Allow Once", "Allow", "Continue")

        val match = PatternMatcher.findBestMatchingTextElement(elements, "Allow")

        assertEquals("Allow", match)
    }

    @Test
    fun findBestMatchingTextElement_caseInsensitiveMatch() {
        val elements = listOf("dismiss", "Skip")

        val match = PatternMatcher.findBestMatchingTextElement(elements, "DISMISS")

        assertEquals("dismiss", match)
    }

    @Test
    fun findBestMatchingTextElement_unrelatedStrings_returnsNull() {
        val elements = listOf("Purchase", "Delete", "Cancel")

        val match = PatternMatcher.findBestMatchingTextElement(elements, "Allow")

        assertNull(match)
    }

    @Test
    fun findBestMatchingTextElement_emptyElements_returnsNull() {
        val match = PatternMatcher.findBestMatchingTextElement(emptyList(), "Skip")

        assertNull(match)
    }

    @Test
    fun findBestMatchingTextElement_allBelowThreshold_returnsNull() {
        // A threshold of 0.99 effectively requires an exact (case-insensitive) match.
        val elements = listOf("Allow Once", "Allow All")

        val match = PatternMatcher.findBestMatchingTextElement(
            elements = elements,
            targetText = "Allow",
            threshold = 0.99
        )

        assertNull(match)
    }

    @Test
    fun findBestMatchingTextElement_picksHighestScoringCandidate() {
        // "Skipper" and "Skip All" are both close to "Skip"; the closer one should win.
        val elements = listOf("Skipper", "Skip All", "Skip")

        val match = PatternMatcher.findBestMatchingTextElement(elements, "Skip")

        assertEquals("Skip", match)
    }

    // -------------------------------------------------------------------------
    // pickActionForPattern (exact-state primary path)
    // -------------------------------------------------------------------------

    @Test
    fun pickActionForPattern_nullPattern_returnsNull() {
        val command = PatternMatcher.pickActionForPattern(
            pattern = null,
            textElements = listOf("Skip"),
            minConfidence = PatternMatcher.MIN_CONFIDENCE
        )

        assertNull(command)
    }

    @Test
    fun pickActionForPattern_belowMinConfidence_returnsNull() {
        val weak = pattern(actionText = "Skip", count = PatternMatcher.MIN_CONFIDENCE - 1)

        val command = PatternMatcher.pickActionForPattern(
            pattern = weak,
            textElements = listOf("Skip"),
            minConfidence = PatternMatcher.MIN_CONFIDENCE
        )

        assertNull(command)
    }

    @Test
    fun pickActionForPattern_atMinConfidenceWithVisibleTarget_returnsClickCommand() {
        val p = pattern(actionText = "Skip", count = PatternMatcher.MIN_CONFIDENCE)

        val command = PatternMatcher.pickActionForPattern(
            pattern = p,
            textElements = listOf("Now Playing", "Skip"),
            minConfidence = PatternMatcher.MIN_CONFIDENCE
        )

        assertNotNull(command)
        assertEquals(ActionType.CLICK, command!!.type)
        assertEquals("Skip", command.target)
    }

    @Test
    fun pickActionForPattern_targetTextNotOnScreen_returnsNull() {
        val p = pattern(actionText = "Skip", count = 10)

        val command = PatternMatcher.pickActionForPattern(
            pattern = p,
            textElements = listOf("Purchase", "Settings"),
            minConfidence = PatternMatcher.MIN_CONFIDENCE
        )

        assertNull(command)
    }

    @Test
    fun pickActionForPattern_scrollActionType_parsedAsScrollForward() {
        val p = pattern(actionText = "Feed", actionType = "SCROLL", count = 10)

        val command = PatternMatcher.pickActionForPattern(
            pattern = p,
            textElements = listOf("Feed"),
            minConfidence = PatternMatcher.MIN_CONFIDENCE
        )

        assertNotNull(command)
        assertEquals(ActionType.SCROLL_FORWARD, command!!.type)
    }

    @Test
    fun pickActionForPattern_usesLiveElementTextNotStoredText() {
        // Pattern remembers "Allow" but screen currently shows "Allow Once".
        // Fuzzy match must succeed AND the command target should be the live text
        // so ActionExecutor can actually find the node.
        val p = pattern(actionText = "Allow", count = 10)

        val command = PatternMatcher.pickActionForPattern(
            pattern = p,
            textElements = listOf("Allow Once"),
            minConfidence = PatternMatcher.MIN_CONFIDENCE
        )

        assertNotNull(command)
        assertEquals("Allow Once", command!!.target)
    }

    // -------------------------------------------------------------------------
    // pickFirstMatchingPackagePattern (cross-state / orientation fallback)
    // -------------------------------------------------------------------------

    @Test
    fun pickFirstMatchingPackagePattern_emptyList_returnsNull() {
        val command = PatternMatcher.pickFirstMatchingPackagePattern(
            patterns = emptyList(),
            excludeState = "portrait",
            textElements = listOf("Skip")
        )

        assertNull(command)
    }

    @Test
    fun pickFirstMatchingPackagePattern_skipsExcludedState() {
        // The only matching pattern is on the excluded (already-tried) state.
        val patterns = listOf(
            pattern(state = "portrait", actionText = "Skip", count = 10)
        )

        val command = PatternMatcher.pickFirstMatchingPackagePattern(
            patterns = patterns,
            excludeState = "portrait",
            textElements = listOf("Skip")
        )

        assertNull(command)
    }

    @Test
    fun pickFirstMatchingPackagePattern_returnsMatchFromDifferentState() {
        // Classic orientation case: "Skip" learned in portrait, we're now in landscape.
        val patterns = listOf(
            pattern(state = "portrait", actionText = "Skip", count = 10)
        )

        val command = PatternMatcher.pickFirstMatchingPackagePattern(
            patterns = patterns,
            excludeState = "landscape",
            textElements = listOf("Skip Ad")
        )

        assertNotNull(command)
        assertEquals(ActionType.CLICK, command!!.type)
        assertEquals("Skip Ad", command.target)
    }

    @Test
    fun pickFirstMatchingPackagePattern_firstMatchWinsInInputOrder() {
        // Caller supplies patterns count-DESC; we rely on that ordering.
        val patterns = listOf(
            pattern(state = "home", actionText = "Continue", count = 20),
            pattern(state = "home", actionText = "Skip", count = 10)
        )

        val command = PatternMatcher.pickFirstMatchingPackagePattern(
            patterns = patterns,
            excludeState = "other",
            textElements = listOf("Continue", "Skip")
        )

        assertNotNull(command)
        assertEquals("Continue", command!!.target)
    }

    @Test
    fun pickFirstMatchingPackagePattern_skipsPatternsWithTargetMissing() {
        // Higher-count pattern's target isn't on screen; fall through to the next one.
        val patterns = listOf(
            pattern(state = "home", actionText = "NotOnScreen", count = 20),
            pattern(state = "home", actionText = "Skip", count = 10)
        )

        val command = PatternMatcher.pickFirstMatchingPackagePattern(
            patterns = patterns,
            excludeState = "other",
            textElements = listOf("Skip")
        )

        assertNotNull(command)
        assertEquals("Skip", command!!.target)
    }

    @Test
    fun pickFirstMatchingPackagePattern_noTargetsVisible_returnsNull() {
        val patterns = listOf(
            pattern(state = "home", actionText = "Skip", count = 10),
            pattern(state = "home", actionText = "Dismiss", count = 8)
        )

        val command = PatternMatcher.pickFirstMatchingPackagePattern(
            patterns = patterns,
            excludeState = "other",
            textElements = listOf("Purchase", "Settings")
        )

        assertNull(command)
    }

    @Test
    fun pickFirstMatchingPackagePattern_crossStateConfidenceIsStricterThanBaseline() {
        // Document the intent: cross-state matching requires more evidence
        // than intra-state matching. Guards against accidental regressions.
        assert(PatternMatcher.MIN_CROSS_STATE_CONFIDENCE > PatternMatcher.MIN_CONFIDENCE)
    }
}
