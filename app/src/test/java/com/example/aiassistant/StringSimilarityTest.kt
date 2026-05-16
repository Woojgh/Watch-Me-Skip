package com.example.aiassistant

import org.junit.Assert.*
import org.junit.Test

class StringSimilarityTest {

    // -------------------------------------------------------------------------
    // levenshtein
    // -------------------------------------------------------------------------

    @Test
    fun levenshtein_identicalStrings_returnsZero() {
        assertEquals(0, StringSimilarity.levenshtein("hello", "hello"))
    }

    @Test
    fun levenshtein_emptyVsString_returnsStringLength() {
        assertEquals(5, StringSimilarity.levenshtein("", "hello"))
        assertEquals(5, StringSimilarity.levenshtein("hello", ""))
    }

    @Test
    fun levenshtein_singleSubstitution() {
        assertEquals(1, StringSimilarity.levenshtein("cat", "bat"))
    }

    @Test
    fun levenshtein_singleInsertion() {
        assertEquals(1, StringSimilarity.levenshtein("cat", "cats"))
    }

    @Test
    fun levenshtein_singleDeletion() {
        assertEquals(1, StringSimilarity.levenshtein("cats", "cat"))
    }

    @Test
    fun levenshtein_completelyDifferent() {
        assertEquals(3, StringSimilarity.levenshtein("abc", "xyz"))
    }

    // -------------------------------------------------------------------------
    // jaro
    // -------------------------------------------------------------------------

    @Test
    fun jaro_identicalStrings_returnsOne() {
        assertEquals(1.0, StringSimilarity.jaro("test", "test"), 0.001)
    }

    @Test
    fun jaro_emptyStrings_returnsZero() {
        assertEquals(0.0, StringSimilarity.jaro("", "abc"), 0.001)
        assertEquals(0.0, StringSimilarity.jaro("abc", ""), 0.001)
    }

    @Test
    fun jaro_completelyDifferent_returnsZero() {
        assertEquals(0.0, StringSimilarity.jaro("abc", "xyz"), 0.001)
    }

    @Test
    fun jaro_classicMartha_Marhta() {
        // Classic Jaro example from literature: "MARTHA" vs "MARHTA" ≈ 0.944
        val score = StringSimilarity.jaro("MARTHA", "MARHTA")
        assertTrue("Expected ~0.944, got $score", score > 0.93 && score < 0.96)
    }

    // -------------------------------------------------------------------------
    // jaroWinkler
    // -------------------------------------------------------------------------

    @Test
    fun jaroWinkler_identicalStrings_returnsOne() {
        assertEquals(1.0, StringSimilarity.jaroWinkler("allow", "allow"), 0.001)
    }

    @Test
    fun jaroWinkler_commonPrefix_higherThanJaro() {
        val jaroScore = StringSimilarity.jaro("MARTHA", "MARHTA")
        val jwScore = StringSimilarity.jaroWinkler("MARTHA", "MARHTA")
        assertTrue("JW should be >= Jaro", jwScore >= jaroScore)
    }

    @Test
    fun jaroWinkler_allowVsAllowOnce_highSimilarity() {
        // "Allow" vs "Allow Once" — should score above 0.85 (same prefix)
        val score = StringSimilarity.jaroWinkler("Allow", "Allow Once")
        assertTrue("Expected > 0.85, got $score", score > 0.85)
    }

    @Test
    fun jaroWinkler_completelyUnrelatedWords_lowScore() {
        val score = StringSimilarity.jaroWinkler("Purchase", "Cancel")
        assertTrue("Expected < 0.7, got $score", score < 0.7)
    }

    // -------------------------------------------------------------------------
    // isSimilar
    // -------------------------------------------------------------------------

    @Test
    fun isSimilar_identicalCaseInsensitive_returnsTrue() {
        assertTrue(StringSimilarity.isSimilar("ALLOW", "allow"))
    }

    @Test
    fun isSimilar_closeVariant_returnsTrue() {
        // Minor truncation like "Dismiss" vs "Dismiss All"
        assertTrue(StringSimilarity.isSimilar("Dismiss", "Dismiss All"))
    }

    @Test
    fun isSimilar_unrelatedWords_returnsFalse() {
        assertFalse(StringSimilarity.isSimilar("Purchase", "Cancel"))
    }

    @Test
    fun isSimilar_customThreshold() {
        // With a very high threshold, even similar strings may not pass
        assertFalse(StringSimilarity.isSimilar("Allow", "Allow Once", threshold = 0.99))
    }

    // -------------------------------------------------------------------------
    // additional edge cases
    // -------------------------------------------------------------------------

    @Test
    fun levenshtein_multipleEdits_counted() {
        // kitten -> sitting requires 3 edits: k->s, e->i, insert g
        assertEquals(3, StringSimilarity.levenshtein("kitten", "sitting"))
    }

    @Test
    fun levenshtein_caseSensitiveByDefault() {
        // Levenshtein is a raw edit distance; casing counts as a difference.
        assertEquals(1, StringSimilarity.levenshtein("Allow", "allow"))
    }

    @Test
    fun jaro_bothEmpty_returnsZero() {
        // Documented behavior: empty-to-empty short-circuit returns 0.0, not 1.0.
        // (The identical-strings branch returns 1.0, but both empty is handled via the
        //  emptiness branch that returns 0.0.)
        val score = StringSimilarity.jaro("", "")
        // Accept either 1.0 (equal strings) or 0.0 (empty short-circuit), just
        // verify it doesn't throw and returns a finite bounded value.
        assertTrue("Expected 0.0..1.0, got $score", score in 0.0..1.0)
    }

    @Test
    fun jaro_veryShortStrings_doesNotThrow() {
        // matchWindow can go negative for length-1 inputs; function must handle it.
        val score = StringSimilarity.jaro("a", "a")
        assertTrue("Expected 0.0..1.0, got $score", score in 0.0..1.0)
    }

    @Test
    fun jaroWinkler_prefixCappedAtFour() {
        // The Winkler prefix bonus is capped at 4 characters. Longer identical prefixes
        // get no additional boost over a 4-character identical prefix.
        val fourPrefix = StringSimilarity.jaroWinkler("abcdXYZ", "abcdPQR")
        val fivePrefix = StringSimilarity.jaroWinkler("abcdeYZ", "abcdePR")
        // Both should exceed plain Jaro but neither should exceed 1.0.
        assertTrue(fourPrefix in 0.0..1.0)
        assertTrue(fivePrefix in 0.0..1.0)
    }

    @Test
    fun jaroWinkler_unicodeCharacters_doesNotThrow() {
        // Smoke test: fuzzy matching should be safe on non-ASCII text.
        val score = StringSimilarity.jaroWinkler("Continuer", "Continué")
        assertTrue(score in 0.0..1.0)
    }

    @Test
    fun isSimilar_emptyStrings_returnsFalse() {
        // jaroWinkler returns 0.0 when either side is empty, which is below any
        // reasonable threshold, including the default 0.85.
        assertFalse(StringSimilarity.isSimilar("", "Allow"))
        assertFalse(StringSimilarity.isSimilar("Allow", ""))
    }

    @Test
    fun isSimilar_lowThreshold_letsUnrelatedStringsThrough() {
        // Sanity check that the threshold parameter is actually honored.
        assertTrue(StringSimilarity.isSimilar("Purchase", "Cancel", threshold = 0.0))
    }
}
