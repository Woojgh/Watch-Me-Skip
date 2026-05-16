package com.example.aiassistant

import android.app.*
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*

/**
 * Overlay service that shows a draggable floating bubble.
 * Tapping the bubble opens/closes a mini control panel with:
 *   - Live AI status text
 *   - Mode toggle buttons (Off / Observe / Assist)
 *   - Shortcut to Accessibility Settings
 *   - Button to open the main app
 */
class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
        private const val CHANNEL_ID = "ai_overlay_channel"
        private const val NOTIFICATION_ID = 1

        /** Called from any thread to push a status message to the overlay. */
        fun updateStatus(text: String) {
            instance?.postStatus(text)
        }

        /** Called when the accessibility service connects or disconnects. */
        fun refreshServiceState() {
            instance?.mainHandler?.post {
                instance?.refreshPanelContent()
            }
        }

        /**
         * Shows a pending-action preview on the bubble so the user can see what
         * the assistant is about to do and cancel it by tapping the bubble.
         * Safe to call from any thread.
         */
        fun showPendingAction(command: ActionCommand) {
            instance?.mainHandler?.post {
                instance?.apply {
                    pendingActionText = command.target
                    updateBubbleAppearance()
                }
            }
        }

        /**
         * Clears the pending-action preview and restores the normal bubble appearance.
         * Always called — whether the action executed, was cancelled, or the job was dropped.
         * Safe to call from any thread.
         */
        fun clearPendingAction() {
            instance?.mainHandler?.post {
                instance?.apply {
                    pendingActionText = null
                    updateBubbleAppearance()
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    // Bubble
    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Expanded panel
    private var panelView: LinearLayout? = null
    private var panelStatusTv: TextView? = null
    private var panelModeBtn: Button? = null
    private var panelWatchSkipBtn: Button? = null
    private var panelEarbudSkipBtn: Button? = null
    private var panelVoiceSkipBtn: Button? = null
    private var panelServiceStatusTv: TextView? = null
    private var panelServiceBtn: Button? = null
    private var panelRefreshRunnable: Runnable? = null

    private var lastStatus = "AI Ready"

    /**
     * Non-null while the assistant has a pending action queued.
     * Drives the amber "about to act" bubble appearance and tap-to-cancel behaviour.
     */
    private var pendingActionText: String? = null

    // Drag tracking
    private var dragInitX = 0
    private var dragInitY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f
    private var isDragging = false

    // Button picker
    private var pickerView: ScrollView? = null

    // Dismiss zone
    private var dismissView: TextView? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var isOverDismissZone = false

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        mainHandler.post { showBubble() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        safeRemove(bubbleView)
        safeRemove(panelView)
        safeRemove(pickerView)
        safeRemove(dismissView)
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun postStatus(text: String) {
        lastStatus = text
        mainHandler.post {
            panelStatusTv?.text = text
            updateBubbleAppearance()
        }
    }

    // -------------------------------------------------------------------------
    // Bubble
    // -------------------------------------------------------------------------

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 200
        }
        bubbleParams = params

        val bubble = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            gravity = Gravity.CENTER
        }
        bubbleView = bubble
        updateBubbleAppearance()

        bubble.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitX = params.x
                    dragInitY = params.y
                    dragTouchX = event.rawX
                    dragTouchY = event.rawY
                    isDragging = false
                    isOverDismissZone = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragTouchX).toInt()
                    val dy = (event.rawY - dragTouchY).toInt()
                    if (!isDragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                        isDragging = true
                        showDismissZone()
                    }
                    if (isDragging) {
                        params.x = dragInitX + dx
                        params.y = dragInitY + dy
                        try { windowManager?.updateViewLayout(v, params) } catch (_: Exception) {}
                        updateDismissHighlight(event.rawX, event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging && isOverDismissZone) {
                        hideDismissZone()
                        stopSelf()
                    } else if (isDragging) {
                        hideDismissZone()
                    } else {
                        if (pendingActionText != null) {
                            AutoAccessibilityService.instance?.cancelPendingAssist()
                        } else {
                            togglePanel()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(bubble, params)
    }

    private fun updateBubbleAppearance() {
        val mode = AutoAccessibilityService.cachedMode
        val active = mode == AgentMode.REMOTE_SKIP
        val connected = AutoAccessibilityService.instance != null
        bubbleView?.apply {
            val pending = pendingActionText
            if (pending != null) {
                val label = if (pending.length > 9) pending.take(9) + "…" else pending
                text = "⧖ $label"
                background = createBubbleBackground(
                    Color.argb(220, 160, 100, 10),
                    serviceColor(connected)
                )
            } else {
                text = if (active) "SKIP\nON" else "SKIP\nOFF"
                background = createBubbleBackground(
                    if (active) Color.argb(215, 130, 70, 170) else Color.argb(215, 70, 70, 70),
                    serviceColor(connected)
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Panel
    // -------------------------------------------------------------------------

    private fun togglePanel() {
        if (panelView != null) dismissPanel() else showPanel()
    }

    private fun showPanel() {
        val wm = windowManager ?: return
        val bp = bubbleParams ?: return

        val panelParams = WindowManager.LayoutParams(
            dp(270),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = maxOf(0, bp.x - dp(270) + dp(80))
            y = bp.y + dp(75)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 22, 22, 22))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // -- Header: title + close button --
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Watch Me Skip"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.argb(200, 180, 180, 180))
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { dismissPanel() }
        })
        panel.addView(header, fullWidthWrap().apply { bottomMargin = dp(10) })

        // -- Status section --
        panel.addView(sectionLabel("Status"))
        val statusTv = TextView(this).apply {
            text = lastStatus
            textSize = 13f
            setTextColor(Color.argb(230, 80, 210, 130))
            setPadding(0, dp(2), 0, dp(12))
        }
        panelStatusTv = statusTv
        panel.addView(statusTv, fullWidthWrap())

        // -- Mode toggle: Off / Remote Skip --
        panel.addView(sectionLabel("Skip Detection", bottomPad = dp(6)))
        val currentMode = AutoAccessibilityService.cachedMode
        val isActive = currentMode == AgentMode.REMOTE_SKIP
        val modeBtn = actionButton(modeToggleLabel(isActive)) {
            val active = AutoAccessibilityService.cachedMode == AgentMode.REMOTE_SKIP
            val next = if (active) AgentMode.OFF else AgentMode.REMOTE_SKIP
            AutoAccessibilityService.setMode(this@OverlayService, next)
            updateBubbleAppearance()
            panelModeBtn?.text = modeToggleLabel(!active)
        }
        panelModeBtn = modeBtn
        panel.addView(modeBtn)

        // -- Skip method toggles --
        panel.addView(sectionLabel("Confirmation Methods", bottomPad = dp(4)))

        val watchBtn = actionButton(skipMethodLabel("Watch", RemoteSkipController.isWatchSkipEnabled(this))) {
            val on = RemoteSkipController.isWatchSkipEnabled(this@OverlayService)
            if (on && RemoteSkipController.countEnabledMethods(this@OverlayService) <= 1) {
                postStatus("At least one method must be on")
                return@actionButton
            }
            RemoteSkipController.setWatchSkipEnabled(this@OverlayService, !on)
            refreshAllMethodButtons()
        }
        panelWatchSkipBtn = watchBtn
        panel.addView(watchBtn)

        val earbudBtn = actionButton(skipMethodLabel("Earbud Gesture", RemoteSkipController.isEarbudSkipEnabled(this))) {
            val on = RemoteSkipController.isEarbudSkipEnabled(this@OverlayService)
            if (on && RemoteSkipController.countEnabledMethods(this@OverlayService) <= 1) {
                postStatus("At least one method must be on")
                return@actionButton
            }
            RemoteSkipController.setEarbudSkipEnabled(this@OverlayService, !on)
            refreshAllMethodButtons()
        }
        panelEarbudSkipBtn = earbudBtn
        panel.addView(earbudBtn)

        val voiceSkipBtn = actionButton(skipMethodLabel("Voice", VoiceSkipListener.isVoiceSkipEnabled(this))) {
            val on = VoiceSkipListener.isVoiceSkipEnabled(this@OverlayService)
            if (on && RemoteSkipController.countEnabledMethods(this@OverlayService) <= 1) {
                postStatus("At least one method must be on")
                return@actionButton
            }
            VoiceSkipListener.setVoiceSkipEnabled(this@OverlayService, !on)
            refreshAllMethodButtons()
        }
        panelVoiceSkipBtn = voiceSkipBtn
        panel.addView(voiceSkipBtn)

        // -- Service status + toggle --
        // Android doesn't allow programmatic enable/disable of accessibility services;
        // the best we can do is show connection state and deep-link to the exact service
        // page in Settings so the user can flip the toggle themselves in one tap.
        panel.addView(sectionLabel("Accessibility Service", bottomPad = dp(4)))
        val svcConnected = AutoAccessibilityService.instance != null
        val svcStatusTv = TextView(this).apply {
            text = if (svcConnected) "● Connected" else "● Disconnected"
            textSize = 12f
            setTextColor(serviceColor(svcConnected))
            setPadding(0, 0, 0, dp(6))
        }
        panelServiceStatusTv = svcStatusTv
        panel.addView(svcStatusTv, fullWidthWrap())
        val svcBtn = actionButton(
            if (svcConnected) "Disable Service \u2192" else "Enable Service \u2192"
        ) {
            // Deep-link directly to this service's toggle in accessibility settings
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val bundle = Bundle()
            bundle.putString(
                ":settings:fragment_args_key",
                "$packageName/${AutoAccessibilityService::class.java.name}"
            )
            intent.putExtra(":settings:show_fragment_args", bundle)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            dismissPanel()
        }
        panelServiceBtn = svcBtn
        panel.addView(svcBtn)

        // -- Button Picker --
        panel.addView(sectionLabel("Button Picker", bottomPad = dp(4)))

        // Auto-suggest: show single-word buttons when a video player is on screen
        val autoSnapshot = AutoAccessibilityService.lastSnapshot
        if (autoSnapshot != null) {
            val quickButtons = RemoteSkipController.collectVideoOverlayButtons(autoSnapshot)
            if (quickButtons.isNotEmpty()) {
                panel.addView(TextView(this).apply {
                    text = "Quick actions"
                    textSize = 9f
                    setTextColor(Color.argb(120, 170, 170, 170))
                    setPadding(0, 0, 0, dp(4))
                })
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
                for (label in quickButtons.take(5)) {
                    row.addView(Button(this).apply {
                        text = label
                        textSize = 11f
                        setBackgroundColor(Color.argb(200, 70, 50, 100))
                        setTextColor(Color.WHITE)
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                        setOnClickListener {
                            dismissPanel()
                            RemoteSkipController.requestPickerSkip(this@OverlayService, label)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(6); bottomMargin = dp(6) }
                    })
                }
                panel.addView(row, fullWidthWrap())
            }
        }

        panel.addView(actionButton("Scan All Buttons") {
            val snapshot = AutoAccessibilityService.lastSnapshot
            if (snapshot == null) {
                postStatus("No screen data — is the service connected?")
                return@actionButton
            }
            val categorized = RemoteSkipController.collectClickableButtons(snapshot)
            if (categorized.isEmpty()) {
                postStatus("No clickable buttons found")
                return@actionButton
            }
            dismissPanel()
            showButtonPicker(categorized)
        })

        // -- Open App --
        panel.addView(actionButton("Open App") {
            startActivity(Intent(this@OverlayService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            dismissPanel()
        })

        wm.addView(panel, panelParams)
        panelView = panel
        startPanelRefresh()
    }

    private fun dismissPanel() {
        stopPanelRefresh()
        safeRemove(panelView)
        panelView = null
        panelStatusTv = null
        panelModeBtn = null
        panelWatchSkipBtn = null
        panelEarbudSkipBtn = null
        panelVoiceSkipBtn = null
        panelServiceStatusTv = null
        panelServiceBtn = null
    }

    /**
     * Starts a 1-second repeating tick that refreshes dynamic panel content
     * (service connection state, mode button highlights, bubble colour)
     * while the panel is open.
     */
    private fun startPanelRefresh() {
        val runnable = object : Runnable {
            override fun run() {
                if (panelView != null) {
                    refreshPanelContent()
                    mainHandler.postDelayed(this, 1_000L)
                }
            }
        }
        panelRefreshRunnable = runnable
        mainHandler.postDelayed(runnable, 1_000L)
    }

    private fun stopPanelRefresh() {
        panelRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        panelRefreshRunnable = null
    }

    /** Updates all live-changing fields inside the open panel. */
    private fun refreshPanelContent() {
        // Service connection status
        val connected = AutoAccessibilityService.instance != null
        panelServiceStatusTv?.apply {
            text = if (connected) "\u25cf Connected" else "\u25cf Disconnected"
            setTextColor(serviceColor(connected))
        }
        panelServiceBtn?.text =
            if (connected) "Disable Service \u2192" else "Enable Service \u2192"

        // Mode toggle (in case mode changed from the main app)
        panelModeBtn?.text =
            modeToggleLabel(AutoAccessibilityService.cachedMode == AgentMode.REMOTE_SKIP)
        refreshAllMethodButtons()

        // Keep bubble in sync too
        updateBubbleAppearance()
    }

    // -------------------------------------------------------------------------
    // Button Picker
    // -------------------------------------------------------------------------

    private fun showButtonPicker(categorized: CategorizedButtons) {
        dismissButtonPicker()
        val wm = windowManager ?: return
        val bp = bubbleParams ?: return

        val screenHeight = resources.displayMetrics.heightPixels
        val maxHeight = (screenHeight * 0.55).toInt()

        val pickerParams = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = maxOf(0, bp.x - dp(280) + dp(80))
            y = bp.y + dp(75)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(245, 22, 22, 22))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Buttons on Screen"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "\u2715"
            textSize = 16f
            setTextColor(Color.argb(200, 180, 180, 180))
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { dismissButtonPicker() }
        })
        container.addView(header, fullWidthWrap().apply { bottomMargin = dp(8) })

        container.addView(TextView(this).apply {
            text = "Tap a button to confirm remotely"
            textSize = 10f
            setTextColor(Color.argb(140, 170, 170, 170))
            setPadding(0, 0, 0, dp(8))
        })

        // Primary buttons (≤3 words)
        for (label in categorized.primary) {
            container.addView(makePickerButton(label))
        }

        // Extended buttons (>3 words) under a separator
        if (categorized.extended.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "More"
                textSize = 10f
                setTextColor(Color.argb(140, 170, 170, 170))
                setPadding(0, dp(10), 0, dp(4))
            })
            for (label in categorized.extended) {
                container.addView(makePickerButton(label))
            }
        }

        val scroll = ScrollView(this).apply {
            addView(container)
        }
        scroll.layoutParams = WindowManager.LayoutParams(
            dp(280),
            maxHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        pickerView = scroll
        wm.addView(scroll, pickerParams)
    }

    private fun makePickerButton(label: String): Button {
        return Button(this).apply {
            val display = if (label.length > 40) label.take(40) + "\u2026" else label
            text = display
            textSize = 12f
            setBackgroundColor(Color.argb(180, 50, 50, 50))
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener {
                dismissButtonPicker()
                RemoteSkipController.requestPickerSkip(this@OverlayService, label)
            }
            layoutParams = fullWidthWrap().apply { bottomMargin = dp(4) }
        }
    }

    private fun dismissButtonPicker() {
        safeRemove(pickerView)
        pickerView = null
    }

    // -------------------------------------------------------------------------
    // Color helpers
    // -------------------------------------------------------------------------

    private fun serviceColor(connected: Boolean): Int =
        if (connected) Color.argb(230, 80, 210, 130) else Color.argb(230, 220, 80, 80)

    private fun createBubbleBackground(modeColor: Int, statusColor: Int): Drawable =
        SplitBottomColorDrawable(
            topColor = modeColor,
            bottomColor = statusColor,
            bottomFraction = 0.20f,
            cornerRadiusPx = dp(14).toFloat()
        )

    private fun modeToggleLabel(active: Boolean): String =
        if (active) "Skip Detection: ON (tap to disable)"
        else "Skip Detection: OFF (tap to enable)"
    private fun skipMethodLabel(name: String, enabled: Boolean): String =
        if (enabled) "$name: ON" else "$name: OFF"

    private fun refreshAllMethodButtons() {
        panelWatchSkipBtn?.text = skipMethodLabel("Watch", RemoteSkipController.isWatchSkipEnabled(this))
        panelEarbudSkipBtn?.text = skipMethodLabel("Earbud Gesture", RemoteSkipController.isEarbudSkipEnabled(this))
        panelVoiceSkipBtn?.text = skipMethodLabel("Voice", VoiceSkipListener.isVoiceSkipEnabled(this))
    }

    private class SplitBottomColorDrawable(
        private val topColor: Int,
        private val bottomColor: Int,
        private val bottomFraction: Float,
        private val cornerRadiusPx: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clipPath = Path()
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return

            rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(rect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

            val clampedBottomFraction = bottomFraction.coerceIn(0f, 1f)
            val splitY = rect.bottom - (rect.height() * clampedBottomFraction)

            canvas.save()
            canvas.clipPath(clipPath)

            paint.color = topColor
            canvas.drawRect(rect.left, rect.top, rect.right, splitY, paint)

            paint.color = bottomColor
            canvas.drawRect(rect.left, splitY, rect.right, rect.bottom, paint)

            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // -------------------------------------------------------------------------
    // View helpers
    // -------------------------------------------------------------------------

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setBackgroundColor(Color.argb(180, 50, 50, 50))
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
        layoutParams = fullWidthWrap().apply { bottomMargin = dp(6) }
    }

    private fun sectionLabel(text: String, bottomPad: Int = 0) = TextView(this).apply {
        this.text = text
        textSize = 10f
        setTextColor(Color.argb(160, 170, 170, 170))
        if (bottomPad > 0) setPadding(0, 0, 0, bottomPad)
    }

    private fun fullWidthWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun safeRemove(view: View?) {
        view?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
    }

    // -------------------------------------------------------------------------
    // Dismiss zone
    // -------------------------------------------------------------------------

    private fun showDismissZone() {
        if (dismissView != null) return
        val wm = windowManager ?: return
        val screenHeight = resources.displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = screenHeight - dp(90)
        }
        dismissParams = params

        val view = TextView(this).apply {
            text = "\u2715"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = createCircleDrawable(Color.argb(200, 180, 40, 40))
        }
        dismissView = view
        wm.addView(view, params)
    }

    private fun hideDismissZone() {
        safeRemove(dismissView)
        dismissView = null
        dismissParams = null
        isOverDismissZone = false
    }

    private fun updateDismissHighlight(rawX: Float, rawY: Float) {
        val dv = dismissView ?: return
        val dp = dismissParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // Dismiss zone center position
        val zoneX = screenWidth / 2f
        val zoneY = dp.y + dp(28).toFloat()

        val dist = kotlin.math.hypot((rawX - zoneX).toDouble(), (rawY - zoneY).toDouble())
        val over = dist < dp(50)
        if (over != isOverDismissZone) {
            isOverDismissZone = over
            dv.background = createCircleDrawable(
                if (over) Color.argb(240, 220, 50, 50) else Color.argb(200, 180, 40, 40)
            )
            dv.textSize = if (over) 28f else 24f
        }
    }

    private fun createCircleDrawable(color: Int): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            override fun draw(canvas: Canvas) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                canvas.drawCircle(cx, cy, kotlin.math.min(cx, cy), paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "AI Assistant Overlay", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows AI Assistant overlay status" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AI Assistant")
                .setContentText("Overlay active \u2014 tap bubble to control")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AI Assistant")
                .setContentText("Overlay active \u2014 tap bubble to control")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .build()
        }
    }
}
