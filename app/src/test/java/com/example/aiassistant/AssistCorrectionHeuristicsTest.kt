package com.example.aiassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistCorrectionHeuristicsTest {

    @Test
    fun shouldRecordCorrection_nonClickEvent_returnsFalse() {
        val shouldRecord = AssistCorrectionHeuristics.shouldRecordCorrection(
            isClickEvent = false,
            actionText = "Play",
            currentState = "pkg:L123",
            assistState = "pkg:L123",
            assistTarget = "Skip Ad",
            timeSinceAssistMs = 1_000L,
            correctionWindowMs = 3_000L
        )

        assertFalse(shouldRecord)
    }

    @Test
    fun shouldRecordCorrection_blankActionText_returnsFalse() {
        val shouldRecord = AssistCorrectionHeuristics.shouldRecordCorrection(
            isClickEvent = true,
            actionText = " ",
            currentState = "pkg:L123",
            assistState = "pkg:L123",
            assistTarget = "Skip Ad",
            timeSinceAssistMs = 1_000L,
            correctionWindowMs = 3_000L
        )

        assertFalse(shouldRecord)
    }

    @Test
    fun shouldRecordCorrection_sameTargetClick_returnsFalse() {
        val shouldRecord = AssistCorrectionHeuristics.shouldRecordCorrection(
            isClickEvent = true,
            actionText = "skip  ad",
            currentState = "pkg:L123",
            assistState = "pkg:L123",
            assistTarget = "Skip Ad",
            timeSinceAssistMs = 800L,
            correctionWindowMs = 3_000L
        )

        assertFalse(shouldRecord)
    }

    @Test
    fun shouldRecordCorrection_differentTargetOnSameState_returnsTrue() {
        val shouldRecord = AssistCorrectionHeuristics.shouldRecordCorrection(
            isClickEvent = true,
            actionText = "Close",
            currentState = "pkg:L123",
            assistState = "pkg:L123",
            assistTarget = "Skip Ad",
            timeSinceAssistMs = 900L,
            correctionWindowMs = 3_000L
        )

        assertTrue(shouldRecord)
    }

    @Test
    fun shouldRecordCorrection_stateMismatch_returnsFalse() {
        val shouldRecord = AssistCorrectionHeuristics.shouldRecordCorrection(
            isClickEvent = true,
            actionText = "Close",
            currentState = "pkg:L999",
            assistState = "pkg:L123",
            assistTarget = "Skip Ad",
            timeSinceAssistMs = 900L,
            correctionWindowMs = 3_000L
        )

        assertFalse(shouldRecord)
    }

    @Test
    fun shouldRecordCorrection_outsideWindow_returnsFalse() {
        val shouldRecord = AssistCorrectionHeuristics.shouldRecordCorrection(
            isClickEvent = true,
            actionText = "Close",
            currentState = "pkg:L123",
            assistState = "pkg:L123",
            assistTarget = "Skip Ad",
            timeSinceAssistMs = 3_500L,
            correctionWindowMs = 3_000L
        )

        assertFalse(shouldRecord)
    }

    @Test
    fun matchesAssistTarget_fuzzyVariant_returnsTrue() {
        val matches = AssistCorrectionHeuristics.matchesAssistTarget(
            actionText = "Skip ads",
            assistTarget = "Skip ad"
        )

        assertTrue(matches)
    }
}
