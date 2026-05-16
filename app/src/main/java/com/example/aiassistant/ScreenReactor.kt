package com.example.aiassistant

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * On each screen change, checks if any learned system-setting patterns
 * match the current screen context. In ASSIST mode, applies the setting
 * change automatically if confidence is high enough.
 */
object ScreenReactor {

    /** Minimum observations before auto-applying a system change. */
    private const val MIN_CONFIDENCE = 3

    /**
     * Minimum ms between any two automatic system-setting changes.
     * Prevents rapid-fire volume/brightness flickers on screens with many patterns.
     */
    private const val SYSTEM_COOLDOWN_MS = 2_000L
    private val lastSystemActionTime = AtomicLong(0L)

    /**
     * Check the current screen against learned system patterns.
     * Only takes action in ASSIST mode.
     */
    suspend fun checkAndReact(
        context: Context,
        snapshot: ScreenSnapshot,
        mode: AgentMode
    ) {
        // Only react in ASSIST mode
        if (mode != AgentMode.ASSIST) return

        // Respect app exclusions
        if (!SafetyChecker.isAppAllowed(context, snapshot.packageName)) return

        val dao = DatabaseHelper.getDB(context).systemPatternDao()
        // Query by both strict and loose state so old and new patterns both match.
        val strict = dao.getByScreenState(snapshot.stableState)
        val loose = if (snapshot.looseState != snapshot.stableState)
            dao.getByScreenState(snapshot.looseState) else emptyList()
        val seenIds = mutableSetOf<Int>()
        val patterns = (strict + loose).filter { seenIds.add(it.id) }

        for (pattern in patterns) {
            // Need enough confidence
            if (pattern.count < MIN_CONFIDENCE) continue

            // Verify the keywords that were present during learning are still on screen
            if (!keywordsMatch(pattern.matchedKeywords, snapshot.textElements)) continue

            // Rate-limit system changes to prevent flickering.
            val now = System.currentTimeMillis()
            if (now - lastSystemActionTime.get() < SYSTEM_COOLDOWN_MS) continue
            lastSystemActionTime.set(now)

            // Apply the system change
            val success = SystemActionExecutor.apply(context, pattern.settingName, pattern.newValue)

            // Log it
            DatabaseHelper.logAction(
                context = context,
                packageName = snapshot.packageName,
                state = snapshot.stableState,
                actionType = "SYSTEM:${pattern.settingName}",
                actionDetail = "${pattern.oldValue} → ${pattern.newValue}",
                success = success
            )

            if (success) {
                OverlayService.updateStatus("${pattern.settingName}: ${pattern.newValue}")
            }
        }
    }

    /**
     * Check that at least half of the learned keywords are present on the current screen.
     * This handles minor screen changes while still requiring meaningful context match.
     */
    internal fun keywordsMatch(storedKeywords: String, screenText: List<String>): Boolean {
        val keywords = storedKeywords.split(",").filter { it.isNotBlank() }
        if (keywords.isEmpty()) return false

        val screenLower = screenText.map { it.lowercase() }
        val matchCount = keywords.count { kw ->
            screenLower.any { it.contains(kw.lowercase()) }
        }

        return matchCount >= (keywords.size / 2).coerceAtLeast(1)
    }
}
