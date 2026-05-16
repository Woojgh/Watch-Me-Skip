package com.example.aiassistant

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.*

/**
 * Monitors all Settings.System changes (volume, brightness, rotation, ringer, etc.).
 * When a change is detected, it diffs against cached state to identify what changed,
 * then records a SystemPatternEntity tagged with the current screen context.
 */
class SystemStateMonitor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var observer: ContentObserver? = null
    private var previousState = mutableMapOf<String, String>()

    /** All settings we track. Easy to extend — just add entries here. */
    private val trackedSettings = listOf(
        TrackedSetting("volume_music") { readVolume(AudioManager.STREAM_MUSIC) },
        TrackedSetting("volume_ring") { readVolume(AudioManager.STREAM_RING) },
        TrackedSetting("volume_alarm") { readVolume(AudioManager.STREAM_ALARM) },
        TrackedSetting("volume_notification") { readVolume(AudioManager.STREAM_NOTIFICATION) },
        TrackedSetting("screen_brightness") { readSystemInt(Settings.System.SCREEN_BRIGHTNESS, -1) },
        TrackedSetting("auto_brightness") { readSystemInt(Settings.System.SCREEN_BRIGHTNESS_MODE, -1) },
        TrackedSetting("accelerometer_rotation") { readSystemInt(Settings.System.ACCELEROMETER_ROTATION, -1) },
        TrackedSetting("ringer_mode") { readRingerMode() }
    )

    fun start() {
        // Cache initial state
        for (setting in trackedSettings) {
            previousState[setting.name] = setting.reader()
        }

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onSettingsChanged()
            }
        }

        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            observer!!
        )
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        scope.cancel()
    }

    private fun onSettingsChanged() {
        val snapshot = AutoAccessibilityService.lastSnapshot ?: return
        val pkg = snapshot.packageName

        for (setting in trackedSettings) {
            val newVal = setting.reader()
            val oldVal = previousState[setting.name] ?: newVal

            if (newVal != oldVal) {
                previousState[setting.name] = newVal

                // Extract top keywords from the screen for context matching
                val keywords = extractKeywords(snapshot)

                scope.launch {
                    try {
                        val dao = DatabaseHelper.getDB(context).systemPatternDao()
                        // New patterns stored under looseState for resilience;
                        // old strict-state patterns are still found by ScreenReactor's dual query.
                        val existing = dao.get(snapshot.looseState, setting.name, newVal)
                            ?: dao.get(snapshot.stableState, setting.name, newVal)
                        if (existing != null) {
                            dao.incrementCount(existing.id)
                        } else {
                            dao.insert(
                                SystemPatternEntity(
                                    packageName = pkg,
                                    screenState = snapshot.looseState,
                                    matchedKeywords = keywords,
                                    settingName = setting.name,
                                    oldValue = oldVal,
                                    newValue = newVal
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Logger.logError("SystemStateMonitor failed to record pattern: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Extract the most meaningful text from the screen snapshot.
     * Filters out very short or very long strings, keeps up to 8 items.
     */
    private fun extractKeywords(snapshot: ScreenSnapshot): String {
        return snapshot.textElements
            .filter { it.length in 2..60 }
            .distinct()
            .take(8)
            .joinToString(",")
    }

    // --- Readers ---

    private fun readVolume(stream: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getStreamVolume(stream).toString()
    }

    private fun readRingerMode(): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.ringerMode.toString()
    }

    private fun readSystemInt(name: String, default: Int): String {
        return try {
            Settings.System.getInt(context.contentResolver, name, default).toString()
        } catch (_: Exception) {
            default.toString()
        }
    }

    private data class TrackedSetting(
        val name: String,
        val reader: () -> String
    )
}
