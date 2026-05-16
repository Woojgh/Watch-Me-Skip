package com.example.aiassistant

import android.content.Context
import android.media.AudioManager
import android.provider.Settings

/**
 * Applies system setting changes. Routes each setting name to the
 * correct Android API (AudioManager, Settings.System, etc.).
 */
object SystemActionExecutor {

    /**
     * Apply a system setting change. Returns true if successful.
     */
    fun apply(context: Context, settingName: String, value: String): Boolean {
        return try {
            when {
                settingName.startsWith("volume_") -> applyVolume(context, settingName, value)
                settingName == "ringer_mode" -> applyRingerMode(context, value)
                settingName == "screen_brightness" -> applySystemInt(context, Settings.System.SCREEN_BRIGHTNESS, value)
                settingName == "auto_brightness" -> applySystemInt(context, Settings.System.SCREEN_BRIGHTNESS_MODE, value)
                settingName == "accelerometer_rotation" -> applySystemInt(context, Settings.System.ACCELEROMETER_ROTATION, value)
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun applyVolume(context: Context, settingName: String, value: String): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (settingName) {
            "volume_music" -> AudioManager.STREAM_MUSIC
            "volume_ring" -> AudioManager.STREAM_RING
            "volume_alarm" -> AudioManager.STREAM_ALARM
            "volume_notification" -> AudioManager.STREAM_NOTIFICATION
            else -> return false
        }
        val vol = value.toIntOrNull() ?: return false
        val max = am.getStreamMaxVolume(stream)
        am.setStreamVolume(stream, vol.coerceIn(0, max), 0)
        return true
    }

    private fun applyRingerMode(context: Context, value: String): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = value.toIntOrNull() ?: return false
        if (mode !in 0..2) return false // SILENT=0, VIBRATE=1, NORMAL=2
        am.ringerMode = mode
        return true
    }

    /**
     * Write to Settings.System. Requires WRITE_SETTINGS permission.
     * Returns false gracefully if not granted.
     */
    private fun applySystemInt(context: Context, name: String, value: String): Boolean {
        if (!Settings.System.canWrite(context)) return false
        val intVal = value.toIntOrNull() ?: return false
        return Settings.System.putInt(context.contentResolver, name, intVal)
    }
}
