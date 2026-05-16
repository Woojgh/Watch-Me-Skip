package com.example.aiassistant

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

object SafetyChecker {

    private const val PREFS = "ai_assistant_prefs"
    private const val KEY_EXCLUDED_APPS = "excluded_apps"
    private const val COOLDOWN_MS = 800L

    /**
     * AtomicLong ensures lock-free, thread-safe read/write from multiple coroutines
     * without the overhead of synchronized blocks.
     */
    private val lastActionTime = AtomicLong(0L)

    /**
     * Dangerous keywords — never auto-click elements containing these.
     */
    private val blockedKeywords = setOf(
        "purchase", "buy", "pay", "payment", "checkout",
        "delete", "remove", "uninstall", "format", "erase", "wipe",
        "send money", "transfer funds", "confirm payment", "place order",
        "reset", "factory reset", "clear data",
        "subscribe", "upgrade plan", "billing"
    )

    /**
     * Returns true if enough time has passed since the last action.
     */
    fun checkCooldown(): Boolean {
        return (System.currentTimeMillis() - lastActionTime.get()) >= COOLDOWN_MS
    }

    fun recordActionTime() {
        lastActionTime.set(System.currentTimeMillis())
    }

    /**
     * Returns true if the action target does NOT contain any blocked keywords.
     */
    fun isActionSafe(command: ActionCommand): Boolean {
        return !containsDangerousText(command.target)
    }

    internal fun containsDangerousText(target: String): Boolean {
        val normalized = target.lowercase().trim()
        if (normalized.isEmpty()) return false

        return blockedKeywords.any { keyword ->
            val regex = Regex("\\b${Regex.escape(keyword)}\\b")
            regex.containsMatchIn(normalized)
        }
    }

    /**
     * Returns true if the app is NOT in the exclusion list.
     */
    fun isAppAllowed(context: Context, packageName: String): Boolean {
        val excluded = getExcludedApps(context)
        return packageName !in excluded
    }

    fun getExcludedApps(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()
    }

    fun setExcludedApps(context: Context, apps: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_EXCLUDED_APPS, apps).apply()
    }

    fun addExcludedApp(context: Context, packageName: String) {
        val current = getExcludedApps(context).toMutableSet()
        current.add(packageName)
        setExcludedApps(context, current)
    }

    fun removeExcludedApp(context: Context, packageName: String) {
        val current = getExcludedApps(context).toMutableSet()
        current.remove(packageName)
        setExcludedApps(context, current)
    }

    /**
     * Removes multiple apps from the exclusion list in one write.
     * Used when resetting learned patterns so old block flags don't linger.
     */
    fun removeExcludedApps(context: Context, packageNames: Set<String>) {
        if (packageNames.isEmpty()) return
        val current = getExcludedApps(context).toMutableSet()
        if (current.removeAll(packageNames)) {
            setExcludedApps(context, current)
        }
    }
}
