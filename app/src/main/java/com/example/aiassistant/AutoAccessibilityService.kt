package com.example.aiassistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

enum class AgentMode { OFF, OBSERVE, ASSIST, REMOTE_SKIP }

class AutoAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * WeakReference prevents a memory leak if the service is destroyed but something
         * still holds a reference to this companion object. External callers treat
         * `instance` as a regular nullable — no API change required.
         */
        private var _instanceRef: WeakReference<AutoAccessibilityService>? = null
        val instance: AutoAccessibilityService?
            get() = _instanceRef?.get()

        private const val PREFS = "ai_assistant_prefs"
        private const val KEY_MODE = "agent_mode"

        /** Latest screen snapshot — read by SystemStateMonitor for context correlation. */
        @Volatile
        var lastSnapshot: ScreenSnapshot? = null

        /** Cached mode — avoids reading SharedPreferences on every event. */
        @Volatile
        var cachedMode: AgentMode = AgentMode.OFF
            private set

        fun getMode(context: Context): AgentMode {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, AgentMode.OFF.name) ?: AgentMode.OFF.name
            val mode = try { AgentMode.valueOf(name) } catch (_: Exception) { AgentMode.OFF }
            cachedMode = mode
            return mode
        }

        fun setMode(context: Context, mode: AgentMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode.name).apply()
            cachedMode = mode
            // Invalidate debounce and re-run pipeline immediately so the user
            // doesn't have to navigate away / toggle the accessibility service
            // for the new mode to apply to the screen already on display.
            instance?.onModeChanged(mode)
        }
    }

    private var currentPackage: String? = null
    private var lastScreenState: String? = null
    private var lastSnapshotTime = 0L
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var systemStateMonitor: SystemStateMonitor? = null

    /** Prevents coroutine pileup — skip if previous work is still running. */
    private val observeBusy = AtomicBoolean(false)
    private val assistBusy = AtomicBoolean(false)

    /** Minimum ms between snapshot builds (applies to both modes). */
    private val snapshotCooldownMs = 500L

    /**
     * How long the user must be idle (no taps/scrolls/text input) before the
     * assistant acts in Assist mode. Prevents the assistant from firing while
     * the user is actively using the screen.
     */
    private val userIdleThresholdMs = 1_500L

    /** Timestamp of the last user-initiated interaction event (all modes). */
    @Volatile private var lastUserInteractionTime = 0L

    /** Pending debounced jobs — cancelled and rescheduled on each new screen change. */
    private var pendingAssistJob: Job? = null
    private var pendingReactorJob: Job? = null

    /**
     * How long after an assistant-executed action to treat incoming accessibility
     * events as assistant-generated rather than user-generated. This covers both
     * the immediate TYPE_VIEW_CLICKED echo and any slightly-delayed follow-up
     * events (e.g. TYPE_VIEW_SCROLLED from a click that triggers a list scroll).
     * Needs to be >= userIdleThresholdMs so the following pendingAssistJob's
     * idle-check can't be accidentally tripped by the assistant's own activity.
     */
    private val assistActionSuppressMs = 1_500L

    /**
     * Set just before the assistant executes an action so that the observation
     * block doesn't record the assistant's own accessibility events as patterns,
     * and so the idle timer isn't reset by the assistant's own clicks.
     */
    @Volatile private var suppressLearningUntil = 0L

    /**
     * Tracks the last action the assistant took. If the user interacts with the
     * same screen within [correctionWindowMs], it's treated as a correction and
     * that pattern's confidence is decremented.
     */
    @Volatile private var lastAssistActionState: String? = null
    @Volatile private var lastAssistActionTarget: String? = null
    @Volatile private var lastAssistActionTime = 0L
    private val correctionWindowMs = 3_000L

    /**
     * Grace period after [suppressLearningUntil] expires during which an
     * incoming WINDOW_CONTENT_CHANGED is still treated as the screen settling
     * from our action and extends the window. Without this, slow animations
     * or chained UI updates would slip past the original 1500ms guess and
     * poison [lastUserInteractionTime].
     */
    private val assistSettleGraceMs = 750L
    private val assistSettleExtensionMs = 750L

    /**
     * Watchdog cadence — periodically force a re-evaluation if we appear
     * dormant. Without this, a single failed idle check on a screen whose
     * looseState never changes again would leave the assist pipeline stuck
     * until the user toggles the accessibility service.
     */
    private val watchdogIntervalMs = 8_000L
    @Volatile private var lastAssistAttemptTime = 0L
    private var watchdogJob: Job? = null

    override fun onServiceConnected() {
        _instanceRef = WeakReference(this)
        // Load cached mode from prefs once at startup
        getMode(this)
        // Start monitoring system setting changes
        systemStateMonitor = SystemStateMonitor(this).also { it.start() }
        // Notify overlay so the bubble colour updates immediately
        OverlayService.refreshServiceState()
        // Decay patterns that haven't been observed in 30+ days.
        scope.launch {
            try {
                DatabaseHelper.decayOldPatterns(this@AutoAccessibilityService)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Logger.logError("Pattern decay failed: ${e.message}")
            }
        }
        startWatchdog()
        kickstartModePipelineOnConnect()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Use cached mode — no disk I/O
        val mode = cachedMode
        if (mode == AgentMode.OFF) return

        // Track current package
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentPackage = event.packageName?.toString()
        }

        // --- INTERACTION TRACKING: update idle timer in all modes ---
        // This must run in both OBSERVE and ASSIST so the idle-delay logic works.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            val interactionTime = System.currentTimeMillis()
            // Extract event data NOW, before the event is recycled by Android.
            // AccessibilityEvent objects are reused after onAccessibilityEvent returns.
            val eventType = event.eventType
            val actionText = extractEventText(event)

            // If the assistant just executed an action, the CLICKED/SCROLLED
            // events we're seeing were generated by the assistant itself, NOT
            // the user. Counting them as user interaction would continuously
            // push lastUserInteractionTime forward, causing every subsequent
            // pendingAssistJob to abort at its idle check and making the
            // service appear stuck until it's manually restarted.
            val assistActing = interactionTime <= suppressLearningUntil
            // Only CLICKED / TEXT_CHANGED reliably mean "the user did something".
            // SCROLLED is too noisy: animations, RecyclerView prefetch, fling
            // settling from our own click, and feed auto-refresh all emit it.
            // Trusting SCROLLED for the idle timer is what causes the freeze:
            // late echo scrolls slip past the assistActing window, bump
            // lastUserInteractionTime forever, and starve every subsequent
            // pendingAssistJob at its idle check.
            val isReliableUserInput =
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            if (!assistActing && isReliableUserInput) {
                lastUserInteractionTime = interactionTime
            }

            // --- OBSERVATION: record user-initiated actions ---
            // Active in OBSERVE mode always. Active in ASSIST mode unless the
            // assistant is currently executing (to avoid recording its own events).
            if (!assistActing && (mode == AgentMode.OBSERVE || mode == AgentMode.ASSIST)) {
                val pkg = currentPackage ?: return
                val state = lastScreenState ?: return

                // --- FEEDBACK LOOP: correction detection ---
                // If the user acts on the same screen the assistant just acted on,
                // within the correction window, treat it as negative feedback.
                if (mode == AgentMode.ASSIST) {
                    val assistState = lastAssistActionState
                    val assistTarget = lastAssistActionTarget
                    val shouldRecordCorrection = AssistCorrectionHeuristics.shouldRecordCorrection(
                        isClickEvent = eventType == AccessibilityEvent.TYPE_VIEW_CLICKED,
                        actionText = actionText,
                        currentState = state,
                        assistState = assistState,
                        assistTarget = assistTarget,
                        timeSinceAssistMs = interactionTime - lastAssistActionTime,
                        correctionWindowMs = correctionWindowMs
                    )
                    if (shouldRecordCorrection && assistState != null && assistTarget != null) {
                        // Clear immediately so rapid taps only count once.
                        lastAssistActionState = null
                        lastAssistActionTarget = null
                        scope.launch {
                            try {
                                DatabaseHelper.recordCorrection(
                                    context = this@AutoAccessibilityService,
                                    state = assistState,
                                    actionText = assistTarget
                                )
                                DatabaseHelper.logAction(
                                    context = this@AutoAccessibilityService,
                                    packageName = pkg,
                                    state = assistState,
                                    actionType = "CORRECTION",
                                    actionDetail = "[-1] $assistTarget",
                                    success = true
                                )
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (e: Exception) {
                                Logger.logError("Correction recording failed: ${e.message}")
                            }
                        }
                    }
                }

                if (!actionText.isNullOrBlank() && observeBusy.compareAndSet(false, true)) {
                    scope.launch {
                        try {
                            Observer.onUserActionDirect(
                                context = this@AutoAccessibilityService,
                                actionText = actionText,
                                eventType = eventType,
                                currentState = state,
                                packageName = pkg
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            Logger.logError("Observer.onUserActionDirect failed: ${e.message}")
                        } finally {
                            observeBusy.set(false)
                        }
                    }
                }
            }
        }

        // --- SCREEN CHANGE: snapshot + assist/remote-skip ---
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            val now = System.currentTimeMillis()

            // While the screen is still settling from our own action, keep
            // pushing the suppress window forward. WINDOW_CONTENT_CHANGED
            // arriving inside (or just after) the original window is much
            // more likely an echo than fresh user activity, so we widen the
            // window automatically rather than relying on a fixed 1500ms
            // guess that fails on slow animations or chained UI updates.
            if (suppressLearningUntil != 0L &&
                now - suppressLearningUntil < assistSettleGraceMs
            ) {
                suppressLearningUntil = now + assistSettleExtensionMs
            }

            // Throttle snapshot builds for both modes
            if (now - lastSnapshotTime < snapshotCooldownMs) return
            lastSnapshotTime = now

            val root = rootInActiveWindow ?: return
            val pkg = currentPackage

            // Build snapshot synchronously (fixes stale-node issue)
            val snapshot = try {
                UIUtils.snapshotScreen(pkg, root)
            } catch (_: Exception) {
                return
            }

            // Update lastSnapshot for SystemStateMonitor context correlation
            lastSnapshot = snapshot

            // Debounce on looseState — ignore changes that only affect non-interactable nodes
            // (e.g. a new ad banner appearing doesn't force a new assist cycle).
            if (snapshot.looseState == lastScreenState) return
            lastScreenState = snapshot.looseState
            RemoteSkipController.onScreenChanged(this, snapshot)

            // Check system patterns and run assist engine after user goes idle.
            // Cancel any previously pending action so we always wait for a fresh
            // idle period from the most recent screen change.
            scheduleReactor(snapshot, mode, "ScreenReactor.checkAndReact failed")

            // Only run action pipelines in modes that can act.
            if (mode == AgentMode.ASSIST) {
                schedulePendingAssist(snapshot, "AssistEngine.executeAction failed")
            } else if (mode == AgentMode.REMOTE_SKIP) {
                schedulePendingRemoteSkipOnly(snapshot, "Remote skip detection failed")
            }
        }
    }
    /**
     * Schedule a debounced remote-only pass that looks for visible "Skip" targets
     * and prompts watch/earbud confirmation, without running observe/assist logic.
     */
    private fun schedulePendingRemoteSkipOnly(snapshot: ScreenSnapshot, errorTag: String) {
        pendingAssistJob?.cancel()
        pendingAssistJob = scope.launch {
            try {
                if (!RemoteSkipController.isRemoteSkipEnabled(this@AutoAccessibilityService)) return@launch
                if (!SafetyChecker.isAppAllowed(this@AutoAccessibilityService, snapshot.packageName)) return@launch
                val pending = RemoteSkipController.findSkipCommand(snapshot) ?: return@launch
                if (!SafetyChecker.isActionSafe(pending)) return@launch

                OverlayService.showPendingAction(pending)

                delay(userIdleThresholdMs)
                // Secondary guard: abort if the user interacted during the delay.
                if (System.currentTimeMillis() - lastUserInteractionTime < userIdleThresholdMs) return@launch
                if (cachedMode != AgentMode.REMOTE_SKIP) return@launch

                lastAssistAttemptTime = System.currentTimeMillis()
                DatabaseHelper.logAction(
                    context = this@AutoAccessibilityService,
                    packageName = snapshot.packageName,
                    state = snapshot.stableState,
                    actionType = "${pending.type.name}:REMOTE_PENDING",
                    actionDetail = "[REMOTE_ONLY] ${pending.target}",
                    success = true
                )
                RemoteSkipController.requestRemoteSkip(
                    context = this@AutoAccessibilityService,
                    snapshot = snapshot,
                    command = pending
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Logger.logError("$errorTag: ${e.message}")
            } finally {
                // Always clear the bubble preview — whether prompted, cancelled, or errored.
                OverlayService.clearPendingAction()
            }
        }
    }

    /**
     * Called when the user toggles modes while the service
     * is already running. Without this, the stableState debounce in
     * onAccessibilityEvent suppresses re-processing of the current screen,
     * making it look like ASSIST "isn't doing anything" until the next screen
     * change — the exact symptom that disappeared after restarting the service.
     */
    fun onModeChanged(newMode: AgentMode) {
        // Reset debounce so the next accessibility event will reprocess.
        lastScreenState = null
        lastSnapshotTime = 0L

        // Cancel any assist/reactor actions pending from the previous mode.
        pendingAssistJob?.cancel()
        pendingAssistJob = null
        pendingReactorJob?.cancel()
        pendingReactorJob = null
        if (newMode != AgentMode.ASSIST && newMode != AgentMode.REMOTE_SKIP) {
            RemoteSkipController.clearPending(this, "mode-change")
        }

        if (newMode == AgentMode.OFF) return

        // Proactively run the new-mode pipeline on the screen already showing.
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val pkg = currentPackage
        val snapshot = try {
            UIUtils.snapshotScreen(pkg, root)
        } catch (_: Exception) {
            return
        }

        lastSnapshot = snapshot
        // Use looseState here for consistency with the screen-change handler,
        // which compares against looseState. Mixing stableState and looseState
        // in this field meant the post-mode-change debounce was always
        // unequal (looseState is prefixed with 'L'), effectively a no-op.
        lastScreenState = snapshot.looseState

        // Also apply the idle delay here — the user just tapped the mode toggle,
        // so they're actively interacting. Wait for them to stop before acting.
        scheduleReactor(snapshot, newMode, "ScreenReactor.checkAndReact failed after mode change")

        if (newMode == AgentMode.ASSIST) {
            schedulePendingAssist(snapshot, "AssistEngine.executeAction failed after mode change")
        } else if (newMode == AgentMode.REMOTE_SKIP) {
            schedulePendingRemoteSkipOnly(snapshot, "Remote skip detection failed after mode change")
        }
    }

    /**
     * Schedule a debounced ScreenReactor pass against [snapshot]. Cancels any
     * pending one. Used by onAccessibilityEvent, onModeChanged and the
     * watchdog so behavior stays consistent across all entry points.
     */
    private fun scheduleReactor(snapshot: ScreenSnapshot, mode: AgentMode, errorTag: String) {
        pendingReactorJob?.cancel()
        pendingReactorJob = scope.launch {
            delay(userIdleThresholdMs)
            // Secondary guard: abort if the user interacted during the delay.
            if (System.currentTimeMillis() - lastUserInteractionTime < userIdleThresholdMs) return@launch
            try {
                ScreenReactor.checkAndReact(this@AutoAccessibilityService, snapshot, mode)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Logger.logError("$errorTag: ${e.message}")
            }
        }
    }

    /**
     * Schedule a debounced AssistEngine pass against [snapshot]. Cancels any
     * pending one. Used by onAccessibilityEvent, onModeChanged and the
     * watchdog so behavior stays consistent across all entry points.
     */
    private fun schedulePendingAssist(snapshot: ScreenSnapshot, errorTag: String) {
        pendingAssistJob?.cancel()
        pendingAssistJob = scope.launch {
            try {
                // Peek at what action we'd take NOW (before the idle delay)
                // so we can show a preview the user can cancel.
                val pending = AssistEngine.findPendingAction(
                    context = this@AutoAccessibilityService,
                    snapshot = snapshot
                ) ?: return@launch

                OverlayService.showPendingAction(pending)

                delay(userIdleThresholdMs)
                // Secondary guard: abort if the user interacted during the delay.
                if (System.currentTimeMillis() - lastUserInteractionTime < userIdleThresholdMs) return@launch
                if (RemoteSkipController.shouldRequireRemoteConfirmation(this@AutoAccessibilityService, pending)) {
                    lastAssistAttemptTime = System.currentTimeMillis()
                    DatabaseHelper.logAction(
                        context = this@AutoAccessibilityService,
                        packageName = snapshot.packageName,
                        state = snapshot.stableState,
                        actionType = "${pending.type.name}:REMOTE_PENDING",
                        actionDetail = pending.target,
                        success = true
                    )
                    RemoteSkipController.requestRemoteSkip(
                        context = this@AutoAccessibilityService,
                        snapshot = snapshot,
                        command = pending
                    )
                    return@launch
                }

                if (assistBusy.compareAndSet(false, true)) {
                    try {
                        lastAssistAttemptTime = System.currentTimeMillis()
                        suppressLearningUntil = lastAssistAttemptTime + assistActionSuppressMs
                        val executed = AssistEngine.executeAction(
                            service = this@AutoAccessibilityService,
                            context = this@AutoAccessibilityService,
                            snapshot = snapshot,
                            command = pending
                        )
                        if (executed != null) {
                            lastAssistActionState = snapshot.looseState
                            lastAssistActionTarget = executed.target
                            lastAssistActionTime = System.currentTimeMillis()
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Logger.logError("$errorTag: ${e.message}")
                    } finally {
                        assistBusy.set(false)
                    }
                }
            } finally {
                // Always clear the bubble preview — whether executed, cancelled, or errored.
                OverlayService.clearPendingAction()
            }
        }
    }

    /**
     * Periodic safety net. If the assist pipeline appears dormant — no recent
     * user input, no recent assist attempt, and no looseState change to wake
     * the normal pipeline — re-snapshot the current screen and run the
     * pipeline once. This breaks the deadlock where a single failed idle
     * check would otherwise persist until the service is restarted.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(watchdogIntervalMs)
                if (cachedMode == AgentMode.OFF) continue
                val now = System.currentTimeMillis()
                // Skip if the user is actively interacting.
                if (now - lastUserInteractionTime < userIdleThresholdMs) continue
                // Skip if a pipeline pass already ran recently.
                if (now - lastAssistAttemptTime < watchdogIntervalMs) continue
                if (now - lastSnapshotTime < watchdogIntervalMs) continue
                forceReevaluateCurrentScreen()
            }
        }
    }

    /**
     * On some devices, a usable root window arrives shortly after service connect.
     * Retry mode bootstrap a few times so prompts start without manual restarts.
     */
    private fun kickstartModePipelineOnConnect() {
        scope.launch {
            repeat(3) {
                delay(700L)
                val mode = cachedMode
                if (mode == AgentMode.OFF) return@launch
                onModeChanged(mode)
                if (lastSnapshot != null) return@launch
            }
        }
    }

    private fun forceReevaluateCurrentScreen() {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val pkg = currentPackage
        val snapshot = try {
            UIUtils.snapshotScreen(pkg, root)
        } catch (_: Exception) {
            return
        }
        lastSnapshot = snapshot
        // Clear the looseState debounce so a real event right after this also
        // reprocesses, and update lastSnapshotTime so we don't re-wake again
        // immediately.
        lastScreenState = null
        lastSnapshotTime = System.currentTimeMillis()

        val mode = cachedMode
        if (mode == AgentMode.OFF) return

        scheduleReactor(snapshot, mode, "Watchdog ScreenReactor.checkAndReact failed")
        if (mode == AgentMode.ASSIST) {
            schedulePendingAssist(snapshot, "Watchdog AssistEngine.executeAction failed")
        } else if (mode == AgentMode.REMOTE_SKIP) {
            schedulePendingRemoteSkipOnly(snapshot, "Watchdog remote skip detection failed")
        }
    }

    /**
     * Extract text from the event SYNCHRONOUSLY before Android recycles it.
     */
    private fun extractEventText(event: AccessibilityEvent): String? {
        event.text?.let { texts ->
            val joined = texts.joinToString(" ").trim()
            if (joined.isNotEmpty()) return joined
        }
        event.contentDescription?.toString()?.let {
            if (it.isNotEmpty()) return it
        }
        return null
    }

    /**
     * Called by [OverlayService] when the user taps the bubble during a pending action.
     * Cancels the queued job; the job's finally block clears the overlay preview.
     */
    fun cancelPendingAssist() {
        pendingAssistJob?.cancel()
        pendingAssistJob = null
        RemoteSkipController.clearPending(this, "overlay-cancel")
    }

    /**
     * Executes a remote-confirmed Skip request from watch or earbuds.
     * Re-validates mode, screen context, app safety, and cooldown before clicking.
     */
    fun executeRemoteSkip(request: RemoteSkipRequest, source: String) {
        scope.launch {
            try {
                if (cachedMode != AgentMode.ASSIST && cachedMode != AgentMode.REMOTE_SKIP) return@launch
                if (!SafetyChecker.isAppAllowed(this@AutoAccessibilityService, request.packageName)) return@launch

                val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return@launch
                val liveSnapshot = try {
                    UIUtils.snapshotScreen(currentPackage, root)
                } catch (_: Exception) {
                    null
                } ?: return@launch
                if (liveSnapshot.packageName != request.packageName) {
                    DatabaseHelper.logAction(
                        context = this@AutoAccessibilityService,
                        packageName = request.packageName,
                        state = request.stableState,
                        actionType = "${request.command.type.name}:REMOTE",
                        actionDetail = "[STALE_PACKAGE:$source] ${request.command.target}",
                        success = false
                    )
                    OverlayService.updateStatus("Remote skip expired")
                    return@launch
                }

                val commandToExecute = when {
                    // Picker-chosen targets: use exact label, don't re-scan by keyword
                    request.origin == SkipOrigin.PICKER -> request.command
                    request.command.type == ActionType.CLICK -> RemoteSkipController.findSkipCommand(liveSnapshot)
                    else -> request.command
                }
                if (commandToExecute == null) {
                    DatabaseHelper.logAction(
                        context = this@AutoAccessibilityService,
                        packageName = request.packageName,
                        state = request.stableState,
                        actionType = "${request.command.type.name}:REMOTE",
                        actionDetail = "[NO_SKIP:$source] ${request.command.target}",
                        success = false
                    )
                    OverlayService.updateStatus("Remote skip expired")
                    return@launch
                }

                if (!SafetyChecker.isActionSafe(commandToExecute)) {
                    DatabaseHelper.logAction(
                        context = this@AutoAccessibilityService,
                        packageName = request.packageName,
                        state = request.stableState,
                        actionType = "${commandToExecute.type.name}:REMOTE",
                        actionDetail = "[BLOCKED:$source] ${commandToExecute.target}",
                        success = false
                    )
                    return@launch
                }

                if (!SafetyChecker.checkCooldown()) {
                    DatabaseHelper.logAction(
                        context = this@AutoAccessibilityService,
                        packageName = request.packageName,
                        state = request.stableState,
                        actionType = "${commandToExecute.type.name}:REMOTE",
                        actionDetail = "[COOLDOWN:$source] ${commandToExecute.target}",
                        success = false
                    )
                    return@launch
                }

                lastAssistAttemptTime = System.currentTimeMillis()
                suppressLearningUntil = lastAssistAttemptTime + assistActionSuppressMs
                val success = ActionExecutor.executeSafe(this@AutoAccessibilityService, commandToExecute)
                SafetyChecker.recordActionTime()

                DatabaseHelper.logAction(
                    context = this@AutoAccessibilityService,
                    packageName = request.packageName,
                    state = request.stableState,
                    actionType = "${commandToExecute.type.name}:REMOTE",
                    actionDetail = "[$source] ${commandToExecute.target}",
                    success = success
                )

                if (success) {
                    lastAssistActionState = liveSnapshot.looseState
                    lastAssistActionTarget = commandToExecute.target
                    lastAssistActionTime = System.currentTimeMillis()
                    OverlayService.updateStatus("REMOTE ${commandToExecute.type.name}: ${commandToExecute.target}")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Logger.logError("Remote skip execution failed: ${e.message}")
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        watchdogJob = null
        systemStateMonitor?.stop()
        systemStateMonitor = null
        RemoteSkipController.release(this)
        lastSnapshot = null
        _instanceRef = null
        scope.cancel()
        // Notify overlay so the bubble colour updates immediately
        OverlayService.refreshServiceState()
    }
}
