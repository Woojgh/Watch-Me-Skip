package com.example.aiassistant

import android.accessibilityservice.AccessibilityService
import android.content.Context

/**
 * Orchestrates the assist pipeline:
 * cooldown -> app exclusion -> pattern match -> safety check -> execute -> log -> overlay
 */
object AssistEngine {

    /**
     * Checks whether there is a safe, confident action to take on this screen
     * WITHOUT executing it. Used to generate a preview before the idle delay fires.
     *
     * Returns the matching command, or null if nothing should be done.
     */
    suspend fun findPendingAction(
        context: Context,
        snapshot: ScreenSnapshot
    ): ActionCommand? {
        // App exclusion
        if (!SafetyChecker.isAppAllowed(context, snapshot.packageName)) return null

        // Find best action from patterns or rules
        val command = PatternMatcher.findBestAction(context, snapshot) ?: return null

        // Safety check — log blocked actions for auditability
        if (!SafetyChecker.isActionSafe(command)) {
            DatabaseHelper.logAction(
                context = context,
                packageName = snapshot.packageName,
                state = snapshot.stableState,
                actionType = command.type.name,
                actionDetail = "[BLOCKED] ${command.target}",
                success = false
            )
            return null
        }

        return command
    }

    /**
     * Executes a command that was pre-validated by [findPendingAction].
     * Handles the action cooldown, execution, logging, and overlay update.
     *
     * Returns the command on success, null if cooldown blocked it or execution failed.
     */
    suspend fun executeAction(
        service: AccessibilityService,
        context: Context,
        snapshot: ScreenSnapshot,
        command: ActionCommand
    ): ActionCommand? {
        // Cooldown guard (re-checked at execution time, not preview time)
        if (!SafetyChecker.checkCooldown()) return null

        val success = ActionExecutor.executeSafe(service, command)
        SafetyChecker.recordActionTime()

        DatabaseHelper.logAction(
            context = context,
            packageName = snapshot.packageName,
            state = snapshot.stableState,
            actionType = command.type.name,
            actionDetail = command.target,
            success = success
        )

        if (success) OverlayService.updateStatus("${command.type.name}: ${command.target}")

        return if (success) command else null
    }

    /**
     * Convenience wrapper: find + execute in one call.
     * Used when no preview is needed (e.g. direct calls from tests or legacy paths).
     */
    suspend fun handleScreen(
        service: AccessibilityService,
        context: Context,
        snapshot: ScreenSnapshot
    ): ActionCommand? {
        val command = findPendingAction(context, snapshot) ?: return null
        return executeAction(service, context, snapshot, command)
    }
}
