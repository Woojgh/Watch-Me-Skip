package com.example.aiassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

enum class SkipOrigin { AUTO, PICKER }

data class RemoteSkipRequest(
    val packageName: String,
    val stableState: String,
    val looseState: String,
    val command: ActionCommand,
    val origin: SkipOrigin = SkipOrigin.AUTO,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Clickable button labels split by word count so the picker can show
 * short labels prominently and long labels in a secondary section.
 */
data class CategorizedButtons(
    val primary: List<String>,   // 1–3 words
    val extended: List<String>   // >3 words
) {
    fun isEmpty(): Boolean = primary.isEmpty() && extended.isEmpty()
}

object RemoteSkipController {
    const val ACTION_CONFIRM_REMOTE_SKIP = "com.example.aiassistant.action.CONFIRM_REMOTE_SKIP"
    const val ACTION_DISMISS_REMOTE_SKIP = "com.example.aiassistant.action.DISMISS_REMOTE_SKIP"
    const val ACTION_CONFIRM_REMOTE_SKIP_TEST = "com.example.aiassistant.action.CONFIRM_REMOTE_SKIP_TEST"
    const val ACTION_DISMISS_REMOTE_SKIP_TEST = "com.example.aiassistant.action.DISMISS_REMOTE_SKIP_TEST"
    private const val PREFS = "ai_assistant_prefs"
    private const val KEY_REMOTE_SKIP_CONFIRM_ENABLED = "remote_skip_confirm_enabled"
    private const val KEY_WATCH_SKIP_ENABLED = "watch_skip_enabled"
    private const val KEY_EARBUD_SKIP_ENABLED = "earbud_skip_enabled"

    private const val CHANNEL_ID = "remote_skip_watch_channel_v2"
    private const val NOTIFICATION_ID = 71_204
    private const val TEST_NOTIFICATION_ID = 71_205
    private const val TEST_NOTIFICATION_TIMEOUT_MS = 20_000L
    private const val REQUEST_TIMEOUT_MS = 20_000L
    private const val DISMISSAL_ID_REMOTE_SKIP = "remote_skip_prompt"
    private const val DISMISSAL_ID_REMOTE_SKIP_TEST = "remote_skip_prompt_test"
    private const val SOURCE_WATCH = "WATCH"
    private const val SOURCE_EARBUD = "EARBUD"
    private const val SOURCE_VOICE = "VOICE"
    private val ACTION_TOKENS = listOf("skip", "play now")

    /**
     * Delay before activating the speech recognizer, giving the prompt tone
     * time to finish and Bluetooth audio profiles time to switch from A2DP
     * (media playback) back to SCO (microphone). The voice tone lasts ~760ms;
     * the extra buffer covers BT profile-switch latency.
     */
    private const val VOICE_RECOGNIZER_DELAY_MS = 900L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingRequest: RemoteSkipRequest? = null
    private var timeoutRunnable: Runnable? = null
    private var mediaSession: MediaSession? = null

    // ---- per-method preferences ------------------------------------------------

    fun isWatchSkipEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WATCH_SKIP_ENABLED, true)
    }

    fun isEarbudSkipEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EARBUD_SKIP_ENABLED, true)
    }

    fun setWatchSkipEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WATCH_SKIP_ENABLED, enabled)
            .apply()
    }

    fun setEarbudSkipEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EARBUD_SKIP_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns the number of skip confirmation methods currently enabled.
     * Used to enforce at-least-one: callers should prevent disabling when this returns 1.
     */
    fun countEnabledMethods(context: Context): Int {
        return listOf(
            isWatchSkipEnabled(context),
            isEarbudSkipEnabled(context),
            VoiceSkipListener.isVoiceSkipEnabled(context)
        ).count { it }
    }

    // ---- core remote-skip logic ------------------------------------------------

    fun isRemoteSkipEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REMOTE_SKIP_CONFIRM_ENABLED, true)
    }

    /**
     * Sends a standalone watch prompt test notification so watch mirroring can
     * be verified independently from skip-detection logic.
     */
    fun showWatchPromptTest(context: Context) {
        val appContext = context.applicationContext
        ensureNotificationChannel(appContext)
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            OverlayService.updateStatus("Enable app notifications for watch skip prompt")
            return
        }

        val testIntent = Intent(appContext, RemoteSkipActionReceiver::class.java).apply {
            action = ACTION_CONFIRM_REMOTE_SKIP_TEST
        }
        val dismissIntent = Intent(appContext, RemoteSkipActionReceiver::class.java).apply {
            action = ACTION_DISMISS_REMOTE_SKIP_TEST
        }

        val testPendingIntent = PendingIntent.getBroadcast(
            appContext,
            1_101,
            testIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissPendingIntent = PendingIntent.getBroadcast(
            appContext,
            1_102,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val testAction = buildWearInlineAction(
            iconRes = android.R.drawable.ic_media_play,
            title = "SKIP",
            pendingIntent = testPendingIntent
        )
        val dismissAction = buildWearInlineAction(
            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
            title = "Dismiss",
            pendingIntent = dismissPendingIntent
        )
        val wearableExtender = NotificationCompat.WearableExtender()
            .addAction(testAction)
            .setContentAction(0)
            .setDismissalId(DISMISSAL_ID_REMOTE_SKIP_TEST)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SKIP TEST")
            .setContentText("Tap SKIP")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(TEST_NOTIFICATION_TIMEOUT_MS)
            .setContentIntent(testPendingIntent)
            .addAction(testAction)
            .addAction(dismissAction)
            .extend(wearableExtender)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(TEST_NOTIFICATION_ID, notification)
            OverlayService.updateStatus("Sent watch prompt test")
        } catch (e: SecurityException) {
            Logger.logError("Unable to post watch test notification: ${e.message}")
            OverlayService.updateStatus("Grant notification permission for watch prompt")
        }
    }

    fun setRemoteSkipEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REMOTE_SKIP_CONFIRM_ENABLED, enabled)
            .apply()
        if (!enabled) {
            clearPending(context, "setting-disabled")
        }
    }

    fun shouldRequireRemoteConfirmation(context: Context, command: ActionCommand): Boolean {
        if (!isRemoteSkipEnabled(context)) return false
        return isSkipClickCommand(command)
    }
    /**
     * Finds a likely skip target directly from the current screen text.
     * Used by REMOTE_SKIP mode, which bypasses observe/assist pattern matching.
     */
    fun findSkipCommand(snapshot: ScreenSnapshot): ActionCommand? {
        val target = snapshot.textElements
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { containsActionKeyword(it) }
            .minByOrNull { it.length }
            ?: return null
        return ActionCommand(type = ActionType.CLICK, target = target)
    }

    private fun isSkipClickCommand(command: ActionCommand): Boolean {
        if (command.type != ActionType.CLICK) return false
        return containsActionKeyword(command.target)
    }

    private fun containsActionKeyword(text: String): Boolean {
        return ACTION_TOKENS.any { text.contains(it, ignoreCase = true) }
    }

    /**
     * Returns the display labels of all clickable elements currently visible on screen,
     * split into primary (≤3 words) and extended (>3 words) categories.
     */
    fun collectClickableButtons(snapshot: ScreenSnapshot): CategorizedButtons {
        val all = snapshot.nodes
            .filter { it.isClickable }
            .mapNotNull { node ->
                val label = node.text?.trim() ?: node.contentDesc?.trim()
                label?.takeIf { it.isNotEmpty() && it.length < 80 }
            }
            .distinct()
        val (primary, extended) = all.partition {
            it.trim().split("\\s+".toRegex()).size <= 3
        }
        return CategorizedButtons(primary, extended)
    }

    /**
     * Returns true when the accessibility tree contains a likely video-player
     * surface (SurfaceView, TextureView, VideoView, or a class whose name
     * contains "Player", "Video", or "Exo").
     */
    fun hasVideoPlayer(snapshot: ScreenSnapshot): Boolean {
        return snapshot.nodes.any { node ->
            val cls = node.className ?: return@any false
            cls == "android.view.SurfaceView" ||
                cls == "android.view.TextureView" ||
                cls == "android.widget.VideoView" ||
                cls.contains("Player", ignoreCase = true) ||
                cls.contains("Video", ignoreCase = true) ||
                cls.contains("Exo", ignoreCase = true)
        }
    }

    /**
     * When a video player is on screen, returns single-word clickable button
     * labels suitable for quick-pick auto-suggestions.
     */
    fun collectVideoOverlayButtons(snapshot: ScreenSnapshot): List<String> {
        if (!hasVideoPlayer(snapshot)) return emptyList()
        return snapshot.nodes
            .filter { it.isClickable }
            .mapNotNull { node ->
                val label = node.text?.trim() ?: node.contentDesc?.trim()
                label?.takeIf {
                    it.isNotEmpty() &&
                        it.length < 80 &&
                        it.trim().split("\\s+".toRegex()).size == 1
                }
            }
            .distinct()
    }

    /**
     * Entry point for picker-chosen buttons. Builds a PICKER-origin request and
     * runs the same confirmation flow as auto-detected targets.
     */
    fun requestPickerSkip(context: Context, buttonLabel: String) {
        val snapshot = AutoAccessibilityService.lastSnapshot ?: return
        val command = ActionCommand(type = ActionType.CLICK, target = buttonLabel)
        val appContext = context.applicationContext

        synchronized(lock) {
            pendingRequest = RemoteSkipRequest(
                packageName = snapshot.packageName,
                stableState = snapshot.stableState,
                looseState = snapshot.looseState,
                command = command,
                origin = SkipOrigin.PICKER
            )
        }

        val watchEnabled = isWatchSkipEnabled(appContext)
        val earbudEnabled = isEarbudSkipEnabled(appContext)
        val voiceEnabled = VoiceSkipListener.isVoiceSkipEnabled(appContext)

        ensureNotificationChannel(appContext)
        if (watchEnabled) showWatchNotification(appContext)
        if (earbudEnabled) enableEarbudGestureWindow(appContext)
        playPromptTone(voiceEnabled)
        VoiceSkipListener.startListening(appContext, VOICE_RECOGNIZER_DELAY_MS)
        scheduleTimeout(appContext)

        val methods = mutableListOf<String>()
        if (voiceEnabled) methods.add("say \"Skip\" or \"Play now\"")
        if (watchEnabled) methods.add("watch")
        if (earbudEnabled) methods.add("earbud")
        val label = if (buttonLabel.length > 20) buttonLabel.take(20) + "…" else buttonLabel
        OverlayService.updateStatus("\"$label\" ready — ${methods.joinToString(" / ")}")
    }

    fun requestRemoteSkip(context: Context, snapshot: ScreenSnapshot, command: ActionCommand) {
        if (!shouldRequireRemoteConfirmation(context, command)) return
        val appContext = context.applicationContext

        synchronized(lock) {
            pendingRequest = RemoteSkipRequest(
                packageName = snapshot.packageName,
                stableState = snapshot.stableState,
                looseState = snapshot.looseState,
                command = command
            )
        }

        val watchEnabled = isWatchSkipEnabled(appContext)
        val earbudEnabled = isEarbudSkipEnabled(appContext)
        val voiceEnabled = VoiceSkipListener.isVoiceSkipEnabled(appContext)
        val voicePickOptions = if (voiceEnabled) collectVideoOverlayButtons(snapshot) else emptyList()

        ensureNotificationChannel(appContext)
        if (watchEnabled) showWatchNotification(appContext)
        if (earbudEnabled) enableEarbudGestureWindow(appContext)
        playPromptTone(voiceEnabled)
        VoiceSkipListener.startListening(appContext, VOICE_RECOGNIZER_DELAY_MS, voicePickOptions)
        scheduleTimeout(appContext)

        val methods = mutableListOf<String>()
        if (voiceEnabled && voicePickOptions.isNotEmpty()) {
            methods.add("say a button name")
        } else if (voiceEnabled) {
            methods.add("say \"Skip\" or \"Play now\"")
        }
        if (watchEnabled) methods.add("watch")
        if (earbudEnabled) methods.add("earbud")
        OverlayService.updateStatus("Action ready — ${methods.joinToString(" / ")}")
    }

    fun onScreenChanged(context: Context, snapshot: ScreenSnapshot) {
        val shouldClear = synchronized(lock) {
            val pending = pendingRequest
            pending != null && pending.packageName != snapshot.packageName
        }
        if (shouldClear) {
            clearPending(context, "screen-changed")
        }
    }

    fun confirmFromWatch(context: Context) {
        confirmPending(context.applicationContext, SOURCE_WATCH)
    }

    fun onWatchTestAction(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(TEST_NOTIFICATION_ID)
        } catch (_: Exception) {}
        OverlayService.updateStatus("Watch action received")
    }

    fun dismissWatchTest(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(TEST_NOTIFICATION_ID)
        } catch (_: Exception) {}
        OverlayService.updateStatus("Watch prompt test dismissed")
    }

    fun confirmFromEarbud(context: Context) {
        confirmPending(context.applicationContext, SOURCE_EARBUD)
    }

    fun confirmFromVoice(context: Context) {
        confirmPending(context.applicationContext, SOURCE_VOICE)
    }

    /**
     * Called when the user says a voice-picked button label instead of the
     * generic "skip" keyword. Swaps the pending request's target to the
     * spoken label, then confirms as usual.
     */
    fun confirmFromVoicePick(context: Context, buttonLabel: String) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val pending = pendingRequest ?: return
            pendingRequest = pending.copy(
                command = ActionCommand(type = ActionType.CLICK, target = buttonLabel),
                origin = SkipOrigin.PICKER
            )
        }
        confirmPending(appContext, SOURCE_VOICE)
    }

    fun clearPending(context: Context, reason: String = "cleared") {
        val hadPending = synchronized(lock) {
            val hadValue = pendingRequest != null
            pendingRequest = null
            hadValue
        }
        if (!hadPending) return

        clearPromptSurface(context.applicationContext)
        if (reason != "confirmed") {
            OverlayService.updateStatus("Remote skip cancelled")
        }
    }

    fun release(context: Context) {
        clearPending(context, "release")
        VoiceSkipListener.stopListening()
        VoiceSkipListener.releaseTts()
        mediaSession?.release()
        mediaSession = null
    }

    private fun confirmPending(context: Context, source: String) {
        val request = synchronized(lock) {
            val next = pendingRequest
            pendingRequest = null
            next
        } ?: return

        clearPromptSurface(context)

        if (System.currentTimeMillis() - request.createdAt > REQUEST_TIMEOUT_MS) {
            OverlayService.updateStatus("Remote skip expired")
            return
        }

        val service = AutoAccessibilityService.instance
        if (service == null) {
            Logger.logError("Remote skip confirmation dropped: accessibility service unavailable")
            return
        }
        service.executeRemoteSkip(request, source)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remote Skip Watch Prompt",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Remote confirmation actions for learned Skip clicks"
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun showWatchNotification(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            OverlayService.updateStatus("Enable app notifications for watch skip prompt")
            return
        }
        val confirmIntent = Intent(context, RemoteSkipActionReceiver::class.java).apply {
            action = ACTION_CONFIRM_REMOTE_SKIP
        }
        val dismissIntent = Intent(context, RemoteSkipActionReceiver::class.java).apply {
            action = ACTION_DISMISS_REMOTE_SKIP
        }

        val confirmPendingIntent = PendingIntent.getBroadcast(
            context,
            1_001,
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            1_002,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val skipAction = buildWearInlineAction(
            iconRes = android.R.drawable.ic_media_next,
            title = "SKIP",
            pendingIntent = confirmPendingIntent
        )
        val dismissAction = buildWearInlineAction(
            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
            title = "Dismiss",
            pendingIntent = dismissPendingIntent
        )
        val wearableExtender = NotificationCompat.WearableExtender()
            .addAction(skipAction)
            .setContentAction(0)
            .setDismissalId(DISMISSAL_ID_REMOTE_SKIP)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_next)
            .setContentTitle("SKIP")
            .setContentText("Tap SKIP")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(REQUEST_TIMEOUT_MS)
            .setContentIntent(confirmPendingIntent)
            .addAction(skipAction)
            .addAction(dismissAction)
            .extend(wearableExtender)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Logger.logError("Unable to post remote skip notification: ${e.message}")
            OverlayService.updateStatus("Grant notification permission for watch skip prompt")
        }
    }

    private fun clearPromptSurface(context: Context) {
        cancelTimeout()
        disableEarbudGestureWindow()
        VoiceSkipListener.stopListening()
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (e: SecurityException) {
            Logger.logError("Unable to clear remote skip notification: ${e.message}")
        }
    }

    private fun scheduleTimeout(context: Context) {
        cancelTimeout()
        timeoutRunnable = Runnable {
            val hasPending = synchronized(lock) { pendingRequest != null }
            if (hasPending) {
                clearPending(context, "timeout")
            }
        }.also {
            mainHandler.postDelayed(it, REQUEST_TIMEOUT_MS)
        }
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun enableEarbudGestureWindow(context: Context) {
        ensureMediaSession(context)
        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_PLAY_PAUSE
            )
            .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
        mediaSession?.isActive = true
    }

    private fun disableEarbudGestureWindow() {
        mediaSession?.isActive = false
    }

    private fun ensureMediaSession(context: Context) {
        if (mediaSession != null) return
        val appContext = context.applicationContext
        mediaSession = MediaSession(appContext, "RemoteSkipSession").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setCallback(object : MediaSession.Callback() {
                override fun onSkipToNext() {
                    confirmFromEarbud(appContext)
                }
                override fun onFastForward() {
                    confirmFromEarbud(appContext)
                }

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = extractMediaButtonEvent(mediaButtonIntent)
                    if (event?.action == KeyEvent.ACTION_DOWN && isEarbudNextGesture(event.keyCode)) {
                        confirmFromEarbud(appContext)
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
        }
    }

    private fun extractMediaButtonEvent(mediaButtonIntent: Intent): KeyEvent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    private fun isEarbudNextGesture(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
    }

    private fun playPromptTone(voiceEnabled: Boolean) {
        if (voiceEnabled) {
            if (playVoiceToneOnStream(AudioManager.STREAM_MUSIC)) return
            if (playVoiceToneOnStream(AudioManager.STREAM_NOTIFICATION)) return
        } else {
            if (playToneOnStream(AudioManager.STREAM_MUSIC)) return
            if (playToneOnStream(AudioManager.STREAM_NOTIFICATION)) return
        }
        Logger.logError("Remote skip chime failed: unable to play tone on available streams")
    }

    private fun playToneOnStream(streamType: Int): Boolean {
        return try {
            val toneGenerator = ToneGenerator(streamType, 100)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 170)
            mainHandler.postDelayed({
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 170)
            }, 220L)
            mainHandler.postDelayed({ toneGenerator.release() }, 550L)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun playVoiceToneOnStream(streamType: Int): Boolean {
        return try {
            val toneGenerator = ToneGenerator(streamType, 100)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            mainHandler.postDelayed({
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }, 180L)
            mainHandler.postDelayed({
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 260)
            }, 360L)
            mainHandler.postDelayed({ toneGenerator.release() }, 760L)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildWearInlineAction(
        iconRes: Int,
        title: String,
        pendingIntent: PendingIntent
    ): NotificationCompat.Action {
        val actionExtender = NotificationCompat.Action.WearableExtender()
            .setHintDisplayActionInline(true)
            .setAvailableOffline(true)
        return NotificationCompat.Action.Builder(iconRes, title, pendingIntent)
            .extend(actionExtender)
            .build()
    }
}
