package com.example.aiassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReactorTest {

    @Test
    fun keywordsMatch_halfOrMoreKeywordsPresent_returnsTrue() {
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "sponsored,visit advertiser,skip",
            screenText = listOf("Sponsored content", "Tap to Skip")
        )

        assertTrue(matched)
    }

    @Test
    fun keywordsMatch_noKeywordsPresent_returnsFalse() {
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "allow,continue",
            screenText = listOf("Purchase now", "Delete")
        )

        assertFalse(matched)
    }

    @Test
    fun keywordsMatch_emptyStoredKeywords_returnsFalse() {
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "",
            screenText = listOf("Allow")
        )

        assertFalse(matched)
    }

    @Test
    fun keywordsMatch_caseInsensitiveMatching() {
        // Stored keyword casing shouldn't have to match screen casing.
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "SPONSORED,SKIP",
            screenText = listOf("sponsored ad", "tap to skip")
        )

        assertTrue(matched)
    }

    @Test
    fun keywordsMatch_singleKeywordMustMatch() {
        // With 1 stored keyword, coerceAtLeast(1) requires exactly 1 match.
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "sponsored",
            screenText = listOf("Sponsored content")
        )

        assertTrue(matched)
    }

    @Test
    fun keywordsMatch_singleKeywordMissing_returnsFalse() {
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "sponsored",
            screenText = listOf("Home", "Settings")
        )

        assertFalse(matched)
    }

    @Test
    fun keywordsMatch_blankEntriesIgnored() {
        // Trailing/empty split segments shouldn't be counted against the required match threshold.
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "sponsored,,, ",
            screenText = listOf("Sponsored content")
        )

        assertTrue(matched)
    }

    @Test
    fun keywordsMatch_fourKeywordsNeedsTwo() {
        // size / 2 = 2 for four keywords; one match is not enough.
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "alpha,beta,gamma,delta",
            screenText = listOf("alpha only")
        )

        assertFalse(matched)
    }

    @Test
    fun keywordsMatch_fourKeywordsWithTwoPresent_returnsTrue() {
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "alpha,beta,gamma,delta",
            screenText = listOf("alpha signal", "beta reading")
        )

        assertTrue(matched)
    }

    @Test
    fun keywordsMatch_emptyScreenText_returnsFalse() {
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "sponsored,skip",
            screenText = emptyList()
        )

        assertFalse(matched)
    }

    @Test
    fun keywordsMatch_substringMatchAllowed() {
        // keywordsMatch uses `contains`, so a stored keyword is allowed to be a
        // substring of a screen element (e.g. "skip" inside "Tap to Skip Now").
        val matched = ScreenReactor.keywordsMatch(
            storedKeywords = "skip",
            screenText = listOf("Tap to Skip Now")
        )

        assertTrue(matched)
    }
}
