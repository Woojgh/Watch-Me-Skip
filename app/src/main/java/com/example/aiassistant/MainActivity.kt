package com.example.aiassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_NOTIFICATIONS_CODE = 10_031
        private const val REQUEST_MICROPHONE_CODE = 10_032
    }

    private lateinit var statusText: TextView
    private lateinit var overlayButton: Button
    private lateinit var watchSkipToggleButton: Button
    private lateinit var earbudSkipToggleButton: Button
    private lateinit var voiceSkipToggleButton: Button
    private lateinit var watchTestButton: Button
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seed default rules on first launch
        scope.launch {
            withContext(Dispatchers.IO) { DatabaseHelper.seedDefaultRules(this@MainActivity) }
        }

        val scroll = ScrollView(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
        }

        // Title
        layout.addView(TextView(this).apply {
            text = "Watch Me Skip"
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        // Service status
        statusText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        layout.addView(statusText)

        // ===================== TOGGLES =====================
        layout.addView(sectionHeader("Toggles"))

        watchSkipToggleButton = Button(this).apply {
            setOnClickListener {
                val enabled = RemoteSkipController.isWatchSkipEnabled(this@MainActivity)
                if (enabled && RemoteSkipController.countEnabledMethods(this@MainActivity) <= 1) {
                    android.widget.Toast.makeText(this@MainActivity, "At least one skip method must be enabled", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                RemoteSkipController.setWatchSkipEnabled(this@MainActivity, !enabled)
                refreshStatus()
            }
        }
        layout.addView(watchSkipToggleButton, buttonParams())

        earbudSkipToggleButton = Button(this).apply {
            setOnClickListener {
                val enabled = RemoteSkipController.isEarbudSkipEnabled(this@MainActivity)
                if (enabled && RemoteSkipController.countEnabledMethods(this@MainActivity) <= 1) {
                    android.widget.Toast.makeText(this@MainActivity, "At least one skip method must be enabled", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                RemoteSkipController.setEarbudSkipEnabled(this@MainActivity, !enabled)
                refreshStatus()
            }
        }
        layout.addView(earbudSkipToggleButton, buttonParams())

        voiceSkipToggleButton = Button(this).apply {
            setOnClickListener {
                val enabled = VoiceSkipListener.isVoiceSkipEnabled(this@MainActivity)
                if (enabled && RemoteSkipController.countEnabledMethods(this@MainActivity) <= 1) {
                    android.widget.Toast.makeText(this@MainActivity, "At least one skip method must be enabled", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!enabled) {
                    maybeRequestMicrophonePermission()
                }
                VoiceSkipListener.setVoiceSkipEnabled(this@MainActivity, !enabled)
                refreshStatus()
            }
        }
        layout.addView(voiceSkipToggleButton, buttonParams())

        overlayButton = Button(this).apply {
            text = "Enable Overlay"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    toggleOverlay()
                }
            }
        }
        layout.addView(overlayButton, buttonParams())

        // ===================== TESTING =====================
        layout.addView(sectionHeader("Testing"))

        watchTestButton = Button(this).apply {
            text = "Test Watch Prompt"
            setOnClickListener {
                maybeRequestNotificationPermission()
                RemoteSkipController.showWatchPromptTest(this@MainActivity)
            }
        }
        layout.addView(watchTestButton, buttonParams())

        // ===================== SETTINGS =====================
        layout.addView(sectionHeader("Settings"))

        layout.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, buttonParams())

        layout.addView(Button(this).apply {
            text = "Safety Settings"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SafetyActivity::class.java))
            }
        }, buttonParams())

        scroll.addView(layout)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // Always enable REMOTE_SKIP — skip detection is the app's purpose
        if (AutoAccessibilityService.getMode(this) != AgentMode.REMOTE_SKIP) {
            AutoAccessibilityService.setMode(this, AgentMode.REMOTE_SKIP)
        }
        maybeRequestNotificationPermission()
        // Auto-start overlay if permission is granted
        if (Settings.canDrawOverlays(this) && OverlayService.instance == null) {
            startForegroundService(Intent(this, OverlayService::class.java))
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val serviceConnected = AutoAccessibilityService.instance != null
        statusText.text = if (serviceConnected) "Service: Connected" else "Service: Disconnected"

        val overlayAllowed = Settings.canDrawOverlays(this)
        overlayButton.text = if (overlayAllowed) "Overlay (tap to toggle)" else "Enable Overlay"

        watchSkipToggleButton.text = if (RemoteSkipController.isWatchSkipEnabled(this))
            "Watch Skip: ON" else "Watch Skip: OFF"
        earbudSkipToggleButton.text = if (RemoteSkipController.isEarbudSkipEnabled(this))
            "Earbud Gesture Skip: ON" else "Earbud Gesture Skip: OFF"
        voiceSkipToggleButton.text = if (VoiceSkipListener.isVoiceSkipEnabled(this))
            "Voice Skip: ON" else "Voice Skip: OFF"
    }

    private fun toggleOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        // Derive running state from the live service instance rather than a
        // local boolean that resets on Activity recreation.
        if (OverlayService.instance != null) {
            stopService(intent)
        } else {
            startForegroundService(intent)
        }
    }

    private fun sectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
    }

    private fun buttonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 16
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasNotificationPermission()) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS_CODE
        )
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun maybeRequestMicrophonePermission() {
        if (hasMicrophonePermission()) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_MICROPHONE_CODE
        )
    }
}
