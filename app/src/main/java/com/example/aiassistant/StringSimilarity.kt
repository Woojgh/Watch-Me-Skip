package com.example.aiassistant

/**
 * String similarity utilities used for fuzzy matching in PatternMatcher.
 * All methods are pure functions with no Android dependencies, making them
 * straightforward to unit-test.
 */
object StringSimilarity {

    /**
     * Computes Levenshtein (edit) distance between two strings.
     * Returns 0 for identical strings; higher values indicate more differences.
     */
    fun levenshtein(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,           // deletion
                    dp[i][j - 1] + 1,           // insertion
                    dp[i - 1][j - 1] + cost     // substitution
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    /**
     * Jaro similarity between two strings. Returns a value in [0.0, 1.0];
     * 1.0 means the strings are identical.
     */
    fun jaro(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        // Characters within this window are considered potential matches.
        val matchWindow = maxOf(s1.length, s2.length) / 2 - 1
        if (matchWindow < 0) return 0.0

        val s1Matched = BooleanArray(s1.length)
        val s2Matched = BooleanArray(s2.length)
        var matches = 0

        for (i in s1.indices) {
            val start = maxOf(0, i - matchWindow)
            val end = minOf(i + matchWindow + 1, s2.length)
            for (j in start until end) {
                if (s2Matched[j] || s1[i] != s2[j]) continue
                s1Matched[i] = true
                s2Matched[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        // Count transpositions: matched characters that are not in the same order.
        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (!s1Matched[i]) continue
            while (!s2Matched[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        return (
            matches.toDouble() / s1.length +
            matches.toDouble() / s2.length +
            (matches - transpositions / 2.0) / matches
        ) / 3.0
    }

    /**
     * Jaro-Winkler similarity. Gives extra weight to strings sharing a common prefix
     * (up to 4 characters). Returns a value in [0.0, 1.0].
     *
     * Well-suited for matching short UI button labels where a shared prefix is a
     * strong indicator of semantic similarity (e.g. "Allow" vs "Allow Once").
     */
    fun jaroWinkler(s1: String, s2: String): Double {
        val jaroScore = jaro(s1, s2)
        val prefixLength = (0 until minOf(s1.length, s2.length, 4))
            .takeWhile { s1[it] == s2[it] }
            .count()
        // Standard Winkler scaling factor is 0.1.
        return jaroScore + prefixLength * 0.1 * (1.0 - jaroScore)
    }

    /**
     * Returns true if the Jaro-Winkler similarity of [s1] and [s2] meets [threshold].
     * Comparison is case-insensitive.
     * The default threshold of 0.85 is a good balance for UI label matching:
     * it accepts minor typos / truncations while rejecting unrelated strings.
     */
    fun isSimilar(s1: String, s2: String, threshold: Double = 0.85): Boolean =
        jaroWinkler(s1.lowercase(), s2.lowercase()) >= threshold
}
