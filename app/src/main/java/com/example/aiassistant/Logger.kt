package com.example.aiassistant

import android.util.Log

object Logger {
    private const val TAG = "BehavioralLearningAssistant"

    // Log error messages
    fun logError(message: String) {
        Log.e(TAG, message)
    }

    // Log pattern matches
    fun logPatternMatch(pattern: String, data: String) {
        Log.i(TAG, "Pattern matched: $pattern, data: $data")
    }

    // Log safety violations
    fun logSafetyViolation(violation: String) {
        Log.w(TAG, "Safety violation: $violation")
    }

    // Log debug information
    fun logDebug(message: String) {
        Log.d(TAG, message)
    }
}
